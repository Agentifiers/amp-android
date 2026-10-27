package com.agentifiers.amp.sync.internal.terminal

import java.io.ByteArrayOutputStream

internal enum class RelayKind(val wire: Int) {
    ToExecutor(1),
    FromExecutor(2),
}

/**
 * One binary relay frame on the thread socket (terminal.md, "Binary relay frame"; `spikes/protocol/lib.ts:126-145`):
 * magic `0xAE`, version 1, kind, flags (bit 0 binary payload, bit 1 fin), channel id length, channel id, payload.
 */
internal class RelayFrame(val kind: RelayKind, val binary: Boolean, val fin: Boolean, val channelId: String, val payload: ByteArray) {
    val flags: Int get() = (if (binary) BINARY else 0) or (if (fin) FIN else 0)

    fun encode(): ByteArray {
        val id = channelId.encodeToByteArray()
        val out = ByteArray(HEADER + id.size + payload.size)
        out[0] = MAGIC.toByte()
        out[1] = VERSION.toByte()
        out[2] = kind.wire.toByte()
        out[3] = flags.toByte()
        out[4] = id.size.toByte()
        id.copyInto(out, HEADER)
        payload.copyInto(out, HEADER + id.size)
        return out
    }

    companion object {
        const val MAX_CHUNK = 1_000_000
        const val MAX_MESSAGE = 16 * 1024 * 1024
        private const val MAGIC = 0xAE
        private const val VERSION = 1
        private const val BINARY = 1
        private const val FIN = 2
        private const val HEADER = 5

        /** [payload] as frames of at most [MAX_CHUNK] payload bytes each; only the last carries fin. */
        fun chunks(kind: RelayKind, binary: Boolean, channelId: String, payload: ByteArray): List<RelayFrame> {
            if (payload.size <= MAX_CHUNK) return listOf(RelayFrame(kind, binary, fin = true, channelId, payload))
            return (payload.indices step MAX_CHUNK).map { start ->
                val end = minOf(start + MAX_CHUNK, payload.size)
                RelayFrame(kind, binary, fin = end == payload.size, channelId, payload.copyOfRange(start, end))
            }
        }

        /** Null for anything that is not a version 1 relay frame. */
        fun decode(bytes: ByteArray): RelayFrame? {
            if (bytes.size < HEADER || (bytes[0].toInt() and 0xff) != MAGIC || bytes[1].toInt() != VERSION) return null
            val kind = RelayKind.entries.firstOrNull { it.wire == bytes[2].toInt() } ?: return null
            val flags = bytes[3].toInt()
            val idLength = bytes[4].toInt() and 0xff
            if (idLength == 0 || bytes.size < HEADER + idLength) return null
            val channelId = bytes.decodeToString(HEADER, HEADER + idLength)
            return RelayFrame(kind, flags and BINARY != 0, flags and FIN != 0, channelId, bytes.copyOfRange(HEADER + idLength, bytes.size))
        }
    }
}

/** Joins relay chunks per channel until fin. A message that grows past [RelayFrame.MAX_MESSAGE] is dropped whole. */
internal class RelayReassembler {
    private class Partial(val binary: Boolean) {
        val bytes = ByteArrayOutputStream()
        var overflowed = false
    }

    private val partials = HashMap<String, Partial>()

    /** The whole message once its last chunk arrives, as one fin frame; null while incomplete or when dropped. */
    fun accept(frame: RelayFrame): RelayFrame? {
        val partial = partials[frame.channelId]
        if (partial == null && frame.fin) return frame
        val growing = partial ?: Partial(frame.binary).also { partials[frame.channelId] = it }
        if (!growing.overflowed) {
            if (growing.bytes.size() + frame.payload.size > RelayFrame.MAX_MESSAGE) {
                growing.overflowed = true
                growing.bytes.reset()
            } else {
                growing.bytes.write(frame.payload)
            }
        }
        if (!frame.fin) return null
        partials.remove(frame.channelId)
        return if (growing.overflowed) null else RelayFrame(frame.kind, growing.binary, fin = true, frame.channelId, growing.bytes.toByteArray())
    }

    fun clear(channelId: String) {
        partials.remove(channelId)
    }

    fun clear() {
        partials.clear()
    }
}
