package com.agentifiers.amp.thread.presence

import com.agentifiers.amp.model.Viewer
import kotlin.time.Duration
import kotlin.time.Instant

/** "Ada Lovelace", then "Ada Lovelace +1" for two viewers; null when nobody else is here. */
internal fun presenceLabel(viewers: List<Viewer>): String? = when (viewers.size) {
    0 -> null
    1 -> viewers[0].name
    else -> "${viewers[0].name} +${viewers.size - 1}"
}

/** screens.md "Multiplayer": "<name> is typing" above the composer. */
internal fun typingLabel(typing: List<Viewer>): String? = when (typing.size) {
    0 -> null
    1 -> "${typing[0].name} is typing"
    2 -> "${typing[0].name} and ${typing[1].name} are typing"
    else -> "${typing[0].name}, ${typing[1].name} and ${typing.size - 2} more are typing"
}

/** "6d 23h left", "2h 5m left", "4m left", or "ending" once under a minute remains. */
internal fun timeLeft(until: Instant, now: Instant): String {
    val left: Duration = until - now
    val days = left.inWholeDays
    val hours = left.inWholeHours % 24
    val minutes = left.inWholeMinutes % 60
    return when {
        days > 0 -> "${days}d ${hours}h left"
        hours > 0 -> "${hours}h ${minutes}m left"
        minutes > 0 -> "${minutes}m left"
        else -> "ending"
    }
}
