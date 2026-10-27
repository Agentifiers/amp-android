package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A client that speaks to the fake the way `:sync` speaks to Amp: Bearer HTTP and Rivet sockets over real sockets. */
internal class Wire(private val server: FakeAmpServer) {
    val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    fun post(path: String, body: JsonObject, headers: Map<String, String> = mapOf("Authorization" to "Bearer test-token")): Pair<Int, String> {
        val builder = Request.Builder().url(server.ampBaseUrl + path).post(body.toString().toRequestBody(JSON))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.newCall(builder.build()).execute().use { it.code to it.body.string() }
    }

    fun internal(method: String, params: JsonObject = json()): JsonObject {
        val (status, body) = post("/api/internal?$method", json("method" to method, "params" to params))
        check(status == 200) { "internal $method answered $status" }
        return parse(body)
    }

    fun threadSocket(threadId: String): Socket {
        val (_, grant) = post("/api/thread-actors", json("usesThreadActors" to true, "threadId" to threadId))
        val params = json(
            "wsToken" to parse(grant).string("wsToken"),
            "clientApplication" to json("type" to "cli", "name" to "amp-android", "version" to "test"),
            "interactiveClient" to true,
            "transport" to "json-rpc",
        )
        val protocols = "rivet, rivet_encoding.bare, rivet_skip_ready_wait, rivet_conn_params.${encode(params)}"
        val url = "${server.actorsBaseUrl}/gateway/threadActor/websocket/?rvt-namespace=default&rvt-method=get&rvt-key=$threadId&rvt-token=pk&rvt-skip-ready-wait=true"
        return Socket(http, Request.Builder().url(url).header("Sec-WebSocket-Protocol", protocols).build())
    }

    fun userSocket(): Socket {
        val (_, grant) = post("/api/user-actor-credentials", json("capability" to "read"))
        val g = parse(grant)
        val protocols = "rivet, rivet_encoding.json, rivet_skip_ready_wait, rivet_conn_params.${encode(json("type" to "user", "wsToken" to g.string("wsToken")))}"
        val url = "${server.actorsBaseUrl}/gateway/userActor/connect?rvt-namespace=default&rvt-method=get&rvt-key=${g.string("userId")}&rvt-token=pk&rvt-skip-ready-wait=true"
        return Socket(http, Request.Builder().url(url).header("Sec-WebSocket-Protocol", protocols).build())
    }

    private fun encode(params: JsonObject) = URLEncoder.encode(params.toString(), "UTF-8").replace("+", "%20")

    companion object {
        val JSON = "application/json".toMediaType()
        fun parse(text: String): JsonObject = FakeJson.parseToJsonElement(text).jsonObject
    }
}

/** One socket's inbound frames in arrival order. Text frames are kept raw; binary frames as bytes. */
internal class Socket(client: OkHttpClient, request: Request) : WebSocketListener() {
    val frames = LinkedBlockingQueue<Any>()
    val protocol = LinkedBlockingQueue<String>()
    private val socket: WebSocket = client.newWebSocket(request, this)
    private var counter = 0

    override fun onOpen(webSocket: WebSocket, response: Response) {
        protocol.put(response.header("Sec-WebSocket-Protocol") ?: "none")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        frames.put(text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        frames.put(bytes)
    }

    fun request(method: String, params: JsonObject): String {
        val id = "thread-client-${System.currentTimeMillis()}-${counter++}"
        socket.send(json("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params).toString())
        return id
    }

    fun notify(method: String, params: JsonObject) {
        socket.send(json("jsonrpc" to "2.0", "method" to method, "params" to params).toString())
    }

    fun sendText(text: String) {
        socket.send(text)
    }

    fun sendBytes(bytes: ByteString) {
        socket.send(bytes)
    }

    /** Text frames as JSON objects (pong excluded) until one matches, returning everything read. */
    fun readUntil(timeoutMs: Long = 15_000, match: (JsonObject) -> Boolean): List<JsonObject> {
        val seen = ArrayList<JsonObject>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val frame = frames.poll(maxOf(left, 0), TimeUnit.MILLISECONDS) ?: error("no matching frame in ${timeoutMs}ms; saw ${seen.map(::kind)}")
            if (frame !is String || frame == "pong") continue
            val obj = Wire.parse(frame)
            seen += obj
            if (match(obj)) return seen
        }
    }

    /** Binary frames until [match] holds for the concatenation so far. */
    fun readBytesUntil(timeoutMs: Long = 10_000, match: (List<ByteString>) -> Boolean): List<ByteString> {
        val seen = ArrayList<ByteString>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!match(seen)) {
            val frame = frames.poll(maxOf(deadline - System.currentTimeMillis(), 0), TimeUnit.MILLISECONDS) ?: error("no matching bytes; saw ${seen.size} frames")
            if (frame is ByteString) seen += frame
        }
        return seen
    }

    fun close() {
        socket.close(1000, null)
    }

    companion object {
        fun kind(frame: JsonObject): String = frame.string("method") ?: frame.string("type") ?: frame.string("id") ?: "?"
    }
}

internal fun JsonObject.params(): JsonObject = obj("params") ?: (this - "type")
internal fun JsonObject.isEvent(type: String): Boolean = (string("method") ?: string("type")) == type && "id" !in this
internal fun JsonObject.message(): JsonObject? = params().obj("message")
internal fun JsonElement.text(): String = (this as JsonPrimitive).content
internal fun JsonObject.strings(key: String): List<String> = (this[key] as JsonArray).map { it.text() }
