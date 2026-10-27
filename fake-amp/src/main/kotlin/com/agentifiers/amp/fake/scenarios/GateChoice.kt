package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.Event
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.GATE_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.Turn
import com.agentifiers.amp.fake.TurnStep
import com.agentifiers.amp.fake.gateStatus
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.listingFor

/**
 * The first send plays a turn that stops on an `ask_user_choice` call in `tool_approval_queue`: one single-choice
 * question with Other and one multiple-choice question. The input shape is not observed [INFERENCE:
 * `{questions[{question, options[{label}], multiSelect, allowOther}]}`]; the answer is the approval response with
 * `askAnswers`. Approving plays the rest of the turn; declining rejects the call as `approvals` does.
 */
internal object GateChoice : ScenarioSpec {
    override val name = "gate-choice"

    const val TOOL_CALL = "TU-fakeAskUserChoice00001"
    private const val TRIGGER = "M-fakeChoiceTrigger00001"
    private const val ASSISTANT = "M-fakeChoiceAssistant001"
    private const val REPLY = "M-fakeChoiceReply0000001"
    private const val RESULT = "M-fakeChoiceResult000001"

    override fun build(): Scenario {
        val captured = ThreadScript.from(Capture.load("s15-approvals"))
        val script = ThreadScript(captured.version, captured.history, captured.greeting, captured.burst, listOf(choiceTurn()), captured.exchanges)
        return Scenario(listOf(FakeThread(GATE_THREAD, listingFor(GATE_THREAD, null, script.version), gateStatus(), script)))
    }

    private fun choiceTurn(): Turn {
        val questions = json(
            "questions" to listOf(
                json(
                    "question" to "Which database should the service use?",
                    "options" to listOf(json("label" to "postgres"), json("label" to "sqlite")),
                    "multiSelect" to false,
                    "allowOther" to true,
                ),
                json(
                    "question" to "Which platforms should CI cover?",
                    "options" to listOf(json("label" to "linux"), json("label" to "macos"), json("label" to "windows")),
                    "multiSelect" to true,
                ),
            ),
        )
        val toolUse = json("type" to "tool_use", "id" to TOOL_CALL, "name" to "ask_user_choice", "blockState" to "complete", "complete" to true, "input" to questions)
        fun state(state: String, messageId: String) = Event("agent_state", json("state" to state, "messageId" to messageId, "agentMode" to "low"))
        fun message(role: String, id: String, vararg content: Any) = Event(
            "message_added",
            json("message" to json("threadId" to GATE_THREAD, "role" to role, "content" to content.toList(), "readAt" to null, "messageId" to id)),
        )
        val item = json(
            "id" to "approval-$TOOL_CALL",
            "toolCallId" to TOOL_CALL,
            "toolName" to "ask_user_choice",
            "args" to questions,
            "context" to "thread",
            "timestamp" to "2026-10-23T05:00:00.000Z",
            "ruleSource" to "built-in",
        )
        val result = json("type" to "tool_result", "toolUseID" to TOOL_CALL, "run" to json("status" to "done", "result" to json("answered" to true)))
        return Turn(
            TRIGGER,
            listOf(
                TurnStep.Emit(100, state("working", TRIGGER)),
                TurnStep.Emit(300, state("streaming", ASSISTANT)),
                TurnStep.Emit(300, message("assistant", ASSISTANT, toolUse)),
                TurnStep.Emit(50, state("tool_use", ASSISTANT)),
                TurnStep.AwaitApproval(100, item),
                TurnStep.Emit(100, state("running_tools", ASSISTANT)),
                TurnStep.Emit(300, message("user", RESULT, result)),
                TurnStep.Emit(300, state("streaming", REPLY)),
                TurnStep.Emit(300, message("assistant", REPLY, json("type" to "text", "text" to "Thanks, I have your choices."))),
                TurnStep.Emit(50, state("idle", REPLY)),
            ),
            parks = false,
        )
    }
}
