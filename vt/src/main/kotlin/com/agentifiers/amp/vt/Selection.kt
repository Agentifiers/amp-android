package com.agentifiers.amp.vt

internal fun selectText(snapshot: ScreenSnapshot, from: GridPoint, to: GridPoint): String {
    val (start, end) = if (from <= to) from to to else to to from
    val out = StringBuilder()
    for (line in start.line.coerceAtLeast(0)..minOf(end.line, snapshot.totalLines - 1)) {
        val row = snapshot.line(line)
        val lastCol = lastColumn(snapshot, row)
        val first = if (line == start.line) headOf(row, start.col.coerceIn(0, lastCol)) else 0
        val last = if (line == end.line) end.col.coerceIn(0, lastCol) else lastCol
        val joined = row.wrapped && line != end.line
        val segment = StringBuilder()
        var contentEnd = 0
        for (col in first..last) {
            if (row.width(col) == 0) continue
            val g = row.grapheme(col)
            segment.append(g.ifEmpty { " " })
            if (g.isNotEmpty() && (joined || g != " ")) contentEnd = segment.length
        }
        segment.setLength(contentEnd)
        out.append(segment)
        if (line != end.line && !row.wrapped) out.append('\n')
    }
    return out.toString()
}

internal fun wordRange(snapshot: ScreenSnapshot, point: GridPoint): Pair<GridPoint, GridPoint>? {
    if (point.line !in 0 until snapshot.totalLines || point.col < 0) return null
    val row = snapshot.line(point.line)
    if (point.col > lastColumn(snapshot, row)) return null
    val head = GridPoint(point.line, headOf(row, point.col))
    if (!isWordCell(snapshot, head)) return null
    var start = head
    while (true) {
        val prev = previousCell(snapshot, start) ?: break
        if (!isWordCell(snapshot, prev)) break
        start = prev
    }
    var end = tailOf(snapshot, head)
    while (true) {
        val next = nextCell(snapshot, end) ?: break
        if (!isWordCell(snapshot, next)) break
        end = tailOf(snapshot, next)
    }
    return start to end
}

internal fun linkUnder(snapshot: ScreenSnapshot, point: GridPoint): String? {
    if (point.line !in 0 until snapshot.totalLines || point.col < 0) return null
    val row = snapshot.line(point.line)
    if (point.col > lastColumn(snapshot, row)) return null
    val col = headOf(row, point.col)
    row.link(col)?.let { return it }
    var first = point.line
    while (first > 0 && point.line - first < MAX_LINK_ROWS && snapshot.line(first - 1).wrapped) first--
    var last = point.line
    while (last < snapshot.totalLines - 1 && last - point.line < MAX_LINK_ROWS && snapshot.line(last).wrapped) last++
    val text = StringBuilder()
    var target = -1
    for (line in first..last) {
        val r = snapshot.line(line)
        for (c in 0..lastColumn(snapshot, r)) {
            if (r.width(c) == 0) continue
            if (line == point.line && c == col) target = text.length
            text.append(r.grapheme(c).ifEmpty { " " })
        }
    }
    for (match in URL.findAll(text)) {
        val url = trimUrl(match.value)
        if (target >= match.range.first && target < match.range.first + url.length) return url
    }
    return null
}

private const val MAX_LINK_ROWS = 32
private val URL = Regex("""https?://[^\s<>"'`]+""")
private const val WORD_DELIMITERS = "\"'`()[]{}<>|,;"

private fun trimUrl(url: String): String {
    var end = url.length
    while (end > 0) {
        val c = url[end - 1]
        val unbalanced = when (c) {
            ')' -> url.count { it == '(' } < url.substring(0, end).count { it == ')' }
            ']' -> url.count { it == '[' } < url.substring(0, end).count { it == ']' }
            else -> false
        }
        if (c in ".,;:!?" || unbalanced) end-- else break
    }
    return url.substring(0, end)
}

private fun lastColumn(snapshot: ScreenSnapshot, row: Row): Int = maxOf(snapshot.cols, row.columns) - 1

private fun headOf(row: Row, col: Int): Int = if (col > 0 && row.width(col) == 0) col - 1 else col

private fun tailOf(snapshot: ScreenSnapshot, head: GridPoint): GridPoint =
    GridPoint(head.line, head.col + snapshot.line(head.line).width(head.col).coerceAtLeast(1) - 1)

private fun isWordCell(snapshot: ScreenSnapshot, p: GridPoint): Boolean {
    val cp = snapshot.line(p.line).codepoint(p.col)
    return cp != 0 && !Character.isWhitespace(cp) && !(cp < 0x80 && WORD_DELIMITERS.indexOf(cp.toChar()) >= 0)
}

private fun previousCell(snapshot: ScreenSnapshot, p: GridPoint): GridPoint? {
    if (p.col > 0) return GridPoint(p.line, headOf(snapshot.line(p.line), p.col - 1))
    if (p.line == 0) return null
    val above = snapshot.line(p.line - 1)
    if (!above.wrapped) return null
    return GridPoint(p.line - 1, headOf(above, lastColumn(snapshot, above)))
}

private fun nextCell(snapshot: ScreenSnapshot, p: GridPoint): GridPoint? {
    val row = snapshot.line(p.line)
    if (p.col < lastColumn(snapshot, row)) return GridPoint(p.line, p.col + 1)
    if (!row.wrapped || p.line + 1 >= snapshot.totalLines) return null
    return GridPoint(p.line + 1, 0)
}
