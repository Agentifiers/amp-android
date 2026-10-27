package com.agentifiers.amp.waymote

import android.media.MediaFormat

/** Where decoded video goes. [SurfaceTarget] renders onto a Surface. */
public fun interface VideoTarget {
    /**
     * A decoder for [mime] at [width]x[height], or null once the target is gone (its Surface was destroyed).
     * [onRendered] and [onError] run on the decoder's thread.
     */
    public fun open(mime: String, width: Int, height: Int, onRendered: () -> Unit, onError: (String) -> Unit): VideoDecoder?
}

public interface VideoDecoder : AutoCloseable {
    /** Queues one access unit, `bytes[offset until bytes.size]`, without blocking. Ignored after [close]. */
    public fun decode(bytes: ByteArray, offset: Int, timestampUs: Long)

    /** Access units queued here and not yet handed to the codec. */
    public val backlog: Int
}

/** One Opus stream as `audio-config` described it. */
public data class AudioStreamFormat(val codec: String, val sampleRate: Int, val channels: Int)

public interface AudioDecoder : AutoCloseable {
    /** Queues one packet, `bytes[offset until bytes.size]`, without blocking. */
    public fun decode(bytes: ByteArray, offset: Int, timestampUs: Long)

    /** Drops queued and buffered audio, for an audio frame with the reset flag. */
    public fun reset()
}

/** The device's codecs. [AndroidMedia] is the real one. */
public interface MediaPlatform {
    public fun supportsVideo(mime: String): Boolean

    /** Throws when the device cannot play [format]; the session reports it and keeps the video. [onError] runs on the decoder's thread. */
    public fun openAudio(format: AudioStreamFormat, onError: (String) -> Unit): AudioDecoder
}

/** The MediaCodec MIME type for a `video-config` codec string, by its RFC 6381 sample entry. */
internal fun videoMime(codec: String): String? = when (codec.substringBefore('.').lowercase()) {
    "avc1", "avc3" -> MediaFormat.MIMETYPE_VIDEO_AVC
    "hvc1", "hev1" -> MediaFormat.MIMETYPE_VIDEO_HEVC
    "vp09" -> MediaFormat.MIMETYPE_VIDEO_VP9
    "vp8" -> MediaFormat.MIMETYPE_VIDEO_VP8
    "av01" -> MediaFormat.MIMETYPE_VIDEO_AV1
    else -> null
}
