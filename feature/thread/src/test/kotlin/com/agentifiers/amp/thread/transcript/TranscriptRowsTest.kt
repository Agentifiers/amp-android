package com.agentifiers.amp.thread.transcript

import com.agentifiers.amp.model.AgentActivity
import com.agentifiers.amp.model.AgentError
import com.agentifiers.amp.model.Compaction
import com.agentifiers.amp.model.EarlierHistory
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.Prompt
import com.agentifiers.amp.model.PromptOrigin
import com.agentifiers.amp.model.Recap
import com.agentifiers.amp.model.Step
import com.agentifiers.amp.model.StepKey
import com.agentifiers.amp.model.ToolRun
import com.agentifiers.amp.model.TranscriptView
import com.agentifiers.amp.model.Turn
import com.agentifiers.amp.model.TurnEnd
import com.agentifiers.amp.model.TurnKey
import com.agentifiers.amp.model.UserBlock
import com.agentifiers.amp.thread.toolcards.call
import com.agentifiers.amp.thread.toolcards.done
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptRowsTest {
    private fun turn(prompt: String, reply: String) = Turn(
        TurnKey(MessageId(prompt)),
        Prompt(MessageId(prompt), persistentListOf(UserBlock.Text("ask $prompt")), PromptOrigin.Typed),
        persistentListOf(Step.Text(StepKey.of(MessageId(reply), 0), "answer $reply", streaming = false)),
        TurnEnd.Done,
        persistentListOf(),
    )

    private fun view(recap: Recap? = null) = TranscriptView.Ready(
        persistentListOf(turn("U-1", "A-1"), turn("U-2", "A-2")), EarlierHistory.None, Compaction.None, recap,
    )

    private fun keys(activity: AgentActivity = AgentActivity.Idle, error: AgentError? = null, recap: Recap? = null) =
        transcriptRows(view(recap), activity, error, emptyList(), Expansion.None).map { it.key }

    @Test
    fun `an errored agent with no error message still ends the transcript with an error row`() {
        val rows = transcriptRows(view(), AgentActivity.Failed, null, emptyList(), Expansion.None)
        assertEquals(TranscriptRow.ErrorRow(null), rows.last())
    }

    @Test
    fun `a known error shows its message once, even while the agent state says error`() {
        val error = AgentError("400 unknown provider for model zhipuai/glm-5.3-flash", "MODEL_PROVIDER_ERROR")
        val rows = transcriptRows(view(), AgentActivity.Failed, error, emptyList(), Expansion.None)
        assertEquals(listOf(TranscriptRow.ErrorRow(error)), rows.filterIsInstance<TranscriptRow.ErrorRow>())
    }

    @Test
    fun `an idle agent with no error has no error row`() {
        assertEquals(listOf("prompt-U-1", "step-A-1:0", "prompt-U-2", "step-A-2:0"), keys())
    }

    @Test
    fun `the recap opens the turn holding its anchor message`() {
        assertEquals(
            listOf("prompt-U-1", "step-A-1:0", "recap", "prompt-U-2", "step-A-2:0"),
            keys(recap = Recap("Goal: rename add.", MessageId("A-2"), 3)),
        )
    }

    @Test
    fun `a recap whose anchor is not loaded opens the latest turn`() {
        assertEquals(
            listOf("prompt-U-1", "step-A-1:0", "recap", "prompt-U-2", "step-A-2:0"),
            keys(recap = Recap("Goal: rename add.", MessageId("A-0"), 3)),
        )
    }

    @Test
    fun `a step whose calls share one status shows only the chip`() {
        val step = Step.Tools(StepKey("A-1:1"), listOf(call("TU-1", "shell_command", mapOf("command" to "ls"), done("ok")), call("TU-2", "shell_command", mapOf("command" to "pwd"), done("ok"))).toPersistentList())
        assertEquals(listOf("2 finished"), stepChips(step))
        assertEquals(null, stepWord(step))
    }

    @Test
    fun `a step with mixed statuses keeps the aggregate word`() {
        val step = Step.Tools(
            StepKey("A-1:1"),
            listOf(call("TU-1", "shell_command", mapOf("command" to "ls"), done("ok")), call("TU-2", "shell_command", mapOf("command" to "rm"), ToolRun.Error("denied", JsonValue.Null))).toPersistentList(),
        )
        assertEquals(listOf("1 finished", "1 failed"), stepChips(step))
        assertEquals("error", stepWord(step))
    }

    @Test
    fun `tool groups, their open calls, and thinking lines stack flush, text after a tool row too, and other rows keep a block gap`() {
        val shell = call("TU-1", "shell_command", mapOf("command" to "ls"), done("ok"))
        val turn = Turn(
            TurnKey(MessageId("U-1")),
            Prompt(MessageId("U-1"), persistentListOf(UserBlock.Text("list")), PromptOrigin.Typed),
            persistentListOf(
                Step.Thinking(StepKey("A-1:0"), "Look first.", redacted = false, streaming = false),
                Step.Tools(StepKey("A-1:1"), persistentListOf(shell)),
                Step.Tools(StepKey("A-1:2"), persistentListOf(call("TU-2", "shell_command", mapOf("command" to "pwd"), done("ok")))),
                Step.Text(StepKey("A-1:3"), "Done.", streaming = false),
            ),
            TurnEnd.Done,
            persistentListOf(),
        )
        val open = Expansion(setOf(StepKey("A-1:1")), emptySet(), emptySet())
        val rows = transcriptRows(TranscriptView.Ready(persistentListOf(turn), EarlierHistory.None, Compaction.None, null), AgentActivity.Idle, null, emptyList(), open)
        assertEquals(
            listOf("step-A-1:0" to false, "step-A-1:1" to true, "call-TU-1" to true, "step-A-1:2" to true, "step-A-1:3" to true),
            rows.zipWithNext { previous, row -> row.key to flushUnder(previous, row) },
        )
    }
}
