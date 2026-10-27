package com.agentifiers.amp.puck

import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.PuckConversationId
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class PuckTextTest {
    private val start = Instant.parse("2026-10-23T12:00:00Z")

    private fun conversation(nn: String, title: String, ageHours: Double?) = ThreadSummary(
        id = ThreadId.parse("T-f1ee7000-0000-4000-8000-0000000000$nn")!!,
        title = title,
        status = FleetStatus.Idle,
        executor = null,
        lastActivity = ageHours?.let { start - (it * 60).toInt().minutes },
        isPuck = true,
    )

    @Test
    fun conversationsOlderThan72HoursGoUnderInactive() {
        val morning = conversation("37", "Puck: morning summary", 2.0)
        val labels = conversation("38", "Puck: label cleanup", 30.0)
        val fresh = conversation("41", "New conversation", null)
        val old = conversation("42", "Puck: old plan", 80.0)

        val groups = groupConversations(listOf(fresh, morning, labels, old), start)

        assertEquals(listOf("New conversation", "Puck: morning summary", "Puck: label cleanup"), groups.recent.map { it.title })
        assertEquals(listOf("Puck: old plan"), groups.inactive.map { it.title })
    }

    @Test
    fun newChatFailureNamesTheReason() {
        val id = PuckConversationId(ThreadId.parse("T-f1ee7000-0000-4000-8000-000000000037")!!)
        assertEquals(null, newChatFailure(Outcome.Ok(id)))
        assertEquals("New Chat failed: you are offline", newChatFailure(Outcome.Failed(EngineError.Offline)))
        assertEquals("New Chat failed: rate limited", newChatFailure(Outcome.Failed(EngineError.Server("429", "rate limited"))))
        assertEquals("New Chat may not have started. Check the conversation list.", newChatFailure(Outcome.Unknown))
    }

    @Test
    fun linkLineIsHiddenOnlyWhileLive() {
        assertEquals(null, linkLine(Link.Live))
        assertEquals("connecting", linkLine(Link.Connecting))
        assertEquals("reconnecting", linkLine(Link.Reconnecting(attempt = 2, cause = "socket closed")))
    }
}
