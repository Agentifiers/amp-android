package com.agentifiers.amp.vt

import com.agentifiers.amp.vt.Row.Companion.WIDE_TAIL

/**
 * One mutable screen row. [freeze] caches the [Row] it builds until the next write, so a snapshot shares every row
 * that did not change since the previous one.
 */
internal class Line(cols: Int) {
    private var cps = IntArray(cols)
    private var styles = LongArray(cols)
    private var extras: HashMap<Int, String>? = null
    private var links: HashMap<Int, String>? = null
    private var frozen: Row? = null

    var wrapped = false
        set(value) {
            if (field != value) {
                edit()
                field = value
            }
        }

    fun freeze(): Row = frozen ?: Row(cps, styles, extras, links, wrapped).also { frozen = it }

    private fun edit() {
        frozen = null
    }

    /** Writes a glyph of width [width] at [col], blanking the other half of any wide glyph it overwrites. */
    fun put(col: Int, cp: Int, width: Int, style: Long, link: String?) {
        edit()
        if (cps[col] == WIDE_TAIL && col > 0) blankCell(col - 1, styles[col - 1])
        if (col + width < cps.size && cps[col + width] == WIDE_TAIL) blankCell(col + width, styles[col + width])
        setCell(col, cp, style, link)
        if (width == 2) setCell(col + 1, WIDE_TAIL, style, link)
    }

    private fun setCell(col: Int, cp: Int, style: Long, link: String?) {
        cps[col] = cp
        styles[col] = style
        extras?.remove(col)
        if (link != null) {
            (links ?: HashMap<Int, String>().also { links = it })[col] = link
        } else {
            links?.remove(col)
        }
    }

    private fun blankCell(col: Int, style: Long) {
        cps[col] = 0
        styles[col] = style
        extras?.remove(col)
        links?.remove(col)
    }

    /** Appends a combining mark to the glyph at [col]; a blank cell takes no mark. */
    fun combine(col: Int, mark: Int) {
        val head = if (cps[col] == WIDE_TAIL && col > 0) col - 1 else col
        val base = cps[head]
        if (base == 0 || base == WIDE_TAIL) return
        edit()
        val map = extras ?: HashMap<Int, String>().also { extras = it }
        val current = map[head] ?: String(Character.toChars(base))
        if (current.length < MAX_GRAPHEME) map[head] = current + String(Character.toChars(mark))
    }

    /** Blanks [from, to) with [style], widening the range over any wide glyph it splits. */
    fun erase(from: Int, to: Int, style: Long) {
        if (from >= to) return
        edit()
        val start = if (from > 0 && cps[from] == WIDE_TAIL) from - 1 else from
        val end = if (to < cps.size && cps[to] == WIDE_TAIL) to + 1 else to
        for (c in start until end) blankCell(c, style)
    }

    fun clear(style: Long) {
        edit()
        cps.fill(0)
        styles.fill(style)
        extras = null
        links = null
        wrapped = false
    }

    /** ICH: shifts cells from [col] right by [n], dropping those pushed past the end. */
    fun insertBlanks(col: Int, n: Int, style: Long) {
        edit()
        if (cps[col] == WIDE_TAIL) erase(col - 1, col + 1, style)
        val count = minOf(n, cps.size - col)
        val moved = cps.size - col - count
        System.arraycopy(cps, col, cps, col + count, moved)
        System.arraycopy(styles, col, styles, col + count, moved)
        extras = shiftKeys(extras, col, count)
        links = shiftKeys(links, col, count)
        for (c in col until col + count) blankCell(c, style)
        val last = cps.size - 1
        if (charWidth(cps[last].coerceAtLeast(0)) == 2) blankCell(last, styles[last])
    }

    /** DCH: removes [n] cells at [col], shifting the rest left and blanking the end. */
    fun deleteChars(col: Int, n: Int, style: Long) {
        edit()
        if (cps[col] == WIDE_TAIL) erase(col - 1, col + 1, style)
        val count = minOf(n, cps.size - col)
        val moved = cps.size - col - count
        if (moved > 0 && cps[col + count] == WIDE_TAIL) blankCell(col + count, styles[col + count])
        System.arraycopy(cps, col + count, cps, col, moved)
        System.arraycopy(styles, col + count, styles, col, moved)
        extras = shiftKeys(extras, col + count, -count, dropFrom = col)
        links = shiftKeys(links, col + count, -count, dropFrom = col)
        for (c in cps.size - count until cps.size) blankCell(c, style)
    }

    private fun shiftKeys(map: HashMap<Int, String>?, from: Int, by: Int, dropFrom: Int = from): HashMap<Int, String>? {
        if (map.isNullOrEmpty()) return map
        val out = HashMap<Int, String>()
        for ((k, v) in map) {
            when {
                k < dropFrom -> out[k] = v
                k >= from && k + by in 0 until cps.size -> out[k + by] = v
            }
        }
        return out
    }

    fun resize(cols: Int) {
        if (cols == cps.size) return
        edit()
        val old = cps.size
        cps = cps.copyOf(cols)
        styles = styles.copyOf(cols)
        if (cols < old) {
            extras?.keys?.removeAll { it >= cols }
            links?.keys?.removeAll { it >= cols }
            val last = cols - 1
            if (charWidth(cps[last].coerceAtLeast(0)) == 2) blankCell(last, styles[last])
        }
    }

    /** The row for scrollback: cells after the last one that is not blank in default style are dropped. */
    fun toHistory(): Row {
        var end = cps.size
        while (end > 0 && cps[end - 1] == 0 && styles[end - 1] == 0L && links?.containsKey(end - 1) != true) end--
        return if (end == cps.size) freeze() else Row(cps, styles, extras, links, wrapped, end)
    }

    companion object {
        private const val MAX_GRAPHEME = 32

        fun of(row: Row, cols: Int): Line = Line(cols).also { line ->
            for (c in 0 until minOf(cols, row.columns)) {
                val w = row.width(c)
                if (w == 0) continue
                if (w == 2 && c + 1 >= cols) break
                val cp = row.codepoint(c)
                line.setCell(c, cp, row.style(c).packed, row.link(c))
                if (w == 2) line.setCell(c + 1, WIDE_TAIL, row.style(c + 1).packed, row.link(c + 1))
                val g = row.grapheme(c)
                if (g.length > Character.charCount(cp)) (line.extras ?: HashMap<Int, String>().also { line.extras = it })[c] = g
            }
            line.wrapped = row.wrapped
        }
    }
}

internal class SavedCursor(
    val col: Int,
    val row: Int,
    val pendingWrap: Boolean,
    val style: Long,
    val link: String?,
    val originMode: Boolean,
    val charsets: CharsetState,
)

/** G0 and G1 designations and which one is shifted in; `true` means DEC special graphics. */
internal data class CharsetState(val g0Graphics: Boolean = false, val g1Graphics: Boolean = false, val shiftOut: Boolean = false) {
    val graphics: Boolean get() = if (shiftOut) g1Graphics else g0Graphics
}

internal class Screen(cols: Int, rows: Int) {
    var lines: Array<Line> = Array(rows) { Line(cols) }
    var saved: SavedCursor? = null

    /** Scrolls [top, bottom] up by [n]; each line leaving the top goes to [history] when it is non-null. */
    fun scrollUp(top: Int, bottom: Int, n: Int, style: Long, history: History?) {
        val count = minOf(n, bottom - top + 1)
        repeat(count) {
            val gone = lines[top]
            history?.push(gone.toHistory())
            System.arraycopy(lines, top + 1, lines, top, bottom - top)
            gone.clear(style)
            lines[bottom] = gone
        }
    }

    fun scrollDown(top: Int, bottom: Int, n: Int, style: Long) {
        val count = minOf(n, bottom - top + 1)
        repeat(count) {
            val gone = lines[bottom]
            System.arraycopy(lines, top, lines, top + 1, bottom - top)
            gone.clear(style)
            lines[top] = gone
        }
    }
}

/**
 * The scrollback ring. Rows live in fixed chunks that are only ever appended to, so a [view] taken for a snapshot
 * stays valid while the ring keeps growing and trimming its head.
 */
internal class History(private val capacity: Int) {
    private var chunks = ArrayList<Array<Row?>>()
    private var start = 0
    var size = 0
        private set
    private var view: List<Row>? = null

    fun push(row: Row) {
        if (capacity <= 0) return
        val end = start + size
        if (end / CHUNK == chunks.size) chunks.add(arrayOfNulls(CHUNK))
        chunks[end / CHUNK][end % CHUNK] = row
        size++
        if (size > capacity) {
            start++
            size--
            if (start == CHUNK) {
                chunks.removeAt(0)
                start = 0
            }
        }
        view = null
    }

    fun removeLast(): Row {
        val index = start + size - 1
        val chunk = index / CHUNK
        val row = chunks[chunk][index % CHUNK]!!
        chunks[chunk] = chunks[chunk].copyOf()
        chunks[chunk][index % CHUNK] = null
        size--
        if ((start + size) % CHUNK == 0 && chunks.size > (start + size) / CHUNK) chunks.removeAt(chunks.size - 1)
        view = null
        return row
    }

    fun clear() {
        chunks = ArrayList()
        start = 0
        size = 0
        view = null
    }

    fun view(): List<Row> = view ?: HistoryView(chunks.toTypedArray(), start, size).also { view = it }

    private class HistoryView(private val chunks: Array<Array<Row?>>, private val start: Int, override val size: Int) :
        AbstractList<Row>() {
        override fun get(index: Int): Row {
            if (index !in 0 until size) throw IndexOutOfBoundsException("$index of $size")
            val i = start + index
            return chunks[i / CHUNK][i % CHUNK]!!
        }
    }

    private companion object {
        const val CHUNK = 256
    }
}
