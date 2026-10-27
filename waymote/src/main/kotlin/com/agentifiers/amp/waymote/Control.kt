package com.agentifiers.amp.waymote

import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val wire = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Raw text control frames, not JSON (e88f9517.js `dn`/`fn`). */
internal const val ACQUIRE = "acquire"
internal const val RELEASE = "release"

/** Text frames the server sends on `/stream`, `/audio` and `/control` (findings/desktop.md, section 1). */
@Serializable
internal sealed interface ControlIn {
    @Serializable @SerialName("video-config")
    data class VideoConfig(val codec: String) : ControlIn

    @Serializable @SerialName("audio-config")
    data class AudioConfig(val version: Int, val enabled: Boolean, val codec: String = "", val sampleRate: Int = 0, val channels: Int = 0) : ControlIn

    @Serializable @SerialName("control-state")
    data class ControlState(val state: ServerControl, val resolvedKeysyms: Boolean = false) : ControlIn

    @Serializable @SerialName("display-state")
    data class DisplayState(
        val displays: List<WireDisplay>,
        val selectedID: String? = null,
        val generation: Long,
        val switching: Boolean,
        val error: String? = null,
    ) : ControlIn {
        /** The web client drops a display-state with a bad display or a selection outside the list (e88f9517.js `xt()`). */
        val valid: Boolean
            get() = generation >= 0 && displays.all { it.width > 0 && it.height > 0 } &&
                (selectedID == null || displays.any { it.id == selectedID })
    }

    @Serializable @SerialName("pong")
    data class Pong(val id: Long) : ControlIn

    @Serializable @SerialName("resize-applied")
    data class ResizeApplied(val request: Int, val generation: Long, val width: Int, val height: Int, val scale: Int) : ControlIn

    @Serializable @SerialName("clipboard")
    data class Clipboard(val text: String) : ControlIn

    @Serializable @SerialName("quality")
    data class Quality(val bitrate: Double, val scale: Double, val fps: Double) : ControlIn

    companion object {
        /** Null for anything this client does not read: unknown types, malformed JSON, or missing fields. */
        fun parse(text: String): ControlIn? = try {
            wire.decodeFromString(serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

@Serializable
internal enum class ServerControl {
    @SerialName("ready") Ready,
    @SerialName("busy") Busy,
    @SerialName("active") Active,
}

@Serializable
internal data class WireDisplay(val id: String, val name: String, val width: Int, val height: Int, val isMain: Boolean)

/** JSON the client sends on `/control`. */
@Serializable
internal sealed interface ControlOut {
    @Serializable @SerialName("ping")
    data class Ping(val id: Long) : ControlOut

    @Serializable @SerialName("feedback")
    data class Feedback(val queue: Int, val dropped: Int, val fps: Int, val rtt: Int, val active: Boolean) : ControlOut

    @Serializable @SerialName("clipboard-write")
    data class ClipboardWrite(val text: String) : ControlOut

    @Serializable @SerialName("display-select")
    data class DisplaySelect(val id: String) : ControlOut

    @Serializable @SerialName("display-presented")
    data class DisplayPresented(val generation: Long) : ControlOut

    @Serializable @SerialName("text")
    data class Text(val action: TextAction, val text: String, val sequence: Long) : ControlOut

    fun encode(): String = wire.encodeToString(serializer(), this)
}

@Serializable
internal enum class TextAction {
    @SerialName("preedit") Preedit,
    @SerialName("commit") Commit,
}
