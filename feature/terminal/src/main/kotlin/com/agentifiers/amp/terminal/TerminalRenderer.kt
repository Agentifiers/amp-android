package com.agentifiers.amp.terminal

import android.graphics.Canvas
import android.graphics.Paint
import com.agentifiers.amp.vt.CursorShape
import com.agentifiers.amp.vt.GridPoint
import com.agentifiers.amp.vt.Row
import com.agentifiers.amp.vt.ScreenSnapshot
import com.agentifiers.amp.vt.Style
import kotlin.math.max

/** A selection from [anchor], where it began, to [focus], where the pointer or handle is now. */
internal data class TerminalSelection(val anchor: GridPoint, val focus: GridPoint) {
    val start: GridPoint get() = minOf(anchor, focus)
    val end: GridPoint get() = maxOf(anchor, focus)

    fun text(snapshot: ScreenSnapshot): String = snapshot.selectionText(start, end)
}

/** What one draw shows: [topLine] is the absolute line (history first) of the first drawn row. */
internal class TerminalFrame(
    val snapshot: ScreenSnapshot,
    val topLine: Int,
    val selection: TerminalSelection?,
    val focused: Boolean,
    val composing: String,
)

/**
 * Draws a snapshot on a fixed grid: each glyph sits at its column times the cell width, so a fallback glyph cannot
 * push later columns. Rows are immutable and shared between snapshots, so each row's runs are built once.
 */
internal class TerminalRenderer(private val font: TerminalFont, private val palette: TerminalPalette) {
    private enum class Kind { Text, Box, Glyph }

    private class Run(val col: Int, val cells: Int, val text: String, val style: Style, val kind: Kind, val codepoint: Int, val link: Boolean)

    private val layouts = object : LinkedHashMap<Row, Array<Run>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Row, Array<Run>>): Boolean = size > MAX_CACHED_ROWS
    }
    private val fill = Paint()
    private val shapes = Paint(Paint.ANTI_ALIAS_FLAG)
    private val builder = StringBuilder()

    fun draw(canvas: Canvas, frame: TerminalFrame) {
        val snapshot = frame.snapshot
        val cw = font.cellWidthPx
        val lh = font.lineHeightPx
        canvas.save()
        canvas.clipRect(0f, 0f, snapshot.cols * cw, snapshot.rows * lh)
        val selStart = frame.selection?.start
        val selEnd = frame.selection?.end
        for (r in 0 until snapshot.rows) {
            val line = frame.topLine + r
            if (line < 0 || line >= snapshot.totalLines) continue
            val row = snapshot.line(line)
            val y = r * lh
            drawBackgrounds(canvas, row, snapshot.cols, y, line, selStart, selEnd)
            for (run in layout(row)) {
                if (run.col < snapshot.cols) drawRun(canvas, run, y)
            }
        }
        drawCursor(canvas, frame)
        canvas.restore()
    }

    private fun drawBackgrounds(canvas: Canvas, row: Row, cols: Int, y: Float, line: Int, selStart: GridPoint?, selEnd: GridPoint?) {
        val cw = font.cellWidthPx
        var col = 0
        while (col < cols) {
            val bg = background(row, col, line, selStart, selEnd)
            var end = col + 1
            while (end < cols && background(row, end, line, selStart, selEnd) == bg) end++
            if (bg != palette.background) {
                fill.color = bg
                canvas.drawRect(col * cw, y, end * cw, y + font.lineHeightPx, fill)
            }
            col = end
        }
    }

    private fun background(row: Row, col: Int, line: Int, selStart: GridPoint?, selEnd: GridPoint?): Int {
        if (selStart != null && selEnd != null && inside(line, col, selStart, selEnd)) return palette.selection
        return palette.colors(row.style(col)).bgArgb()
    }

    private fun inside(line: Int, col: Int, start: GridPoint, end: GridPoint): Boolean {
        if (line < start.line || line > end.line) return false
        if (line == start.line && col < start.col) return false
        return !(line == end.line && col > end.col)
    }

    private fun drawRun(canvas: Canvas, run: Run, y: Float) {
        val cw = font.cellWidthPx
        val x = run.col * cw
        val fg = palette.colors(run.style).fgArgb()
        when (run.kind) {
            Kind.Box -> {
                shapes.color = fg
                drawBoxGlyph(canvas, run.codepoint, x, y, cw, font.lineHeightPx, shapes)
            }
            Kind.Text, Kind.Glyph -> if (run.text.isNotBlank()) {
                val paint = font.paint(run.style.bold, run.style.italic)
                paint.color = fg
                val glyphX = if (run.kind == Kind.Glyph) x + (run.cells * cw - paint.measureText(run.text)).coerceAtLeast(0f) / 2f else x
                canvas.drawText(run.text, glyphX, y + font.baselinePx, paint)
            }
        }
        val stroke = max(1f, font.lineHeightPx / 16f)
        if (run.style.underline || run.link) {
            fill.color = fg
            val underlineY = y + font.baselinePx + stroke * 1.5f
            canvas.drawRect(x, underlineY, x + run.cells * cw, underlineY + stroke, fill)
        }
        if (run.style.strikethrough) {
            fill.color = fg
            val strikeY = y + font.lineHeightPx / 2f
            canvas.drawRect(x, strikeY, x + run.cells * cw, strikeY + stroke, fill)
        }
    }

    private fun drawCursor(canvas: Canvas, frame: TerminalFrame) {
        val snapshot = frame.snapshot
        val cursor = snapshot.cursor
        val r = snapshot.scrollback + cursor.row - frame.topLine
        if (r !in 0 until snapshot.rows) return
        val cw = font.cellWidthPx
        val lh = font.lineHeightPx
        val y = r * lh
        var x = cursor.col * cw
        if (frame.composing.isNotEmpty()) {
            val paint = font.paint(bold = false, italic = false)
            val width = paint.measureText(frame.composing)
            fill.color = palette.selection
            canvas.drawRect(x, y, x + width, y + lh, fill)
            paint.color = palette.foreground
            canvas.drawText(frame.composing, x, y + font.baselinePx, paint)
            fill.color = palette.foreground
            canvas.drawRect(x, y + font.baselinePx + 2f, x + width, y + font.baselinePx + 2f + max(1f, lh / 16f), fill)
            x += width
        }
        if (!cursor.visible) return
        val stroke = max(1f, cw / 6f)
        fill.color = palette.cursor
        if (!frame.focused) {
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = stroke
            canvas.drawRect(x + stroke / 2f, y + stroke / 2f, x + cw - stroke / 2f, y + lh - stroke / 2f, fill)
            fill.style = Paint.Style.FILL
            return
        }
        when (cursor.shape) {
            CursorShape.Block -> {
                canvas.drawRect(x, y, x + cw, y + lh, fill)
                if (frame.composing.isEmpty()) redrawUnderCursor(canvas, snapshot, cursor.row, cursor.col, x, y)
            }
            CursorShape.Underline -> canvas.drawRect(x, y + lh - stroke, x + cw, y + lh, fill)
            CursorShape.Bar -> canvas.drawRect(x, y, x + stroke, y + lh, fill)
        }
    }

    private fun redrawUnderCursor(canvas: Canvas, snapshot: ScreenSnapshot, row: Int, col: Int, x: Float, y: Float) {
        val line = snapshot.lines[row]
        val cp = line.codepoint(col)
        if (cp == 0) return
        val style = line.style(col)
        if (isBoxGlyph(cp)) {
            shapes.color = palette.background
            drawBoxGlyph(canvas, cp, x, y, font.cellWidthPx, font.lineHeightPx, shapes)
            return
        }
        val paint = font.paint(style.bold, style.italic)
        paint.color = palette.background
        canvas.drawText(line.grapheme(col), x, y + font.baselinePx, paint)
    }

    private fun layout(row: Row): Array<Run> = layouts.getOrPut(row) { buildRuns(row) }

    private fun buildRuns(row: Row): Array<Run> {
        val runs = ArrayList<Run>()
        val n = row.columns
        var col = 0
        while (col < n) {
            val cp = row.codepoint(col)
            val width = row.width(col)
            if (width == 0) {
                col++
                continue
            }
            val style = row.style(col)
            val link = row.link(col) != null
            when {
                cp != 0 && isBoxGlyph(cp) -> {
                    runs += Run(col, 1, "", style, Kind.Box, cp, link)
                    col++
                }
                isRunCell(row, col) -> {
                    val start = col
                    builder.setLength(0)
                    while (col < n && isRunCell(row, col) && row.style(col) == style && (row.link(col) != null) == link) {
                        val c = row.codepoint(col)
                        if (c == 0) builder.append(' ') else builder.appendCodePoint(c)
                        col++
                    }
                    runs += Run(start, col - start, builder.toString(), style, Kind.Text, 0, link)
                }
                else -> {
                    runs += Run(col, width, row.grapheme(col), style, Kind.Glyph, cp, link)
                    col += width
                }
            }
        }
        return runs.toTypedArray()
    }

    /** Latin cells advance exactly one cell in the monospace font, so they draw as one string. */
    private fun isRunCell(row: Row, col: Int): Boolean {
        val cp = row.codepoint(col)
        if (cp == 0) return row.width(col) == 1
        return cp in 0x20..0x24F && row.width(col) == 1 && row.grapheme(col).length == 1
    }

    private companion object {
        const val MAX_CACHED_ROWS = 1024
    }
}
