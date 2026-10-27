package com.agentifiers.amp.waymote

/** Every Waymote binary frame starts with this version byte. */
internal const val WIRE_VERSION = 2

/**
 * A `/stream` frame (e88f9517.js `An()`): 40-byte little-endian header, encoded access unit from [PAYLOAD].
 * The payload stays in [bytes] so it reaches the decoder without a copy.
 */
internal class VideoFrame(
    val keyframe: Boolean,
    /** The decoder drops its queue and waits for the next keyframe. */
    val flush: Boolean,
    val timestampUs: Long,
    val generation: Long,
    val width: Int,
    val height: Int,
    /** The last input sequence the server applied. */
    val inputAck: Long,
    val bytes: ByteArray,
) {
    companion object {
        const val PAYLOAD = 40
        private const val KIND = 1

        fun parse(bytes: ByteArray): VideoFrame? {
            if (bytes.size < PAYLOAD || bytes.u8(0) != WIRE_VERSION || bytes.u8(1) != KIND) return null
            val flags = bytes.u8(2)
            return VideoFrame(
                keyframe = flags and 1 != 0,
                flush = flags and 2 != 0,
                timestampUs = bytes.u64(12),
                generation = bytes.u32(20),
                width = bytes.u16(24),
                height = bytes.u16(26),
                inputAck = bytes.u32(36),
                bytes = bytes,
            )
        }
    }
}

/** An `/audio` frame (e88f9517.js `zn()`): 24-byte header, one Opus packet from [PAYLOAD]. */
internal class AudioFrame(val reset: Boolean, val timestampUs: Long, val generation: Long, val bytes: ByteArray) {
    companion object {
        const val PAYLOAD = 24
        private const val KIND = 2

        fun parse(bytes: ByteArray): AudioFrame? {
            if (bytes.size < PAYLOAD || bytes.u8(0) != WIRE_VERSION || bytes.u8(1) != KIND) return null
            return AudioFrame(reset = bytes.u8(2) and 1 != 0, timestampUs = bytes.u64(12), generation = bytes.u32(20), bytes = bytes)
        }
    }
}

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xff
private fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
private fun ByteArray.u32(at: Int): Long = (u16(at).toLong() or (u16(at + 2).toLong() shl 16))
private fun ByteArray.u64(at: Int): Long = u32(at) or (u32(at + 4) shl 32)
