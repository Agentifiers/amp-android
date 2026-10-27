package com.agentifiers.amp.model

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

class FleetStatusTest {
    private val at = Instant.parse("2026-10-24T15:40:00Z")
    private val thread = ThreadId.parse("T-f1ee7000-0000-4000-8000-000000000001")!!

    private val approval = ToolApprovalGate(
        GateId("toolu_01"), at, Answerability.Answerable, GateSource.BuiltIn,
        ToolCallId("toolu_01"), ToolName("shell_command"), "rm -rf build", JsonValue.Null,
    )
    private val confirm = PluginGate.Confirm(
        GateId("req-1"), at, Answerability.Answerable, GateSource.Plugin("spike"), "Spike confirm", "Allow the spike to continue?", fileField = null,
    )

    private fun state(activity: AgentActivity, vararg gates: HumanGate) =
        ThreadState.initial(thread).copy(activity = activity, link = Link.Live, gates = persistentListOf(*gates))

    @Test
    fun `a pending gate outranks what the agent is doing`() {
        val statuses = listOf(
            state(AgentActivity.RunningTools, confirm, approval),
            state(AgentActivity.Failed, confirm),
            state(AgentActivity.Failed),
            state(AgentActivity.ComposingToolCall),
            state(AgentActivity.Idle),
            state(AgentActivity.Unknown),
        ).map { it.fleetStatus(unread = true) }
        assertEquals(
            listOf("needs approval", "needs input", "error", "working", "done", null),
            statuses.map { it?.word },
        )
        assertEquals(listOf("Bash: rm -rf build", "Spike confirm"), statuses.take(2).map { (it as FleetStatus.Waiting).summary })
    }

    @Test
    fun `a thread waiting on approval leaves Snoozed for Needs you, ahead of stopped threads`() {
        fun row(n: Int, status: FleetStatus, snooze: SnoozeState = SnoozeState.NotSnoozed) =
            ThreadSummary(ThreadId.parse("T-f1ee7000-0000-4000-8000-00000000000$n")!!, "Thread $n", status, null, snooze = snooze, lastActivity = at)
        val snoozed = SnoozeState.Snoozed(SnoozeUntil.Indefinitely, SnoozeSource.ThisDevice)
        val sidebar = arrangeSidebar(
            listOf(
                row(1, FleetStatus.Error),
                row(2, FleetStatus.NeedsApproval(approval), snoozed),
                row(3, FleetStatus.Working, snoozed),
            ),
            at,
            Freshness.Live,
        )
        assertEquals(listOf("Thread 2", "Thread 1"), sidebar.needsYou.map { it.title })
        assertEquals(listOf("Thread 3"), sidebar.snoozed.map { it.title })
    }
}
