package com.agentifiers.amp.sync.internal.auth

import com.agentifiers.amp.sync.internal.net.LOCAL_HOSTS
import com.agentifiers.amp.sync.internal.net.await
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.long
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** `GET <server>/api/auth/oauth-config`, accepted only with a `clientID` and an https `apiURL`. */
internal class OAuthConfig(
    val clientId: String,
    val apiUrl: HttpUrl,
    val deviceLoginPath: String?,
    val cliCodeRedemption: Boolean,
) {
    companion object {
        /** [allowLocalHttp] (debuggable builds) also accepts an http `apiURL` on the hosts the fake server uses. */
        fun parse(body: JsonObject?, allowLocalHttp: Boolean): OAuthConfig? {
            val clientId = body?.string("clientID")?.takeIf { it.isNotBlank() } ?: return null
            val apiUrl = body.string("apiURL")?.toHttpUrlOrNull() ?: return null
            if (!apiUrl.isHttps && !(allowLocalHttp && apiUrl.host in LOCAL_HOSTS)) return null
            return OAuthConfig(clientId, apiUrl, body.string("deviceLoginPath"), body.bool("cliCodeRedemption") == true)
        }
    }
}

/** A token response: `{access_token, refresh_token, expires_in?, user?{email}}`. */
internal class TokenGrant(val accessToken: String, val refreshToken: String?, val expiresAt: Instant?, val email: String?) {
    companion object {
        fun parse(body: JsonObject?, now: Instant): TokenGrant? {
            val access = body?.string("access_token")?.takeIf { it.isNotBlank() } ?: return null
            return TokenGrant(access, body.string("refresh_token"), expiryOf(access, body.long("expires_in"), now), body.obj("user")?.string("email"))
        }

        /** `expires_in` when present, else the `exp` claim of a JWT-shaped token, else unknown. */
        fun expiryOf(accessToken: String, expiresIn: Long?, now: Instant): Instant? {
            if (expiresIn != null) return now + expiresIn.seconds
            if (!JWT.matches(accessToken)) return null
            val claims = try {
                AmpJson.parseToJsonElement(Base64.getUrlDecoder().decode(accessToken.split('.')[1]).decodeToString()) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: SerializationException) {
                null
            }
            return claims?.long("exp")?.let(Instant::fromEpochSeconds)
        }

        private val JWT = Regex("^[\\w-]+\\.[\\w-]+\\.[\\w-]+$")
    }
}

/** One answer to a device-code poll, read from its status and `error`. */
internal sealed interface DevicePoll {
    data object Pending : DevicePoll
    data object SlowDown : DevicePoll
    data object Denied : DevicePoll
    data object Expired : DevicePoll
    data class RetryAfter(val seconds: Long) : DevicePoll
    class Tokens(val grant: TokenGrant) : DevicePoll
    data object Unknown : DevicePoll

    companion object {
        fun parse(reply: AuthReply, now: Instant): DevicePoll {
            if (reply.status in 200..299) return TokenGrant.parse(reply.body, now)?.let(::Tokens) ?: Unknown
            if (reply.status == 429 || reply.status >= 500) return reply.retryAfterSeconds?.let(::RetryAfter) ?: RetryAfter(0)
            return when (reply.body?.string("error")) {
                "authorization_pending" -> Pending
                "slow_down" -> SlowDown
                "access_denied" -> Denied
                "expired_token" -> Expired
                else -> Unknown
            }
        }
    }
}

internal sealed interface RefreshAnswer {
    class Tokens(val grant: TokenGrant) : RefreshAnswer
    data object InvalidGrant : RefreshAnswer
    class Unreachable(val reason: String) : RefreshAnswer
}

internal class AuthReply(val status: Int, val body: JsonObject?, val retryAfterSeconds: Long?)

/** The sign-in, refresh and logout endpoints. None of them carries the account's stored Bearer automatically. */
internal class AuthCalls(private val client: OkHttpClient, private val clock: Clock = Clock.System) {
    fun now(): Instant = clock.now()

    suspend fun oauthConfig(server: HttpUrl): AuthReply = call(Request.Builder().url(server.under("api/auth/oauth-config")).get().build())

    suspend fun form(url: HttpUrl, fields: Map<String, String>): AuthReply =
        call(Request.Builder().url(url).post(FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()).build())

    suspend fun json(url: HttpUrl, body: JsonObject, bearer: String? = null): AuthReply =
        call(Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).apply { bearer?.let { header("Authorization", "Bearer $it") } }.build())

    /** `grant_type=refresh_token` at `<apiURL>/user_management/authenticate`. */
    suspend fun refresh(apiUrl: String, clientId: String, refreshToken: String): RefreshAnswer {
        val url = apiUrl.toHttpUrlOrNull()?.under("user_management/authenticate") ?: return RefreshAnswer.Unreachable("bad apiURL")
        val reply = try {
            form(url, mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken, "client_id" to clientId))
        } catch (e: IOException) {
            return RefreshAnswer.Unreachable(e.javaClass.simpleName)
        }
        if (reply.status in 200..299) return TokenGrant.parse(reply.body, now())?.let(RefreshAnswer::Tokens) ?: RefreshAnswer.Unreachable("unreadable refresh")
        if (reply.body?.string("error") == "invalid_grant") return RefreshAnswer.InvalidGrant
        return RefreshAnswer.Unreachable("HTTP ${reply.status}")
    }

    /** `POST <server>/api/auth/oauth-logout` with Bearer. True when Amp confirmed. */
    suspend fun logout(server: HttpUrl, accessToken: String): Boolean = try {
        json(server.under("api/auth/oauth-logout"), JsonObject(emptyMap()), accessToken).status in 200..299
    } catch (_: IOException) {
        false
    }

    private suspend fun call(request: Request): AuthReply = withContext(Dispatchers.IO) {
        client.newCall(request).await().use { response ->
            val body = try {
                AmpJson.parseToJsonElement(response.body.string()) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
            AuthReply(response.code, body, response.header("Retry-After")?.trim()?.toLongOrNull())
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

internal fun HttpUrl.under(path: String): HttpUrl = newBuilder().addPathSegments(path).build()
