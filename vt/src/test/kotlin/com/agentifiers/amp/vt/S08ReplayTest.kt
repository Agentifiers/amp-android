package com.agentifiers.amp.vt

import org.junit.Assert.assertEquals
import org.junit.Test

class S08ReplayTest {
    private val screen = VtEngine(80, 24).apply { feed(s08PtyOutput()) }.snapshot()

    @Test
    fun gitStatusOutputLandsOnItsOwnRows() {
        assertEquals("/private/tmp/amp-scratch", screen.lines[18].text)
        assertEquals(" M calc.py", screen.lines[19].text)
        assertEquals("?? __pycache__/", screen.lines[20].text)
        assertEquals(VtColor.palette(1), screen.lines[20].style(0).fg)
    }

    @Test
    fun zshPromptMarkerLeavesNoBlankLine() {
        val rows = (15..23).map { screen.lines[it].text }
        assertEquals(
            listOf(
                "  |",
                "/private/tmp/amp-scratch  | \uD83D\uDD00 main",
                "$ pwd && git status --short",
                "/private/tmp/amp-scratch",
                " M calc.py",
                "?? __pycache__/",
                "  |",
                "/private/tmp/amp-scratch  | \uD83D\uDD00 main",
                "$",
            ),
            rows,
        )
        assertEquals(Cursor(2, 23, true, CursorShape.Block), screen.cursor)
    }

    @Test
    fun emojiInThePromptTakesTwoCells() {
        val prompt = screen.lines[22]
        assertEquals(0x1F500, prompt.codepoint(28))
        assertEquals(listOf(2, 0, 1), listOf(prompt.width(28), prompt.width(29), prompt.width(30)))
        assertEquals("m", prompt.grapheme(31))
    }

    @Test
    fun clearScreenKeepsNothingAndScrollingKeepsTheBanner() {
        assertEquals(listOf("     Welcome to the Orb.", ""), screen.history.map { it.text })
        assertEquals(VtModes(bracketedPaste = true), screen.modes)
    }
}
