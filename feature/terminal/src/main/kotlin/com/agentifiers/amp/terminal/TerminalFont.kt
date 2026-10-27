package com.agentifiers.amp.terminal

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.floor

/** Text size in whole sp, 8 to 24 (terminal.md, "Rendering in Compose"). */
@JvmInline
internal value class TextSizeSp private constructor(val value: Int) {
    fun larger(): TextSizeSp = of(value + 1)
    fun smaller(): TextSizeSp = of(value - 1)

    companion object {
        const val MIN = 8
        const val MAX = 24
        val Default = TextSizeSp(12)
        fun of(value: Int): TextSizeSp = TextSizeSp(value.coerceIn(MIN, MAX))
    }
}

/** The text size is remembered per device, not per window or thread. */
internal class TextSizePreference(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("amp-terminal", Context.MODE_PRIVATE)

    fun read(): TextSizeSp = TextSizeSp.of(prefs.getInt(KEY, TextSizeSp.Default.value))
    fun write(size: TextSizeSp) = prefs.edit().putInt(KEY, size.value).apply()

    private companion object {
        const val KEY = "textSizeSp"
    }
}

/**
 * The monospace grid at one text size, in pixels from the window's density and font scale. The line height keeps
 * the 12sp/16sp ratio of the layout prototype at every size ([lineHeightSp]).
 */
internal class TerminalFont(val textSizePx: Float, val lineHeightPx: Float) {
    private val paints = Array(4) { style ->
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, style)
            textSize = textSizePx
            fontFeatureSettings = "liga 0, calt 0"
        }
    }

    val cellWidthPx: Float = paints[Typeface.NORMAL].measureText("0123456789".repeat(10)) / 100f

    /** Glyphs sit centered in the line: the baseline's distance from the line's top. */
    val baselinePx: Float = paints[Typeface.NORMAL].fontMetrics.let { (lineHeightPx - (it.descent - it.ascent)) / 2f - it.ascent }

    fun paint(bold: Boolean, italic: Boolean): Paint = paints[(if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)]
}

internal fun lineHeightSp(size: TextSizeSp): Float = size.value * 16f / 12f

internal data class GridSize(val cols: Int, val rows: Int) {
    /** terminal.md, "Open": at least 2 columns and 1 row before the first `open`. */
    val usable: Boolean get() = cols >= 2 && rows >= 1
}

/** terminal.md, "Columns per arrangement": the grid area already excludes the pane's 24dp of horizontal padding. */
internal fun gridSize(gridWidthPx: Float, gridHeightPx: Float, cellWidthPx: Float, lineHeightPx: Float): GridSize =
    GridSize(floor(gridWidthPx / cellWidthPx).toInt().coerceAtLeast(0), floor(gridHeightPx / lineHeightPx).toInt().coerceAtLeast(0))
