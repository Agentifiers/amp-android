package com.agentifiers.amp.sync.internal.auth

import com.agentifiers.amp.model.CredentialKind
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.sync.api.AccountCandidate
import com.agentifiers.amp.sync.api.SignInFailure
import com.agentifiers.amp.sync.api.SignInStep
import com.agentifiers.amp.sync.internal.net.Endpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException

/** Where a confirmed credential goes. The engine applies the account rules and answers SignedIn or Failed. */
internal interface AccountBook {
    /** No credential from [server] could be stored: five accounts are signed in and none on [server] needs sign-in. */
    fun refuses(server: ServerEnvironment): Boolean

    suspend fun store(pending: PendingCredential): SignInStep
}

/** What a sign-in method proved before the identity check. */
internal sealed interface Proof {
    class OAuth(val grant: TokenGrant, val config: OAuthConfig) : Proof
    class Pasted(val token: String) : Proof
}

/** Ends an attempt with [reason]; thrown inside a method and turned into [SignInStep.Failed] once, by [SignInFlows]. */
internal class SignInStopped(val reason: SignInFailure) : Exception(null, null, false, false)

/**
 * The three sign-in methods as cold flows. Each refuses before any request when [AccountBook.refuses], proves a credential,
 * checks whose it is, and suspends at [SignInStep.Confirm] until the UI answers.
 */
internal class SignInFlows(
    private val client: OkHttpClient,
    private val book: AccountBook,
    private val allowLocalHttp: Boolean,
    private val endpointOf: (ServerEnvironment) -> Endpoint?,
) {
    private val calls = AuthCalls(client)

    fun device(server: ServerEnvironment): Flow<SignInStep> = attempt(server) { emit ->
        DeviceFlow(calls).run(server, discover(server), emit)
    }

    fun browser(server: ServerEnvironment): Flow<SignInStep> = attempt(server) { emit ->
        LoopbackFlow(calls).run(server, discover(server), emit)
    }

    fun paste(server: ServerEnvironment, token: String): Flow<SignInStep> = attempt(server) {
        Proof.Pasted(tokenOrNull(token) ?: throw SignInStopped(SignInFailure.TokenRejected))
    }

    /** The checked identity behind [proof], ready to store. */
    suspend fun pendingOf(server: ServerEnvironment, proof: Proof): PendingCredential {
        val endpoint = endpointOf(server) ?: throw SignInStopped(SignInFailure.NotAmpServer)
        val token = when (proof) {
            is Proof.OAuth -> proof.grant.accessToken
            is Proof.Pasted -> proof.token
        }
        val identity = when (val found = identify(client, endpoint, token)) {
            is Identity.Known -> found
            Identity.Rejected -> throw SignInStopped(if (proof is Proof.Pasted) SignInFailure.TokenRejected else SignInFailure.ServerRejected)
            is Identity.Unreachable -> throw SignInStopped(SignInFailure.Network)
            Identity.Unreadable -> throw SignInStopped(SignInFailure.ServerRejected)
        }
        return when (proof) {
            is Proof.OAuth -> PendingCredential.OAuth(
                server = server,
                userId = identity.userId,
                email = proof.grant.email ?: identity.email,
                accessToken = proof.grant.accessToken,
                refreshToken = proof.grant.refreshToken ?: throw SignInStopped(SignInFailure.ServerRejected),
                expiresAt = proof.grant.expiresAt,
                apiUrl = proof.config.apiUrl.toString(),
                clientId = proof.config.clientId,
            )
            is Proof.Pasted -> PendingCredential.AccessToken(server, identity.userId, identity.email, proof.token)
        }
    }

    private fun attempt(server: ServerEnvironment, method: suspend (emit: suspend (SignInStep) -> Unit) -> Proof): Flow<SignInStep> = flow {
        if (book.refuses(server)) return@flow emit(SignInStep.Failed(SignInFailure.TooManyAccounts))
        val pending = try {
            pendingOf(server, method { emit(it) })
        } catch (stopped: SignInStopped) {
            return@flow emit(SignInStep.Failed(stopped.reason))
        } catch (_: IOException) {
            return@flow emit(SignInStep.Failed(SignInFailure.Network))
        }
        val kind = if (pending is PendingCredential.OAuth) CredentialKind.OAuth else CredentialKind.AccessToken
        val candidate = AccountCandidate(pending.email, server, kind, pending)
        emit(SignInStep.Confirm(candidate))
        if (candidate.decision.await()) emit(book.store(pending))
    }

    private suspend fun discover(server: ServerEnvironment): OAuthConfig {
        val base = baseOf(server)
        return OAuthConfig.parse(calls.oauthConfig(base).takeIf { it.status in 200..299 }?.body, allowLocalHttp)
            ?: throw SignInStopped(SignInFailure.NotAmpServer)
    }

    companion object {
        fun baseOf(server: ServerEnvironment): HttpUrl = server.ampBaseUrl.toHttpUrlOrNull() ?: throw SignInStopped(SignInFailure.NotAmpServer)

        /** A token is printable ASCII with no spaces; anything else was mis-pasted. */
        fun tokenOrNull(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.all { c -> c.code in 0x21..0x7e } }
    }
}
