package com.agentifiers.amp.waymote

import com.agentifiers.amp.sync.api.DesktopStream

/** What a Desktop pane shows. One value at a time; a session never leaves [Ended]. */
public sealed interface SessionState {
    public data object Connecting : SessionState

    public data class Streaming(
        val displays: Displays,
        val control: Control,
        val video: Video,
        val audio: Audio,
        /** The size the server last applied after a resize request. */
        val resolution: RemoteSize?,
        val quality: Quality?,
        /** The newest text the remote desktop copied. */
        val remoteClipboard: String?,
    ) : SessionState

    public data class Ended(val reason: EndReason) : SessionState
}

public data class RemoteDisplay(val id: String, val name: String, val width: Int, val height: Int, val isMain: Boolean)

/**
 * The remote displays. [switching] holds from a selection until the first frame of the new generation is on
 * screen; input other than reset and resize is withheld meanwhile.
 */
public data class Displays(val list: List<RemoteDisplay>, val selected: String?, val switching: Boolean, val error: String?)

/** Who drives input. [Busy] means another viewer holds control. */
public enum class Control { Watching, Requesting, Active, Busy }

public sealed interface Video {
    public data object Waiting : Video

    /** Frames are on screen at this coded size. */
    public data class Showing(val width: Int, val height: Int) : Video
}

public sealed interface Audio {
    public data object Pending : Audio

    /** The server has no audio for this desktop. */
    public data object Off : Audio

    public data object Playing : Audio

    public data class Failed(val reason: String) : Audio
}

public data class Quality(val bitrateKbps: Int, val scalePercent: Int, val fps: Int)

public sealed interface EndReason {
    /** The device has no decoder for the `video-config` codec string. */
    public data class UnsupportedCodec(val codec: String) : EndReason

    public data class Closed(val stream: DesktopStream) : EndReason

    public data class Failed(val message: String) : EndReason
}
