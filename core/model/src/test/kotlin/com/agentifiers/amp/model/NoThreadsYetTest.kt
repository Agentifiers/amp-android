package com.agentifiers.amp.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

class NoThreadsYetTest {
    private val at = Instant.parse("2026-10-24T15:40:00Z")

    private fun row(n: Int, archived: Boolean = false, isPuck: Boolean = false) =
        ThreadSummary(ThreadId.parse("T-f1ee7000-0000-4000-8000-00000000000$n")!!, "Thread $n", FleetStatus.Idle, null, archived = archived, lastActivity = at, isPuck = isPuck)

    private fun noThreadsYet(freshness: Freshness, vararg rows: ThreadSummary) = arrangeSidebar(rows.toList(), at, freshness).noThreadsYet

    @Test
    fun `an account says no threads yet only once its fleet loads with nothing to list`() {
        assertEquals(
            listOf(true, true, false, false, false, true),
            listOf(
                noThreadsYet(Freshness.Live),
                noThreadsYet(Freshness.Cached(at)),
                noThreadsYet(Freshness.NotLoaded),
                noThreadsYet(Freshness.Live, row(1)),
                noThreadsYet(Freshness.Live, row(2, archived = true)),
                noThreadsYet(Freshness.Live, row(3, isPuck = true)),
            ),
        )
    }
}
