package com.agentifiers.amp.vt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenModelTest {
    private fun VtEngine.type(text: String) = feed(text.toByteArray())

    private fun ScreenSnapshot.rows() = lines.map { it.text }

    @Test
    fun shrinkingPushesTopRowsToHistoryAndGrowingPullsThemBack() {
        val vt = VtEngine(10, 4)
        vt.type("1\r\n2\r\n3\r\n4")
        vt.resize(10, 2)
        var s = vt.snapshot()
        assertEquals(listOf("3", "4"), s.rows())
        assertEquals(listOf("1", "2"), s.history.map { it.text })
        assertEquals(Cursor(1, 1, true, CursorShape.Block), s.cursor)
        vt.resize(10, 4)
        s = vt.snapshot()
        assertEquals(listOf("1", "2", "3", "4"), s.rows())
        assertEquals(0, s.scrollback)
        assertEquals(Cursor(1, 3, true, CursorShape.Block), s.cursor)
        vt.type("!")
        assertEquals("4!", vt.snapshot().lines[3].text)
    }

    @Test
    fun shrinkingDropsBlankRowsBelowTheCursorFirst() {
        val vt = VtEngine(10, 4)
        vt.type("top")
        vt.resize(10, 2)
        val s = vt.snapshot()
        assertEquals(listOf("top", ""), s.rows())
        assertEquals(0, s.scrollback)
    }

    @Test
    fun narrowingTruncatesRowsAndClampsTheCursor() {
        val vt = VtEngine(10, 2)
        vt.type("abcdefgh")
        vt.resize(5, 2)
        assertEquals(listOf("abcde", ""), vt.snapshot().rows())
        assertEquals(4, vt.snapshot().cursor.col)
        vt.resize(8, 2)
        vt.type("\tX")
        assertEquals("abcde  X", vt.snapshot().lines[0].text)
    }

    @Test
    fun scrollbackRingKeepsTheNewestRows() {
        val vt = VtEngine(10, 2, scrollbackRows = 3)
        vt.type((0..9).joinToString("\r\n"))
        val s = vt.snapshot()
        assertEquals(listOf("5", "6", "7"), s.history.map { it.text })
        assertEquals(listOf("8", "9"), s.rows())
        assertEquals("7\n8", s.selectionText(GridPoint(2, 0), GridPoint(3, 0)))
    }

    @Test
    fun aSnapshotKeepsItsHistoryWhileTheRingMovesOn() {
        val vt = VtEngine(10, 1, scrollbackRows = 300)
        vt.type((0..299).joinToString("\r\n"))
        val before = vt.snapshot()
        vt.type("\r\n" + (300..999).joinToString("\r\n"))
        val after = vt.snapshot()
        assertEquals(listOf("0", "150", "298"), listOf(before.history[0].text, before.history[150].text, before.history[298].text))
        assertEquals(listOf("699", "998"), listOf(after.history[0].text, after.history[299].text))
    }

    @Test
    fun clearingTheScreenLeavesScrollbackAloneAndEd3ClearsIt() {
        val vt = VtEngine(10, 2)
        vt.type("a\r\nb\r\nc")
        vt.type("\u001b[H\u001b[2J")
        var s = vt.snapshot()
        assertEquals(listOf("", ""), s.rows())
        assertEquals(listOf("a"), s.history.map { it.text })
        vt.type("\u001b[3J")
        s = vt.snapshot()
        assertEquals(0, s.scrollback)
    }

    @Test
    fun alternateScreenHasNoScrollbackAndRestoresThePrimary() {
        val vt = VtEngine(10, 2)
        vt.type("shell\r\n$ ")
        vt.type("\u001b[?1049h\u001b[Hvim\r\n1\r\n2\r\n3")
        var s = vt.snapshot()
        assertEquals(listOf("2", "3"), s.rows())
        assertEquals(0, s.scrollback)
        assertEquals(true, s.modes.altScreen)
        vt.type("\u001b[?1049l")
        s = vt.snapshot()
        assertEquals(listOf("shell", "$"), s.rows())
        assertEquals(Cursor(2, 1, true, CursorShape.Block), s.cursor)
    }

    @Test
    fun markReconnectedAddsADimRowToScrollback() {
        val vt = VtEngine(20, 2)
        vt.type("old")
        vt.markReconnected()
        val s = vt.snapshot()
        assertEquals(listOf("[Reconnected]"), s.history.map { it.text })
        assertEquals(true, s.history[0].style(0).dim)
        assertEquals(listOf("old", ""), s.rows())
    }

    @Test
    fun noticeWritesDimTextOnItsOwnLine() {
        val vt = VtEngine(40, 3)
        vt.type("\u001b[1m$ ls")
        vt.notice("[No terminal output received.]")
        vt.type("x")
        val s = vt.snapshot()
        assertEquals(listOf("$ ls", "[No terminal output received.]", "x"), s.rows())
        assertEquals(listOf(true, false), listOf(s.lines[1].style(0).dim, s.lines[1].style(0).bold))
        assertEquals(true, s.lines[2].style(0).bold)
    }

    @Test
    fun snapshotsShareRowsThatDidNotChange() {
        val vt = VtEngine(10, 3)
        vt.type("a\r\nb")
        val first = vt.snapshot()
        assertSame(first, vt.snapshot())
        vt.type("c")
        val second = vt.snapshot()
        assertSame(first.lines[0], second.lines[0])
        assertNotSame(first.lines[1], second.lines[1])
        assertEquals(listOf("b", "bc"), listOf(first.lines[1].text, second.lines[1].text))
        assertEquals(true, second.revision > first.revision)
    }

    @Test
    fun oscTitleClipboardAndBellBecomeEvents() {
        val vt = VtEngine(10, 2)
        vt.type("\u001b]2;build\u0007\u001b]52;c;aGVsbG8=\u0007\u001b]52;c;?\u0007\u0007\u001b]52;c;!!\u001b\\")
        assertEquals(listOf(VtEvent.Title("build"), VtEvent.ClipboardWrite("hello"), VtEvent.Bell), vt.takeEvents())
        assertEquals("build", vt.title)
        assertEquals(emptyList<VtEvent>(), vt.takeEvents())
    }

    @Test
    fun oscLongerThanOneMebibyteIsDropped() {
        val vt = VtEngine(10, 2)
        vt.type("\u001b]2;" + "a".repeat(1_048_577) + "\u0007ok")
        assertEquals("", vt.title)
        assertEquals("ok", vt.snapshot().lines[0].text)
    }

    @Test
    fun utf8SplitAcrossFeedsAndInvalidBytes() {
        val vt = VtEngine(10, 2)
        val e = "é".toByteArray()
        vt.feed(byteArrayOf(e[0]))
        vt.feed(byteArrayOf(e[1], 0xff.toByte(), 'x'.code.toByte(), 0xe4.toByte(), 'y'.code.toByte()))
        assertEquals("é\uFFFDx\uFFFDy", vt.snapshot().lines[0].text)
    }

    @Test
    fun combiningMarksJoinThePreviousCell() {
        val vt = VtEngine(10, 2)
        vt.type("e\u0301x")
        val row = vt.snapshot().lines[0]
        assertEquals(listOf("e\u0301", "x"), listOf(row.grapheme(0), row.grapheme(1)))
        assertEquals("e\u0301x", row.text)
    }

    @Test
    fun decSpecialGraphicsDrawLines() {
        val vt = VtEngine(10, 2)
        vt.type("\u001b(0lqk\u001b(Bq")
        assertEquals("┌─┐q", vt.snapshot().lines[0].text)
    }

    @Test
    fun sgrColorsInEveryForm() {
        val vt = VtEngine(10, 2)
        vt.type("\u001b[38:2::10:20:30mA\u001b[48;2;1;2;3mB\u001b[0;38;5;208;4mC\u001b[24;91;7mD\u001b[m")
        val row = vt.snapshot().lines[0]
        assertEquals(VtColor.rgb(10, 20, 30), row.style(0).fg)
        assertEquals(VtColor.rgb(1, 2, 3), row.style(1).bg)
        assertEquals(listOf(VtColor.palette(208), VtColor.Default), listOf(row.style(2).fg, row.style(2).bg))
        assertEquals(true, row.style(2).underline)
        assertEquals(listOf(false, true), listOf(row.style(3).underline, row.style(3).inverse))
        assertEquals(VtColor.palette(9), row.style(3).fg)
    }

    @Test
    fun repliesForDeviceAttributesAndCursorPosition() {
        val vt = VtEngine(10, 5)
        vt.type("\u001b[c\u001b[>c\u001b[2;4r\u001b[?6h\u001b[2;3H\u001b[6n\u001b[5n")
        assertEquals("\u001b[?62;22c\u001b[>1;10;0c\u001b[2;3R\u001b[0n", String(vt.takeReplies(), Charsets.US_ASCII))
        assertEquals(0, vt.takeReplies().size)
    }

    @Test
    fun aWideGlyphAtTheLastColumnWrapsAndLeavesItBlank() {
        val vt = VtEngine(5, 2)
        vt.type("abcd中x")
        val s = vt.snapshot()
        assertEquals(listOf("abcd", "中x"), s.rows())
        assertEquals(listOf(true, 0), listOf(s.lines[0].wrapped, s.lines[0].codepoint(4)))
        assertEquals("abcd中x", s.selectionText(GridPoint(0, 0), GridPoint(1, 4)))
    }

    @Test
    fun overwritingHalfAWideGlyphBlanksTheOtherHalf() {
        val vt = VtEngine(6, 1)
        vt.type("中文\u001b[1;2Hx")
        val row = vt.snapshot().lines[0]
        assertEquals(" x文", row.text)
        assertEquals(listOf(1, 1, 2, 0), (0..3).map { row.width(it) })
    }

    @Test
    fun insertModeShiftsTheRestOfTheLine() {
        val vt = VtEngine(6, 1)
        vt.type("abcdef\r\u001b[4hXY\u001b[4lZ")
        assertEquals("XYZbcd", vt.snapshot().lines[0].text)
    }

    @Test
    fun erasingKeepsTheCurrentBackground() {
        val vt = VtEngine(4, 2)
        vt.type("\u001b[1;44mab\u001b[K\r\n\u001b[0mcd")
        val row = vt.snapshot().lines[0]
        assertEquals(VtColor.palette(4), row.style(3).bg)
        assertEquals(false, row.style(3).bold)
        assertEquals(Style.Default, vt.snapshot().lines[1].style(3))
    }
}
