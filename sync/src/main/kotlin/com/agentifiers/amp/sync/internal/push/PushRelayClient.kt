package com.agentifiers.amp.sync.internal.push

import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One account's row at the relay. [secret] authorizes update and delete; only the vault stores it. */
internal data class RelayRegistration(val rid: String, val pushUrl: String, val secret: String)

internal sealed interface RelayAnswer<out T> {
    data class Ok<T>(val value: T) : RelayAnswer<T>

    /** The relay no longer has the registration (404, or 410 from a relay that already dropped it). */
    data object Gone : RelayAnswer<Nothing>
    data object Unauthorized : RelayAnswer<Nothing>
    data class Failed(val reason: String) : RelayAnswer<Nothing>
}

/** The app's side of `docs/design/push-relay.md`: register, update and delete one registration. */
internal class PushRelayClient(private val client: OkHttpClient, private val base: HttpUrl) {

    suspend fun register(fcmToken: String): RelayAnswer<RelayRegistration> {
        val request = Request.Builder().url(base.resolve("v1/registrations")!!).post(tokenBody(fcmToken)).build()
        return exchange(request) { status, json ->
            if (status != 201) return@exchange failure(status)
            val rid = json?.string("rid")
            val pushUrl = json?.string("pushUrl")
            val secret = json?.string("secret")
            when {
                rid == null || pushUrl == null || secret == null -> RelayAnswer.Failed("the relay sent a registration this version cannot read")
                ridOf(pushUrl) != rid -> RelayAnswer.Failed("the relay's rid does not match its push URL")
                else -> RelayAnswer.Ok(RelayRegistration(rid, pushUrl, secret))
            }
        }
    }

    suspend fun update(registration: RelayRegistration, fcmToken: String): RelayAnswer<Unit> {
        val request = authorized(registration).put(tokenBody(fcmToken)).build()
        return exchange(request) { status, _ -> if (status == 204) RelayAnswer.Ok(Unit) else failure(status) }
    }

    /** Idempotent: a registration the relay no longer has is already deleted. */
    suspend fun delete(registration: RelayRegistration): RelayAnswer<Unit> {
        val request = authorized(registration).delete().build()
        return exchange(request) { status, _ ->
            when (status) {
                204, 404, 410 -> RelayAnswer.Ok(Unit)
                else -> failure(status)
            }
        }
    }

    /**
     * Converges [current] onto [fcmToken]: registers when there is none, updates the token otherwise, and registers
     * again when the relay has forgotten the row. A new registration means a new push URL, which the caller must
     * give Amp again.
     */
    suspend fun ensure(current: RelayRegistration?, fcmToken: String): RelayAnswer<RelayRegistration> {
        if (current == null) return register(fcmToken)
        return when (val updated = update(current, fcmToken)) {
            is RelayAnswer.Ok -> RelayAnswer.Ok(current)
            RelayAnswer.Gone -> register(fcmToken)
            RelayAnswer.Unauthorized -> RelayAnswer.Unauthorized
            is RelayAnswer.Failed -> updated
        }
    }

    private fun authorized(registration: RelayRegistration): Request.Builder =
        Request.Builder().url(base.resolve("v1/registrations/${registration.rid}")!!).header("Authorization", "Bearer ${registration.secret}")

    private fun tokenBody(fcmToken: String) = buildJsonObject { put("fcmToken", fcmToken) }.toString().toRequestBody(JSON)

    private fun failure(status: Int): RelayAnswer<Nothing> = when (status) {
        401 -> RelayAnswer.Unauthorized
        404, 410 -> RelayAnswer.Gone
        else -> RelayAnswer.Failed("relay answered HTTP $status")
    }

    private suspend fun <T> exchange(request: Request, decode: (Int, JsonObject?) -> RelayAnswer<T>): RelayAnswer<T> = try {
        client.newCall(request).await().use { response ->
            val json = try {
                AmpJson.parseToJsonElement(response.body.string()) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
            decode(response.code, json)
        }
    } catch (e: IOException) {
        RelayAnswer.Failed(e.message ?: e.javaClass.simpleName)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Unpadded base64url SHA-256 of the push URL's last path segment, the capability (relay/README.md). */
        fun ridOf(pushUrl: String): String? {
            val capability = pushUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest(capability.encodeToByteArray())
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, closing, _ -> closing.close() }
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
    })
}
