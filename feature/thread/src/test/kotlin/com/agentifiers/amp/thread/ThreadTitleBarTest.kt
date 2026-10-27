package com.agentifiers.amp.thread

import androidx.compose.ui.unit.dp
import com.agentifiers.amp.model.AgentActivity
import com.agentifiers.amp.model.ExecutorStatus
import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.StopReason
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadTitleBarTest {
    private val thread = ThreadState.initial(ThreadId.parse("T-01a16702-3202-73fb-8f52-e4498ccd3721")!!)

    private fun live(activity: AgentActivity) = statusLine(thread.copy(link = Link.Live, activity = activity))

    @Test
    fun `a live thread uses the sidebar row's words`() {
        assertEquals("working", live(AgentActivity.RunningTools))
        assertEquals("working", live(AgentActivity.ComposingToolCall))
        assertEquals("working", live(AgentActivity.Streaming))
        assertEquals("compacting", live(AgentActivity.Compacting))
        assertEquals("idle", live(AgentActivity.Idle))
        assertEquals("error", live(AgentActivity.Failed))
        assertEquals("waiting", live(AgentActivity.Reported("waiting")))
        assertEquals("connected", live(AgentActivity.Unknown))
    }

    @Test
    fun `a socket that is not live says what the socket is doing`() {
        assertEquals("catching up", statusLine(thread.copy(link = Link.Resuming, activity = AgentActivity.RunningTools)))
        assertEquals("reconnecting, attempt 2: timeout", statusLine(thread.copy(link = Link.Reconnecting(2, "timeout"))))
        assertEquals("not connected", statusLine(thread.copy(link = Link.Closed)))
        assertEquals("archived", statusLine(thread.copy(link = Link.Stopped(StopReason.Archived))))
    }

    @Test
    fun `the executor chip is a short name and a state`() {
        val runner = executorChip(ExecutorStatus.Attached("local-client", "running", "Executor connected"))
        assertEquals(ExecutorChip("Runner", "running"), runner)
        assertEquals("Runner · running", runner?.text)
        assertEquals(ExecutorChip("Orb", "failed"), executorChip(ExecutorStatus.Attached("orb", "failed", null)))
        assertEquals("No executor", executorChip(ExecutorStatus.None)?.text)
        assertNull(executorChip(ExecutorStatus.Attached(null, null, null)))
    }

    @Test
    fun `a sleeping orb reads as asleep or waking, never as no executor`() {
        assertEquals("Orb asleep", executorChip(ExecutorStatus.OrbAsleep)?.text)
        assertEquals("Waking orb", executorChip(ExecutorStatus.OrbWaking)?.text)
        assertEquals("Orb asleep", executorChip(ExecutorStatus.OrbWakeFailed("The orb did not come back online within 2 minutes."))?.text)
    }

    // Share, Multiplayer, presence, in the title bar's priority order. The executor and mode moved to the status line.
    private val controls = listOf(48.dp, 48.dp, 96.dp)

    @Test
    fun `the 382dp Triage pane keeps Share and Multiplayer`() {
        assertEquals(2, titleBarFit(382.dp, title = 300.dp, controls = controls, menu = true))
    }

    @Test
    fun `a 300dp pane keeps Share`() {
        assertEquals(1, titleBarFit(300.dp, title = 300.dp, controls = controls, menu = true))
    }

    @Test
    fun `a title shorter than the floor leaves its room to controls`() {
        assertEquals(3, titleBarFit(382.dp, title = 60.dp, controls = controls, menu = true))
    }

    @Test
    fun `a wide pane keeps every control`() {
        assertEquals(3, titleBarFit(1080.dp, title = 300.dp, controls = controls, menu = true))
    }

    @Test
    fun `More Actions takes no room when it has no items and every control fits`() {
        assertEquals(3, titleBarFit(382.dp, title = 300.dp, controls = controls, menu = false))
        assertEquals(2, titleBarFit(330.dp, title = 300.dp, controls = controls, menu = false))
    }

    @Test
    fun `a control that does not fit takes every lower one with it`() {
        assertEquals(0, titleBarFit(382.dp, title = 300.dp, controls = listOf(200.dp, 48.dp), menu = true))
    }
}
