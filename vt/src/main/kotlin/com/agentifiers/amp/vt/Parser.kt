package com.agentifiers.amp.vt

/** Parameters of one CSI sequence. A missing parameter reads as [MISSING]. */
internal class CsiParams {
    val values = IntArray(MAX)
    var count = 0

    /** Bit i is set when parameter i followed a `:` and so is a sub-parameter of the one before it. */
    var colons = 0L
    var prefix = 0
    var intermediate = 0

    fun isSub(i: Int): Boolean = i < MAX && (colons ushr i) and 1L != 0L

    /** Parameter [i], or [default] when it is missing or 0. */
    fun at(i: Int, default: Int): Int {
        val v = if (i < count) values[i] else MISSING
        return if (v <= 0) default else v
    }

    fun raw(i: Int): Int = if (i < count) values[i] else MISSING

    companion object {
        const val MAX = 32
        const val MISSING = -1
    }
}

/** Receives what the [Parser] recognizes. */
internal interface Performer {
    fun print(cp: Int)
    fun execute(c: Int)
    fun escDispatch(intermediate: Int, final: Int)
    fun csiDispatch(params: CsiParams, final: Int)
    fun oscDispatch(data: CharSequence)
}

/**
 * Paul Williams' DEC parser (vt100.net/emu/dec_ansi_parser) over UTF-8 decoded codepoints. The DCS states and the
 * SOS, PM and APC string share one state, because every payload they carry is consumed and dropped.
 */
internal class Parser(private val out: Performer) {
    private enum class State { Ground, Escape, EscapeIntermediate, CsiEntry, CsiParam, CsiIntermediate, CsiIgnore, OscString, IgnoredString }

    private var state = State.Ground
    private val csi = CsiParams()
    private var digits = false
    private var escIntermediate = 0
    private val osc = StringBuilder()
    private var oscOverflow = false

    private var utf8Remaining = 0
    private var utf8Cp = 0
    private var utf8Min = 0

    fun feed(bytes: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xff
            if (b in 0x20..0x7e && utf8Remaining == 0 && state == State.Ground) out.print(b) else decode(b)
        }
    }

    private fun decode(b: Int) {
        if (utf8Remaining > 0) {
            if (b and 0xc0 == 0x80) {
                utf8Cp = (utf8Cp shl 6) or (b and 0x3f)
                if (--utf8Remaining == 0) {
                    val cp = utf8Cp
                    advance(if (cp < utf8Min || cp in 0xd800..0xdfff || cp > 0x10ffff) REPLACEMENT else cp)
                }
                return
            }
            utf8Remaining = 0
            advance(REPLACEMENT)
        }
        when (b) {
            in 0x00..0x7f -> advance(b)
            in 0xc2..0xdf -> startSequence(b and 0x1f, 1, 0x80)
            in 0xe0..0xef -> startSequence(b and 0x0f, 2, 0x800)
            in 0xf0..0xf4 -> startSequence(b and 0x07, 3, 0x10000)
            else -> advance(REPLACEMENT)
        }
    }

    private fun startSequence(bits: Int, remaining: Int, min: Int) {
        utf8Cp = bits
        utf8Remaining = remaining
        utf8Min = min
    }

    private fun advance(cp: Int) {
        if (cp == 0x18 || cp == 0x1a) {
            if (state == State.OscString) clearOsc()
            state = State.Ground
            return
        }
        if (cp == 0x1b) {
            if (state == State.OscString) finishOsc()
            enterEscape()
            return
        }
        when (state) {
            State.Ground -> when {
                cp < 0x20 -> out.execute(cp)
                cp == 0x7f || cp in 0x80..0x9f -> Unit
                else -> out.print(cp)
            }
            State.Escape -> when {
                cp < 0x20 -> out.execute(cp)
                cp in 0x20..0x2f -> {
                    escIntermediate = cp
                    state = State.EscapeIntermediate
                }
                cp == '['.code -> enterCsi()
                cp == ']'.code -> {
                    osc.setLength(0)
                    oscOverflow = false
                    state = State.OscString
                }
                cp == 'P'.code || cp == 'X'.code || cp == '^'.code || cp == '_'.code -> state = State.IgnoredString
                cp in 0x30..0x7e -> {
                    state = State.Ground
                    out.escDispatch(0, cp)
                }
            }
            State.EscapeIntermediate -> when {
                cp < 0x20 -> out.execute(cp)
                cp in 0x20..0x2f -> escIntermediate = cp
                cp in 0x30..0x7e -> {
                    state = State.Ground
                    out.escDispatch(escIntermediate, cp)
                }
            }
            State.CsiEntry, State.CsiParam -> when {
                cp < 0x20 -> out.execute(cp)
                cp in 0x30..0x39 -> digit(cp - 0x30)
                cp == ';'.code -> separator(colon = false)
                cp == ':'.code -> separator(colon = true)
                cp in 0x3c..0x3f -> if (state == State.CsiEntry) {
                    csi.prefix = cp
                    state = State.CsiParam
                } else {
                    state = State.CsiIgnore
                }
                cp in 0x20..0x2f -> {
                    csi.intermediate = cp
                    state = State.CsiIntermediate
                }
                cp in 0x40..0x7e -> dispatchCsi(cp)
            }
            State.CsiIntermediate -> when {
                cp < 0x20 -> out.execute(cp)
                cp in 0x20..0x2f -> csi.intermediate = cp
                cp in 0x30..0x3f -> state = State.CsiIgnore
                cp in 0x40..0x7e -> dispatchCsi(cp)
            }
            State.CsiIgnore -> when {
                cp < 0x20 -> out.execute(cp)
                cp in 0x40..0x7e -> state = State.Ground
            }
            State.OscString -> when {
                cp == 0x07 -> {
                    finishOsc()
                    state = State.Ground
                }
                cp < 0x20 -> Unit
                oscOverflow -> Unit
                osc.length >= MAX_OSC -> {
                    oscOverflow = true
                    clearOsc()
                }
                else -> osc.appendCodePoint(cp)
            }
            State.IgnoredString -> Unit
        }
    }

    private fun enterEscape() {
        state = State.Escape
        escIntermediate = 0
    }

    private fun enterCsi() {
        state = State.CsiEntry
        csi.count = 0
        csi.colons = 0L
        csi.prefix = 0
        csi.intermediate = 0
        digits = false
    }

    private fun digit(d: Int) {
        state = State.CsiParam
        if (csi.count == 0) csi.count = 1
        val i = csi.count - 1
        if (i >= CsiParams.MAX) return
        val v = if (digits) csi.values[i] else 0
        csi.values[i] = minOf(v * 10 + d, 65535)
        digits = true
    }

    private fun separator(colon: Boolean) {
        state = State.CsiParam
        if (csi.count == 0) {
            csi.count = 1
            csi.values[0] = CsiParams.MISSING
        }
        if (csi.count < CsiParams.MAX) {
            if (colon) csi.colons = csi.colons or (1L shl csi.count)
            csi.values[csi.count] = CsiParams.MISSING
        }
        csi.count++
        digits = false
    }

    private fun dispatchCsi(final: Int) {
        state = State.Ground
        if (csi.count > CsiParams.MAX) csi.count = CsiParams.MAX
        out.csiDispatch(csi, final)
    }

    private fun finishOsc() {
        if (!oscOverflow) out.oscDispatch(osc)
        clearOsc()
        oscOverflow = false
    }

    /** Drops the buffer's storage after a large OSC 52 so an idle session does not hold megabytes. */
    private fun clearOsc() {
        osc.setLength(0)
        if (osc.capacity() > 65_536) osc.trimToSize()
    }

    private companion object {
        const val REPLACEMENT = 0xfffd
        const val MAX_OSC = 1 shl 20
    }
}
