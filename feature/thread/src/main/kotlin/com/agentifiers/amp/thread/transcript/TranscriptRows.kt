package com.agentifiers.amp.thread.transcript

import com.agentifiers.amp.design.markdown.markdownPlainText
import com.agentifiers.amp.model.AgentActivity
import com.agentifiers.amp.model.AgentError
import com.agentifiers.amp.model.Compaction
import com.agentifiers.amp.model.EarlierHistory
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.PendingSend
import com.agentifiers.amp.model.Prompt
import com.agentifiers.amp.model.Recap
import com.agentifiers.amp.model.Step
import com.agentifiers.amp.model.StepKey
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolCallId
import com.agentifiers.amp.model.ToolRun
import com.agentifiers.amp.model.ToolRunKind
import com.agentifiers.amp.model.TranscriptView
import com.agentifiers.amp.model.TurnEnd
import com.agentifiers.amp.model.TurnKey
import com.agentifiers.amp.model.UserBlock
import com.agentifiers.amp.thread.toolcards.toolSearchText

/**
 * One LazyColumn item. A turn becomes several rows (prompt, each step, its end), so a long turn never composes as
 * one tall item, and an expanded step group adds one row per call. Rows are listed oldest first.
 */
sealed interface TranscriptRow {
    val key: String

    data class LoadEarlier(val state: EarlierHistory) : TranscriptRow {
        override val key get() = KEY

        companion object {
            const val KEY = "load-earlier"
        }
    }
    data object CompactionDivider : TranscriptRow { override val key get() = "compaction" }
    data class PromptRow(val turn: TurnKey, val prompt: Prompt) : TranscriptRow { override val key get() = "prompt-${prompt.id.raw}" }
    data class StepRow(val turn: TurnKey, val step: Step) : TranscriptRow { override val key get() = "step-${step.key.raw}" }

    /** One call of an expanded step group. */
    data class CallRow(val group: StepKey, val call: ToolCall) : TranscriptRow { override val key get() = "call-${call.id.raw}" }
    data class EndRow(val turn: TurnKey, val end: TurnEnd) : TranscriptRow { override val key get() = "end-${turn.anchor.raw}" }
    data class RecapRow(val recap: Recap) : TranscriptRow { override val key get() = "recap" }
    data class ActivityRow(val activity: AgentActivity.Busy) : TranscriptRow { override val key get() = "activity" }
    /** A null [error] means the agent state says error but no `error_set` has told us the message. */
    data class ErrorRow(val error: AgentError?) : TranscriptRow { override val key get() = "error" }
    data class PendingRow(val send: PendingSend) : TranscriptRow { override val key get() = "pending-${send.id.raw}" }
}

/** What the reader opened. A step group with an active call is open regardless, as screens.md asks. */
data class Expansion(val steps: Set<StepKey>, val tools: Set<ToolCallId>, val thinking: Set<StepKey>) {
    fun isOpen(step: Step.Tools): Boolean = step.key in steps || step.summary.active > 0

    companion object {
        val None = Expansion(emptySet(), emptySet(), emptySet())
    }
}

fun transcriptRows(
    view: TranscriptView.Ready,
    activity: AgentActivity,
    error: AgentError?,
    pending: List<PendingSend>,
    expansion: Expansion,
): List<TranscriptRow> {
    val rows = ArrayList<TranscriptRow>()
    if (view.earlier != EarlierHistory.None) rows += TranscriptRow.LoadEarlier(view.earlier)
    val recap = view.recap
    // The recap opens the latest turn it covers, which is where a returning reader starts (screens.md "Recap", UX-28).
    val recapTurn = recap?.let { r -> r.anchor?.let { id -> view.turns.firstOrNull { it.contains(id) } } ?: view.turns.lastOrNull() }
    val compacted = view.compaction as? Compaction.Done
    val cutTurn = compacted?.cut?.let { id -> view.turns.firstOrNull { turn -> turn.contains(id) } }
    if (compacted != null && cutTurn == null) rows += TranscriptRow.CompactionDivider
    for (turn in view.turns) {
        if (turn === cutTurn) rows += TranscriptRow.CompactionDivider
        if (recap != null && turn === recapTurn) rows += TranscriptRow.RecapRow(recap)
        turn.prompt?.let { rows += TranscriptRow.PromptRow(turn.key, it) }
        for (step in turn.steps) {
            rows += TranscriptRow.StepRow(turn.key, step)
            if (step is Step.Tools && expansion.isOpen(step)) step.calls.mapTo(rows) { TranscriptRow.CallRow(step.key, it) }
        }
        if (turn.end == TurnEnd.Interrupted || turn.end == TurnEnd.Failed) rows += TranscriptRow.EndRow(turn.key, turn.end)
    }
    if (recap != null && rows.none { it is TranscriptRow.RecapRow }) rows += TranscriptRow.RecapRow(recap)
    pending.mapTo(rows) { TranscriptRow.PendingRow(it) }
    if (activity is AgentActivity.Busy) rows += TranscriptRow.ActivityRow(activity)
    when {
        error != null -> rows += TranscriptRow.ErrorRow(error)
        activity == AgentActivity.Failed -> rows += TranscriptRow.ErrorRow(null)
    }
    return rows
}

private fun com.agentifiers.amp.model.Turn.contains(id: MessageId): Boolean =
    prompt?.id == id || key.anchor == id || steps.any { it.key.raw.startsWith(id.raw + ":") }

/**
 * Whether [row] sits flush under [previous] instead of one block gap below it. A run of step-group rows, their open
 * call rows, and thinking lines reads as one list, and a 48dp tool row carries its own air on both sides, so a block
 * gap next to one would only spread the transcript.
 */
fun flushUnder(previous: TranscriptRow, row: TranscriptRow): Boolean =
    previous.isToolLine && row.isToolLine || previous.isToolRow || row.isToolRow

private val TranscriptRow.isToolRow: Boolean
    get() = this is TranscriptRow.CallRow || this is TranscriptRow.StepRow && step is Step.Tools

private val TranscriptRow.isToolLine: Boolean
    get() = isToolRow || this is TranscriptRow.StepRow && step is Step.Thinking

/**
 * Where each find match lives. Searching covers prompts, assistant text, thinking, tool labels, summaries and output.
 * A match inside a call names its group and call, so moving to it can open both.
 */
data class FindHit(val rowKey: String, val group: StepKey?, val call: ToolCallId?, val thinking: StepKey?)

fun findHits(view: TranscriptView.Ready, query: String): List<FindHit> {
    if (query.isBlank()) return emptyList()
    val hits = ArrayList<FindHit>()
    fun count(text: String): Int = occurrences(text, query)
    for (turn in view.turns) {
        turn.prompt?.let { prompt ->
            val text = prompt.blocks.filterIsInstance<UserBlock.Text>().joinToString("\n") { markdownPlainText(it.markdown) }
            repeat(count(text)) { hits += FindHit("prompt-${prompt.id.raw}", null, null, null) }
        }
        for (step in turn.steps) {
            val row = "step-${step.key.raw}"
            when (step) {
                is Step.Text -> repeat(count(markdownPlainText(step.markdown))) { hits += FindHit(row, null, null, null) }
                is Step.Thinking -> repeat(count(step.text)) { hits += FindHit(row, null, null, step.key) }
                is Step.Manual -> repeat(count(step.command + "\n" + manualOutput(step.run))) { hits += FindHit(row, null, null, null) }
                is Step.Tools -> for (call in step.calls) {
                    repeat(count(toolSearchText(call))) { hits += FindHit("call-${call.id.raw}", step.key, call.id, null) }
                }
            }
        }
    }
    return hits
}

fun occurrences(text: String, query: String): Int {
    if (query.isEmpty()) return 0
    var count = 0
    var from = text.indexOf(query, ignoreCase = true)
    while (from >= 0) {
        count++
        from = text.indexOf(query, from + query.length, ignoreCase = true)
    }
    return count
}

/** The output a manual command row shows: `result.output`, or the error text. */
fun manualOutput(run: ToolRun): String = when (run) {
    is ToolRun.Done -> run.result["output"].string.orEmpty()
    is ToolRun.Error -> run.message ?: run.result["output"].string.orEmpty()
    else -> ""
}

/** The word a step group header shows, with the chips "N finished", "N active", "N cancelled", "N rejected". */
fun stepChips(step: Step.Tools): List<String> {
    val s = step.summary
    return listOfNotNull(
        s.finished.takeIf { it > 0 }?.let { "$it finished" },
        s.active.takeIf { it > 0 }?.let { "$it active" },
        s.failed.takeIf { it > 0 }?.let { "$it failed" },
        s.cancelled.takeIf { it > 0 }?.let { "$it cancelled" },
        s.rejected.takeIf { it > 0 }?.let { "$it rejected" },
    )
}

/** The aggregate word after the chips, or null when one chip already says it because every call shares that status. */
fun stepWord(step: Step.Tools): String? = step.summary.aggregate.word.takeIf { stepChips(step).size > 1 }
