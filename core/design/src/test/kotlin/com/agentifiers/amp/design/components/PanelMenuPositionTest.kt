package com.agentifiers.amp.design.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/** A TriFold inner screen at 2 px per dp: creases at 335dp and 717dp, a 1080 x 900dp window, menus kept 48dp off its top and bottom. */
class PanelMenuPositionTest {
    private val window = IntSize(2160, 1800)
    private val trifold = PanelMenuPosition(listOf(670, 1434), top = 96, bottom = 1704)
    private val menu = IntSize(400, 600)

    private fun PanelMenuPosition.place(anchor: IntRect, direction: LayoutDirection = LayoutDirection.Ltr) =
        calculatePosition(anchor, window, direction, menu)

    @Test
    fun `a sidebar row menu at the first crease ends at the anchor instead of crossing it`() {
        assertEquals(IntOffset(270, 496), trifold.place(IntRect(574, 400, 670, 496)))
    }

    @Test
    fun `a thread menu at the second crease stays in the middle panel`() {
        assertEquals(IntOffset(1034, 196), trifold.place(IntRect(1338, 100, 1434, 196)))
    }

    @Test
    fun `a menu that fits after its anchor starts at the anchor`() {
        assertEquals(IntOffset(700, 196), trifold.place(IntRect(700, 100, 796, 196)))
    }

    @Test
    fun `a menu wider than the room on both sides of its anchor hugs the panel`() {
        assertEquals(IntOffset(270, 196), trifold.place(IntRect(280, 100, 390, 196)))
    }

    @Test
    fun `right to left prefers ending at the anchor's end`() {
        assertEquals(IntOffset(700, 196), trifold.place(IntRect(700, 100, 1200, 196)))
        assertEquals(IntOffset(800, 196), trifold.place(IntRect(700, 100, 1200, 196), LayoutDirection.Rtl))
    }

    @Test
    fun `with no creases the window is the panel`() {
        assertEquals(IntOffset(574, 496), PanelMenuPosition(emptyList(), top = 96, bottom = 1704).place(IntRect(574, 400, 670, 496)))
    }

    @Test
    fun `a menu with no room below its anchor opens above it`() {
        assertEquals(IntOffset(700, 900), trifold.place(IntRect(700, 1500, 796, 1596)))
    }

    /** The New Thread composer's Project chip on a 792dp tall window: status bar 32dp, navigation bar 32dp, a 64dp menu. */
    private val composer = PanelMenuPosition(listOf(670, 1434), top = 64, bottom = 1520)
    private val projectMenu = IntSize(400, 128)
    private val visibleFrame = IntSize(2160, 1456)

    @Test
    fun `a menu that ends on the navigation bar opens below its anchor, whatever the visible frame's height`() {
        assertEquals(IntOffset(998, 1392), composer.calculatePosition(IntRect(998, 1296, 1300, 1392), visibleFrame, LayoutDirection.Ltr, projectMenu))
    }

    @Test
    fun `a menu one pixel past the navigation bar opens above its anchor`() {
        assertEquals(IntOffset(998, 1169), composer.calculatePosition(IntRect(998, 1297, 1300, 1393), visibleFrame, LayoutDirection.Ltr, projectMenu))
    }
}
