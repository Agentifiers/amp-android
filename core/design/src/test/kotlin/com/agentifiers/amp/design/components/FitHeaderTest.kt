package com.agentifiers.amp.design.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class FitHeaderTest {
    @Test
    fun theTriageSidebarWidthKeepsOneOfFourActionsBesideBack() {
        assertEquals(HeaderFit(visible = 1, more = true), fitHeader(335.dp, actions = 4, navigation = true, menu = false))
    }

    @Test
    fun theThreadPaneWidthKeepsTwoOfFourActionsBesideBack() {
        assertEquals(HeaderFit(visible = 2, more = true), fitHeader(382.dp, actions = 4, navigation = true, menu = false))
    }

    @Test
    fun withoutBackTheSidebarWidthKeepsTwoOfFourActions() {
        assertEquals(HeaderFit(visible = 2, more = true), fitHeader(335.dp, actions = 4, navigation = false, menu = false))
    }

    @Test
    fun aShortTitleLendsItsUnusedFloorToActions() {
        assertEquals(HeaderFit(visible = 3, more = true), fitHeader(335.dp, actions = 6, navigation = true, menu = false, title = 70.dp))
    }

    @Test
    fun actionsThatFitStayInTheRowBesideTheMenu() {
        assertEquals(HeaderFit(visible = 2, more = true), fitHeader(382.dp, actions = 2, navigation = false, menu = true))
    }

    @Test
    fun actionsThatFitWithoutAMenuShowNoMoreButton() {
        assertEquals(HeaderFit(visible = 3, more = false), fitHeader(382.dp, actions = 3, navigation = false, menu = false))
    }

    @Test
    fun aPaneTooNarrowForTheTitleShowsOnlyMoreActions() {
        assertEquals(HeaderFit(visible = 0, more = true), fitHeader(240.dp, actions = 3, navigation = true, menu = false))
    }

    @Test
    fun noActionsAndNoMenuShowNoMoreButton() {
        assertEquals(HeaderFit(visible = 0, more = false), fitHeader(335.dp, actions = 0, navigation = false, menu = false))
    }
}
