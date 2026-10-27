package com.agentifiers.amp.fleet

import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.Freshness
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.model.arrangeSidebar
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class SidebarSectionsTest {
    private val now = Instant.parse("2026-10-24T15:40:00Z")

    private fun id(n: Int) = ThreadId.parse("T-f1ee7000-0000-4000-8000-0000000000%02d".format(n))!!

    private fun thread(n: Int, status: FleetStatus, hoursAgo: Int, unread: Boolean = false, archived: Boolean = false, executor: String? = null) =
        ThreadSummary(id(n), "Thread $n", status, executor = executor, archived = archived, lastActivity = now - hoursAgo.hours, unread = unread)

    private fun headingsAndRows(summaries: List<ThreadSummary>) =
        arrangeSidebar(summaries, now, Freshness.Live).sections().map { it.heading to it.rows.map { row -> row.id.raw } }

    @Test
    fun `an unread idle thread is the one row under Needs you (1), and empty sections are left out`() {
        val archived = (2..13).map { thread(it, FleetStatus.Idle, hoursAgo = 100, archived = true) }
        assertEquals(
            listOf(
                "Needs you (1)" to listOf("T-f1ee7000-0000-4000-8000-000000000001"),
                "Archived (12)" to (2..13).map { "T-f1ee7000-0000-4000-8000-0000000000%02d".format(it) },
            ),
            headingsAndRows(listOf(thread(1, FleetStatus.Done, hoursAgo = 8, unread = true)) + archived),
        )
    }

    @Test
    fun `Needs you counts its waiting, stopped, and finished rows`() {
        val summaries = listOf(
            thread(1, FleetStatus.Done, hoursAgo = 1, unread = true),
            thread(2, FleetStatus.Error, hoursAgo = 2),
            thread(3, FleetStatus.NeedsInput("Bash: ./gradlew migrate"), hoursAgo = 3),
            thread(4, FleetStatus.Idle, hoursAgo = 4),
        )
        assertEquals(
            listOf(
                "Needs you (3)" to listOf(
                    "T-f1ee7000-0000-4000-8000-000000000003",
                    "T-f1ee7000-0000-4000-8000-000000000002",
                    "T-f1ee7000-0000-4000-8000-000000000001",
                ),
                "No Project (1)" to listOf("T-f1ee7000-0000-4000-8000-000000000004"),
            ),
            headingsAndRows(summaries),
        )
    }

    @Test
    fun `an account with no threads has no sections at all`() {
        assertEquals(emptyList<Pair<String, List<String>>>(), headingsAndRows(emptyList()))
    }

    @Test
    fun `rows leave out the executor most threads run on, unless no executor covers half of them`() {
        val host = "devbox.local"
        assertEquals(
            host,
            usualExecutor(listOf(thread(1, FleetStatus.Idle, 1, executor = host), thread(2, FleetStatus.Idle, 1, executor = host), thread(3, FleetStatus.Idle, 1, executor = "a1.medium"), thread(4, FleetStatus.Idle, 1))),
        )
        assertEquals(
            null,
            usualExecutor(listOf(thread(1, FleetStatus.Idle, 1, executor = host), thread(2, FleetStatus.Idle, 1, executor = "a1.medium"), thread(3, FleetStatus.Idle, 1, executor = "mac-amp-app"))),
        )
    }

    @Test
    fun `rail avatars take two letters so Plan and Polish read apart`() {
        assertEquals(
            listOf("PA", "PO", "Re", "SB", "?"),
            listOf("Plan arc", "Polish onboarding", "relay", "sidebar-bugs", null).map(::railInitials),
        )
    }
}
