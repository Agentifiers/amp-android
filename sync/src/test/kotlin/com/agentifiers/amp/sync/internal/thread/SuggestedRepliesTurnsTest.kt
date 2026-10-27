package com.agentifiers.amp.sync.internal.thread

import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.PromptOrigin
import com.agentifiers.amp.model.Step
import com.agentifiers.amp.model.SuggestedReply
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.Turn
import com.agentifiers.amp.model.TurnEnd
import com.agentifiers.amp.model.UserBlock
import com.agentifiers.amp.sync.api.PushEvent
import com.agentifiers.amp.sync.internal.push.PushPayload
import com.agentifiers.amp.sync.internal.wire.AssistantState
import com.agentifiers.amp.sync.internal.wire.DecodedMessage
import com.agentifiers.amp.sync.internal.wire.Part
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestedRepliesTurnsTest {
    private val block = "<suggested-replies>\n**Register a fixed console hostname** for sign-in and deploy dev\nCommit and push everything to both repos\n</suggested-replies>"
    private val prompt = DecodedMessage.User(MessageId("U-1"), listOf(UserBlock.Text("What is left?")), PromptOrigin.Typed, emptyMap())

    private fun assistant(id: String, text: String) =
        DecodedMessage.Assistant(MessageId(id), mapOf(0 to Part.Text(text)), AssistantState.Complete("end_turn"))

    private fun texts(turn: Turn) = turn.steps.filterIsInstance<Step.Text>().map { it.markdown to it.streaming }

    @Test
    fun `a finished reply's block becomes the turn's replies and leaves its text step`() {
        val turn = buildTurns(listOf(prompt, assistant("A-1", "Both apps deploy now.\n\n$block")), emptyMap()).single()
        assertEquals(listOf("Both apps deploy now." to false), texts(turn))
        assertEquals(
            listOf(SuggestedReply("Register a fixed console hostname", " for sign-in and deploy dev"), SuggestedReply("", "Commit and push everything to both repos")),
            turn.replies,
        )
        assertEquals(TurnEnd.Done, turn.end)
    }

    @Test
    fun `a reply that is only a block leaves no text step, and a later message without one clears the replies`() {
        val only = buildTurns(listOf(prompt, assistant("A-1", block)), emptyMap()).single()
        assertEquals(emptyList<Pair<String, Boolean>>(), texts(only))
        assertEquals(2, only.replies.size)

        val later = buildTurns(listOf(prompt, assistant("A-1", "First.\n$block"), assistant("A-2", "Second thoughts.")), emptyMap()).single()
        assertEquals(listOf("First." to false, "Second thoughts." to false), texts(later))
        assertEquals(emptyList<SuggestedReply>(), later.replies)
    }

    @Test
    fun `a streaming reply shows its prose without the half-written block`() {
        val streaming = mapOf(MessageId("A-1") to StreamingMessage(persistentMapOf(0 to Part.Text("Deploying now.\n\n<suggested-replies>\n**Register a fixed"))))
        val turn = buildTurns(listOf(prompt), streaming).single()
        assertEquals(listOf("Deploying now." to true), texts(turn))
        assertEquals(TurnEnd.Open, turn.end)
    }

    @Test
    fun `a thread_idle push body loses its block`() {
        val payload = """{"kind":"thread_idle","threadId":"T-0a0b0c0d-0000-7000-8000-000000000044","title":"Deploy","tag":"t","body":"Both apps deploy now.\n\n<suggested-replies>\nShip it\n</suggested-replies>"}"""
        assertEquals(
            PushEvent.ThreadIdle(ThreadId.parse("T-0a0b0c0d-0000-7000-8000-000000000044")!!, "t", "Deploy", "Both apps deploy now."),
            PushPayload.parse(payload.encodeToByteArray()),
        )
    }

    @Test
    fun `a thread_idle push body loses its gallery fences`() {
        val payload = """{"kind":"thread_idle","threadId":"T-0a0b0c0d-0000-7000-8000-000000000044","title":"Deploy","tag":"t","body":"Three screens.\n\n:::gallery carousel\n![Sidebar](https://h/a.png)\n:::\n\nDone."}"""
        assertEquals(
            PushEvent.ThreadIdle(ThreadId.parse("T-0a0b0c0d-0000-7000-8000-000000000044")!!, "t", "Deploy", "Three screens.\n\n![Sidebar](https://h/a.png)\n\nDone."),
            PushPayload.parse(payload.encodeToByteArray()),
        )
    }
}
