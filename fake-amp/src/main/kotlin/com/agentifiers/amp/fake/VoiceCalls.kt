package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonObject
import okhttp3.WebSocket
import java.util.Base64
import java.util.concurrent.ScheduledFuture
import kotlin.math.abs

/**
 * What the fake Puck answers each time the caller stops talking: [transcript], then [clip] (PCM16 LE, 24 kHz, mono),
 * then, on the first answer only, a move to the thread [navigateTo].
 */
internal class VoiceScript(val transcript: String, val clip: ByteArray, val navigateTo: String) {
    companion object {
        /** `ffmpeg -f lavfi -i sine=frequency=440:sample_rate=24000:duration=1 -f s16le -ar 24000 -ac 1`: 48000 bytes. */
        fun tone(): ByteArray =
            VoiceScript::class.java.getResourceAsStream("/voice/puck-tone-440hz-24k.s16le")?.use { it.readBytes() }
                ?: error("voice/puck-tone-440hz-24k.s16le is not bundled in :fake-amp")
    }
}

/**
 * Puck voice over the web's PCM path (web bundle `62d3d593.js:149618`, schemas `11958a39.js:35642-36042`): `client_puck_voice_start`
 * answers `{}` and goes connecting then listening; non-silent uplink sets `userSpeaking`, and 0.3 s of silence after it
 * plays one answer; `client_puck_voice_stop` ends the call. Server VAD and speech are scripted, not recorded.
 */
internal class VoiceCalls(private val script: VoiceScript, private val actor: ThreadActor, private val world: World) {
    private sealed interface Call {
        val socket: WebSocket

        class Connecting(override val socket: WebSocket, val timer: ScheduledFuture<*>) : Call

        /** [silentBytes] counts the silence heard since the caller last spoke. */
        data class Listening(override val socket: WebSocket, val userSpeaking: Boolean, val silentBytes: Int) : Call

        class Answering(override val socket: WebSocket, val timer: ScheduledFuture<*>) : Call
    }

    private var call: Call? = null
    private var navigated = false

    fun receive(socket: WebSocket, id: String?, method: String, params: JsonObject) {
        when (method) {
            "client_puck_voice_start" -> start(socket)
            "client_puck_voice_audio" -> hear(socket, params.string("audioBase64"))
            "client_puck_voice_stop" -> if (call != null) {
                cancel()
                actor.emit(state("ended"))
            }
        }
        if (id != null) actor.send(socket, json("jsonrpc" to "2.0", "id" to id, "result" to json()))
    }

    fun drop(socket: WebSocket) {
        if (call?.socket == socket) cancel()
    }

    private fun start(socket: WebSocket) {
        cancel()
        actor.emit(state("connecting"))
        call = Call.Connecting(socket, world.schedule(world.gap(STEP_MS)) { listen(socket) })
    }

    private fun listen(socket: WebSocket) {
        call = Call.Listening(socket, userSpeaking = false, silentBytes = 0)
        actor.emit(state("listening"))
    }

    private fun hear(socket: WebSocket, base64: String?) {
        val listening = call as? Call.Listening ?: return
        if (listening.socket != socket) return
        val pcm = base64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return
        when {
            peak(pcm) >= SPEECH_PEAK -> {
                if (!listening.userSpeaking) actor.emit(state("listening", userSpeaking = true))
                call = listening.copy(userSpeaking = true, silentBytes = 0)
            }
            !listening.userSpeaking -> Unit
            listening.silentBytes + pcm.size < SILENCE_BYTES -> call = listening.copy(silentBytes = listening.silentBytes + pcm.size)
            else -> {
                actor.emit(state("listening"))
                answer(socket)
            }
        }
    }

    private fun answer(socket: WebSocket) {
        val steps = ArrayList<Pair<Long, Event>>()
        steps += STEP_MS to state("thinking")
        steps += STEP_MS to Event("puck_voice_transcript", json("delta" to script.transcript))
        steps += STEP_MS to state("listening", puckSpeaking = true)
        for (from in script.clip.indices step CHUNK_BYTES) {
            val chunk = script.clip.copyOfRange(from, minOf(from + CHUNK_BYTES, script.clip.size))
            steps += CHUNK_MS to Event("puck_voice_audio", json("audioBase64" to Base64.getEncoder().encodeToString(chunk), "sampleRate" to 24_000))
        }
        steps += STEP_MS to state("listening")
        if (!navigated) {
            navigated = true
            steps += STEP_MS to Event("puck_platform_navigation", json("destination" to json("type" to "thread", "threadID" to script.navigateTo)))
        }
        play(socket, steps, 0)
    }

    private fun play(socket: WebSocket, steps: List<Pair<Long, Event>>, next: Int) {
        if (next == steps.size) {
            call = Call.Listening(socket, userSpeaking = false, silentBytes = 0)
            return
        }
        val (gapMs, event) = steps[next]
        call = Call.Answering(socket, world.schedule(world.gap(gapMs)) {
            actor.emit(event)
            play(socket, steps, next + 1)
        })
    }

    private fun cancel() {
        when (val current = call) {
            is Call.Connecting -> current.timer.cancel(false)
            is Call.Answering -> current.timer.cancel(false)
            is Call.Listening, null -> Unit
        }
        call = null
    }

    private fun state(status: String, userSpeaking: Boolean = false, puckSpeaking: Boolean = false) =
        Event("puck_voice_state", json("status" to status, "userSpeaking" to userSpeaking, "puckSpeaking" to puckSpeaking))

    private fun peak(pcm: ByteArray): Int {
        var peak = 0
        for (i in 0 until pcm.size - 1 step 2) {
            val sample = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
            peak = maxOf(peak, abs(sample))
        }
        return peak
    }

    private companion object {
        const val STEP_MS = 50L
        const val CHUNK_MS = 100L

        /** 100 ms of PCM16 at 24 kHz. */
        const val CHUNK_BYTES = 4_800

        /** 0.3 s of PCM16 at 24 kHz. */
        const val SILENCE_BYTES = 14_400
        const val SPEECH_PEAK = 1_000
    }
}
