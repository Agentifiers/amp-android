package com.agentifiers.amp.thread.gates

import com.agentifiers.amp.model.ElicitField
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElicitationFormTest {
    private val fields = listOf(
        ElicitField.Text("title", "Title"),
        ElicitField.Number("points", "Points"),
        ElicitField.YesNo("urgent", "Urgent"),
        ElicitField.OneOf("team", "Team", persistentListOf("web", "app")),
    )

    @Test
    fun `fields Submit waits on say required in their label`() {
        assertEquals(listOf("Title", "Points (required)", "Urgent", "Team (required)"), fields.map { it.markedLabel() })
    }

    @Test
    fun `an empty form names the first required field`() {
        assertEquals("Fill in Points to submit", submitBlocker(fields, emptyMap()))
    }

    @Test
    fun `a number field holding text asks for a number`() {
        assertEquals("Enter a number in Points to submit", submitBlocker(fields, mapOf("points" to "three")))
    }

    @Test
    fun `an unchosen single choice is named once the number is in`() {
        assertEquals("Choose Team to submit", submitBlocker(fields, mapOf("points" to "3")))
    }

    @Test
    fun `a complete form has no blocker`() {
        assertNull(submitBlocker(fields, mapOf("points" to "3", "team" to "1")))
    }

    @Test
    fun `an empty required field shows its error only after a submit attempt`() {
        val (title, points, urgent, team) = fields
        assertEquals(listOf(null, null, null, null), fields.map { fieldError(it, null, attempted = false) })
        assertEquals(listOf(null, "Points is required", null, "Team is required"), fields.map { fieldError(it, null, attempted = true) })
        assertNull(fieldError(title, "", attempted = true))
        assertNull(fieldError(urgent, "false", attempted = true))
        assertEquals("Points is required", fieldError(points, "  ", attempted = true))
    }

    @Test
    fun `a field's error clears once it holds a value, and a bad number says so before any attempt`() {
        val (_, points, _, team) = fields
        assertNull(fieldError(points, "3", attempted = true))
        assertNull(fieldError(team, "1", attempted = true))
        assertEquals("Enter a number.", fieldError(points, "three", attempted = false))
    }
}
