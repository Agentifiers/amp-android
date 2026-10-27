package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import java.net.URLDecoder

/** Routes every request the app makes to Amp (transport.md, endpoint table) onto the [World]. */
internal class Routes(private val world: World) : Dispatcher() {

    override fun dispatch(request: RecordedRequest): MockResponse = try {
        route(request)
    } catch (e: Exception) {
        System.err.println("fake-amp: ${request.method} ${request.url.encodedPath} failed: $e")
        reply(500, json("ok" to false, "error" to json("code" to "internal", "message" to e.toString())))
    }

    private fun route(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        world.sync { world.web?.route(request) }?.let { return it }
        return when {
            AUTH_PATH.containsMatchIn(path) -> auth(request, path)
            path.startsWith(Attachments.UPLOAD_PATH) && request.method == "PUT" -> {
                val name = request.url.queryParameter("name") ?: return reply(400, json("error" to "missing name"))
                val type = request.headers["Content-Type"] ?: "application/octet-stream"
                reply(200, world.sync { world.attachments.put(name, type, request.body?.toByteArray() ?: ByteArray(0)) })
            }
            path.startsWith(Attachments.CONTENT_PATH + "/") -> attachment(request, path.removePrefix(Attachments.CONTENT_PATH + "/"))
            path.startsWith("/api/") -> api(request, path)
            path == "/actors/gateway/threadActor/websocket/" -> threadSocket(request)
            path == "/actors/gateway/threadActor/request/rpc" -> threadRpc(request)
            path == "/actors/gateway/userActor/connect" -> userSocket(request)
            path.startsWith("/actors/gateway/userActor/action/") -> userAction(request, path.substringAfterLast('/'))
            path.startsWith("/portal/") -> portal(path)
            else -> reply(404, json("error" to "fake-amp has no route for $path"))
        }
    }

    /** Every `/api` route needs a Bearer token the scenario's auth recognizes. */
    private fun api(request: RecordedRequest, path: String): MockResponse {
        val identity = bearerOf(request)?.let { token -> world.sync { world.auth.identityOf(token) } }
            ?: return reply(401, json("error" to "Unauthorized"))
        val body = bodyJson(request)
        return when {
            path == "/api/internal" -> {
                val method = request.url.encodedQuery ?: body?.string("method") ?: return reply(400, World.notFound("No method"))
                reply(200, world.sync { world.internal(method, body?.obj("params") ?: json(), identity) })
            }
            path == "/api/thread-actors" -> world.sync { world.threadGrant(body ?: json()) }.let { (status, answer) -> reply(status, answer) }
            path == "/api/user-actor-credentials" -> reply(201, world.sync { world.userGrant(identity) })
            path == "/api/attachments/resumable" -> when (body?.string("action")) {
                "prepare" -> reply(200, world.sync { world.attachments.prepare(body) })
                "complete" -> world.sync { world.attachments.complete(body) }?.let { reply(200, it) }
                    ?: reply(400, json("error" to "Upload was not received"))
                else -> reply(400, json("error" to "Unknown action"))
            }
            DIFF_CAPTURES.matches(path) -> MockResponse.Builder().code(204).build()
            else -> reply(404, json("error" to "fake-amp has no route for $path"))
        }
    }

    /** Attachment content needs Bearer, and answers 401 without it (S12). */
    private fun attachment(request: RecordedRequest, key: String): MockResponse {
        if (bearerOf(request) == null) return reply(401, json("error" to "Unauthorized"))
        val stored = world.sync { world.attachments.get(key) } ?: return reply(404, json("error" to "Not found"))
        return MockResponse.Builder().code(200).setHeader("Content-Type", stored.mediaType).body(Buffer().write(stored.bytes)).build()
    }

    private fun threadSocket(request: RecordedRequest): MockResponse {
        val params = connParams(request) ?: return reply(400, json("error" to "missing rivet_conn_params"))
        if (!world.sync { world.knows(params.string("wsToken")) }) return reply(401, json("error" to "Invalid wsToken"))
        val thread = world.sync { world.thread(request.url.queryParameter("rvt-key")) } ?: return reply(404, json("error" to "Actor not found"))
        return upgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = world.post { thread.attach(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) = world.post { thread.receive(webSocket, text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = world.post { thread.receive(webSocket, bytes) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = world.post { thread.detach(webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = world.post { thread.detach(webSocket) }
        })
    }

    private fun userSocket(request: RecordedRequest): MockResponse {
        val params = connParams(request) ?: return reply(400, json("error" to "missing rivet_conn_params"))
        if (!world.sync { world.knows(params.string("wsToken")) }) return reply(401, json("error" to "Invalid wsToken"))
        return upgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = world.post { world.attachUser(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) = world.post { world.receiveUser(webSocket, text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = world.post { world.detachUser(webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = world.post { world.detachUser(webSocket) }
        })
    }

    /** The stateless path carries the actor token in `x-rivet-conn-params` and answers JSON-RPC with HTTP 200 (S6, S18). */
    private fun threadRpc(request: RecordedRequest): MockResponse {
        val params = request.headers["x-rivet-conn-params"]?.let(::parseObject)
        if (!world.sync { world.knows(params?.string("wsToken")) }) return reply(401, json("error" to "Invalid wsToken"))
        val frame = bodyJson(request) ?: return reply(400, json("error" to "Body is not JSON"))
        return world.sync {
            val thread = world.thread(request.url.queryParameter("rvt-key")) ?: return@sync reply(404, json("error" to "Actor not found"))
            reply(200, world.statelessRpc(thread, frame))
        }
    }

    private fun userAction(request: RecordedRequest, name: String): MockResponse {
        val params = request.headers["x-rivet-conn-params"]?.let(::parseObject)
        if (!world.sync { world.knows(params?.string("wsToken")) }) return reply(401, json("error" to "Invalid wsToken"))
        val args = bodyJson(request)?.array("args") ?: JsonArray(emptyList())
        val output = world.sync { world.userAction(name, args) } ?: return reply(404, json("code" to "action_not_found", "message" to "Action $name not found"))
        return reply(200, json("output" to output))
    }

    /**
     * The S13 data plane: the login URL redirects to the portal root (`captures/s13-portal.jsonl:8`). The recorded root
     * answered 503; this one serves a page naming the port so a web view has something to show.
     */
    private fun portal(path: String): MockResponse {
        val host = path.removePrefix("/portal/").substringBefore('/')
        if (path.endsWith("/__amp_auth/headless")) {
            return MockResponse.Builder().code(302).setHeader("Location", "${world.origin}/portal/$host/").build()
        }
        val title = listOfNotNull("Fake portal", PORT_LABEL.find(host)?.groupValues?.get(1)).joinToString(" ")
        val page = "<!doctype html><html><head><title>$title</title></head><body><h1>$title</h1><p>Served by fake-amp for $host.</p></body></html>"
        return MockResponse.Builder().code(200).setHeader("Content-Type", "text/html; charset=utf-8").body(page).build()
    }

    private fun upgrade(listener: WebSocketListener): MockResponse =
        MockResponse.Builder().webSocketUpgrade(listener).setHeader("Sec-WebSocket-Protocol", "rivet").build()

    /** The JSON inside the `rivet_conn_params.<encodeURIComponent(json)>` subprotocol. */
    private fun connParams(request: RecordedRequest): JsonObject? {
        val offered = request.headers.values("Sec-WebSocket-Protocol").flatMap { it.split(',') }.map { it.trim() }
        if ("rivet" !in offered) return null
        val encoded = offered.firstOrNull { it.startsWith(CONN_PARAMS) }?.removePrefix(CONN_PARAMS) ?: return null
        return parseObject(URLDecoder.decode(encoded, "UTF-8"))
    }

    private fun auth(request: RecordedRequest, path: String): MockResponse {
        val body = request.body?.utf8().orEmpty()
        val form = AuthRequest(request.method ?: "GET", path, { request.url.queryParameter(it) }, body, bearerOf(request))
        val answer = world.sync { world.auth.route(form, world.origin) }
            ?: return reply(404, json("error" to "fake-amp has no route for $path"))
        return MockResponse.Builder().code(answer.status).setHeader("Content-Type", "application/json").apply {
            answer.headers.forEach { (name, value) -> setHeader(name, value) }
        }.body(world.served(answer.body)).build()
    }

    private fun bearerOf(request: RecordedRequest): String? =
        request.headers["Authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }

    private fun bodyJson(request: RecordedRequest): JsonObject? = request.body?.utf8()?.let(::parseObject)

    private fun parseObject(text: String): JsonObject? = runCatching { FakeJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun capturedBody(capture: String, channel: String): JsonObject = Capture.load(capture).responseBody(channel)

    private fun reply(code: Int, body: JsonObject): MockResponse =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(world.served(body)).build()

    private companion object {
        const val CONN_PARAMS = "rivet_conn_params."
        val DIFF_CAPTURES = Regex("^/api/threads/[^/]+/diff-captures/latest$")
        val AUTH_PATH = Regex("^/(api/auth/|auth/|user_management/)")
        val PORT_LABEL = Regex("-p(\\d+)$")
    }
}
