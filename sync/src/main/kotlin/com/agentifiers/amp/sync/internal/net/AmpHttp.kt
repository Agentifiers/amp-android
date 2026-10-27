package com.agentifiers.amp.sync.internal.net

import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.long
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data object Unauthorized : Outcome<Nothing>
    /** [reached] is false only when the request never left the device, so even an AtMostOnce write may go again. */
    data class Failed(val status: Int?, val reason: String, val reached: Boolean = true) : Outcome<Nothing>
}

/** [threadVersion] is the thread's latest `seq`; a socket with no cache resumes from it and replays no history. */
internal data class ThreadGrant(val wsToken: String, val threadVersion: Long? = null, val agentMode: String? = null)
internal data class UserGrant(val userId: String, val wsToken: String)

/** What an account's credential answers when a request needs its Bearer token. */
internal sealed interface Bearer {
    class Token(val value: String) : Bearer

    /** Amp rejected the credential for good; the account needs a new sign-in. */
    data object SignInNeeded : Bearer

    /** A refresh could not reach Amp; the request fails without touching the account. */
    class Unavailable(val reason: String) : Bearer
}

internal interface BearerTokens {
    /** A token that is not about to expire, refreshed first when it is. */
    suspend fun current(): Bearer

    /** Amp answered 401 to [token]; refresh unless another caller already replaced it. */
    suspend fun rejected(token: String): Bearer
}

/** A token with no refresh: the sign-in identity check before anything is stored. */
internal class FixedBearer(private val token: String) : BearerTokens {
    override suspend fun current(): Bearer = Bearer.Token(token)
    override suspend fun rejected(token: String): Bearer = Bearer.SignInNeeded
}

/**
 * Every HTTP call one account makes. Bearer goes on the `/api` routes; actor gateway actions carry the actor token
 * instead. A Bearer request is sent with a token that is not about to expire, and a 401 refreshes it and retries once.
 */
internal class AmpHttp(
    private val client: OkHttpClient,
    private val endpoint: Endpoint,
    private val tokens: BearerTokens,
) {
    /** `POST /api/internal?<method>`. Success is decided by `ok`, never by the status, because errors also arrive with 200. */
    suspend fun internal(method: String, params: JsonObject): Outcome<JsonElement> {
        val body = buildJsonObject {
            put("method", method)
            put("params", params)
        }
        val url = endpoint.base.newBuilder().addPathSegments("api/internal").encodedQuery(method).build()
        return exchange(bearer(url).post(body.toBody()).build()) { status, json ->
            when (json?.bool("ok")) {
                true -> Outcome.Ok(json["result"] ?: JsonNull)
                false -> Outcome.Failed(status, json.obj("error")?.let { "${it.string("code")}: ${it.string("message")}" } ?: "error")
                null -> Outcome.Failed(status, "unexpected response from $method")
            }
        }
    }

    /** `POST /api/thread-actors` mints the socket's `wsToken`. An archived thread answers 409. */
    suspend fun threadGrant(thread: ThreadId): Outcome<ThreadGrant> {
        val body = buildJsonObject {
            put("usesThreadActors", true)
            put("threadId", thread.raw)
        }
        val url = endpoint.base.newBuilder().addPathSegments("api/thread-actors").build()
        return exchange(bearer(url).post(body.toBody()).build()) { status, json ->
            val token = json?.string("wsToken")
            if (status in 200..299 && token != null) {
                Outcome.Ok(ThreadGrant(token, json.long("threadVersion"), json.string("agentMode")))
            } else {
                Outcome.Failed(status, json?.string("code") ?: "HTTP $status")
            }
        }
    }

    /** `POST /api/thread-actors` without a `threadId` creates a thread in [agentMode] (`captures/s04-live-thread.jsonl:1-2`). */
    suspend fun createThread(agentMode: String): Outcome<ThreadId> {
        val body = buildJsonObject {
            put("usesThreadActors", true)
            put("agentMode", agentMode)
        }
        val url = endpoint.base.newBuilder().addPathSegments("api/thread-actors").build()
        return exchange(bearer(url).post(body.toBody()).build()) { status, json ->
            val thread = json?.string("threadId")?.let(ThreadId::parse)
            if (status in 200..299 && thread != null) Outcome.Ok(thread) else Outcome.Failed(status, json?.string("code") ?: json?.string("error") ?: "HTTP $status")
        }
    }

    suspend fun userGrant(): Outcome<UserGrant> {
        val body = buildJsonObject { put("capability", "read") }
        val url = endpoint.base.newBuilder().addPathSegments("api/user-actor-credentials").build()
        return exchange(bearer(url).post(body.toBody()).build()) { status, json ->
            val userId = json?.string("userId")
            val token = json?.string("wsToken")
            if (status in 200..299 && userId != null && token != null) Outcome.Ok(UserGrant(userId, token)) else Outcome.Failed(status, "HTTP $status")
        }
    }

    /** A stateless user actor action. The actor token travels in `x-rivet-conn-params`, with no Authorization header. */
    suspend fun userAction(grant: UserGrant, name: String, args: JsonArray): Outcome<JsonElement> {
        val url = endpoint.gateway("userActor", "action/$name", grant.userId)
        val params = buildJsonObject {
            put("type", "user")
            put("wsToken", grant.wsToken)
        }
        val request = Request.Builder().url(url)
            .header("x-rivet-encoding", "json")
            .header("x-rivet-conn-params", params.toString())
            .post(buildJsonObject { put("args", args) }.toBody())
            .build()
        return exchange(request) { status, json ->
            val output = json?.get("output")
            if (status in 200..299 && output != null) Outcome.Ok(output) else Outcome.Failed(status, "HTTP $status")
        }
    }

    /** The token is attached in [exchange], which knows whether it is fresh. */
    private fun bearer(url: HttpUrl) = Request.Builder().url(url).tag(BearerAuth::class.java, BearerAuth)

    private suspend fun <T> exchange(request: Request, decode: (Int, JsonObject?) -> Outcome<T>): Outcome<T> {
        if (request.tag(BearerAuth::class.java) == null) return send(request, decode)
        val first = tokens.current()
        val outcome = sendWith(first, request, decode)
        if (outcome !is Outcome.Unauthorized || first !is Bearer.Token) return outcome
        return sendWith(tokens.rejected(first.value), request, decode)
    }

    private suspend fun <T> sendWith(bearer: Bearer, request: Request, decode: (Int, JsonObject?) -> Outcome<T>): Outcome<T> = when (bearer) {
        is Bearer.Token -> send(request.newBuilder().header("Authorization", "Bearer ${bearer.value}").build(), decode)
        Bearer.SignInNeeded -> Outcome.Unauthorized
        is Bearer.Unavailable -> Outcome.Failed(null, bearer.reason)
    }

    private suspend fun <T> send(request: Request, decode: (Int, JsonObject?) -> Outcome<T>): Outcome<T> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).await().use { response ->
                if (response.code == 401) return@withContext Outcome.Unauthorized
                val json = try {
                    AmpJson.parseToJsonElement(response.body.string()) as? JsonObject
                } catch (_: SerializationException) {
                    null
                }
                decode(response.code, json)
            }
        } catch (e: IOException) {
            val unreached = e is java.net.ConnectException || e is java.net.UnknownHostException || e is java.net.NoRouteToHostException
            Outcome.Failed(null, e.message ?: e.javaClass.simpleName, reached = !unreached)
        }
    }

    private fun JsonObject.toBody() = toString().toRequestBody(JSON)

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/**
 * The account's server: internal RPC and grants on [base], the Rivet actor gateway under [actors]
 * (`<actors>/gateway/<actor>/...`), and the publishable token every gateway URL carries.
 */
internal class Endpoint(val base: HttpUrl, private val actors: HttpUrl, val rivetPublishableToken: String) {
    fun gateway(actor: String, path: String, key: String): HttpUrl = actors.newBuilder()
        .addPathSegments("gateway/$actor/$path")
        .addQueryParameter("rvt-namespace", "default")
        .addQueryParameter("rvt-method", "get")
        .addQueryParameter("rvt-key", key)
        .addQueryParameter("rvt-token", rivetPublishableToken)
        .addQueryParameter("rvt-skip-ready-wait", "true")
        .build()

    /**
     * A server on this machine (`:fake-amp` in a debug build) takes the account's Web Push itself at
     * `<base>/push/<account>`, so such an account never registers with the deployed relay. Null for any other server.
     * Release builds cannot sign in to a server on these hosts.
     */
    fun ownPushUrl(account: AccountId): String? =
        if (base.host in LOCAL_HOSTS) base.newBuilder().addPathSegment("push").addPathSegment(account.raw).build().toString() else null
}

/** The hosts a debug build reaches a server on this machine through: the device itself, or the emulator's alias for its host. */
internal val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

/** Marks a request that [AmpHttp] signs with the account's Bearer token. */
private object BearerAuth

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, closing, _ -> closing.close() }
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
    })
}
