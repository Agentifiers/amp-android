package com.agentifiers.amp.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingMeasureTest {
    private fun threadColumn(arrangement: Arrangement): Column =
        planLayout(InnerLandscape, Workspace.Wide.arrange(arrangement, onePane = false)).columns.single { it.role == SlotRole.Thread }

    @Test
    fun `Read starts the 600dp measure on the pane's own edge at the 335 crease`() {
        val column = threadColumn(Arrangement.Read)
        assertEquals(335f, column.startDp)
        assertEquals(0f, column.readingOffsetDp(listOf(335f, 717f), 600f))
    }

    @Test
    fun `Focus starts the measure on the 335 crease, where Read has it`() {
        val column = threadColumn(Arrangement.Focus)
        assertEquals(0f, column.startDp)
        assertEquals(335f, column.readingOffsetDp(listOf(335f, 717f), 600f))
    }

    @Test
    fun `a pane no wider than the measure is not measured`() {
        assertNull(Column(SlotRole.Thread, 335f, 382f).readingOffsetDp(listOf(335f, 717f), 600f))
    }

    @Test
    fun `a wide window without creases centers the measure`() {
        assertEquals(240f, Column(SlotRole.Thread, 0f, 1080f).readingOffsetDp(emptyList(), 600f))
    }
}
