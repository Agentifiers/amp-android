package com.agentifiers.amp.waymote

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mouse buttons with their evdev codes (e88f9517.js `Fe`, indexed by DOM `button` 0..4). */
public enum class MouseButton(internal val evdev: Int) { Left(272), Middle(274), Right(273), Side(275), Extra(276) }

/** Byte 2 of a key struct. */
public enum class KeyAction(internal val wire: Int) { Up(0), Down(1), Repeat(2) }

/** One 16-byte binary control struct (e88f9517.js `Y()`): version, kind, flags, pad, u32 a@4, u32 b@8, u32 c@12, little-endian. */
internal sealed interface InputEvent {
    /** [x] and [y] span the presented video, 0..65535. */
    data class PointerAbsolute(val x: Int, val y: Int) : InputEvent

    data class Button(val button: MouseButton, val down: Boolean) : InputEvent

    data class Wheel(val dx: Float, val dy: Float) : InputEvent

    /** [keysym] is 0 unless the server reported `resolvedKeysyms`. */
    data class Key(val linuxCode: Int, val action: KeyAction, val keysym: Int) : InputEvent

    /** Cancels held buttons and keys on the server; carries no sequence. */
    data object Reset : InputEvent

    /** [scale120] is the device scale in 120ths; even dimensions within [RemoteSize] bounds. */
    data class Resize(val width: Int, val height: Int, val scale120: Int) : InputEvent

    data class PointerRelative(val dx: Float, val dy: Float) : InputEvent
}

/**
 * Encodes input structs and owns the two counters the protocol threads through them: the input sequence,
 * shared with `text` messages, and the 16-bit resize request id. Both wrap past 0.
 */
internal class InputEncoder {
    private var sequence = 0L
    private var resizeRequest = 0

    fun nextSequence(): Long {
        sequence = (sequence + 1) and 0xffffffffL
        if (sequence == 0L) sequence = 1
        return sequence
    }

    fun encode(event: InputEvent): ByteArray {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0, WIRE_VERSION.toByte())
        when (event) {
            is InputEvent.PointerAbsolute -> buffer.struct(1, 0, event.x, event.y, nextSequence())
            is InputEvent.Button -> buffer.struct(2, if (event.down) 1 else 0, event.button.evdev, 0, nextSequence())
            is InputEvent.Wheel -> buffer.floats(3, event.dx, event.dy, nextSequence())
            is InputEvent.Key -> buffer.struct(4, event.action.wire, event.linuxCode, event.keysym, nextSequence())
            InputEvent.Reset -> buffer.struct(5, 0, 0, 0, 0)
            is InputEvent.Resize -> {
                resizeRequest = (resizeRequest + 1) and 0xffff
                if (resizeRequest == 0) resizeRequest = 1
                buffer.struct(6, 0, event.width, event.height, (resizeRequest.toLong() shl 16) or event.scale120.toLong())
            }
            is InputEvent.PointerRelative -> buffer.floats(8, event.dx, event.dy, nextSequence())
        }
        return buffer.array()
    }

    private fun ByteBuffer.struct(kind: Int, flags: Int, a: Int, b: Int, c: Long) {
        put(1, kind.toByte()).put(2, flags.toByte()).putInt(4, a).putInt(8, b).putInt(12, c.toInt())
    }

    private fun ByteBuffer.floats(kind: Int, a: Float, b: Float, c: Long) {
        put(1, kind.toByte()).putFloat(4, a.coerceIn(-DELTA_LIMIT, DELTA_LIMIT)).putFloat(8, b.coerceIn(-DELTA_LIMIT, DELTA_LIMIT)).putInt(12, c.toInt())
    }

    private companion object {
        const val DELTA_LIMIT = 4096f
    }
}
