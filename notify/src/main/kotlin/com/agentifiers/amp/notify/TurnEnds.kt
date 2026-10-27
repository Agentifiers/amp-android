package com.agentifiers.amp.notify

import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.sync.api.PushEvent
import java.time.LocalDate
import kotlin.time.Instant

/**
 * What a user actor summary says the user should hear about, for when Amp sends no push: Amp stays silent for a thread
 * while any client has it open (S23). Every path that reads summaries (the foreground socket, the background check)
 * asks the same questions here.
 */
internal object TurnEnds {
    /**
     * The tag of Amp's own `thread_idle` push for the turn whose last assistant message is [reply]
     * (`s11-push.jsonl:77-78`: the tag names the `protocolMessageID` of the turn's final `end_turn` message, which
     * `getRecentThreads` and `threadStatusUpdated` report as `latestAssistantMessageID`). Both paths post under it,
     * so the ledger keeps whichever arrives first.
     */
    fun tag(thread: ThreadId, reply: MessageId): String = "amp-thread-${thread.raw}-idle-${reply.raw}"

    /**
     * The finish [summary] shows: idle, with a last reply, and a turn that ended after [since]. It does not need the
     * working state before it, because a check every few minutes can miss a whole turn; the ledger makes a repeat a
     * no-op. Amp sends the idle that ends a turn a few milliseconds before the update that carries the turn's reply
     * (S14 lines 40-41), so a live caller waits for updates to settle before it asks.
     */
    fun finished(summary: ThreadSummary, since: Instant): PushEvent.ThreadIdle? {
        if (summary.status != FleetStatus.Idle && summary.status != FleetStatus.Done) return null
        val reply = summary.lastReply ?: return null
        val ended = summary.lastTurnEndedAt ?: return null
        if (ended <= since) return null
        return PushEvent.ThreadIdle(summary.id, tag(summary.id, reply), summary.title ?: "Amp", "")
    }

    /** A thread whose turn is still running, so the background check keeps looking. A waiting dialog does not count. */
    fun working(summary: ThreadSummary): Boolean = summary.status == FleetStatus.Working || summary.status == FleetStatus.Compacting

    /** The user actor's `indicator{kind:"action-required"}` (S15b): one Needs you per thread and day, whichever path sees it. */
    fun needsYouTag(summary: ThreadSummary, day: LocalDate): String? =
        if (summary.status.word == NEEDS_YOU) "amp-thread-${summary.id.raw}-action-required-$day" else null

    private const val NEEDS_YOU = "action-required"
}
