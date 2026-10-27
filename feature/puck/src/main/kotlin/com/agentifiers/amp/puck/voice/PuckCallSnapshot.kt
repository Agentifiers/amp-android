package com.agentifiers.amp.puck.voice

import com.agentifiers.amp.model.PuckConversationId
import com.agentifiers.amp.sync.api.PuckVoiceState

/** What the call controls show. [route] is the current audio endpoint's name; [canSwitchRoute] when Telecom offers another. */
internal data class PuckCallSnapshot(
    val conversation: PuckConversationId,
    val voice: PuckVoiceState,
    val muted: Boolean,
    val route: String?,
    val canSwitchRoute: Boolean,
) {
    val over: Boolean get() = voice is PuckVoiceState.Ended
}

internal fun PuckVoiceState.words(): String = when (this) {
    PuckVoiceState.Idle, PuckVoiceState.Connecting -> "Connecting"
    is PuckVoiceState.Live -> when {
        puckSpeaking -> "Puck speaking"
        userSpeaking -> "You're speaking"
        else -> "Listening"
    }
    is PuckVoiceState.Thinking -> "Thinking"
    is PuckVoiceState.Ended -> error?.let { "Ended: $it" } ?: "Ended"
}

/** Muted audio still goes out, as zeros of the same length, the way the web sends it. */
internal fun uplinkFrame(buffer: ByteArray, length: Int, muted: Boolean): ByteArray = if (muted) ByteArray(length) else buffer.copyOf(length)

/** The endpoint after [current] in [available], wrapping; null when there is nothing else to switch to. */
internal fun <T> nextRoute(available: List<T>, current: T?): T? {
    if (available.size < 2) return null
    return available[(available.indexOf(current) + 1).mod(available.size)]
}
