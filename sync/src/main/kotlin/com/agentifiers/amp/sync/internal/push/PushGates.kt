package com.agentifiers.amp.sync.internal.push

import com.agentifiers.amp.model.Answerability
import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.GateResponse
import com.agentifiers.amp.model.GateSource
import com.agentifiers.amp.model.HumanGate
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.PluginGate
import com.agentifiers.amp.model.PluginOutcome
import com.agentifiers.amp.model.ToolApprovalGate
import com.agentifiers.amp.model.ToolCallId
import com.agentifiers.amp.model.ToolName
import com.agentifiers.amp.model.UnsupportedGate
import com.agentifiers.amp.model.UnsupportedWhy
import com.agentifiers.amp.sync.internal.wire.InboundFrame
import com.agentifiers.amp.sync.internal.wire.array
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Instant

/** A gate request on the wire: the socket method and its params. */
internal data class GateWireRequest(val method: String, val params: JsonObject)

/**
 * The gates a short socket's burst shows, and how to answer and recognize the settling of one. The approval wire is
 * read from the CLI schema (`fake-amp` Approvals); the plugin dialog wire is measured (`s15b-plugin-dialog.jsonl:64-68`).
 */
internal object PushGates {
    /** Pending gates after the events seen so far: the latest approval queue plus the latest plugin snapshot. */
    fun pending(events: List<InboundFrame.Event>): List<HumanGate> {
        val approvals = events.lastOrNull { it.type == "tool_approval_queue" }?.body?.array("approvals").orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::approval) }
        val dialogs = events.mapNotNull(::pluginEvent).lastOrNull { it.string("event") == "plugin.ui.snapshot" }
            ?.obj("data")?.array("requests").orEmpty().mapNotNull { (it as? JsonObject)?.let(::dialog) }
        return approvals + dialogs
    }

    fun request(response: GateResponse): GateWireRequest? = when (response) {
        is GateResponse.Approval -> GateWireRequest(
            "client_tool_approval_response",
            buildJsonObject {
                put("toolCallId", response.call.raw)
                put("accepted", response.accepted)
                if (response.denyFeedback != null || response.askAnswers != JsonValue.Null) {
                    putJsonObject("input") {
                        response.denyFeedback?.let { put("denyFeedback", it) }
                        if (response.askAnswers != JsonValue.Null) put("askAnswers", element(response.askAnswers))
                    }
                }
            },
        )
        is GateResponse.PluginAnswer -> GateWireRequest(
            "executor_plugin_message",
            buildJsonObject {
                putJsonObject("message") {
                    put("type", "request")
                    put("id", "plugin-ui-respond-${response.gate.raw}-1")
                    put("method", "plugin.ui.respond")
                    putJsonObject("params") {
                        put("requestId", response.gate.raw)
                        when (val outcome = response.outcome) {
                            is PluginOutcome.Result -> put("result", element(outcome.value))
                            is PluginOutcome.Cancelled -> putJsonObject("error") { put("message", outcome.reason) }
                        }
                    }
                }
            },
        )
        is GateResponse.Elicitation, is GateResponse.McpServer -> null
    }

    /** True when [event] shows [gate] is no longer waiting: gone from the approval queue, or settled or removed. */
    fun settles(event: InboundFrame.Event, gate: HumanGate): Boolean = when (gate) {
        is ToolApprovalGate -> event.type == "tool_approval_queue" &&
            event.body.array("approvals").orEmpty().none { (it as? JsonObject)?.string("toolCallId") == gate.call.raw }
        else -> pluginEvent(event)?.let { message ->
            message.string("event") in setOf("plugin.ui.request_settled", "plugin.ui.request_removed") &&
                message.obj("data")?.let { it.obj("request")?.string("requestId") ?: it.string("requestId") } == gate.id.raw
        } == true
    }

    private fun pluginEvent(event: InboundFrame.Event): JsonObject? =
        if (event.type == "plugin_message") event.body.obj("message")?.takeIf { it.string("type") == "event" } else null

    private fun approval(item: JsonObject): HumanGate? {
        val call = item.string("toolCallId") ?: return null
        val id = GateId(item.string("id") ?: call)
        val raisedAt = item.string("timestamp")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Clock.System.now()
        val tool = item.string("toolName") ?: return UnsupportedGate(id, raisedAt, Answerability.Answerable, GateSource.BuiltIn, "Approval", UnsupportedWhy.DecodeFailed)
        val args = item.obj("args") ?: JsonObject(emptyMap())
        val summary = args.string("command") ?: args.string("path") ?: args.toString()
        return ToolApprovalGate(id, raisedAt, Answerability.Answerable, GateSource.BuiltIn, ToolCallId(call), ToolName(tool), summary, value(args))
    }

    private fun dialog(request: JsonObject): HumanGate? {
        val id = GateId(request.string("requestId") ?: return null)
        val options = request.obj("params")?.obj("options") ?: JsonObject(emptyMap())
        val title = options.string("title").orEmpty()
        val now = Clock.System.now()
        val source = GateSource.Plugin(request.string("requester") ?: "plugin")
        return when (request.string("method")) {
            "ui.confirm" -> PluginGate.Confirm(id, now, Answerability.Answerable, source, title, options.string("message").orEmpty(), null)
            "ui.input" -> if (options.bool("secret") == true) {
                PluginGate.SecretInput(id, now, Answerability.Answerable, source, title, options.string("helpText").orEmpty(), options.string("submitButtonText") ?: "Submit")
            } else {
                PluginGate.Input(id, now, Answerability.Answerable, source, title, options.string("helpText").orEmpty(), options.string("submitButtonText") ?: "Submit")
            }
            else -> UnsupportedGate(id, now, Answerability.Answerable, source, "Plugin form", UnsupportedWhy.UnknownKind)
        }
    }

    fun value(element: JsonElement): JsonValue = when (element) {
        JsonNull -> JsonValue.Null
        is JsonPrimitive -> when {
            element.isString -> JsonValue.Str(element.content)
            element.booleanOrNull != null -> JsonValue.Bool(element.booleanOrNull!!)
            else -> element.doubleOrNull?.let(JsonValue::Num) ?: JsonValue.Str(element.content)
        }
        is JsonArray -> JsonValue.Arr(element.map(::value).toPersistentList())
        is JsonObject -> JsonValue.Obj(element.mapValues { value(it.value) }.toPersistentMap())
    }

    fun element(value: JsonValue): JsonElement = when (value) {
        JsonValue.Null -> JsonNull
        is JsonValue.Bool -> JsonPrimitive(value.v)
        is JsonValue.Num -> if (value.v % 1.0 == 0.0 && kotlin.math.abs(value.v) < 1e15) JsonPrimitive(value.v.toLong()) else JsonPrimitive(value.v)
        is JsonValue.Str -> JsonPrimitive(value.v)
        is JsonValue.Arr -> JsonArray(value.items.map(::element))
        is JsonValue.Obj -> JsonObject(value.fields.mapValues { element(it.value) })
    }
}
