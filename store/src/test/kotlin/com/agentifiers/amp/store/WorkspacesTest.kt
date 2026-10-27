package com.agentifiers.amp.store

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspacesTest {
    private fun entry(key: String, arrangement: String, account: String = "acct-1") = WorkspaceEntry(key, account, arrangement, "{}")

    @Test
    fun aSecondSaveForAWindowReplacesItsFirstAndMovesToTheEnd() {
        val saved = WorkspacesProto(listOf(entry("task-3/wide", "Triage"), entry("task-4/wide", "Read")))
            .upsert(entry("task-3/wide", "Review"))

        assertEquals(listOf(entry("task-4/wide", "Read"), entry("task-3/wide", "Review")), saved.windows)
    }

    @Test
    fun theLeastRecentlySavedWindowsDropBeyondTheLimit() {
        val saved = WorkspacesProto(listOf(entry("task-1/wide", "Triage"), entry("task-2/wide", "Triage")))
            .upsert(entry("task-5/compact", "Puck"), keep = 2)

        assertEquals(listOf("task-2/wide", "task-5/compact"), saved.windows.map { it.windowKey })
    }

    @Test
    fun oneWindowKeepsASaveForEachAccount() {
        val saved = WorkspacesProto(listOf(entry("task-3/wide", "Triage", "acct-1")))
            .upsert(entry("task-3/wide", "Review", "acct-2"))
            .upsert(entry("task-3/wide", "Read", "acct-1"))

        assertEquals(listOf(entry("task-3/wide", "Review", "acct-2"), entry("task-3/wide", "Read", "acct-1")), saved.windows)
    }

    @Test
    fun forgettingAnAccountDropsOnlyItsSaves() {
        val saved = WorkspacesProto(listOf(entry("task-3/wide", "Triage", "acct-1"), entry("task-3/wide", "Review", "acct-2"), entry("task-4/compact", "Puck", "acct-1")))

        assertEquals(listOf(entry("task-3/wide", "Review", "acct-2")), saved.without("acct-1").windows)
    }
}
