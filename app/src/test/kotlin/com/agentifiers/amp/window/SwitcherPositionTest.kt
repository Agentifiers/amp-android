package com.agentifiers.amp.window

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/** A TriFold inner screen at 2 px per dp: 792dp tall, a 32dp status bar and navigation bar, an 8dp gap, a 300dp tall card. */
class SwitcherPositionTest {
    private val trifold = IntRect(0, 64, 2160, 1520)
    private val card = IntSize(638, 600)

    private fun SwitcherPosition.place(size: IntSize = card) = calculatePosition(IntRect.Zero, IntSize(2160, 1456), LayoutDirection.Ltr, size)

    @Test
    fun `the sidebar's avatar has the card 8dp under it, across the sidebar`() {
        assertEquals(IntOffset(16, 244), SwitcherPosition(IntRect(40, 172, 96, 228), start = 16, area = trifold, gap = 16).place())
    }

    @Test
    fun `the rail's avatar at the window's foot has the card end 8dp over it`() {
        assertEquals(IntOffset(16, 804), SwitcherPosition(IntRect(52, 1420, 108, 1476), start = 16, area = trifold, gap = 16).place())
    }

    @Test
    fun `a card that fits neither side keeps inside the area, as near the avatar as it can`() {
        assertEquals(IntOffset(16, 104), SwitcherPosition(IntRect(40, 700, 96, 756), start = 16, area = trifold, gap = 16).place(IntSize(638, 1400)))
    }

    @Test
    fun `with no sidebar the card starts at the avatar, kept inside the window`() {
        val cover = IntRect(0, 64, 822, 1520)
        assertEquals(IntOffset(40, 244), SwitcherPosition(IntRect(40, 172, 96, 228), start = null, area = cover, gap = 16).place(IntSize(640, 600)))
        assertEquals(IntOffset(166, 244), SwitcherPosition(IntRect(700, 172, 756, 228), start = null, area = cover, gap = 16).place(IntSize(640, 600)))
    }

    @Test
    fun `a switcher restored without its avatar sits at the area's top`() {
        assertEquals(IntOffset(16, 80), SwitcherPosition(null, start = 16, area = trifold, gap = 16).place())
    }
}
