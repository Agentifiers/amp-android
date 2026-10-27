package com.agentifiers.amp.sync.internal.wire

import com.agentifiers.amp.model.Seq
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One inbound thread actor text frame, normalized. Parsing never throws. */
internal sealed interface InboundFrame {
    /** A JSON-RPC notification (live) or a bare `{type}` frame (replay and the connect burst). */
    data class Event(val type: String, val seq: Seq?, val body: JsonObject) : InboundFrame

    data class Response(val id: String, val result: JsonObject?, val error: RpcError?) : InboundFrame

    /** A `client_*_result` notification for a request that carried a `requestId`. Never a fact. */
    data class Result(val requestId: String, val type: String, val body: JsonObject) : InboundFrame

    data object Pong : InboundFrame

    data class Unparseable(val length: Int) : InboundFrame
}

internal data class RpcError(val code: Long?, val message: String)

internal object FrameParser {
    fun parse(text: String): List<InboundFrame> {
        if (text == "pong") return listOf(InboundFrame.Pong)
        val element = try {
            AmpJson.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return listOf(InboundFrame.Unparseable(text.length))
        }
        return if (element is JsonArray) element.map(::frameOf) else listOf(frameOf(element))
    }

    private fun frameOf(element: JsonElement): InboundFrame {
        val frame = element as? JsonObject ?: return InboundFrame.Unparseable(element.toString().length)
        if ("jsonrpc" in frame) {
            val id = frame.string("id")
            if (id != null && ("result" in frame || "error" in frame)) {
                val error = frame.obj("error")?.let { RpcError(it.long("code"), it.string("message") ?: "error") }
                return InboundFrame.Response(id, frame.obj("result"), error)
            }
            val method = frame.string("method") ?: return InboundFrame.Unparseable(frame.toString().length)
            return event(method, frame.obj("params") ?: JsonObject(emptyMap()))
        }
        val type = frame.string("type") ?: return InboundFrame.Unparseable(frame.toString().length)
        return event(type, JsonObject(frame - "type"))
    }

    private fun event(type: String, body: JsonObject): InboundFrame {
        val requestId = body.string("requestId")
        if (type.endsWith("_result") && requestId != null) return InboundFrame.Result(requestId, type, body)
        return InboundFrame.Event(type, body.long("seq")?.let(::Seq), body)
    }
}
