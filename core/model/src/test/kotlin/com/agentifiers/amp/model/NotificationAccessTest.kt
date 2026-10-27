package com.agentifiers.amp.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationAccessTest {
    private fun access(granted: Boolean = false, rationale: Boolean = false, enabled: Boolean = false, history: PromptHistory = PromptHistory.Never, runtime: Boolean = true, off: List<String> = emptyList()) =
        NotificationAccess.of(runtime, granted, rationale, enabled, history, off)

    @Test
    fun `a fresh install is asked once`() {
        assertEquals(NotificationAccess.FirstAsk, access())
    }

    @Test
    fun `Not Now leaves the prompt for Turn On only`() {
        assertEquals(NotificationAccess.AskAgain, access(history = PromptHistory.Declined))
    }

    @Test
    fun `one denial still lets Turn On ask, two leave only settings`() {
        assertEquals(NotificationAccess.AskAgain, access(rationale = true, history = PromptHistory.Requested))
        assertEquals(NotificationAccess.SettingsOnly, access(rationale = false, history = PromptHistory.Requested))
    }

    @Test
    fun `a denial in another screen is never followed by an automatic ask`() {
        assertEquals(NotificationAccess.AskAgain, access(rationale = true, history = PromptHistory.Never))
    }

    @Test
    fun `granted shows the channels that are off`() {
        assertEquals(NotificationAccess.Allowed(listOf("Finished")), access(granted = true, enabled = true, off = listOf("Finished")))
    }

    @Test
    fun `granted but blocked in Android settings needs settings`() {
        assertEquals(NotificationAccess.SettingsOnly, access(granted = true, enabled = false))
    }

    @Test
    fun `before Android 13 only the app switch counts`() {
        assertEquals(NotificationAccess.Allowed(emptyList()), access(runtime = false, enabled = true))
        assertEquals(NotificationAccess.SettingsOnly, access(runtime = false, enabled = false))
    }
}
