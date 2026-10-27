package com.agentifiers.amp.sync.internal.net

import com.agentifiers.amp.model.ThreadId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** What the thread actor socket reports, in arrival order. */
internal sealed interface SocketSignal {
    data class Text(val text: String) : SocketSignal
    class Binary(val bytes: ByteArray) : SocketSignal
    data class Ended(val cause: String) : SocketSignal
}

/**
 * One thread actor WebSocket (transport.md, "Socket URL" and "Subprotocols"). The `wsToken` travels only
 * inside the `rivet_conn_params` subprotocol. The server must select `rivet`.
 */
internal class ThreadSocket private constructor(private val signalChannel: Channel<SocketSignal>) : WebSocketListener() {
    val signals: ReceiveChannel<SocketSignal> get() = signalChannel
    private lateinit var webSocket: WebSocket
    private var counter = 0L

    /** Sends a JSON-RPC request and returns its id. Responses arrive through [signals]. */
    fun request(method: String, params: JsonObject): String? {
        val id = "thread-client-${System.currentTimeMillis()}-${counter++}"
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        return if (webSocket.send(frame.toString())) id else null
    }

    /** Sends a JSON-RPC notification: no id, no response. `executor_plugin_message` goes this way (S15b :63, :66). */
    fun notify(method: String, params: JsonObject): Boolean = webSocket.send(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }.toString(),
    )

    fun sendBinary(bytes: ByteArray): Boolean = webSocket.send(bytes.toByteString())

    /** Ends the session from this side: the reader sees [SocketSignal.Ended] with [cause]. */
    fun abort(cause: String) {
        signalChannel.trySend(SocketSignal.Ended(cause))
        webSocket.cancel()
    }

    fun close() {
        webSocket.close(1000, null)
        signalChannel.close()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        val selected = response.header("Sec-WebSocket-Protocol")
        if (selected != "rivet") {
            webSocket.cancel()
            signalChannel.trySend(SocketSignal.Ended("server selected subprotocol $selected"))
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        signalChannel.trySend(SocketSignal.Text(text))
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        signalChannel.trySend(SocketSignal.Binary(bytes.toByteArray()))
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        signalChannel.trySend(SocketSignal.Ended("closed $code $reason".trim()))
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        signalChannel.trySend(SocketSignal.Ended(t.message ?: t.javaClass.simpleName))
    }

    companion object {
        fun open(client: OkHttpClient, endpoint: Endpoint, thread: ThreadId, wsToken: String, clientVersion: String): ThreadSocket {
            val params = buildJsonObject {
                put("wsToken", wsToken)
                putJsonObject("clientApplication") {
                    put("type", "cli")
                    put("name", "amp-android")
                    put("version", clientVersion)
                }
                put("interactiveClient", true)
                put("transport", "json-rpc")
            }
            val protocols = listOf("rivet", "rivet_encoding.bare", "rivet_skip_ready_wait", "rivet_conn_params.${encodeURIComponent(params.toString())}")
            val request = Request.Builder()
                .url(endpoint.gateway("threadActor", "websocket/", thread.raw))
                .header("Sec-WebSocket-Protocol", protocols.joinToString(", "))
                .build()
            val socket = ThreadSocket(Channel(Channel.UNLIMITED))
            socket.webSocket = client.newWebSocket(request, socket)
            return socket
        }

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

        /** JavaScript's encodeURIComponent, which the CLI and the spike use for the conn params. */
        internal fun encodeURIComponent(value: String): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val c = (byte.toInt() and 0xff)
                if (c < 0x80 && c.toChar() in UNRESERVED) append(c.toChar()) else append('%').append("%02X".format(c))
            }
        }
    }
}
