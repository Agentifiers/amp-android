package com.agentifiers.amp.design

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectedVisibleRowTest {
    /** The machine strip in dp at font scale 1: Changes, Files, Terminal, Portal, Space after an 8dp edge. */
    private val strip = listOf(Span(8, 98), Span(98, 162), Span(162, 254), Span(254, 328), Span(328, 402))

    @Test
    fun terminalInA363dpPaneKeepsChangesWhole() {
        assertEquals(0, scrollShowing(current = 0, viewport = 363, spans = strip, selected = 2, peek = 40))
    }

    @Test
    fun spaceScrollsChangesFullyOutInsteadOfCuttingItsStart() {
        assertEquals(98, scrollShowing(current = 0, viewport = 363, spans = strip, selected = 4, peek = 40))
    }

    @Test
    fun goingBackToTerminalStaysWhereTheStripIs() {
        assertEquals(98, scrollShowing(current = 98, viewport = 363, spans = strip, selected = 2, peek = 40))
        assertEquals(8, scrollShowing(current = 98, viewport = 363, spans = strip, selected = 0, peek = 40))
    }

    @Test
    fun aNarrowRowShowsTheSelectedChipAndDropsTheNeighbors() {
        val chips = listOf(Span(0, 115), Span(121, 236), Span(242, 300))
        assertEquals(121, scrollShowing(current = 0, viewport = 197, spans = chips, selected = 1, peek = 40))
    }
}
