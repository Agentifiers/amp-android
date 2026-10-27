package com.agentifiers.amp.design.code

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight

private const val ESC = '\u001B'
private const val BEL = '\u0007'

/**
 * Terminal output with every escape sequence removed and SGR foreground colors and bold kept as spans. [ansi] is the
 * 16-color table, SGR 30-37 then 90-97.
 */
fun ansiToAnnotatedString(text: String, ansi: List<Color>): AnnotatedString {
    val out = AnnotatedString.Builder(text.length)
    var pen = Pen()
    var runStart = 0
    fun setPen(next: Pen) {
        if (next == pen) return
        pen.style?.let { if (out.length > runStart) out.addStyle(it, runStart, out.length) }
        pen = next
        runStart = out.length
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == ESC -> {
                val end = escapeEnd(text, i)
                if (text.getOrNull(i + 1) == '[' && text[end - 1] == 'm') setPen(pen.apply(text.substring(i + 2, end - 1), ansi))
                i = end
            }
            c == '\r' && text.getOrNull(i + 1) == '\n' -> i++
            else -> {
                out.append(c)
                i++
            }
        }
    }
    setPen(Pen())
    return out.toAnnotatedString()
}

private data class Pen(val color: Color? = null, val bold: Boolean = false) {
    val style: SpanStyle?
        get() = if (color == null && !bold) null else SpanStyle(color = color ?: Color.Unspecified, fontWeight = if (bold) FontWeight.Bold else null)

    fun apply(params: String, ansi: List<Color>): Pen {
        val codes = params.split(';', ':').map { it.toIntOrNull() ?: 0 }
        var pen = this
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> pen = Pen()
                1 -> pen = pen.copy(bold = true)
                22 -> pen = pen.copy(bold = false)
                in 30..37 -> pen = pen.copy(color = ansi[code - 30])
                39 -> pen = pen.copy(color = null)
                in 90..97 -> pen = pen.copy(color = ansi[code - 90 + 8])
                38, 48 -> {
                    val (color, used) = extendedColor(codes, i + 1, ansi)
                    if (code == 38 && color != null) pen = pen.copy(color = color)
                    i += used
                }
            }
            i++
        }
        return pen
    }
}

/** `5;n` (256 colors) or `2;r;g;b` (truecolor) starting at [at]; returns the color and how many codes it used. */
private fun extendedColor(codes: List<Int>, at: Int, ansi: List<Color>): Pair<Color?, Int> = when (codes.getOrNull(at)) {
    5 -> palette256(codes.getOrElse(at + 1) { 0 }, ansi) to 2
    2 -> Color(codes.getOrElse(at + 1) { 0 }, codes.getOrElse(at + 2) { 0 }, codes.getOrElse(at + 3) { 0 }) to 4
    else -> null to 0
}

private fun palette256(n: Int, ansi: List<Color>): Color? = when (n) {
    in 0..15 -> ansi[n]
    in 16..231 -> {
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        val k = n - 16
        Color(levels[k / 36], levels[k / 6 % 6], levels[k % 6])
    }
    in 232..255 -> (8 + (n - 232) * 10).let { Color(it, it, it) }
    else -> null
}

/** Index just past the escape sequence starting at [start]: CSI, OSC/DCS-style strings, or a short escape. */
private fun escapeEnd(text: String, start: Int): Int {
    val kind = text.getOrNull(start + 1) ?: return text.length
    return when (kind) {
        '[' -> {
            var i = start + 2
            while (i < text.length && text[i].code !in 0x40..0x7E) i++
            (i + 1).coerceAtMost(text.length)
        }
        ']', 'P', 'X', '^', '_' -> {
            var i = start + 2
            while (i < text.length) {
                if (text[i] == BEL) return i + 1
                if (text[i] == ESC && text.getOrNull(i + 1) == '\\') return i + 2
                i++
            }
            text.length
        }
        else -> {
            var i = start + 1
            while (i < text.length && text[i].code in 0x20..0x2F) i++
            (i + 1).coerceAtMost(text.length)
        }
    }
}
