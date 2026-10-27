package com.agentifiers.amp.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap

/**
 * The transcript, grouped into turns so a LazyColumn composes only what is visible and a collapsed step group
 * is one row however many calls it holds.
 */
sealed interface TranscriptView {
    data object Loading : TranscriptView
    data class Failed(val reason: String) : TranscriptView
    data class Ready(
        val turns: PersistentList<Turn>,
        val earlier: EarlierHistory,
        val compaction: Compaction,
        val recap: Recap?,
    ) : TranscriptView
}

/** Whether older messages exist before the first loaded one, from the page's `hasMoreBefore`. */
sealed interface EarlierHistory {
    data object None : EarlierHistory
    data object Available : EarlierHistory
    data object Loading : EarlierHistory
    data class Failed(val reason: String) : EarlierHistory
}

/** From `compaction_started` and `compaction_complete{cutMessageId?}`. */
sealed interface Compaction {
    data object None : Compaction
    data object Running : Compaction
    /** [cut] is the first message the compacted context kept; null when the event did not name it. */
    data class Done(val cut: MessageId?) : Compaction
}

/** `thread_recap{recap{text, messageID}, recapSeq}`; [seq] is what `client_dismiss_recap` names. */
data class Recap(val text: String, val anchor: MessageId?, val seq: Long?)

/**
 * One human prompt and everything the agent did until the turn ended. A user message whose content is only
 * tool_result blocks is not a human turn; its results attach to the calls before it. [prompt] is null for the
 * agent's work before the first loaded human message. [replies] come from the `<suggested-replies>` block that ends
 * the turn's latest assistant message; its text steps hold the prose without the block.
 */
data class Turn(
    val key: TurnKey,
    val prompt: Prompt?,
    val steps: PersistentList<Step>,
    val end: TurnEnd,
    val replies: PersistentList<SuggestedReply>,
)

@JvmInline
value class TurnKey(val anchor: MessageId)

data class Prompt(val id: MessageId, val blocks: PersistentList<UserBlock>, val origin: PromptOrigin)

/** The badge a prompt carries, from the user message's `meta`. */
enum class PromptOrigin(val badge: String?) {
    Typed(null),
    Steer("Steered"),
    Puck("From Puck"),
    Voice("From a voice call"),
    Automation("From an automation"),
    Plugin("From a plugin"),
    OtherThread("From another thread"),
    ChangesAction("From Changes"),
}

sealed interface UserBlock {
    data class Text(val markdown: String) : UserBlock
    data class Image(val source: ImageSource, val sourcePath: String?) : UserBlock
}

sealed interface ImageSource {
    /** ampcode.com `user-content` URLs need the account's Bearer; AuthedImage adds it. */
    data class Url(val url: String) : ImageSource
    data class Inline(val mediaType: String, val base64: String) : ImageSource
}

/** From the last assistant message's `state`: complete with a stop reason other than tool_use, cancelled, or error. */
enum class TurnEnd { Open, Done, Interrupted, Failed }

/** Steps in agent order. Consecutive tool calls form one [Tools] group. */
sealed interface Step {
    val key: StepKey

    data class Text(override val key: StepKey, val markdown: String, val streaming: Boolean) : Step
    data class Thinking(override val key: StepKey, val text: String, val redacted: Boolean, val streaming: Boolean) : Step
    data class Tools(override val key: StepKey, val calls: PersistentList<ToolCall>) : Step {
        val summary: StepSummary get() = StepSummary.of(calls)
    }

    /** An `info` message: a command the user ran directly (`manual_bash_invocation`). */
    data class Manual(override val key: StepKey, val command: String, val cwd: String?, val run: ToolRun) : Step
}

/** messageId + block index; stable across deltas. */
@JvmInline
value class StepKey(val raw: String) {
    companion object {
        fun of(message: MessageId, index: Int) = StepKey("${message.raw}:$index")
    }
}

/** An amp-view block's HTML, rendered by :feature:web in a sandboxed WebView. */
data class AmpViewBlock(val key: StepKey, val html: String)

/** Counts for a group of calls; [aggregate] applies the web's precedence: active, then error, cancelled, rejected, done. */
data class StepSummary(val finished: Int, val active: Int, val cancelled: Int, val rejected: Int, val failed: Int) {
    val aggregate: ToolRunKind
        get() = when {
            active > 0 -> ToolRunKind.Active
            failed > 0 -> ToolRunKind.Error
            cancelled > 0 -> ToolRunKind.Cancelled
            rejected > 0 -> ToolRunKind.Rejected
            else -> ToolRunKind.Done
        }

    companion object {
        fun of(calls: List<ToolCall>): StepSummary {
            var finished = 0
            var active = 0
            var cancelled = 0
            var rejected = 0
            var failed = 0
            for (call in calls) when (call.run.kind) {
                ToolRunKind.Done -> finished++
                ToolRunKind.Error -> failed++
                ToolRunKind.Cancelled -> cancelled++
                ToolRunKind.Rejected -> rejected++
                ToolRunKind.Queued, ToolRunKind.Blocked, ToolRunKind.Active, ToolRunKind.CancelRequested -> active++
            }
            return StepSummary(finished, active, cancelled, rejected, failed)
        }
    }
}

/** A tool call. Input and result are domain [JsonValue]s, so cards decode them without importing :sync. */
data class ToolCall(
    val id: ToolCallId,
    val name: ToolName,
    /** `metadata.displayTitle`; absent for most tools. */
    val displayTitle: String?,
    val input: ToolInput,
    val run: ToolRun,
)

sealed interface ToolInput {
    val value: JsonValue

    /** `complete: false`: the model is still writing the arguments. */
    data class Streaming(override val value: JsonValue) : ToolInput
    data class Complete(override val value: JsonValue) : ToolInput
}

/** A tool call's state, from the `tool_result` block that names it; [Pending] until one arrives. */
sealed interface ToolRun {
    val kind: ToolRunKind

    data object Pending : ToolRun { override val kind get() = ToolRunKind.Active }
    data object Queued : ToolRun { override val kind get() = ToolRunKind.Queued }
    data object BlockedOnUser : ToolRun { override val kind get() = ToolRunKind.Blocked }
    data class Active(val progress: JsonValue) : ToolRun { override val kind get() = ToolRunKind.Active }
    data object CancelRequested : ToolRun { override val kind get() = ToolRunKind.CancelRequested }

    /** [userInput] is the tool_result's `userInput` (`{accepted, askAnswers?, denyFeedback?}`), [JsonValue.Null] when absent. */
    data class Done(val result: JsonValue, val userInput: JsonValue) : ToolRun { override val kind get() = ToolRunKind.Done }
    data class Error(val message: String?, val result: JsonValue) : ToolRun { override val kind get() = ToolRunKind.Error }
    data class Cancelled(val reason: String?) : ToolRun { override val kind get() = ToolRunKind.Cancelled }
    data class Rejected(val feedback: String?) : ToolRun { override val kind get() = ToolRunKind.Rejected }
}

/** The status text every card and step group shows (screens.md, "Status text"). */
enum class ToolRunKind(val word: String) {
    Queued("queued"),
    Blocked("waiting for you"),
    Active("running"),
    CancelRequested("cancelling"),
    Done("done"),
    Error("error"),
    Cancelled("cancelled"),
    Rejected("rejected"),
}

/** Minimal JSON domain value with tolerant accessors. Not kotlinx JsonElement, which is a wire type. */
sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val v: Boolean) : JsonValue
    data class Num(val v: Double) : JsonValue
    data class Str(val v: String) : JsonValue
    data class Arr(val items: PersistentList<JsonValue>) : JsonValue
    data class Obj(val fields: PersistentMap<String, JsonValue>) : JsonValue

    /** [Null] when absent or not an object. */
    operator fun get(key: String): JsonValue = (this as? Obj)?.fields?.get(key) ?: Null

    /** The string, or null for any other kind. */
    val string: String? get() = (this as? Str)?.v

    /** A whole number, or null for any other kind. */
    val int: Int? get() = (this as? Num)?.v?.takeIf { it % 1.0 == 0.0 }?.toInt()

    /** The items, or an empty list for any other kind. */
    val list: List<JsonValue> get() = (this as? Arr)?.items.orEmpty()
}
