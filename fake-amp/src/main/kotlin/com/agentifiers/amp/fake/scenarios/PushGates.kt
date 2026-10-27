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
 * Notification answers on the S15 thread. Every send, over the socket or `/request/rpc`, plays the same scripted turn:
 * a few seconds of work, one `tool_approval_queue` item for `rm -rf build` that waits for
 * `client_tool_approval_response`, then the reply. Later plays mint fresh ids, so each turn is a new gate. The work
 * before the gate is long enough for a Watch flow to see the thread working.
 */
internal object PushGates : ScenarioSpec {
    override val name = "push-gates"

    const val TITLE = "Clean the build directory"
    private const val TOOL_CALL = "TU-pushGateShellCall00001"
    private const val TRIGGER = "M-pushGateTrigger0000001"
    private const val ASSISTANT = "M-pushGateAssistant00001"
    private const val REPLY = "M-pushGateReply000000001"
    private const val RESULT = "M-pushGateResult00000001"
    private const val WORK_STEPS = 20

    override fun build(): Scenario {
        val captured = ThreadScript.from(Capture.load("s15-approvals"))
        val script = ThreadScript(captured.version, captured.history, captured.greeting, captured.burst, listOf(gatedTurn()), captured.exchanges)
        return Scenario(listOf(FakeThread(GATE_THREAD, listingFor(GATE_THREAD, TITLE, script.version), gateStatus(), script)))
    }

    private fun gatedTurn(): Turn {
        val command = json("command" to "rm -rf build", "workdir" to "/private/tmp/amp-scratch")
        val toolUse = json("type" to "tool_use", "id" to TOOL_CALL, "name" to "shell_command", "blockState" to "complete", "complete" to true, "input" to command)
        fun state(state: String, messageId: String) = Event("agent_state", json("state" to state, "messageId" to messageId, "agentMode" to "low"))
        fun message(role: String, id: String, vararg content: Any) = Event(
            "message_added",
            json("message" to json("threadId" to GATE_THREAD, "role" to role, "content" to content.toList(), "readAt" to null, "messageId" to id)),
        )
        val item = json(
            "id" to "approval-$TOOL_CALL",
            "toolCallId" to TOOL_CALL,
            "toolName" to "shell_command",
            "args" to command,
            "reason" to "Matches an ask rule for shell commands",
            "toAllow" to listOf("rm -rf build"),
            "context" to "thread",
            "timestamp" to "2026-10-23T05:00:00.000Z",
            "matchedRule" to json("tool" to "shell_command", "action" to "ask", "matches" to json("command" to "rm *")),
            "ruleSource" to "user",
        )
        val result = json("type" to "tool_result", "toolUseID" to TOOL_CALL, "run" to json("status" to "done", "result" to json("output" to "", "exitCode" to 0)))
        val work = List(WORK_STEPS) { i -> TurnStep.Emit(400, state(if (i % 2 == 0) "working" else "streaming", ASSISTANT)) }
        return Turn(
            TRIGGER,
            listOf(TurnStep.Emit(100, state("working", TRIGGER))) + work + listOf(
                TurnStep.Emit(300, message("assistant", ASSISTANT, toolUse)),
                TurnStep.Emit(50, state("tool_use", ASSISTANT)),
                TurnStep.AwaitApproval(100, item),
                TurnStep.Emit(100, state("running_tools", ASSISTANT)),
                TurnStep.Emit(300, message("user", RESULT, result)),
                TurnStep.Emit(300, state("streaming", REPLY)),
                TurnStep.Emit(300, message("assistant", REPLY, json("type" to "text", "text" to "Removed the build directory."))),
                TurnStep.Emit(50, state("idle", REPLY)),
            ),
            parks = false,
        )
    }
}
