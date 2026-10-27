package com.agentifiers.amp.design.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

/** WCAG 2.1 contrast for the Forest palette; the tokens and ratios print to the test's standard output. */
class PaletteContrastTest {
    private data class Pair(val name: String, val fg: Color, val bg: Color, val minimum: Double)

    private fun luminance(color: Color): Double {
        fun channel(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun ratio(fg: Color, bg: Color): Double {
        val (light, dark) = listOf(luminance(fg), luminance(bg)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun pairs(p: AmpPalette): List<Pair> {
        val grounds = listOf("canvas" to p.surfaces.canvas, "pane" to p.surfaces.pane, "raised" to p.surfaces.raised)
        val texts = listOf("ink" to p.text.ink, "inkMuted" to p.text.inkMuted, "accent" to p.accents.accent) + with(p.status) {
            listOf(
                "working" to working, "needsYou" to needsYou, "done" to done, "error" to error, "idle" to idle,
                "success" to success, "warning" to warning, "danger" to danger, "link" to link, "mention" to mention,
            )
        }
        val diff = p.code.diff
        return texts.flatMap { (fg, fgColor) -> grounds.map { (bg, bgColor) -> Pair("$fg on $bg", fgColor, bgColor, 4.5) } } +
            grounds.map { (bg, bgColor) -> Pair("brandMark on $bg", p.accents.brandMark, bgColor, 3.0) } +
            listOf(
                Pair("onAccent on accent", p.text.onAccent, p.accents.accent, 4.5),
                Pair("onAccent on danger", p.text.onAccent, p.status.danger, 4.5),
                Pair("ink on accentMuted", p.text.ink, p.accents.accentMuted, 4.5),
                Pair("ink on control", p.text.ink, p.surfaces.control, 4.5),
                Pair("accent on accentMuted", p.accents.accent, p.accents.accentMuted, 4.5),
                Pair("addedText on addedBackground", diff.addedText, diff.addedBackground, 4.5),
                Pair("removedText on removedBackground", diff.removedText, diff.removedBackground, 4.5),
                Pair("ink on addedStrongBackground", p.text.ink, diff.addedStrongBackground, 4.5),
                Pair("ink on removedStrongBackground", p.text.ink, diff.removedStrongBackground, 4.5),
                Pair("editorText on editorBackground", p.code.editorText, p.code.editorBackground, 4.5),
            )
    }

    /** Color.toString prints channel floats; a reader wants #AARRGGBB. */
    private fun hexed(text: String): String = Regex("""Color\(([\d.]+), ([\d.]+), ([\d.]+), ([\d.]+), [^)]*\)""").replace(text) { m ->
        val (r, g, b, a) = m.destructured
        "#%02X%02X%02X%02X".format(*listOf(a, r, g, b).map { (it.toFloat() * 255 + 0.5f).toInt() }.toTypedArray())
    }

    @Test
    fun forestMeetsWcagContrast() {
        println(hexed(Forest.toString()))
        val failures = pairs(Forest).mapNotNull { pair ->
            val r = ratio(pair.fg, pair.bg)
            println("  %-36s %5.2f:1  (needs %.1f)".format(pair.name, r, pair.minimum))
            if (r < pair.minimum) "${pair.name} is %.2f:1".format(r) else null
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun ratiosMatchBrandMd() {
        assertEquals(15.2, ratio(Forest.accents.brandMark, Forest.surfaces.canvas), 0.05)
        assertEquals(10.93, ratio(Forest.text.onAccent, Forest.accents.accent), 0.01)
        assertEquals(10.38, ratio(Forest.accents.accent, Forest.surfaces.pane), 0.01)
        assertEquals(11.50, ratio(Forest.text.ink, Forest.surfaces.pane), 0.01)
        assertEquals(4.64, ratio(Forest.code.diff.addedText, Forest.code.diff.addedBackground), 0.01)
    }

    @Test
    fun derivedTokensMatchTheWebMeasurements() {
        fun hex(color: Color) = hexed(color.toString())
        assertEquals("#FF020302", hex(Forest.surfaces.canvas))
        assertEquals("#FF0C1516", hex(Forest.surfaces.pane))
        assertEquals("#FF1D2626", hex(Forest.surfaces.raised))
        assertEquals("#FF2A3132", hex(Forest.surfaces.control))
        assertEquals("#FF1B2324", hex(Forest.surfaces.line))
        assertEquals("#FFC2C2C0", hex(Forest.accents.accent))
    }
}
