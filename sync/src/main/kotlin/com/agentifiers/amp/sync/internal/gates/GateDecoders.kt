package com.agentifiers.amp.sync.internal.gates

import com.agentifiers.amp.model.Answerability
import com.agentifiers.amp.model.ChoiceGate
import com.agentifiers.amp.model.ChoiceQuestion
import com.agentifiers.amp.model.ElicitField
import com.agentifiers.amp.model.ElicitationGate
import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.GateSource
import com.agentifiers.amp.model.HumanGate
import com.agentifiers.amp.model.PluginCommand
import com.agentifiers.amp.model.PluginGate
import com.agentifiers.amp.model.SafeElicitUrl
import com.agentifiers.amp.model.ToolApprovalGate
import com.agentifiers.amp.model.ToolCallId
import com.agentifiers.amp.model.ToolName
import com.agentifiers.amp.model.UnsupportedGate
import com.agentifiers.amp.model.UnsupportedWhy
import com.agentifiers.amp.sync.internal.wire.array
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Wire to [GateFact]. Every gate decodes against the viewer `write` capability; [GateBook.pending] lowers it
 * for contributors and readers. A gate that cannot be decoded becomes [UnsupportedGate], never an exception.
 */
internal object GateDecoders {
    /** `tool_approval_queue{approvals}`. Item shape read from the web client (`3a8e98d6.js` `pr`), not observed (S15). */
    fun approvalQueue(body: JsonObject): GateFact? {
        val items = body.array("approvals") ?: return null
        return GateFact.Approvals(items.mapIndexedNotNull { index, item -> approval(item as? JsonObject, index) })
    }

    /** `plugin_message{message}`. Null for plugin events that say nothing about gates, such as `link-pattern.list`. */
    fun pluginMessage(body: JsonObject): GateFact? {
        val message = body.obj("message") ?: return null
        if (message.string("type") == "response") return reply(message)
        val data = message.obj("data") ?: JsonObject(emptyMap())
        return when (message.string("event")) {
            "plugin.ui.snapshot" -> data.array("requests")?.let { requests ->
                GateFact.PluginSnapshot(requests.mapNotNull { (it as? JsonObject)?.takeIf(::isPending)?.let(::pluginRequest) })
            }
            "plugin.ui.request_added" -> data.obj("request")?.takeIf(::isPending)?.let { request ->
                request.string("requestId")?.let { GateFact.PluginRaised(GateId(it), pluginRequest(request)) }
            }
            "plugin.ui.request_settled" -> data.obj("request")?.string("requestId")?.let { GateFact.PluginSettled(GateId(it)) }
            "plugin.ui.request_removed" -> (data.string("requestId") ?: data.obj("request")?.string("requestId"))?.let { GateFact.PluginRemoved(GateId(it)) }
            "ui.notify" -> data.string("message")?.let(GateFact::Notice)
            "command.list" -> data.array("commands")?.let { commands -> GateFact.Commands(commands.mapNotNull { (it as? JsonObject)?.let(::command) }) }
            else -> null
        }
    }

    private fun reply(message: JsonObject): GateFact? {
        val id = message.string("id") ?: return null
        val result = message.obj("result")
        val failure = message.obj("error")?.let { it.string("message") ?: "The plugin refused the request." }
            ?: result?.takeIf { it.bool("ok") == false }?.let { it.obj("error")?.string("message") ?: "The plugin command failed." }
            ?: result?.string("status")?.takeIf { it != "accepted" }?.let { "The plugin answered $it." }
        return GateFact.PluginReply(id, failure)
    }

    private fun isPending(request: JsonObject) = request.string("status") == "pending"

    private fun approval(item: JsonObject?, index: Int): HumanGate? {
        if (item == null) return null
        val call = item.string("toolCallId")
        val id = GateId(call ?: item.string("id") ?: "approval-$index")
        val raisedAt = item.string("timestamp")?.let(::isoInstant) ?: EPOCH
        val tool = item.string("toolName")
        val args = item.obj("args")
        val source = tool?.let(::toolSource) ?: GateSource.BuiltIn
        if (call == null || tool == null || args == null) return UnsupportedGate(id, raisedAt, Answerability.Answerable, source, "Approval", UnsupportedWhy.DecodeFailed)
        if (tool == ASK_USER_CHOICE) {
            val questions = choiceQuestions(args)
                ?: return UnsupportedGate(id, raisedAt, Answerability.Answerable, source, "Question", UnsupportedWhy.DecodeFailed)
            return ChoiceGate(id, raisedAt, Answerability.Answerable, source, ToolCallId(call), questions.toPersistentList())
        }
        return ToolApprovalGate(
            id, raisedAt, Answerability.Answerable, source, ToolCallId(call), ToolName(tool),
            summary = summaryOf(args), input = args.toJsonValue(), reason = item.string("reason"),
        )
    }

    /** MCP tools are named `mcp__<server>__<tool>` [INFERENCE: the CLI's naming]; everything else is built in. */
    private fun toolSource(tool: String): GateSource =
        tool.split("__").takeIf { it.size >= 3 && it[0] == "mcp" }?.let { GateSource.Mcp(it[1]) } ?: GateSource.BuiltIn

    /** The whole command for shell tools; otherwise all of the input, so Allow never approves unseen arguments. */
    private fun summaryOf(args: JsonObject): String {
        val command = args.string("command") ?: args.string("cmd")
        return if (command != null && args.keys.all { it in SHELL_KEYS }) command else PrettyJson.encodeToString(JsonObject.serializer(), args)
    }

    /**
     * ask_user_choice input is not observed [INFERENCE]: `{questions:[{question, options:[label | {label}], multiSelect?, allowOther?}]}`,
     * or one question at the top level. Answers key on the question text.
     */
    private fun choiceQuestions(args: JsonObject): List<ChoiceQuestion>? {
        val raw = args.array("questions")?.map { it as? JsonObject ?: return null } ?: listOf(args)
        if (raw.isEmpty()) return null
        return raw.map { question ->
            val prompt = question.string("question") ?: question.string("prompt") ?: return null
            val options = question.array("options")?.map { option ->
                (option as? JsonPrimitive)?.takeIf { it.isString }?.content ?: (option as? JsonObject)?.string("label") ?: return null
            } ?: return null
            ChoiceQuestion(
                key = prompt,
                prompt = prompt,
                options = options.toPersistentList(),
                multi = question.bool("multiSelect") ?: question.bool("multi") ?: false,
                allowOther = question.bool("allowOther") ?: false,
            )
        }
    }

    private fun pluginRequest(request: JsonObject): HumanGate? {
        val id = GateId(request.string("requestId") ?: return null)
        val target = request.obj("target")?.string("kind")
        if (target != null && target != "interactive") return null
        val raisedAt = request.string("createdAt")?.let(::httpInstant) ?: EPOCH
        val source = GateSource.Plugin(request.string("pluginName") ?: request.obj("invocation")?.string("pluginName") ?: "Plugin")
        val params = request.obj("params") ?: JsonObject(emptyMap())
        val options = params.obj("options") ?: JsonObject(emptyMap())
        val unsupported = { label: String, why: UnsupportedWhy -> UnsupportedGate(id, raisedAt, Answerability.Answerable, source, label, why) }
        return when (request.string("method")) {
            "ui.confirm" -> {
                val title = options.string("title") ?: return unsupported("Plugin dialog", UnsupportedWhy.DecodeFailed)
                if (options.array("fields")?.isNotEmpty() == true) return unsupported(title, UnsupportedWhy.AnswerPathNotVerified)
                PluginGate.Confirm(id, raisedAt, Answerability.Answerable, source, title, options.string("message").orEmpty(), null, options.string("confirmButtonText") ?: "Yes")
            }
            "ui.input" -> {
                val title = options.string("title") ?: return unsupported("Plugin dialog", UnsupportedWhy.DecodeFailed)
                val help = options.string("helpText").orEmpty()
                val submit = options.string("submitButtonText") ?: "Submit"
                if (options.bool("secret") == true) {
                    PluginGate.SecretInput(id, raisedAt, Answerability.Answerable, source, title, help, submit, options.string("placeholder"))
                } else {
                    PluginGate.Input(id, raisedAt, Answerability.Answerable, source, title, help, submit, options.string("initialValue").orEmpty(), options.string("placeholder"))
                }
            }
            "ui.select" -> {
                val title = options.string("title") ?: return unsupported("Plugin choice", UnsupportedWhy.DecodeFailed)
                val choices = options.array("options")?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return unsupported(title, UnsupportedWhy.DecodeFailed) }
                    ?: return unsupported(title, UnsupportedWhy.DecodeFailed)
                PluginGate.Select(id, raisedAt, Answerability.Answerable, source, title, choices.toPersistentList())
            }
            ELICITATION -> elicitation(id, raisedAt, params) ?: unsupported("MCP question", UnsupportedWhy.DecodeFailed)
            else -> unsupported("Plugin request", UnsupportedWhy.UnknownKind)
        }
    }

    /**
     * MCP `elicitation/create` params (spec 2025-11-25): `{message, requestedSchema{properties, required}}` in form mode,
     * `{mode:"url", message, url}` in URL mode. The server name rides in `serverName` [INFERENCE]. Multiple-choice
     * fields are refused, as Amp cancels those (customize__mcp.md:285).
     */
    private fun elicitation(id: GateId, raisedAt: Instant, params: JsonObject): ElicitationGate? {
        val message = params.string("message") ?: return null
        val source = GateSource.Mcp(params.string("serverName") ?: "MCP server")
        val expiresAt = raisedAt + ELICITATION_TTL
        if (params.string("mode") == "url") {
            val url = params.string("url")?.let(SafeElicitUrl::parse) ?: return null
            return ElicitationGate.OpenUrl(id, raisedAt, Answerability.Answerable, source, message, url, expiresAt)
        }
        val properties = params.obj("requestedSchema")?.obj("properties") ?: return null
        val fields = properties.map { (key, value) ->
            val schema = value as? JsonObject ?: return null
            val label = schema.string("title") ?: schema.string("description") ?: key
            val choices = schema.array("enum") ?: schema.array("oneOf")?.let { list -> JsonArray(list.mapNotNull { (it as? JsonObject)?.get("const") }) }
            when {
                choices != null -> ElicitField.OneOf(key, label, choices.map { (it as? JsonPrimitive)?.content ?: return null }.toPersistentList())
                schema.string("type") == "string" -> ElicitField.Text(key, label)
                schema.string("type") == "number" || schema.string("type") == "integer" -> ElicitField.Number(key, label)
                schema.string("type") == "boolean" -> ElicitField.YesNo(key, label)
                else -> return null
            }
        }
        return ElicitationGate.Form(id, raisedAt, Answerability.Answerable, source, message, fields.toPersistentList(), expiresAt)
    }

    private fun command(command: JsonObject): PluginCommand? {
        val availability = command.obj("availability")?.string("type")
        if (availability == "hidden") return null
        val plugin = command.string("pluginName") ?: return null
        return PluginCommand(plugin, command.string("id") ?: return null, command.string("title") ?: return null, command.string("category"), availability == null || availability == "enabled")
    }

    private fun isoInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

    /** Plugin dialogs stamp `createdAt` as an HTTP date: "Fri, 23 Oct 2026 05:32:17 GMT" (S15b :64). */
    private fun httpInstant(text: String): Instant? = runCatching {
        Instant.fromEpochSeconds(ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond())
    }.getOrNull()

    private val PrettyJson = Json { prettyPrint = true }
    private val SHELL_KEYS = setOf("command", "cmd", "workdir")
    private val EPOCH = Instant.fromEpochSeconds(0)
    private val ELICITATION_TTL = 10.minutes
    const val ASK_USER_CHOICE = "ask_user_choice"
    const val ELICITATION = "elicitation/create"
}
