package com.agentifiers.amp.vt

import java.io.ByteArrayOutputStream
import java.util.Base64

internal class KotlinVt(cols: Int, rows: Int, scrollbackRows: Int) : VtEngine, Performer {
    init {
        require(cols >= 1 && rows >= 1) { "terminal size ${cols}x$rows" }
    }

    override var cols: Int = cols
        private set
    override var rows: Int = rows
        private set
    override var title: String = ""
        private set
    override var modes: VtModes = VtModes()
        private set

    private val parser = Parser(this)
    private val history = History(scrollbackRows)
    private val primary = Screen(cols, rows)
    private val alternate = Screen(cols, rows)
    private var screen = primary

    private var col = 0
    private var row = 0
    private var pendingWrap = false
    private var style = 0L
    private var link: String? = null
    private var charsets = CharsetState()
    private var originMode = false
    private var autowrap = true
    private var insertMode = false
    private var newlineMode = false
    private var top = 0
    private var bottom = rows - 1
    private var tabStops = defaultTabStops(cols)
    private var cursorShape = CursorShape.Block
    private var lastPrinted = 0

    private val replies = ByteArrayOutputStream()
    private val events = ArrayList<VtEvent>()
    private var revision = 0L
    private var lastSnapshot: ScreenSnapshot? = null

    private val blankStyle: Long get() = style and BG_BITS

    override fun feed(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        parser.feed(bytes, offset, length)
        revision++
    }

    override fun snapshot(): ScreenSnapshot {
        lastSnapshot?.let { if (it.revision == revision) return it }
        val lines = Array(rows) { screen.lines[it].freeze() }.asList()
        val cursor = Cursor(col, row, modes.cursorVisible, cursorShape)
        return ScreenSnapshot(cols, rows, lines, history.view(), cursor, modes, title, revision).also { lastSnapshot = it }
    }

    override fun takeReplies(): ByteArray = replies.toByteArray().also { replies.reset() }

    override fun takeEvents(): List<VtEvent> = events.toList().also { events.clear() }

    override fun markReconnected() {
        val text = "[Reconnected]"
        val line = Line(text.length)
        text.forEachIndexed { i, c -> line.put(i, c.code, 1, Style.DIM, null) }
        history.push(line.freeze())
        revision++
    }

    override fun notice(text: String) {
        if (col != 0 || pendingWrap) newLine()
        val saved = style
        val savedLink = link
        style = Style.DIM
        link = null
        text.codePoints().forEach { if (it >= 0x20) print(it) }
        style = saved
        link = savedLink
        newLine()
        revision++
    }

    override fun encodeKey(key: VtKey, mods: VtMods): ByteArray = keyBytes(key, mods, modes.cursorKeysApplication)

    override fun encodePaste(text: String): ByteArray = pasteBytes(text, modes.bracketedPaste)

    override fun encodeMouse(event: VtMouse): ByteArray? = mouseBytes(event, modes.mouseTracking, modes.sgrMouse)

    override fun encodeFocus(focused: Boolean): ByteArray? = focusBytes(focused, modes.focusEvents)

    override fun resize(cols: Int, rows: Int) {
        require(cols >= 1 && rows >= 1) { "terminal size ${cols}x$rows" }
        if (cols == this.cols && rows == this.rows) return
        val cursorRow = row
        val primaryCursor = if (screen === primary) cursorRow else primary.saved?.row ?: cursorRow
        val alternateCursor = if (screen === alternate) cursorRow else 0
        val newPrimaryRow = resizeScreen(primary, cols, rows, primaryCursor, history)
        val newAlternateRow = resizeScreen(alternate, cols, rows, alternateCursor, null)
        this.cols = cols
        this.rows = rows
        row = if (screen === primary) newPrimaryRow else newAlternateRow
        col = col.coerceAtMost(cols - 1)
        pendingWrap = false
        primary.saved = primary.saved?.fitted(if (screen === primary) null else newPrimaryRow, cols, rows)
        alternate.saved = alternate.saved?.fitted(null, cols, rows)
        top = 0
        bottom = rows - 1
        tabStops = defaultTabStops(cols)
        revision++
    }

    /** Returns the cursor row after the resize. Growing pulls rows back from [history]; shrinking pushes into it. */
    private fun resizeScreen(target: Screen, cols: Int, rows: Int, cursorRow: Int, history: History?): Int {
        val old = target.lines
        for (line in old) line.resize(cols)
        if (rows == old.size) return cursorRow
        val lines = ArrayList<Line>(rows)
        var cursor = cursorRow
        if (rows > old.size) {
            val pulled = if (history == null) 0 else minOf(rows - old.size, history.size)
            repeat(pulled) { lines.add(0, Line.of(history!!.removeLast(), cols)) }
            lines.addAll(old)
            while (lines.size < rows) lines.add(Line(cols))
            cursor += pulled
        } else {
            val pushed = (cursorRow - (rows - 1)).coerceAtLeast(0)
            for (i in 0 until pushed) history?.push(old[i].toHistory())
            for (i in pushed until pushed + rows) lines.add(old[i])
            cursor -= pushed
        }
        target.lines = lines.toTypedArray()
        return cursor.coerceIn(0, rows - 1)
    }

    override fun print(cp: Int) {
        val glyph = if (charsets.graphics && cp in 0x5f..0x7e) DEC_GRAPHICS[cp - 0x5f].code else cp
        val width = charWidth(glyph)
        if (width == 0) {
            combine(glyph)
            return
        }
        if (pendingWrap) wrap()
        if (width == 2 && col == cols - 1) {
            if (!autowrap || cols < 2) return
            screen.lines[row].erase(col, cols, blankStyle)
            wrap()
        }
        val line = screen.lines[row]
        if (insertMode) line.insertBlanks(col, width, blankStyle)
        line.put(col, glyph, width, style, link)
        lastPrinted = glyph
        if (col + width >= cols) {
            col = cols - 1
            pendingWrap = autowrap
        } else {
            col += width
        }
    }

    private fun wrap() {
        screen.lines[row].wrapped = true
        index()
        col = 0
    }

    private fun combine(mark: Int) {
        val target = if (pendingWrap) col else col - 1
        if (target >= 0) screen.lines[row].combine(target, mark)
    }

    override fun execute(c: Int) {
        when (c) {
            0x07 -> events.add(VtEvent.Bell)
            0x08 -> {
                pendingWrap = false
                if (col > 0) col--
            }
            0x09 -> tabForward(1)
            0x0a, 0x0b, 0x0c -> {
                index()
                if (newlineMode) col = 0
            }
            0x0d -> carriageReturn()
            0x0e -> charsets = charsets.copy(shiftOut = true)
            0x0f -> charsets = charsets.copy(shiftOut = false)
        }
    }

    private fun carriageReturn() {
        col = 0
        pendingWrap = false
    }

    private fun newLine() {
        carriageReturn()
        index()
    }

    private fun index() {
        pendingWrap = false
        if (row == bottom) scrollUp(1) else if (row < rows - 1) row++
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (row == top) scrollDown(1) else if (row > 0) row--
    }

    private fun scrollUp(n: Int) {
        val keep = screen === primary && top == 0 && bottom == rows - 1
        screen.scrollUp(top, bottom, n, blankStyle, if (keep) history else null)
    }

    private fun scrollDown(n: Int) = screen.scrollDown(top, bottom, n, blankStyle)

    override fun escDispatch(intermediate: Int, final: Int) {
        when (intermediate) {
            0 -> when (final.toChar()) {
                'D' -> index()
                'E' -> newLine()
                'M' -> reverseIndex()
                'H' -> tabStops[col] = true
                '7' -> saveCursor()
                '8' -> restoreCursor()
                'c' -> fullReset()
            }
            '#'.code -> if (final == '8'.code) screenAlignment()
            '('.code -> charsets = charsets.copy(g0Graphics = final == '0'.code)
            ')'.code -> charsets = charsets.copy(g1Graphics = final == '0'.code)
        }
    }

    override fun csiDispatch(params: CsiParams, final: Int) {
        if (params.intermediate != 0) {
            if (params.intermediate == ' '.code && final == 'q'.code && params.prefix == 0) setCursorShape(params.at(0, 0))
            return
        }
        when (params.prefix) {
            0 -> csiPlain(params, final)
            '?'.code -> when (final.toChar()) {
                'h' -> for (i in 0 until params.count) setPrivateMode(params.raw(i), true)
                'l' -> for (i in 0 until params.count) setPrivateMode(params.raw(i), false)
                'n' -> if (params.raw(0) == 6) reply("\u001b[?${cursorReportRow()};${col + 1}R")
            }
            '>'.code -> if (final == 'c'.code && params.at(0, 0) == 0) reply(DA2)
        }
    }

    private fun csiPlain(p: CsiParams, final: Int) {
        when (final.toChar()) {
            'A' -> cursorUp(p.at(0, 1))
            'B' -> cursorDown(p.at(0, 1))
            'C', 'a' -> cursorForward(p.at(0, 1))
            'D' -> cursorBack(p.at(0, 1))
            'E' -> {
                cursorDown(p.at(0, 1))
                col = 0
            }
            'F' -> {
                cursorUp(p.at(0, 1))
                col = 0
            }
            'G', '`' -> moveTo(row, p.at(0, 1) - 1)
            'H', 'f' -> cursorPosition(p.at(0, 1) - 1, p.at(1, 1) - 1)
            'd' -> cursorPosition(p.at(0, 1) - 1, col)
            'e' -> cursorDown(p.at(0, 1))
            'I' -> tabForward(p.at(0, 1))
            'Z' -> tabBack(p.at(0, 1))
            'J' -> eraseDisplay(p.at(0, 0))
            'K' -> eraseLine(p.at(0, 0))
            'X' -> {
                pendingWrap = false
                screen.lines[row].erase(col, minOf(cols, col + p.at(0, 1)), blankStyle)
            }
            '@' -> {
                pendingWrap = false
                screen.lines[row].insertBlanks(col, p.at(0, 1), blankStyle)
            }
            'P' -> {
                pendingWrap = false
                screen.lines[row].deleteChars(col, p.at(0, 1), blankStyle)
            }
            'L' -> insertLines(p.at(0, 1))
            'M' -> deleteLines(p.at(0, 1))
            'S' -> scrollUp(p.at(0, 1))
            'T' -> if (p.count <= 1) scrollDown(p.at(0, 1))
            'b' -> if (lastPrinted != 0) repeat(minOf(p.at(0, 1), cols * rows)) { print(lastPrinted) }
            'g' -> when (p.at(0, 0)) {
                0 -> tabStops[col] = false
                3 -> tabStops.fill(false)
            }
            'r' -> setScrollRegion(p.at(0, 1) - 1, p.at(1, rows) - 1)
            'm' -> selectGraphicRendition(p)
            'n' -> when (p.at(0, 0)) {
                5 -> reply("\u001b[0n")
                6 -> reply("\u001b[${cursorReportRow()};${col + 1}R")
            }
            'c' -> if (p.at(0, 0) == 0) reply(DA1)
            'h' -> for (i in 0 until p.count) setAnsiMode(p.raw(i), true)
            'l' -> for (i in 0 until p.count) setAnsiMode(p.raw(i), false)
            's' -> saveCursor()
            'u' -> restoreCursor()
        }
    }

    private fun cursorReportRow(): Int = row - (if (originMode) top else 0) + 1

    private fun reply(text: String) {
        replies.write(text.toByteArray(Charsets.US_ASCII))
    }

    private fun moveTo(r: Int, c: Int) {
        row = r.coerceIn(0, rows - 1)
        col = c.coerceIn(0, cols - 1)
        pendingWrap = false
    }

    private fun cursorPosition(r: Int, c: Int) {
        if (originMode) moveTo((top + r).coerceAtMost(bottom), c) else moveTo(r, c)
    }

    private fun cursorUp(n: Int) = moveTo(if (row >= top) maxOf(top, row - n) else row - n, col)

    private fun cursorDown(n: Int) = moveTo(if (row <= bottom) minOf(bottom, row + n) else row + n, col)

    private fun cursorForward(n: Int) = moveTo(row, col + n)

    private fun cursorBack(n: Int) = moveTo(row, col - n)

    private fun tabForward(n: Int) {
        pendingWrap = false
        repeat(n) {
            var c = col + 1
            while (c < cols - 1 && !tabStops[c]) c++
            col = minOf(c, cols - 1)
        }
    }

    private fun tabBack(n: Int) {
        pendingWrap = false
        repeat(n) {
            var c = col - 1
            while (c > 0 && !tabStops[c]) c--
            col = maxOf(c, 0)
        }
    }

    private fun eraseDisplay(mode: Int) {
        pendingWrap = false
        val lines = screen.lines
        when (mode) {
            0 -> {
                lines[row].erase(col, cols, blankStyle)
                lines[row].wrapped = false
                for (r in row + 1 until rows) lines[r].clear(blankStyle)
            }
            1 -> {
                for (r in 0 until row) lines[r].clear(blankStyle)
                lines[row].erase(0, col + 1, blankStyle)
            }
            2 -> for (line in lines) line.clear(blankStyle)
            3 -> history.clear()
        }
    }

    private fun eraseLine(mode: Int) {
        pendingWrap = false
        val line = screen.lines[row]
        when (mode) {
            0 -> {
                line.erase(col, cols, blankStyle)
                line.wrapped = false
            }
            1 -> line.erase(0, col + 1, blankStyle)
            2 -> line.clear(blankStyle)
        }
    }

    private fun insertLines(n: Int) {
        if (row < top || row > bottom) return
        screen.scrollDown(row, bottom, n, blankStyle)
        carriageReturn()
    }

    private fun deleteLines(n: Int) {
        if (row < top || row > bottom) return
        screen.scrollUp(row, bottom, n, blankStyle, null)
        carriageReturn()
    }

    private fun setScrollRegion(t: Int, b: Int) {
        val newBottom = b.coerceAtMost(rows - 1)
        if (t >= newBottom) return
        top = t
        bottom = newBottom
        cursorPosition(0, 0)
    }

    private fun selectGraphicRendition(p: CsiParams) {
        if (p.count == 0) {
            style = 0L
            return
        }
        var s = Style(style)
        var i = 0
        while (i < p.count) {
            val code = p.raw(i)
            var next = i + 1
            when (code) {
                CsiParams.MISSING, 0 -> s = Style.Default
                1 -> s = s.with(Style.BOLD, true)
                2 -> s = s.with(Style.DIM, true)
                3 -> s = s.with(Style.ITALIC, true)
                4 -> s = s.with(Style.UNDERLINE, !(p.isSub(next) && p.raw(next) == 0))
                5, 6 -> s = s.with(Style.BLINK, true)
                7 -> s = s.with(Style.INVERSE, true)
                8 -> s = s.with(Style.INVISIBLE, true)
                9 -> s = s.with(Style.STRIKETHROUGH, true)
                21 -> s = s.with(Style.UNDERLINE, true)
                22 -> s = s.with(Style.BOLD, false).with(Style.DIM, false)
                23 -> s = s.with(Style.ITALIC, false)
                24 -> s = s.with(Style.UNDERLINE, false)
                25 -> s = s.with(Style.BLINK, false)
                27 -> s = s.with(Style.INVERSE, false)
                28 -> s = s.with(Style.INVISIBLE, false)
                29 -> s = s.with(Style.STRIKETHROUGH, false)
                in 30..37 -> s = s.with(fg = VtColor.palette(code - 30))
                39 -> s = s.with(fg = VtColor.Default)
                in 40..47 -> s = s.with(bg = VtColor.palette(code - 40))
                49 -> s = s.with(bg = VtColor.Default)
                in 90..97 -> s = s.with(fg = VtColor.palette(code - 90 + 8))
                in 100..107 -> s = s.with(bg = VtColor.palette(code - 100 + 8))
                38, 48, 58 -> {
                    val (color, consumed) = extendedColor(p, i)
                    next = i + consumed
                    if (color != null && code == 38) s = s.with(fg = color)
                    if (color != null && code == 48) s = s.with(bg = color)
                }
            }
            while (next < p.count && p.isSub(next)) next++
            i = next
        }
        style = s.packed
    }

    /** Reads `38;5;n`, `38;2;r;g;b` and their colon forms starting at [i]; returns the color and the params used. */
    private fun extendedColor(p: CsiParams, i: Int): Pair<VtColor?, Int> {
        if (p.isSub(i + 1)) {
            var subs = 0
            while (p.isSub(i + 1 + subs)) subs++
            return when (p.raw(i + 1)) {
                5 -> (if (subs >= 2) VtColor.palette(p.raw(i + 2).coerceAtLeast(0)) else null) to 1 + subs
                2 -> {
                    val first = if (subs >= 5) i + 3 else i + 2
                    (if (subs >= 4) rgb(p, first) else null) to 1 + subs
                }
                else -> null to 1 + subs
            }
        }
        return when (p.raw(i + 1)) {
            5 -> (if (i + 2 < p.count) VtColor.palette(p.raw(i + 2).coerceAtLeast(0)) else null) to 3
            2 -> (if (i + 4 < p.count) rgb(p, i + 2) else null) to 5
            else -> null to 2
        }
    }

    private fun rgb(p: CsiParams, first: Int): VtColor =
        VtColor.rgb(p.raw(first).coerceAtLeast(0), p.raw(first + 1).coerceAtLeast(0), p.raw(first + 2).coerceAtLeast(0))

    private fun setCursorShape(n: Int) {
        cursorShape = when (n) {
            0, 1, 2 -> CursorShape.Block
            3, 4 -> CursorShape.Underline
            5, 6 -> CursorShape.Bar
            else -> return
        }
    }

    private fun setAnsiMode(mode: Int, on: Boolean) {
        when (mode) {
            4 -> insertMode = on
            20 -> newlineMode = on
        }
    }

    private fun setPrivateMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> modes = modes.copy(cursorKeysApplication = on)
            6 -> {
                originMode = on
                cursorPosition(0, 0)
            }
            7 -> {
                autowrap = on
                if (!on) pendingWrap = false
            }
            25 -> modes = modes.copy(cursorVisible = on)
            47 -> switchScreen(on, clearAlternate = false)
            1047 -> {
                if (!on && screen === alternate) clearScreen(alternate)
                switchScreen(on, clearAlternate = false)
            }
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> if (on) {
                if (screen === primary) saveCursor()
                switchScreen(true, clearAlternate = true)
            } else if (screen === alternate) {
                switchScreen(false, clearAlternate = false)
                restoreCursor()
            }
            1000 -> modes = modes.copy(mouseTracking = if (on) MouseTracking.Click else MouseTracking.Off)
            1002 -> modes = modes.copy(mouseTracking = if (on) MouseTracking.Drag else MouseTracking.Off)
            1003 -> modes = modes.copy(mouseTracking = if (on) MouseTracking.Any else MouseTracking.Off)
            1004 -> modes = modes.copy(focusEvents = on)
            1006 -> modes = modes.copy(sgrMouse = on)
            2004 -> modes = modes.copy(bracketedPaste = on)
        }
    }

    private fun switchScreen(alternateOn: Boolean, clearAlternate: Boolean) {
        val target = if (alternateOn) alternate else primary
        if (target === screen) return
        if (alternateOn && clearAlternate) clearScreen(alternate)
        screen = target
        modes = modes.copy(altScreen = alternateOn)
        pendingWrap = false
    }

    private fun clearScreen(target: Screen) {
        for (line in target.lines) line.clear(blankStyle)
    }

    private fun saveCursor() {
        screen.saved = SavedCursor(col, row, pendingWrap, style, link, originMode, charsets)
    }

    private fun restoreCursor() {
        val saved = screen.saved
        if (saved == null) {
            originMode = false
            moveTo(0, 0)
            style = 0L
            charsets = CharsetState()
            return
        }
        originMode = saved.originMode
        moveTo(saved.row, saved.col)
        pendingWrap = saved.pendingWrap && autowrap
        style = saved.style
        link = saved.link
        charsets = saved.charsets
    }

    private fun screenAlignment() {
        top = 0
        bottom = rows - 1
        originMode = false
        style = 0L
        for (line in screen.lines) {
            line.clear(0L)
            for (c in 0 until cols) line.put(c, 'E'.code, 1, 0L, null)
        }
        moveTo(0, 0)
    }

    private fun fullReset() {
        primary.saved = null
        alternate.saved = null
        screen = primary
        style = 0L
        link = null
        charsets = CharsetState()
        originMode = false
        autowrap = true
        insertMode = false
        newlineMode = false
        top = 0
        bottom = rows - 1
        tabStops = defaultTabStops(cols)
        cursorShape = CursorShape.Block
        lastPrinted = 0
        modes = VtModes()
        clearScreen(primary)
        clearScreen(alternate)
        moveTo(0, 0)
    }

    override fun oscDispatch(data: CharSequence) {
        val semi = data.indexOf(';')
        if (semi < 0) return
        val command = data.substring(0, semi)
        val rest = data.substring(semi + 1)
        when (command) {
            "0", "2" -> {
                title = rest
                events.add(VtEvent.Title(rest))
            }
            "8" -> {
                val uriStart = rest.indexOf(';')
                if (uriStart >= 0) link = rest.substring(uriStart + 1).ifEmpty { null }
            }
            "52" -> {
                val payload = rest.substringAfter(';', missingDelimiterValue = "")
                if (payload.isEmpty() || payload == "?") return
                val decoded = try {
                    Base64.getDecoder().decode(payload)
                } catch (_: IllegalArgumentException) {
                    return
                }
                events.add(VtEvent.ClipboardWrite(String(decoded, Charsets.UTF_8)))
            }
        }
    }

    /** The saved cursor clamped to a resized screen, moved to [row] when the resize relocated it. */
    private fun SavedCursor.fitted(row: Int?, cols: Int, rows: Int): SavedCursor =
        SavedCursor(col.coerceAtMost(cols - 1), (row ?: this.row).coerceAtMost(rows - 1), false, style, link, originMode, charsets)

    private companion object {
        const val BG_BITS = ((1L shl 26) - 1) shl 26
        const val DA1 = "\u001b[?62;22c"
        const val DA2 = "\u001b[>1;10;0c"

        /** DEC special graphics for 0x5f..0x7e, as xterm draws them in Unicode. */
        const val DEC_GRAPHICS = " \u25c6\u2592\u2409\u240c\u240d\u240a\u00b0\u00b1\u2424\u240b\u2518\u2510\u250c\u2514\u253c\u23ba\u23bb\u2500\u23bc\u23bd\u251c\u2524\u2534\u252c\u2502\u2264\u2265\u03c0\u2260\u00a3\u00b7"

        fun defaultTabStops(cols: Int) = BooleanArray(cols) { it > 0 && it % 8 == 0 }
    }
}
