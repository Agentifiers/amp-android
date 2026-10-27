package com.agentifiers.amp.sync.internal.auth

import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.model.UserId
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.FixedBearer
import com.agentifiers.amp.sync.internal.net.Outcome
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.security.MessageDigest

/** An account is the pair (server origin, userId), so the same user on the same server is the same account. */
internal fun accountIdOf(server: ServerEnvironment, userId: UserId): AccountId {
    val digest = MessageDigest.getInstance("SHA-256").digest("${server.ampBaseUrl}\n${userId.raw}".encodeToByteArray())
    return AccountId("A-" + digest.take(8).joinToString("") { "%02x".format(it) })
}

internal sealed interface Identity {
    class Known(val userId: UserId, val email: String) : Identity
    data object Rejected : Identity
    class Unreachable(val reason: String) : Identity
    data object Unreadable : Identity
}

/** `POST /api/user-actor-credentials` names the user; `getUserInfo` gives the email, read tolerantly because it was never captured. */
internal suspend fun identify(client: OkHttpClient, endpoint: Endpoint, token: String): Identity {
    val http = AmpHttp(client, endpoint, FixedBearer(token))
    val userId = when (val grant = http.userGrant()) {
        is Outcome.Ok -> UserId(grant.value.userId)
        Outcome.Unauthorized -> return Identity.Rejected
        is Outcome.Failed -> return if (grant.status == null) Identity.Unreachable(grant.reason) else Identity.Unreadable
    }
    return when (val info = http.internal("getUserInfo", JsonObject(emptyMap()))) {
        is Outcome.Ok -> Identity.Known(userId, labelOf(info.value as? JsonObject))
        Outcome.Unauthorized -> Identity.Rejected
        is Outcome.Failed -> if (info.status == null) Identity.Unreachable(info.reason) else Identity.Unreadable
    }
}

private fun labelOf(info: JsonObject?): String =
    listOf("email", "username", "displayName", "name").firstNotNullOfOrNull { info?.string(it) } ?: "Amp account"
