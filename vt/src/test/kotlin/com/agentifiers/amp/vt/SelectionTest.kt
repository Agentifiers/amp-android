package com.agentifiers.amp.vt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionTest {
    private fun screen(cols: Int, rows: Int, text: String) = VtEngine(cols, rows).apply { feed(text.toByteArray()) }.snapshot()

    @Test
    fun wrappedRowsJoinWithoutANewline() {
        val s = screen(10, 3, "abcdefghijKLM\r\nnext   ")
        assertEquals(listOf(true, false), listOf(s.lines[0].wrapped, s.lines[1].wrapped))
        assertEquals("abcdefghijKLM\nnext", s.selectionText(GridPoint(0, 0), GridPoint(2, 9)))
        assertEquals("defghijKL", s.selectionText(GridPoint(1, 1), GridPoint(0, 3)))
    }

    @Test
    fun selectionRunsFromScrollbackIntoTheScreen() {
        val s = screen(10, 2, "one\r\ntwo\r\nthree")
        assertEquals(1, s.scrollback)
        assertEquals("one\ntwo\nthree", s.selectionText(GridPoint(0, 0), GridPoint(2, 9)))
        assertEquals("ne\ntw", s.selectionText(GridPoint(0, 1), GridPoint(1, 1)))
    }

    @Test
    fun wideGlyphsAreCopiedOnce() {
        val s = screen(10, 1, "中文 ok")
        assertEquals("中文 ok", s.selectionText(GridPoint(0, 1), GridPoint(0, 9)))
    }

    @Test
    fun wordAtStopsAtSpacesAndBracketsAndFollowsWraps() {
        val s = screen(10, 4, "say (hi-there)\r\nxx abcdefghijk")
        assertEquals(GridPoint(0, 5) to GridPoint(1, 2), s.wordAt(GridPoint(0, 7)))
        assertEquals("hi-there", s.wordAt(GridPoint(1, 0))!!.let { (a, b) -> s.selectionText(a, b) })
        assertEquals(GridPoint(2, 3) to GridPoint(3, 3), s.wordAt(GridPoint(3, 1)))
        assertNull(s.wordAt(GridPoint(0, 3)))
        assertNull(s.wordAt(GridPoint(3, 8)))
    }

    @Test
    fun wordAtCoversBothCellsOfWideGlyphs() {
        val s = screen(10, 1, "中文 x")
        assertEquals(GridPoint(0, 0) to GridPoint(0, 3), s.wordAt(GridPoint(0, 1)))
    }

    @Test
    fun linkAtReadsOsc8Targets() {
        val s = screen(40, 1, "\u001b]8;;https://ampcode.com/threads\u001b\\docs\u001b]8;;\u001b\\ plain")
        assertEquals("docs plain", s.lines[0].text)
        assertEquals("https://ampcode.com/threads", s.linkAt(GridPoint(0, 0)))
        assertEquals("https://ampcode.com/threads", s.linkAt(GridPoint(0, 3)))
        assertNull(s.linkAt(GridPoint(0, 6)))
    }

    @Test
    fun linkAtFindsPlainUrlsAcrossWrappedRows() {
        val s = screen(20, 3, "see https://example.com/a(b). ok")
        assertEquals("see https://example.", s.lines[0].text)
        assertEquals("https://example.com/a(b)", s.linkAt(GridPoint(1, 2)))
        assertEquals("https://example.com/a(b)", s.linkAt(GridPoint(0, 4)))
        assertNull(s.linkAt(GridPoint(1, 8)))
        assertNull(s.linkAt(GridPoint(0, 1)))
    }
}
