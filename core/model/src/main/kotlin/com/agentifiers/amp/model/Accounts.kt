package com.agentifiers.amp.model

import kotlinx.collections.immutable.PersistentList

/**
 * Where one account's Amp lives: internal RPC and `/api/thread-actors` under [ampBaseUrl], the Rivet
 * gateway under [actorsBaseUrl] (`<actorsBaseUrl>/gateway/<actor>/...`). Production is
 * `https://ampcode.com` and `https://ampcode.com/actors`; a fake or staging server sets its own pair.
 */
data class ServerEnvironment(val ampBaseUrl: String, val actorsBaseUrl: String) {
    val host: String get() = ampBaseUrl.substringAfter("://").substringBefore('/')

    companion object {
        val Production = ServerEnvironment("https://ampcode.com", "https://ampcode.com/actors")

        /**
         * A server the user typed, normalized to its origin with the path dropped. Only https is accepted;
         * [allowLocalHttp] (debug builds) also accepts http for localhost, 127.0.0.1 and 10.0.2.2.
         */
        fun fromUserInput(input: String, allowLocalHttp: Boolean): ServerEnvironment? {
            val trimmed = input.trim().let { if ("://" in it) it else "https://$it" }
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val local = host in LOCAL_HOSTS
            if (scheme != "https" && !(scheme == "http" && allowLocalHttp && local)) return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val origin = "$scheme://$host$port"
            return ServerEnvironment(origin, "$origin/actors")
        }

        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")
    }
}

data class Account(
    val id: AccountId,
    val email: String,
    val server: ServerEnvironment,
    val workspace: WorkspaceRef,
    val auth: AuthHealth,
    val credential: CredentialKind = CredentialKind.OAuth,
)

/** How the account signed in. A personal access token has no refresh and outlives sign-out on the server. */
enum class CredentialKind { OAuth, AccessToken }

sealed interface WorkspaceRef {
    data object Personal : WorkspaceRef
    data class Team(val id: String, val name: String) : WorkspaceRef
}

sealed interface AuthHealth {
    data object Ok : AuthHealth
    data object Refreshing : AuthHealth
    /** The server rejected the stored credential (invalid_grant, or a pasted token that stopped working). */
    data object NeedsSignIn : AuthHealth
    /** Passkey sudo required for remote control. */
    data object NeedsStepUp : AuthHealth
}

/** [lastUsed] picks the account for a new window. Each open window carries its own account. */
data class AccountRoster(val accounts: PersistentList<Account>, val lastUsed: AccountId?) {
    val isFull: Boolean get() = accounts.size >= MAX_ACCOUNTS

    companion object {
        /** The official apps keep up to five accounts signed in (auth-and-security.md). */
        const val MAX_ACCOUNTS = 5
    }
}
