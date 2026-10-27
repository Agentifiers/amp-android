package com.agentifiers.amp.design.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PanelSpanTest {
    private val trifold = listOf(335.dp, 717.dp)

    @Test
    fun `a sheet from the TriFold's middle pane fills that pane and stays between the creases`() {
        assertEquals(PanelSpan(335.dp, 382.dp), panelSpan(335.dp..717.dp, trifold, maxWidth = 640.dp))
    }

    @Test
    fun `a pane that spans a crease puts the sheet in its wider side`() {
        assertEquals(PanelSpan(335.dp, 382.dp), panelSpan(0.dp..717.dp, trifold, maxWidth = 640.dp))
        assertEquals(PanelSpan(335.dp, 382.dp), panelSpan(335.dp..1080.dp, trifold, maxWidth = 640.dp))
    }

    @Test
    fun `the right pane keeps its sheet in the right panel`() {
        assertEquals(PanelSpan(717.dp, 363.dp), panelSpan(717.dp..1080.dp, trifold, maxWidth = 640.dp))
    }

    @Test
    fun `the whole TriFold window gives the middle panel, capped`() {
        assertEquals(PanelSpan(335.dp, 382.dp), panelSpan(0.dp..1080.dp, trifold, maxWidth = 640.dp))
        assertEquals(PanelSpan(376.dp, 300.dp), panelSpan(0.dp..1080.dp, trifold, maxWidth = 300.dp))
    }

    @Test
    fun `a wide pane with no crease centers a 640dp sheet`() {
        assertEquals(PanelSpan(220.dp, 640.dp), panelSpan(0.dp..1080.dp, emptyList(), maxWidth = 640.dp))
    }

    @Test
    fun `the cover's single panel takes the whole width`() {
        assertEquals(PanelSpan(0.dp, 411.dp), panelSpan(0.dp..411.dp, emptyList(), maxWidth = 640.dp))
    }
}
