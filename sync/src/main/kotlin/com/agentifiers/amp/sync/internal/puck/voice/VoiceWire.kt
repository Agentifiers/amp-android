package com.agentifiers.amp.sync.internal.puck.voice

import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.long
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URISyntaxException
import java.util.Base64

internal enum class VoiceStatus { Connecting, Listening, Thinking, Ended, Error }

/** One actor-to-client voice event, parsed. Shapes from the web schema module `11958a39.js:35642-36042`. */
internal sealed interface VoiceEvent {
    data class State(val status: VoiceStatus, val userSpeaking: Boolean, val puckSpeaking: Boolean, val error: String?) : VoiceEvent

    /** PCM16 little-endian, 24 kHz, mono. */
    class Audio(val pcm: ByteArray) : VoiceEvent

    /** Downlink this client cannot play: ADPCM, Opus, or PCM at 16 kHz. */
    data class Unplayable(val format: String, val sampleRate: Long?) : VoiceEvent

    data class Transcript(val delta: String) : VoiceEvent
    data class Navigation(val destination: PaneDestination) : VoiceEvent
    data class Malformed(val type: String) : VoiceEvent
}

/**
 * The web's PCM path over the thread socket (`62d3d593.js:149618`, `150030`): start with only `version:"v3"`,
 * upload `audioBase64` notifications without `format`, stop with no params.
 */
internal object VoiceWire {
    const val START = "client_puck_voice_start"
    const val AUDIO = "client_puck_voice_audio"
    const val STOP = "client_puck_voice_stop"

    /** `client_puck_voice_audio.audioBase64` max length (`Sn=32000`); 24000 bytes encode to exactly 32000 characters. */
    const val MAX_UPLINK_BYTES = 24_000

    val startParams: JsonObject = buildJsonObject { put("version", "v3") }
    val stopParams: JsonObject = JsonObject(emptyMap())

    private const val PCM16_24K = "pcm16_24k"
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    /** Uplink notifications for [pcm], split so each stays within the server's limit. Chunks keep whole samples. */
    fun audioParams(pcm: ByteArray): List<JsonObject> =
        (pcm.indices step MAX_UPLINK_BYTES).map { from ->
            val chunk = pcm.copyOfRange(from, minOf(from + MAX_UPLINK_BYTES, pcm.size))
            buildJsonObject { put("audioBase64", encoder.encodeToString(chunk)) }
        }

    /** Null when [type] is not a voice event, so the thread fold keeps it. */
    fun decode(type: String, body: JsonObject): VoiceEvent? = when (type) {
        "puck_voice_state" -> state(body) ?: VoiceEvent.Malformed(type)
        "puck_voice_audio" -> audio(body) ?: VoiceEvent.Malformed(type)
        "puck_voice_transcript" -> body.string("delta")?.takeIf { it.isNotEmpty() }?.let(VoiceEvent::Transcript) ?: VoiceEvent.Malformed(type)
        "puck_platform_navigation" -> body.obj("destination")?.let(::destination)?.let(VoiceEvent::Navigation) ?: VoiceEvent.Malformed(type)
        else -> null
    }

    private fun state(body: JsonObject): VoiceEvent.State? {
        val status = when (body.string("status")) {
            "connecting" -> VoiceStatus.Connecting
            "listening" -> VoiceStatus.Listening
            "thinking" -> VoiceStatus.Thinking
            "ended" -> VoiceStatus.Ended
            "error" -> VoiceStatus.Error
            else -> return null
        }
        return VoiceEvent.State(status, body.bool("userSpeaking") == true, body.bool("puckSpeaking") == true, body.string("error"))
    }

    private fun audio(body: JsonObject): VoiceEvent? {
        val base64 = body.string("audioBase64") ?: return null
        val format = body.string("format") ?: PCM16_24K
        val sampleRate = body.long("sampleRate")
        if (format != PCM16_24K || sampleRate != 24_000L) return VoiceEvent.Unplayable(format, sampleRate)
        return try {
            VoiceEvent.Audio(decoder.decode(base64))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun destination(destination: JsonObject): PaneDestination? {
        val thread = destination.string("threadID")?.let(ThreadId::parse) ?: return null
        return when (destination.string("type")) {
            "thread" -> PaneDestination.Thread(thread)
            "thread_view" -> when (destination.string("view")) {
                "portal" -> PaneDestination.Machine(thread, MachineTab.Portal(null))
                "diffs" -> PaneDestination.Machine(thread, MachineTab.Changes(null))
                else -> null
            }
            "file" -> {
                val path = destination.string("path") ?: return null
                when (destination.string("view")) {
                    "diffs" -> PaneDestination.Machine(thread, MachineTab.Changes(path))
                    "files" -> PaneDestination.Machine(thread, MachineTab.Files(path))
                    else -> null
                }
            }
            "portal" -> destination.string("url")?.let(::httpUri)?.let { PaneDestination.Machine(thread, MachineTab.Portal(portOf(it))) }
            else -> null
        }
    }

    private fun httpUri(url: String): URI? =
        try {
            URI(url).takeIf { it.scheme == "http" || it.scheme == "https" }
        } catch (_: URISyntaxException) {
            null
        }

    /**
     * Portal hosts name their port as a `-p<port>` suffix on the first label (`t-…-p8765.onamp.dev`,
     * `captures/s13-portal.jsonl`); a URL with an explicit port uses that.
     */
    private fun portOf(uri: URI): Int? =
        uri.host?.substringBefore('.')?.let { PORT_LABEL.find(it) }?.groupValues?.get(1)?.toIntOrNull() ?: uri.port.takeIf { it > 0 }

    private val PORT_LABEL = Regex("-p(\\d{1,5})$")
}
