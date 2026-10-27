package com.agentifiers.amp.sync.internal.puck.voice

import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.StopReason
import com.agentifiers.amp.sync.api.PuckVoiceChannel
import com.agentifiers.amp.sync.api.PuckVoiceState
import com.agentifiers.amp.sync.internal.net.Outcome
import com.agentifiers.amp.sync.internal.thread.ThreadEngine
import com.agentifiers.amp.sync.internal.wire.InboundFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Puck voice on one conversation thread's actor socket, over the web's PCM path. A call holds the socket open
 * from [start] until it ends. A socket drop ends the call: a new socket does not carry the old call on.
 * [thread] and [scope] belong to the account session; build one engine per conversation, because the thread
 * engine has one [VoiceTap].
 */
internal class PuckVoiceEngine(private val thread: ThreadEngine, private val scope: CoroutineScope) : PuckVoiceChannel, VoiceTap {
    /** One placed call. [started] turns true once `client_puck_voice_start` is on the wire; [tail] is only touched on the tap. */
    private class Call(val lease: AutoCloseable) {
        @Volatile var started = false
        var tail = ""
    }

    private val lock = Any()

    @Volatile private var call: Call? = null

    private val mutableState = MutableStateFlow<PuckVoiceState>(PuckVoiceState.Idle)
    override val state: StateFlow<PuckVoiceState> = mutableState.asStateFlow()

    private val audio = Channel<ByteArray>(Channel.UNLIMITED)
    override val downlink: Flow<ByteArray> = audio.receiveAsFlow()

    private val destinations = Channel<PaneDestination>(Channel.UNLIMITED)
    override val navigation: Flow<PaneDestination> = destinations.receiveAsFlow()

    override suspend fun start() {
        val placed = synchronized(lock) {
            if (call != null) return
            Call(thread.hold()).also {
                call = it
                mutableState.value = PuckVoiceState.Connecting
            }
        }
        while (audio.tryReceive().isSuccess) Unit
        thread.voiceTap = this
        val link = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { thread.state.first { it.link == Link.Live || it.link is Link.Stopped } }?.link
        if (link != Link.Live) return end(placed, unreachable(link), hangUp = false)
        synchronized(lock) {
            if (call !== placed) return
            placed.started = true
        }
        val answer = thread.requestLive(VoiceWire.START, VoiceWire.startParams)
        if (answer is Outcome.Failed) end(placed, answer.reason, hangUp = false)
    }

    override fun uplink(opusFrame: ByteArray) {
        val placed = call ?: return
        if (!placed.started || opusFrame.isEmpty()) return
        VoiceWire.audioParams(opusFrame).forEach { thread.notifyLive(VoiceWire.AUDIO, it) }
    }

    override fun stop() {
        call?.let { end(it, null, hangUp = true) }
    }

    override fun onEvent(event: InboundFrame.Event): Boolean {
        val parsed = VoiceWire.decode(event.type, event.body) ?: return false
        val placed = call ?: return true
        when (parsed) {
            is VoiceEvent.State -> when (parsed.status) {
                VoiceStatus.Connecting -> show(placed, PuckVoiceState.Connecting)
                VoiceStatus.Listening -> show(placed, PuckVoiceState.Live(parsed.userSpeaking, parsed.puckSpeaking, placed.tail))
                VoiceStatus.Thinking -> show(placed, PuckVoiceState.Thinking(placed.tail))
                VoiceStatus.Ended -> end(placed, null, hangUp = false)
                VoiceStatus.Error -> end(placed, parsed.error ?: "Puck voice failed.", hangUp = false)
            }
            is VoiceEvent.Audio -> audio.trySend(parsed.pcm)
            is VoiceEvent.Unplayable ->
                end(placed, "Puck sent ${parsed.format} audio at ${parsed.sampleRate ?: "an unknown rate"} Hz, which this app cannot play.", hangUp = true)
            is VoiceEvent.Transcript -> {
                placed.tail = (placed.tail + parsed.delta).takeLast(TAIL_CHARS)
                when (val shown = mutableState.value) {
                    is PuckVoiceState.Live -> show(placed, shown.copy(transcriptTail = placed.tail))
                    is PuckVoiceState.Thinking -> show(placed, shown.copy(transcriptTail = placed.tail))
                    else -> Unit
                }
            }
            is VoiceEvent.Navigation -> destinations.trySend(parsed.destination)
            is VoiceEvent.Malformed -> Unit
        }
        return true
    }

    override fun onSocketEnded() {
        val placed = call ?: return
        if (placed.started) end(placed, "The connection to Puck dropped.", hangUp = false)
    }

    private fun show(placed: Call, next: PuckVoiceState) = synchronized(lock) {
        if (call === placed) mutableState.value = next
    }

    /** [hangUp] sends `client_puck_voice_stop` first when the server still thinks the call is on. */
    private fun end(placed: Call, reason: String?, hangUp: Boolean) {
        synchronized(lock) {
            if (call !== placed) return
            call = null
            mutableState.value = PuckVoiceState.Ended(reason)
        }
        if (!hangUp || !placed.started) return placed.lease.close()
        scope.launch {
            thread.requestLive(VoiceWire.STOP, VoiceWire.stopParams)
            placed.lease.close()
        }
    }

    private fun unreachable(link: Link?): String = when ((link as? Link.Stopped)?.reason) {
        StopReason.Archived -> "This Puck conversation is archived."
        StopReason.MissingRivetToken -> "This build has no Rivet token."
        null -> "Could not reach Puck."
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val TAIL_CHARS = 280
    }
}
