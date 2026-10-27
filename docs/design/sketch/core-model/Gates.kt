// :core:model  package amp.model
// Human gates: everything that blocks an agent on a person. One queue, one dock, one notification
// kind, one answer intent. Each gate builds its own response so a response can never be sent to
// the wrong kind of gate, and a gate the viewer cannot answer has no responder at all.
package amp.model

import kotlinx.collections.immutable.PersistentList
import kotlin.time.Instant

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
enum class ReadOnlyReason { ContributorCannotApprove, ReaderOnly, AnsweredElsewhere, Expired, RequiresHumanOnOtherClient }

sealed interface GateSource {
    data object BuiltIn : GateSource
    data class Plugin(val name: String) : GateSource
    data class Mcp(val server: String) : GateSource
}

/**
 * tool_approval_queue + client_tool_approval_response{toolCallId, accepted, input?}. Spike S15 was
 * INCONCLUSIVE: the turn failed before any tool call, so only the empty {approvals:[]} on connect is
 * recorded. The answer must go over the socket (/request/rpc returns -32601, S18). A queue entry that
 * fails to decode becomes UnsupportedGate, so the core loop degrades to [Answer on web].
 */
data class ToolApprovalGate(
    override val id: GateId,
    override val raisedAt: Instant,
    override val answerability: Answerability,
    override val source: GateSource,
    val call: ToolCallId,
    val tool: ToolName,
    val summary: String,          // e.g. the shell command, the MCP tool + args
    val input: JsonValue,
) : HumanGate {
    fun approve(): GateResponse = TODO("requires answerability == Answerable; GateResponse.Approval(call, true, null)")
    fun deny(feedback: String?): GateResponse = TODO()
}

/** Built-in ask_user_choice tool; answered through the same approval response with askAnswers. */
data class ChoiceGate(
    override val id: GateId,
    override val raisedAt: Instant,
    override val answerability: Answerability,
    override val source: GateSource,
    val call: ToolCallId,
    val questions: PersistentList<ChoiceQuestion>,
) : HumanGate {
    fun answer(selections: Map<String, List<String>>, freeText: Map<String, String>): GateResponse = TODO()
}
data class ChoiceQuestion(val key: String, val prompt: String, val options: PersistentList<String>, val multi: Boolean, val allowOther: Boolean)

/**
 * Plugin ctx.ui.* (customize-enterprise.md:24). Wire measured in spike S15b (s15b-plugin-dialog.jsonl):
 *  - raised: plugin_message{message{type:"event", event:"plugin.ui.request_added", data{request{requestId,
 *    method:"ui.confirm"|"ui.input", params{options}, invocation, target, requester, status:"pending",
 *    createdAt, updatedAt}}}} (:64)
 *  - settled: plugin.ui.request_settled{request{…, status:"responded"|"errored", response}} (:67)
 *  - pending dialogs are re-listed in plugin.ui.snapshot on every reconnect (S15c :101)
 *  - ui.notify arrives as a message, not a request (:73), and renders as a snackbar
 * ui.confirm and ui.input are measured. ui.select renders natively but is read from the docs, not
 * observed. Any other method, or a payload that fails to decode, becomes UnsupportedGate. GateId is the requestId.
 */
sealed interface PluginGate : HumanGate {
    data class Confirm(override val id: GateId, override val raisedAt: Instant, override val answerability: Answerability, override val source: GateSource,
                       val title: String, val body: String, val fileField: FileFieldSpec?) : PluginGate {
        fun respond(confirmed: Boolean, file: ByteArray?): GateResponse = TODO("file ≤ 64 KiB UTF-8 (customize-enterprise.md:329)")
    }
    data class Input(override val id: GateId, override val raisedAt: Instant, override val answerability: Answerability, override val source: GateSource,
                     val title: String, val helpText: String, val submitLabel: String) : PluginGate {
        fun respond(text: String): GateResponse = TODO()
    }
    /** ui.input with secret = true. Its answer is Ephemeral (ThreadIntent.AnswerSecret), so the value never reaches Room. */
    data class SecretInput(override val id: GateId, override val raisedAt: Instant, override val answerability: Answerability, override val source: GateSource,
                           val title: String, val helpText: String, val submitLabel: String) : PluginGate {
        fun respond(text: String): SecretPluginAnswer = TODO()
    }
    data class Select(override val id: GateId, override val raisedAt: Instant, override val answerability: Answerability, override val source: GateSource,
                      val title: String, val options: PersistentList<String>) : PluginGate {
        fun respond(index: Int): GateResponse = TODO()
    }
}
data class FileFieldSpec(val label: String, val maxBytes: Int)

/**
 * Any gate the app cannot decode or cannot answer natively (B's OnlyInWeb arm): an unknown plugin
 * request kind, an approval or elicitation payload that fails to parse, or a kind whose answer path
 * is not spiked. The dock and the notification render [Answer on web], which opens
 * AccountWebPage.ThreadOnWeb for this thread. It has no responder, so no GateResponse can be built.
 */
data class UnsupportedGate(
    override val id: GateId,
    override val raisedAt: Instant,
    override val answerability: Answerability,
    override val source: GateSource,
    val kindLabel: String,           // shown on the card: "MCP question", "Plugin form", "Approval"
    val why: UnsupportedWhy,
) : HumanGate
enum class UnsupportedWhy { UnknownKind, DecodeFailed, AnswerPathNotVerified }

/**
 * MCP elicitation (customize-enterprise.md:187-190). URL mode opens only https or http://localhost.
 * The delivery channel is NOT VERIFIED (customize-enterprise.md:362); until a capture shows it, every
 * elicitation the decoder sees arrives as UnsupportedGate(why = DecodeFailed).
 */
sealed interface ElicitationGate : HumanGate {
    data class Form(override val id: GateId, override val raisedAt: Instant, override val answerability: Answerability, override val source: GateSource,
                    val message: String, val fields: PersistentList<ElicitField>, val expiresAt: Instant) : ElicitationGate {
        fun submit(values: Map<String, JsonValue>): GateResponse = TODO()
        fun decline(): GateResponse = TODO()
    }
    data class OpenUrl(override val id: GateId, override val raisedAt: Instant, override val answerability: Answerability, override val source: GateSource,
                       val message: String, val url: SafeElicitUrl, val expiresAt: Instant) : ElicitationGate {
        fun continueAfterOpening(): GateResponse = TODO()
        fun decline(): GateResponse = TODO()
    }
}
sealed interface ElicitField {
    val key: String
    data class Text(override val key: String, val label: String) : ElicitField
    data class Number(override val key: String, val label: String) : ElicitField
    data class YesNo(override val key: String, val label: String) : ElicitField
    data class OneOf(override val key: String, val label: String, val options: PersistentList<String>) : ElicitField
}
/** Only constructible through parse(), which enforces https or http://localhost; UI never re-validates. */
@JvmInline value class SafeElicitUrl private constructor(val url: String) {
    companion object { fun parse(raw: String): SafeElicitUrl? = TODO("https://… or http://localhost[:port]/…, else null") }
}

/** Repository MCP server approval in orbs: listMCPServerApprovals✓ / approveMCPServer✓. */
data class McpServerApprovalGate(
    override val id: GateId, override val raisedAt: Instant, override val answerability: Answerability, override val source: GateSource,
    val serverName: String, val command: String, val specHash: String,
) : HumanGate {
    fun approve(scope: McpApprovalScope): GateResponse = TODO()
}
enum class McpApprovalScope { Thread, Project, Always }

/**
 * Built only by gate methods (constructors are internal to :core:model), mapped to the wire by :sync.
 * A notification action carries a GateResponse as a versioned token from :sync's GateTokens
 * (sync/Api.kt), so the Allow button carries the exact response that was shown and the receiver needs
 * no network read before answering.
 */
sealed interface GateResponse {
    val gate: GateId

    /** client_tool_approval_response{toolCallId, accepted, input?{askAnswers?, denyFeedback?}} */
    data class Approval internal constructor(override val gate: GateId, val call: ToolCallId, val accepted: Boolean,
                                             val denyFeedback: String?, val askAnswers: JsonValue) : GateResponse
    /**
     * executor_plugin_message{message{type:"request", id:"plugin-ui-respond-<requestId>-<n>",
     * method:"plugin.ui.respond", params{requestId, result | error}}} (S15b :66, measured). `error` is
     * how a dialog is cancelled; it settles as status "errored" (S15c :115).
     */
    data class PluginAnswer internal constructor(override val gate: GateId, val outcome: PluginOutcome) : GateResponse
    data class Elicitation internal constructor(override val gate: GateId, val accepted: Boolean, val values: JsonValue) : GateResponse
    data class McpServer internal constructor(override val gate: GateId, val serverName: String, val specHash: String, val command: String, val scope: McpApprovalScope) : GateResponse
}

/**
 * plugin.ui.respond for PluginGate.SecretInput. Not a GateResponse, so ThreadIntent.Answer cannot carry
 * it and GateTokens cannot encode it; it travels only in ThreadIntent.AnswerSecret, and a secret gate's
 * notification posts [Open] only.
 */
data class SecretPluginAnswer internal constructor(val gate: GateId, val text: String)

sealed interface PluginOutcome {
    data class Result(val value: JsonValue) : PluginOutcome
    data class Cancelled(val reason: String) : PluginOutcome
}

/** A plugin command from plugin_message command.list (S15b :50). Run with ThreadIntent.RunPluginCommand. */
data class PluginCommand(val plugin: String, val id: String, val title: String, val category: String?, val enabled: Boolean)
