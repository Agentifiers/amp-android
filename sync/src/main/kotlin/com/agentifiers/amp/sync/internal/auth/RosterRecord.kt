package com.agentifiers.amp.sync.internal.auth

import com.agentifiers.amp.model.Account
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AuthHealth
import com.agentifiers.amp.model.CredentialKind
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.model.UserId
import com.agentifiers.amp.model.WorkspaceRef
import com.agentifiers.amp.store.AccountEntry
import com.agentifiers.amp.sync.internal.vault.StoredCredential

/** One signed-in account as `roster.pb` keeps it: everything but the secrets. */
internal class RosterRecord(val account: Account, val userId: UserId) {
    fun toEntry(): AccountEntry = AccountEntry(
        id = account.id.raw,
        email = account.email,
        baseUrl = account.server.ampBaseUrl,
        workspaceJson = "",
        pushInstance = account.id.raw,
        actorsBaseUrl = account.server.actorsBaseUrl,
        userId = userId.raw,
        credentialKind = account.credential.name,
        needsSignIn = account.auth == AuthHealth.NeedsSignIn,
    )

    companion object {
        /** Null for an entry this version cannot place: no user, no actors origin, or an unknown credential kind. */
        fun fromEntry(entry: AccountEntry): RosterRecord? {
            if (entry.userId.isBlank() || entry.actorsBaseUrl.isBlank()) return null
            val kind = CredentialKind.entries.firstOrNull { it.name == entry.credentialKind } ?: return null
            val auth = if (entry.needsSignIn) AuthHealth.NeedsSignIn else AuthHealth.Ok
            val account = Account(AccountId(entry.id), entry.email, ServerEnvironment(entry.baseUrl, entry.actorsBaseUrl), WorkspaceRef.Personal, auth, kind)
            return RosterRecord(account, UserId(entry.userId))
        }

        fun of(pending: PendingCredential): RosterRecord {
            val kind = if (pending is PendingCredential.OAuth) CredentialKind.OAuth else CredentialKind.AccessToken
            val id = accountIdOf(pending.server, pending.userId)
            return RosterRecord(Account(id, pending.email, pending.server, WorkspaceRef.Personal, AuthHealth.Ok, kind), pending.userId)
        }
    }
}

internal fun PendingCredential.toStored(): StoredCredential = when (this) {
    is PendingCredential.OAuth -> StoredCredential.OAuth(accessToken, refreshToken, expiresAt, apiUrl, clientId)
    is PendingCredential.AccessToken -> StoredCredential.AccessToken(token)
}
