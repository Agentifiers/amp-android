package com.agentifiers.amp.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlin.time.Instant

/**
 * Everything that blocks an agent on a person. Each gate builds its own response, so a response can never
 * be sent to the wrong kind of gate, and a gate the viewer cannot answer has no responder at all.
 */
sealed interface HumanGate {
    val id: GateId
    val raisedAt: Instant
    val answerability: Answerability
    val source: GateSource
}

sealed interface Answerability {
    data object Answerable : Answerability
    data class ReadOnly(val reason: ReadOnlyReason) : Answerability
}

enum class ReadOnlyReason { ContributorCannotApprove, ContributorCannotEnterSecrets, ReaderOnly, AnsweredElsewhere, Expired, RequiresHumanOnOtherClient }

sealed interface GateSource {
    data object BuiltIn : GateSource
    data class Plugin(val name: String) : GateSource
    data class Mcp(val server: String) : GateSource
}

private fun HumanGate.requireAnswerable() =
    check(answerability == Answerability.Answerable) { "gate ${id.raw} is $answerability and has no responder" }

/**
 * tool_approval_queue + client_tool_approval_response{toolCallId, accepted, input?}. The approval wire is
 * read from the web client, not observed (spike S15 queued none). The answer goes over the socket.
 */
data class ToolApprovalGate(
    override val id: GateId,
    override val raisedAt: Instant,
    override val answerability: Answerability,
    override val source: GateSource,
    val call: ToolCallId,
    val tool: ToolName,
    /** The shell command, or the MCP tool and its arguments. */
    val summary: String,
    val input: JsonValue,
    /** The policy's reason for asking, such as "Potentially destructive git operation". */
    val reason: String? = null,
) : HumanGate {
    fun approve(): GateResponse {
        requireAnswerable()
        return GateResponse.Approval(id, call, accepted = true, denyFeedback = null, askAnswers = JsonValue.Null)
    }

    fun deny(feedback: String?): GateResponse {
        requireAnswerable()
        return GateResponse.Approval(id, call, accepted = false, denyFeedback = feedback, askAnswers = JsonValue.Null)
    }
}

/** Built-in ask_user_choice tool; answered through the approval response with askAnswers. */
data class ChoiceGate(
    override val id: GateId,
    override val raisedAt: Instant,
    override val answerability: Answerability,
    override val source: GateSource,
    val call: ToolCallId,
    val questions: PersistentList<ChoiceQuestion>,
) : HumanGate {
    /**
     * askAnswers is `record<string, string>` in the web client's schema (11958a39.js, `client_tool_approval_response`),
     * so each question key maps to one string: the chosen options, then the free text, joined with ", ".
     */
    fun answer(selections: Map<String, List<String>>, freeText: Map<String, String>): GateResponse {
        requireAnswerable()
        val answers = questions.associate { question ->
            val parts = selections[question.key].orEmpty() + listOfNotNull(freeText[question.key]?.trim()?.takeIf { it.isNotEmpty() })
            question.key to JsonValue.Str(parts.joinToString(", "))
        }
        return GateResponse.Approval(id, call, accepted = true, denyFeedback = null, askAnswers = JsonValue.Obj(answers.toPersistentMap()))
    }

    fun decline(): GateResponse {
        requireAnswerable()
        return GateResponse.Approval(id, call, accepted = false, denyFeedback = null, askAnswers = JsonValue.Null)
    }
}

data class ChoiceQuestion(val key: String, val prompt: String, val options: PersistentList<String>, val multi: Boolean, val allowOther: Boolean)

/**
 * Plugin ctx.ui.* dialogs, measured in spike S15b: raised by plugin.ui.request_added, settled by
 * plugin.ui.request_settled, re-listed in plugin.ui.snapshot on reconnect. ui.confirm answers a boolean and
 * ui.input a string (s15b-plugin-dialog.jsonl). ui.select is read from docs, not observed. GateId is the requestId.
 */
sealed interface PluginGate : HumanGate {
    /** Cancel and dismiss answer `{requestId, error}`, which settles the dialog as "errored" (S15c :115). */
    fun cancel(reason: String): GateResponse {
        requireAnswerable()
        return GateResponse.PluginAnswer(id, PluginOutcome.Cancelled(reason))
    }

    data class Confirm(
        override val id: GateId,
        override val raisedAt: Instant,
        override val answerability: Answerability,
        override val source: GateSource,
        val title: String,
        val body: String,
        val fileField: FileFieldSpec?,
        val confirmLabel: String = "Yes",
    ) : PluginGate {
        fun respond(confirmed: Boolean, file: ByteArray?): GateResponse {
            requireAnswerable()
            if (file == null) return GateResponse.PluginAnswer(id, PluginOutcome.Result(JsonValue.Bool(confirmed)))
            val spec = requireNotNull(fileField) { "gate ${id.raw} has no file field" }
            require(file.size <= spec.maxBytes) { "file is ${file.size} bytes, the field allows ${spec.maxBytes}" }
            val fields = mapOf("confirmed" to JsonValue.Bool(confirmed), "file" to JsonValue.Str(file.decodeToString(throwOnInvalidSequence = true)))
            return GateResponse.PluginAnswer(id, PluginOutcome.Result(JsonValue.Obj(fields.toPersistentMap())))
        }
    }

    data class Input(
        override val id: GateId,
        override val raisedAt: Instant,
        override val answerability: Answerability,
        override val source: GateSource,
        val title: String,
        val helpText: String,
        val submitLabel: String,
        val initialValue: String = "",
        val placeholder: String? = null,
    ) : PluginGate {
        fun respond(text: String): GateResponse {
            requireAnswerable()
            return GateResponse.PluginAnswer(id, PluginOutcome.Result(JsonValue.Str(text)))
        }
    }

    /** ui.input with secret = true. Its answer is Ephemeral (ThreadIntent.AnswerSecret), so the value never reaches Room. */
    data class SecretInput(
        override val id: GateId,
        override val raisedAt: Instant,
        override val answerability: Answerability,
        override val source: GateSource,
        val title: String,
        val helpText: String,
        val submitLabel: String,
        val placeholder: String? = null,
    ) : PluginGate {
        fun respond(text: String): SecretPluginAnswer {
            requireAnswerable()
            return SecretPluginAnswer(id, text)
        }
    }

    data class Select(
        override val id: GateId,
        override val raisedAt: Instant,
        override val answerability: Answerability,
        override val source: GateSource,
        val title: String,
        val options: PersistentList<String>,
    ) : PluginGate {
        fun respond(index: Int): GateResponse {
            requireAnswerable()
            require(index in options.indices) { "option $index is outside ${options.indices}" }
            return GateResponse.PluginAnswer(id, PluginOutcome.Result(JsonValue.Num(index.toDouble())))
        }
    }
}

/** UTF-8 file attached to a confirm dialog; at most 64 KiB (customize-enterprise.md:329). */
data class FileFieldSpec(val label: String, val maxBytes: Int)

/**
 * A gate the app cannot decode or cannot answer natively. The dock and the notification render
 * [Answer on web], which opens AccountWebPage.ThreadOnWeb. It has no responder.
 */
data class UnsupportedGate(
    override val id: GateId,
    override val raisedAt: Instant,
    override val answerability: Answerability,
    override val source: GateSource,
    /** Shown on the card: "MCP question", "Plugin form", "Approval". */
    val kindLabel: String,
    val why: UnsupportedWhy,
) : HumanGate

enum class UnsupportedWhy { UnknownKind, DecodeFailed, AnswerPathNotVerified, AnswerRejected }

/**
 * MCP elicitation. The delivery channel is not observed. :sync decodes it only in the MCP `elicitation/create`
 * shape on the plugin dialog channel [INFERENCE]; anything else becomes UnsupportedGate.
 */
sealed interface ElicitationGate : HumanGate {
    data class Form(
        override val id: GateId,
        override val raisedAt: Instant,
        override val answerability: Answerability,
        override val source: GateSource,
        val message: String,
        val fields: PersistentList<ElicitField>,
        val expiresAt: Instant,
    ) : ElicitationGate {
        fun submit(values: Map<String, JsonValue>): GateResponse {
            requireAnswerable()
            return GateResponse.Elicitation(id, accepted = true, values = JsonValue.Obj(values.toPersistentMap()))
        }

        fun decline(): GateResponse {
            requireAnswerable()
            return GateResponse.Elicitation(id, accepted = false, values = JsonValue.Null)
        }
    }

    data class OpenUrl(
        override val id: GateId,
        override val raisedAt: Instant,
        override val answerability: Answerability,
        override val source: GateSource,
        val message: String,
        val url: SafeElicitUrl,
        val expiresAt: Instant,
    ) : ElicitationGate {
        fun continueAfterOpening(): GateResponse {
            requireAnswerable()
            return GateResponse.Elicitation(id, accepted = true, values = JsonValue.Null)
        }

        fun decline(): GateResponse {
            requireAnswerable()
            return GateResponse.Elicitation(id, accepted = false, values = JsonValue.Null)
        }
    }
}

sealed interface ElicitField {
    val key: String

    data class Text(override val key: String, val label: String) : ElicitField
    data class Number(override val key: String, val label: String) : ElicitField
    data class YesNo(override val key: String, val label: String) : ElicitField
    data class OneOf(override val key: String, val label: String, val options: PersistentList<String>) : ElicitField
}

/** Only constructible through [parse], which accepts https or http://localhost, so UI never re-validates. */
@JvmInline
value class SafeElicitUrl private constructor(val url: String) {
    companion object {
        private val shape = Regex("^(https://[^\\s/?#]+|http://localhost(:\\d{1,5})?)([/?#]\\S*)?$", RegexOption.IGNORE_CASE)

        fun parse(raw: String): SafeElicitUrl? = if (shape.matches(raw)) SafeElicitUrl(raw) else null
    }
}

/** Repository MCP server approval in orbs: listMCPServerApprovals / approveMCPServer. */
data class McpServerApprovalGate(
    override val id: GateId,
    override val raisedAt: Instant,
    override val answerability: Answerability,
    override val source: GateSource,
    val serverName: String,
    val command: String,
    val specHash: String,
) : HumanGate {
    fun approve(scope: McpApprovalScope): GateResponse {
        requireAnswerable()
        return GateResponse.McpServer(id, serverName, specHash, command, scope)
    }
}

enum class McpApprovalScope { Thread, Project, Always }

/** Built only by gate methods (constructors are internal to :core:model) and mapped to the wire by :sync. */
sealed interface GateResponse {
    val gate: GateId

    /** client_tool_approval_response{toolCallId, accepted, input?{askAnswers?, denyFeedback?}} */
    @ConsistentCopyVisibility
    data class Approval internal constructor(
        override val gate: GateId,
        val call: ToolCallId,
        val accepted: Boolean,
        val denyFeedback: String?,
        val askAnswers: JsonValue,
    ) : GateResponse

    /** plugin.ui.respond{requestId, result | error}; `error` cancels the dialog, which settles as "errored". */
    @ConsistentCopyVisibility
    data class PluginAnswer internal constructor(override val gate: GateId, val outcome: PluginOutcome) : GateResponse

    @ConsistentCopyVisibility

    data class Elicitation internal constructor(override val gate: GateId, val accepted: Boolean, val values: JsonValue) : GateResponse

    @ConsistentCopyVisibility

    data class McpServer internal constructor(
        override val gate: GateId,
        val serverName: String,
        val specHash: String,
        val command: String,
        val scope: McpApprovalScope,
    ) : GateResponse
}

/**
 * plugin.ui.respond for PluginGate.SecretInput. Not a GateResponse, so ThreadIntent.Answer cannot carry it
 * and a notification token cannot encode it; it travels only in ThreadIntent.AnswerSecret.
 */
@ConsistentCopyVisibility
data class SecretPluginAnswer internal constructor(val gate: GateId, val text: String)

sealed interface PluginOutcome {
    data class Result(val value: JsonValue) : PluginOutcome
    data class Cancelled(val reason: String) : PluginOutcome
}

/** A plugin command from plugin_message command.list (S15b). Run with ThreadIntent.RunPluginCommand. */
data class PluginCommand(val plugin: String, val id: String, val title: String, val category: String?, val enabled: Boolean)

/** How a gate left the dock while this device watched. The dock shows it as a one-line record. */
data class GateRecord(val gate: GateId, val title: String, val outcome: GateOutcome, val at: Instant)

sealed interface GateOutcome {
    /** Answered from this device, with the line to show: "Allowed by you", "Declined". */
    data class ByYou(val line: String) : GateOutcome
    /** Settled by another client; the dock shows "Answered elsewhere" for 3 seconds. */
    data object Elsewhere : GateOutcome
    /** Amp withdrew a plugin dialog with plugin.ui.request_removed. */
    data object Withdrawn : GateOutcome
}

/** A plugin `ui.notify` message (S15b :73). [id] counts up per thread engine so each shows once. */
data class PluginNotice(val id: Long, val text: String)
