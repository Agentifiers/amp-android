package com.agentifiers.amp.sync.internal.push

import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.GateResponse
import com.agentifiers.amp.model.GateResponses
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.McpApprovalScope
import com.agentifiers.amp.model.PluginOutcome
import com.agentifiers.amp.model.ToolCallId
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * `1.` then the base64url of a short JSON object, one shape per [GateResponse] kind. The version prefix lets a
 * notification posted by an older build fail to decode rather than answer the wrong thing.
 */
internal object GateTokenCodec {
    private const val PREFIX = "1."

    fun encode(response: GateResponse): String {
        val json = buildJsonObject {
            put("g", response.gate.raw)
            when (response) {
                is GateResponse.Approval -> {
                    put("k", "approval")
                    put("c", response.call.raw)
                    put("ok", response.accepted)
                    response.denyFeedback?.let { put("f", it) }
                    putValue("x", response.askAnswers)
                }
                is GateResponse.PluginAnswer -> {
                    put("k", "plugin")
                    when (val outcome = response.outcome) {
                        is PluginOutcome.Result -> putValue("r", outcome.value)
                        is PluginOutcome.Cancelled -> put("cancel", outcome.reason)
                    }
                }
                is GateResponse.Elicitation -> {
                    put("k", "elicitation")
                    put("ok", response.accepted)
                    putValue("x", response.values)
                }
                is GateResponse.McpServer -> {
                    put("k", "mcp")
                    put("s", response.serverName)
                    put("h", response.specHash)
                    put("cmd", response.command)
                    put("scope", response.scope.name)
                }
            }
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().encodeToByteArray())
    }

    fun decode(token: String): GateResponse? {
        if (!token.startsWith(PREFIX)) return null
        val json = try {
            AmpJson.parseToJsonElement(Base64.getUrlDecoder().decode(token.removePrefix(PREFIX)).decodeToString()) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SerializationException) {
            null
        } ?: return null
        val gate = json.string("g")?.let(::GateId) ?: return null
        return when (json.string("k")) {
            "approval" -> GateResponses.approval(
                gate,
                ToolCallId(json.string("c") ?: return null),
                json.bool("ok") ?: return null,
                json.string("f"),
                json.value("x"),
            )
            "plugin" -> GateResponses.pluginAnswer(
                gate,
                json.string("cancel")?.let(PluginOutcome::Cancelled) ?: PluginOutcome.Result(json.value("r")),
            )
            "elicitation" -> GateResponses.elicitation(gate, json.bool("ok") ?: return null, json.value("x"))
            "mcp" -> GateResponses.mcpServer(
                gate,
                json.string("s") ?: return null,
                json.string("h") ?: return null,
                json.string("cmd") ?: return null,
                json.string("scope")?.let { name -> McpApprovalScope.entries.firstOrNull { it.name == name } } ?: return null,
            )
            else -> null
        }
    }

    private fun JsonObjectBuilder.putValue(key: String, value: JsonValue) {
        if (value != JsonValue.Null) put(key, PushGates.element(value))
    }

    private fun JsonObject.value(key: String): JsonValue = this[key]?.let(PushGates::value) ?: JsonValue.Null
}
