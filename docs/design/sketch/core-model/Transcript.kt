// :core:model  package amp.model
// Transcript domain. Turn-grouped and windowed so a LazyColumn composes only what is visible and
// collapsed step groups keep long threads far below the 5000-node snapshot cap.
package amp.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlin.time.Instant

sealed interface TranscriptView {
    data object Loading : TranscriptView
    data class Ready(
        val turns: PersistentList<Turn>,
        val earlier: EarlierHistory,
        /** Local sends not yet echoed by message_added; merged at read from the outbox. */
        val pending: PersistentList<OutboxEntry>,
    ) : TranscriptView
}

sealed interface EarlierHistory {
    data object None : EarlierHistory
    data class Available(val before: MessageIndex) : EarlierHistory
    data object Loading : EarlierHistory
    /** getThreadTranscriptPage returned status "stale" (spike S9): reload from the tail. */
    data object Stale : EarlierHistory
}

/**
 * One human prompt and everything the agent did until the turn ended. A user message whose content
 * is only tool_result blocks is not a human turn (core.md:410); it folds into the step group.
 */
data class Turn(
    val key: TurnKey,
    val prompt: Prompt,
    val steps: PersistentList<Step>,
    val end: TurnEnd,
)
@JvmInline value class TurnKey(val anchor: MessageId)

sealed interface Prompt {
    data class Human(val id: MessageId, val author: Author, val blocks: PersistentList<UserBlock>, val sentAt: Instant, val origin: PromptOrigin, val reactions: PersistentMap<String, Int>) : Prompt
    /** The first turn of a thread created by an agent, automation or event before any human spoke. */
    data class System(val id: MessageId, val origin: PromptOrigin, val blocks: PersistentList<UserBlock>) : Prompt
}

/** Badge source for a prompt: meta.fromPuck, fromAutomation, steer, fromVoiceCall, … (core.md:408). */
sealed interface PromptOrigin {
    data object Typed : PromptOrigin
    data object Steer : PromptOrigin
    data object Voice : PromptOrigin
    data object Puck : PromptOrigin
    data class Automation(val kind: AutomationKind) : PromptOrigin
    data class Plugin(val name: String) : PromptOrigin
    data class Agent(val fromThread: ThreadId) : PromptOrigin
    data object ChangesAction : PromptOrigin  // Ship, Review, Restack prompts (meta.changesWorkflow)
}
enum class AutomationKind { Schedule, Event }

sealed interface UserBlock {
    data class Text(val markdown: String) : UserBlock
    data class Image(val source: ImageSource, val sourcePath: String?) : UserBlock
}
sealed interface ImageSource {
    /** ampcode.com/user-content URLs need Bearer (spike S12); AuthedImage adds it. */
    data class Url(val url: String) : ImageSource
    data class Inline(val mediaType: String, val base64: String) : ImageSource
}

/** Steps in agent order. Consecutive tool calls form one collapsible StepGroup. */
sealed interface Step {
    val key: StepKey
    data class Text(override val key: StepKey, val markdown: String, val streaming: Boolean, val views: PersistentList<AmpViewBlock>) : Step
    data class Thinking(override val key: StepKey, val text: String, val redacted: Boolean, val streaming: Boolean) : Step
    data class Tools(override val key: StepKey, val calls: PersistentList<ToolCall>, val summary: StepSummary) : Step
    data class Manual(override val key: StepKey, val command: String, val run: ToolRun) : Step   // role "info"
    data class Compaction(override val key: StepKey, val phase: CompactionPhase) : Step
    data class Marker(override val key: StepKey, val kind: MarkerKind) : Step                    // cancelled, retried, edited, truncated
}
@JvmInline value class StepKey(val raw: String)  // messageId + blockIndex; stable across deltas
enum class CompactionPhase { Running, Done }
enum class MarkerKind { Cancelled, Retried, Edited, Truncated, Handoff, Forked }

/** Fenced ```amp-view blocks render in a sandboxed WebSurface (core.md:423). Domain value only. */
data class AmpViewBlock(val key: StepKey, val html: String)

/** Aggregate rule from core.md:438: any active → active, else error, cancelled, rejected, done. */
data class StepSummary(val finished: Int, val active: Int, val cancelled: Int, val rejected: Int, val failed: Int) {
    val aggregate: ToolRunKind get() = TODO("apply core.md:438 precedence")
}

/**
 * A tool call. `input` and results are domain JsonValue, not wire DTOs, so feature renderers can
 * decode tolerantly without importing :sync.
 */
data class ToolCall(
    val id: ToolCallId,
    val name: ToolName,
    val displayTitle: String?,                 // metadata.displayTitle; absent for most tools
    val input: ToolInput,
    val run: ToolRun,
    val children: PersistentList<ToolCall>,    // subagent / nested / parentToolUseId
)

sealed interface ToolInput {
    data class Streaming(val partial: JsonValue) : ToolInput
    data class Complete(val value: JsonValue) : ToolInput
    fun string(key: String): String? = TODO()
    fun stringList(key: String): List<String> = TODO()
}

/** Tool run status (core.md:437-438). One sum type, every state carries only what it has. */
sealed interface ToolRun {
    data object Queued : ToolRun
    data class BlockedOnUser(val gate: GateId) : ToolRun
    data class Active(val progress: JsonValue) : ToolRun
    data object CancelRequested : ToolRun
    data class Done(val result: JsonValue, val userInput: UserInputRecord?) : ToolRun
    data class Error(val message: String) : ToolRun
    data class Cancelled(val reason: String) : ToolRun
    data class Rejected(val feedback: String?) : ToolRun
    val kind: ToolRunKind get() = TODO()
}
enum class ToolRunKind { Queued, Blocked, Active, CancelRequested, Done, Error, Cancelled, Rejected }
data class UserInputRecord(val accepted: Boolean, val answeredBy: UserId?)

/** Minimal JSON domain value with tolerant accessors. Not kotlinx JsonElement (that is a wire type). */
sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val v: Boolean) : JsonValue
    data class Num(val v: Double) : JsonValue
    data class Str(val v: String) : JsonValue
    data class Arr(val items: PersistentList<JsonValue>) : JsonValue
    data class Obj(val fields: PersistentMap<String, JsonValue>) : JsonValue
    operator fun get(key: String): JsonValue = TODO("Null when absent or not an object")
}
