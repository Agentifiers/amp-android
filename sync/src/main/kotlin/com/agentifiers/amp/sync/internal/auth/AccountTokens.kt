package com.agentifiers.amp.sync.internal.auth

import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.sync.internal.net.Bearer
import com.agentifiers.amp.sync.internal.net.BearerTokens
import com.agentifiers.amp.sync.internal.vault.StoredCredential
import com.agentifiers.amp.sync.internal.vault.TokenVault
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes

/**
 * One account's Bearer token. An OAuth token is refreshed 2 minutes before it expires and after a 401, one
 * refresh at a time per account; the vault's compare-and-set keeps a refresh that lost a race from overwriting
 * the winner. `invalid_grant`, or a 401 on a token that cannot refresh, calls [onSignInNeeded] and every later
 * request fails without touching the network until [replace] stores a new credential.
 */
internal class AccountTokens(
    private val account: AccountId,
    private val vault: TokenVault,
    private val calls: AuthCalls,
    signInNeeded: Boolean,
    private val onSignInNeeded: () -> Unit,
) : BearerTokens {
    private val refreshing = Mutex()

    @Volatile private var cached: StoredCredential? = null

    @Volatile private var dead = signInNeeded

    override suspend fun current(): Bearer {
        if (dead) return Bearer.SignInNeeded
        return when (val stored = cached ?: load()) {
            null -> giveUp()
            is StoredCredential.AccessToken -> Bearer.Token(stored.token)
            is StoredCredential.OAuth -> if (stored.isFresh()) Bearer.Token(stored.accessToken) else refresh(rejected = null)
        }
    }

    override suspend fun rejected(token: String): Bearer {
        if (dead) return Bearer.SignInNeeded
        return when (val stored = load()) {
            null -> giveUp()
            is StoredCredential.AccessToken -> if (stored.token != token) Bearer.Token(stored.token) else giveUp()
            is StoredCredential.OAuth -> refresh(rejected = token)
        }
    }

    /** A new sign-in for this account: the credential is in the vault and requests may go out again. */
    suspend fun replace(credential: StoredCredential) = refreshing.withLock {
        vault.write(account, credential)
        cached = credential
        dead = false
    }

    private suspend fun refresh(rejected: String?): Bearer = refreshing.withLock {
        if (dead) return@withLock Bearer.SignInNeeded
        val stored = load() as? StoredCredential.OAuth ?: return@withLock giveUp()
        if (stored.accessToken != rejected && stored.isFresh()) return@withLock Bearer.Token(stored.accessToken)
        when (val answer = calls.refresh(stored.apiUrl, stored.clientId, stored.refreshToken)) {
            is RefreshAnswer.Tokens -> {
                val next = StoredCredential.OAuth(
                    accessToken = answer.grant.accessToken,
                    refreshToken = answer.grant.refreshToken ?: stored.refreshToken,
                    expiresAt = answer.grant.expiresAt,
                    apiUrl = stored.apiUrl,
                    clientId = stored.clientId,
                )
                if (vault.compareAndSet(account, stored.refreshToken, next)) {
                    cached = next
                    Bearer.Token(next.accessToken)
                } else {
                    when (val winner = load()) {
                        is StoredCredential.OAuth -> Bearer.Token(winner.accessToken)
                        is StoredCredential.AccessToken -> Bearer.Token(winner.token)
                        null -> giveUp()
                    }
                }
            }
            RefreshAnswer.InvalidGrant -> giveUp()
            is RefreshAnswer.Unreachable -> Bearer.Unavailable(answer.reason)
        }
    }

    private suspend fun load(): StoredCredential? = vault.read(account).also { cached = it }

    private fun StoredCredential.OAuth.isFresh(): Boolean = expiresAt == null || expiresAt - calls.now() > REFRESH_AHEAD

    private fun giveUp(): Bearer {
        if (!dead) {
            dead = true
            onSignInNeeded()
        }
        return Bearer.SignInNeeded
    }

    private companion object {
        val REFRESH_AHEAD = 2.minutes
    }
}
