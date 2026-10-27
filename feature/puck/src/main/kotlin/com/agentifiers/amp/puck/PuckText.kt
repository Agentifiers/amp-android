package com.agentifiers.amp.puck

import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.StopReason
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Outcome
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The switcher's two groups. The web groups by `updatedAt` within 72 hours (collab-apps.md, "Puck conversations"). */
internal data class ConversationGroups(val recent: List<ThreadSummary>, val inactive: List<ThreadSummary>)

/** A conversation without a timestamp yet is one `open` just made, so it counts as recent. */
internal fun groupConversations(conversations: List<ThreadSummary>, now: Instant): ConversationGroups {
    val (recent, inactive) = conversations.partition { summary -> summary.lastActivity?.let { now - it < 72.hours } ?: true }
    return ConversationGroups(recent, inactive)
}

/** The line under the header when New Chat did not produce a conversation; null on success. */
internal fun newChatFailure(outcome: Outcome<*>): String? = when (outcome) {
    is Outcome.Ok -> null
    is Outcome.Failed -> "New Chat failed: ${outcome.error.describe()}"
    Outcome.Unknown -> "New Chat may not have started. Check the conversation list."
    is Outcome.Refused -> "New Chat refused: ${outcome.why}"
}

private fun EngineError.describe(): String = when (this) {
    EngineError.Offline -> "you are offline"
    EngineError.Unauthorized -> "sign in again"
    EngineError.StepUpRequired -> "confirm with your passkey on web"
    EngineError.PaymentRequired -> "out of credits"
    is EngineError.NotFound -> "$what not found"
    is EngineError.Server -> message
    is EngineError.Drift -> "Amp answered in a shape this app does not understand ($where)"
}

/** The link state line; null while live. */
internal fun linkLine(link: Link): String? = when (link) {
    Link.Live -> null
    Link.Connecting -> "connecting"
    Link.Resuming -> "catching up"
    is Link.Reconnecting -> "reconnecting"
    Link.Closed -> "not connected"
    is Link.Stopped -> when (link.reason) {
        StopReason.Archived -> "archived"
        StopReason.MissingRivetToken -> "this build has no Rivet token"
    }
}
