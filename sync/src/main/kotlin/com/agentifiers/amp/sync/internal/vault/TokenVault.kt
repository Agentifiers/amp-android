package com.agentifiers.amp.sync.internal.vault

import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.long
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/** The credential one account signs its requests with. Plain classes, so no generated toString prints a token. */
internal sealed interface StoredCredential {
    class OAuth(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Instant?,
        val apiUrl: String,
        val clientId: String,
    ) : StoredCredential

    class AccessToken(val token: String) : StoredCredential
}

/** A signed-out account's refresh token, kept until Amp ends its session or [since] is seven days old. */
internal class PendingRevocation(
    val ampBaseUrl: String,
    val apiUrl: String,
    val clientId: String,
    val refreshToken: String,
    val since: Instant,
)

/** Encrypted files, each under its own key. Destroying a name removes the file and its key; absent names are fine. */
internal interface SealedFiles {
    fun read(name: String): ByteArray?
    fun write(name: String, plaintext: ByteArray)
    fun destroy(name: String)
    fun names(): List<String>
}

/**
 * The only place a credential is written. Each account has one file, `vault-<accountId>`, whose `token` entry
 * changes only under that account's lock, so a refresh's compare-and-set never loses a concurrent write. Other
 * entries in the file (push keys) are carried through untouched.
 */
internal class TokenVault(private val files: SealedFiles) {
    private val locks = ConcurrentHashMap<AccountId, Mutex>()

    suspend fun read(account: AccountId): StoredCredential? = locked(account) { entries(account).obj(TOKEN)?.let(::decode) }

    suspend fun write(account: AccountId, credential: StoredCredential) = locked(account) { putToken(account, credential) }

    /** Writes [next] only if the stored refresh token is still [expectedRefresh]. */
    suspend fun compareAndSet(account: AccountId, expectedRefresh: String, next: StoredCredential.OAuth): Boolean = locked(account) {
        val stored = entries(account).obj(TOKEN)?.let(::decode) as? StoredCredential.OAuth
        if (stored?.refreshToken != expectedRefresh) return@locked false
        putToken(account, next)
        true
    }

    suspend fun wipe(account: AccountId) = locked(account) { files.destroy(vaultName(account)) }

    suspend fun keepForRevocation(account: AccountId, pending: PendingRevocation) = withContext(Dispatchers.IO) {
        val entry = buildJsonObject {
            put("ampBaseUrl", pending.ampBaseUrl)
            put("apiUrl", pending.apiUrl)
            put("clientId", pending.clientId)
            put("refreshToken", pending.refreshToken)
            put("since", pending.since.toEpochMilliseconds())
        }
        files.write(revocationName(account), entry.toString().encodeToByteArray())
    }

    suspend fun pendingRevocations(): Map<AccountId, PendingRevocation> = withContext(Dispatchers.IO) {
        files.names().filter { it.startsWith(REVOKE_PREFIX) }.mapNotNull { name ->
            val entry = files.read(name)?.let(::parse) ?: return@mapNotNull null
            val pending = PendingRevocation(
                ampBaseUrl = entry.string("ampBaseUrl") ?: return@mapNotNull null,
                apiUrl = entry.string("apiUrl") ?: return@mapNotNull null,
                clientId = entry.string("clientId") ?: return@mapNotNull null,
                refreshToken = entry.string("refreshToken") ?: return@mapNotNull null,
                since = Instant.fromEpochMilliseconds(entry.long("since") ?: return@mapNotNull null),
            )
            AccountId(name.removePrefix(REVOKE_PREFIX)) to pending
        }.toMap()
    }

    suspend fun dropRevocation(account: AccountId) = withContext(Dispatchers.IO) { files.destroy(revocationName(account)) }

    private suspend fun <T> locked(account: AccountId, block: () -> T): T =
        locks.getOrPut(account) { Mutex() }.withLock { withContext(Dispatchers.IO) { block() } }

    private fun entries(account: AccountId): JsonObject = files.read(vaultName(account))?.let(::parse) ?: JsonObject(emptyMap())

    private fun putToken(account: AccountId, credential: StoredCredential) {
        val next = JsonObject(entries(account) + (TOKEN to encode(credential)))
        files.write(vaultName(account), next.toString().encodeToByteArray())
    }

    private fun parse(bytes: ByteArray): JsonObject? = try {
        AmpJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun encode(credential: StoredCredential): JsonObject = when (credential) {
        is StoredCredential.OAuth -> buildJsonObject {
            put("kind", OAUTH)
            put("accessToken", credential.accessToken)
            put("refreshToken", credential.refreshToken)
            credential.expiresAt?.let { put("expiresAt", it.toEpochMilliseconds()) }
            put("apiUrl", credential.apiUrl)
            put("clientId", credential.clientId)
        }
        is StoredCredential.AccessToken -> buildJsonObject {
            put("kind", ACCESS_TOKEN)
            put("token", credential.token)
        }
    }

    private fun decode(entry: JsonObject): StoredCredential? = when (entry.string("kind")) {
        OAUTH -> StoredCredential.OAuth(
            accessToken = entry.string("accessToken") ?: return null,
            refreshToken = entry.string("refreshToken") ?: return null,
            expiresAt = entry.long("expiresAt")?.let(Instant::fromEpochMilliseconds),
            apiUrl = entry.string("apiUrl") ?: return null,
            clientId = entry.string("clientId") ?: return null,
        )
        ACCESS_TOKEN -> StoredCredential.AccessToken(entry.string("token") ?: return null)
        else -> null
    }

    private companion object {
        const val TOKEN = "token"
        const val OAUTH = "oauth"
        const val ACCESS_TOKEN = "accessToken"
        const val VAULT_PREFIX = "vault-"
        const val REVOKE_PREFIX = "revoke-"

        fun vaultName(account: AccountId) = VAULT_PREFIX + account.raw
        fun revocationName(account: AccountId) = REVOKE_PREFIX + account.raw
    }
}
