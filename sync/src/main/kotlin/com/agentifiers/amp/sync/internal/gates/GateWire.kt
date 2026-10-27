package com.agentifiers.amp.sync.internal.gates

import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.GateResponse
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.McpApprovalScope
import com.agentifiers.amp.model.PluginOutcome
import com.agentifiers.amp.model.SecretPluginAnswer
import com.agentifiers.amp.model.ThreadId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** How one gate answer or plugin command travels. Never `/request/rpc`, which refuses all of them (S18). */
internal sealed interface GateRequest {
    /** A thread socket JSON-RPC request, settled by the response with the same id. */
    data class Socket(val method: String, val params: JsonObject) : GateRequest

    /**
     * An `executor_plugin_message` JSON-RPC notification. The plugin host answers with
     * `plugin_message{message{type:"response", id = [replyId]}}` (S15b :63-:74).
     */
    data class Plugin(val replyId: String, val params: JsonObject) : GateRequest {
        val method: String get() = EXECUTOR_PLUGIN_MESSAGE
    }

    /** An internal RPC over HTTP. */
    data class Internal(val method: String, val params: JsonObject) : GateRequest

    companion object {
        const val EXECUTOR_PLUGIN_MESSAGE = "executor_plugin_message"
    }
}

internal object GateWire {
    /** [attempt] numbers the respond ids the way the web client does: `plugin-ui-respond-<requestId>-<n>` (S15b :66). */
    fun request(response: GateResponse, attempt: Int): GateRequest = when (response) {
        is GateResponse.Approval -> GateRequest.Socket(
            "client_tool_approval_response",
            buildJsonObject {
                put("toolCallId", response.call.raw)
                put("accepted", response.accepted)
                if (response.askAnswers is JsonValue.Obj || response.denyFeedback != null) {
                    putJsonObject("input") {
                        if (response.askAnswers is JsonValue.Obj) put("askAnswers", response.askAnswers.toJsonElement())
                        response.denyFeedback?.let { put("denyFeedback", it) }
                    }
                }
            },
        )
        is GateResponse.PluginAnswer -> when (val outcome = response.outcome) {
            is PluginOutcome.Result -> respond(response.gate, attempt, "result", outcome.value.toJsonElement())
            is PluginOutcome.Cancelled -> respond(response.gate, attempt, "error", JsonPrimitive(outcome.reason))
        }
        is GateResponse.Elicitation -> respond(
            response.gate, attempt, "result",
            buildJsonObject {
                put("action", if (response.accepted) "accept" else "decline")
                if (response.accepted && response.values is JsonValue.Obj) put("content", response.values.toJsonElement())
            },
        )
        // approveMCPServer and its params are read from the web client, not observed.
        is GateResponse.McpServer -> GateRequest.Internal(
            "approveMCPServer",
            buildJsonObject {
                put("serverName", response.serverName)
                put("specHash", response.specHash)
                put("command", response.command)
                put("scope", response.scope.wire)
            },
        )
    }

    fun request(secret: SecretPluginAnswer, attempt: Int): GateRequest = respond(secret.gate, attempt, "result", JsonPrimitive(secret.text))

    /** `command.execute{pluginName, commandId, threadID}` (S15b :63). */
    fun runCommand(thread: ThreadId, plugin: String, commandId: String, replyId: String): GateRequest.Plugin =
        GateRequest.Plugin(
            replyId,
            message(replyId, "command.execute") {
                put("pluginName", plugin)
                put("commandId", commandId)
                put("threadID", thread.raw)
            },
        )

    private fun respond(gate: GateId, attempt: Int, key: String, value: JsonElement): GateRequest.Plugin {
        val id = "plugin-ui-respond-${gate.raw}-$attempt"
        return GateRequest.Plugin(
            id,
            message(id, "plugin.ui.respond") {
                put("requestId", gate.raw)
                put(key, value)
            },
        )
    }

    private fun message(id: String, method: String, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        putJsonObject("message") {
            put("type", "request")
            put("id", id)
            put("method", method)
            putJsonObject("params", params)
        }
    }

    private val McpApprovalScope.wire: String
        get() = when (this) {
            McpApprovalScope.Thread -> "thread"
            McpApprovalScope.Project -> "user"
            McpApprovalScope.Always -> "workspace"
        }
}
