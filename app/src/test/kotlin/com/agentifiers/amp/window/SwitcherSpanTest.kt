package com.agentifiers.amp.window

import com.agentifiers.amp.layout.Arrangement
import com.agentifiers.amp.layout.Column
import com.agentifiers.amp.layout.LayoutPlan
import com.agentifiers.amp.layout.SlotRole
import com.agentifiers.amp.layout.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwitcherSpanTest {
    private fun plan(columns: List<Column>, creasesDp: List<Float>) =
        LayoutPlan(WidthClass.Large, Arrangement.Triage, windowBar = null, columns = columns, dividers = emptyList(), switcher = null, creasesDp = creasesDp)

    private val trifold = listOf(335f, 717f)

    @Test
    fun theTriFoldSidebarHoldsTheSwitcherInsideItsPanel() {
        val triage = plan(listOf(Column(SlotRole.Nav, 0f, 335f), Column(SlotRole.Thread, 335f, 382f), Column(SlotRole.Machine, 717f, 363f)), trifold)
        assertEquals(8f..327f, switcherSpan(triage, 8f))
    }

    @Test
    fun aRailLendsTheSwitcherItsPanelUpToTheFirstCrease() {
        val review = plan(listOf(Column(SlotRole.Nav, 0f, 72f, rail = true), Column(SlotRole.Thread, 72f, 263f), Column(SlotRole.Machine, 335f, 745f)), trifold)
        assertEquals(8f..327f, switcherSpan(review, 8f))
    }

    @Test
    fun aRailWithoutCreasesLendsTheSwitcherNoSpan() {
        assertNull(switcherSpan(plan(listOf(Column(SlotRole.Nav, 0f, 72f, rail = true), Column(SlotRole.Thread, 72f, 700f)), emptyList()), 8f))
    }

    @Test
    fun aWindowWithoutTheSidebarLendsTheSwitcherNoSpan() {
        assertNull(switcherSpan(plan(listOf(Column(SlotRole.Thread, 0f, 411f)), emptyList()), 8f))
    }
}
