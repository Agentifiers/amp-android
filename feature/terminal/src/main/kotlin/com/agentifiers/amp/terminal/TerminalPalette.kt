package com.agentifiers.amp.terminal

import androidx.compose.ui.graphics.toArgb
import com.agentifiers.amp.design.theme.AmpPalette
import com.agentifiers.amp.vt.Style
import com.agentifiers.amp.vt.VtColor

/** An xterm-style palette on the app's canvas and ink: 0-15 the ansi tokens, 16-231 the 6x6x6 cube, 232-255 grays. */
internal class TerminalPalette(colors: AmpPalette) {
    val background: Int = colors.surfaces.canvas.toArgb()
    val foreground: Int = colors.text.ink.toArgb()
    val selection: Int = colors.accents.accentMuted.toArgb()
    val cursor: Int = colors.text.ink.toArgb()

    private val table = IntArray(256) { index ->
        when {
            index < 16 -> colors.code.ansi[index].toArgb()
            index < 232 -> (index - 16).let { cubeLevels[it / 36] shl 16 or (cubeLevels[it / 6 % 6] shl 8) or cubeLevels[it % 6] } or OPAQUE
            else -> (8 + (index - 232) * 10).let { it shl 16 or (it shl 8) or it } or OPAQUE
        }
    }

    private fun resolve(color: VtColor, default: Int, brighten: Boolean): Int {
        color.rgb?.let { return it or OPAQUE }
        val index = color.paletteIndex ?: return default
        return table[if (brighten && index < 8) index + 8 else index]
    }

    /** Foreground and background of a cell as ARGB, with bold-as-bright, inverse, dim and invisible applied. */
    fun colors(style: Style): Long {
        var fg = resolve(style.fg, foreground, brighten = style.bold)
        var bg = resolve(style.bg, background, brighten = false)
        if (style.inverse) fg = bg.also { bg = fg }
        if (style.dim) fg = blend(fg, bg)
        if (style.invisible) fg = bg
        return (fg.toLong() shl 32) or (bg.toLong() and 0xFFFFFFFFL)
    }

    private fun blend(fg: Int, bg: Int): Int {
        fun mix(shift: Int) = ((fg ushr shift and 0xFF) * 6 + (bg ushr shift and 0xFF) * 4) / 10 shl shift
        return OPAQUE or mix(16) or mix(8) or mix(0)
    }

    private companion object {
        val cubeLevels = intArrayOf(0, 95, 135, 175, 215, 255)
        const val OPAQUE = 0xFF shl 24
    }
}

internal fun Long.fgArgb(): Int = (this ushr 32).toInt()
internal fun Long.bgArgb(): Int = toInt()
