package com.agentifiers.amp.ui

import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.components.HeaderFit
import org.junit.Assert.assertEquals
import org.junit.Test

class MachineHeaderTest {
    @Test
    fun aTriagePaneWithSixTabsDrawsIconsAndMovesExpandIntoMore() {
        assertEquals(MachineHeaderFit(icons = true, HeaderFit(visible = 0, more = true)), fitMachineHeader(363.dp, 430.dp, 6, 3, menu = false, 48.dp, 4.dp))
    }

    @Test
    fun aTriagePaneWithFiveTabsDrawsIconsAndKeepsExpand() {
        assertEquals(MachineHeaderFit(icons = true, HeaderFit(visible = 1, more = true)), fitMachineHeader(363.dp, 360.dp, 5, 3, menu = false, 48.dp, 4.dp))
    }

    @Test
    fun aTwoPanelPaneKeepsLabelsAndShowsEveryControl() {
        assertEquals(MachineHeaderFit(icons = false, HeaderFit(visible = 3, more = false)), fitMachineHeader(745.dp, 360.dp, 5, 3, menu = false, 48.dp, 4.dp))
    }

    @Test
    fun labelsStayWhenTheyFitBesideOneControl() {
        assertEquals(MachineHeaderFit(icons = false, HeaderFit(visible = 0, more = true)), fitMachineHeader(420.dp, 360.dp, 5, 3, menu = false, 48.dp, 4.dp))
    }

    @Test
    fun theCoverDrawsIconsAndShowsItsOneControl() {
        assertEquals(MachineHeaderFit(icons = true, HeaderFit(visible = 1, more = false)), fitMachineHeader(411.dp, 430.dp, 6, 1, menu = false, 48.dp, 4.dp))
    }

    @Test
    fun aTabWithItsOwnMenuShowsMoreBesideTheControlsThatStillFit() {
        assertEquals(MachineHeaderFit(icons = true, HeaderFit(visible = 1, more = true)), fitMachineHeader(363.dp, 360.dp, 5, 1, menu = true, 48.dp, 4.dp))
        assertEquals(MachineHeaderFit(icons = false, HeaderFit(visible = 3, more = true)), fitMachineHeader(745.dp, 360.dp, 5, 3, menu = true, 48.dp, 4.dp))
    }
}
