package com.agentifiers.amp.thread.composer

import com.agentifiers.amp.model.AgentActivity
import com.agentifiers.amp.model.AgentMode
import com.agentifiers.amp.model.ComposerActionKind
import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.Guard
import com.agentifiers.amp.model.IntentId
import com.agentifiers.amp.model.OutboxEntry
import com.agentifiers.amp.model.OutboxState
import com.agentifiers.amp.model.QueuedMessageId
import com.agentifiers.amp.model.ThreadIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerActionsTest {
    private val busy = listOf(
        AgentActivity.Working, AgentActivity.Streaming, AgentActivity.ComposingToolCall, AgentActivity.RunningTools, AgentActivity.Compacting,
    )
    private val notBusy = listOf(AgentActivity.Unknown, AgentActivity.Idle, AgentActivity.Failed, AgentActivity.Reported("waiting"))

    @Test
    fun `not busy offers only Send`() {
        notBusy.forEach { activity ->
            assertEquals(listOf(ComposerActionKind.Send), composerActions(activity, hasText = false))
            assertEquals(listOf(ComposerActionKind.Send), composerActions(activity, hasText = true))
        }
    }

    @Test
    fun `busy without text offers Queue and Interrupt`() {
        busy.forEach { activity ->
            assertEquals(listOf(ComposerActionKind.Queue, ComposerActionKind.Interrupt), composerActions(activity, hasText = false))
        }
    }

    @Test
    fun `busy with text turns Interrupt into Interrupt and Send`() {
        busy.forEach { activity ->
            assertEquals(listOf(ComposerActionKind.Queue, ComposerActionKind.InterruptAndSend), composerActions(activity, hasText = true))
        }
    }

    @Test
    fun `each action dispatches its commands in order`() {
        assertEquals(listOf("Send Auto: fix the build"), intentsFor(ComposerActionKind.Send, "fix the build").map(::describe))
        assertEquals(listOf("Send Steer: use gradle"), intentsFor(ComposerActionKind.Steer, "use gradle").map(::describe))
        assertEquals(listOf("Send Queue: then ship"), intentsFor(ComposerActionKind.Queue, "then ship").map(::describe))
        assertEquals(listOf("Interrupt"), intentsFor(ComposerActionKind.Interrupt, "kept in field").map(::describe))
        assertEquals(listOf("Interrupt", "Send Auto: start over"), intentsFor(ComposerActionKind.InterruptAndSend, "start over").map(::describe))
    }

    @Test
    fun `failed outbox entries explain themselves`() {
        fun notice(state: OutboxState) = outboxNotice(OutboxEntry(IntentId("I-1"), "Rename", state))
        assertEquals("Title too long", notice(OutboxState.Rejected("Title too long")))
        assertEquals("Rename sent, but the connection dropped before Amp answered.", notice(OutboxState.Unknown))
        assertEquals("Rename was not sent: the queued message is gone.", notice(OutboxState.Dropped(Guard.QueuedStillPresent(QueuedMessageId("q1")))))
        assertEquals("Rename was not sent: the agent is no longer in an error state.", notice(OutboxState.Dropped(Guard.AgentFailed)))
        assertEquals("Rename was not sent: setup is no longer failed.", notice(OutboxState.Dropped(Guard.SetupFailed)))
        assertEquals("Rename was not sent: it was answered elsewhere.", notice(OutboxState.Dropped(Guard.GatePending(GateId("g1")))))
        assertNull(notice(OutboxState.Queued))
        assertNull(notice(OutboxState.Sending(attempt = 2)))
    }

    @Test
    fun `mode chip shows the catalog name, else the key`() {
        val modes = listOf(AgentMode("medium", "Medium", locked = false), AgentMode("ultra", "Ultra", locked = true))
        assertEquals("Medium", modeDisplay("medium", modes))
        assertEquals("plugin-review", modeDisplay("plugin-review", modes))
        assertEquals("low", modeDisplay("low", emptyList()))
    }

    private fun describe(intent: ThreadIntent): String = when (intent) {
        is ThreadIntent.Send -> "Send ${intent.mode}: ${intent.draft.text}"
        ThreadIntent.Interrupt -> "Interrupt"
        else -> "unexpected $intent"
    }
}
