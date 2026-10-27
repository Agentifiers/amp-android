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
 * S15 never queued an approval, because every turn failed with `MODEL_PROVIDER_ERROR` first. The first send here plays
 * a scripted turn that stops on one `tool_approval_queue` item in the shape the CLI schema describes (transport.md,
 * human gates); the second send replays the recorded S15 failure.
 */
internal object Approvals : ScenarioSpec {
    override val name = "approvals"

    const val TOOL_CALL = "TU-fakeApprovalShell00001"
    private const val TRIGGER = "M-fakeApprovalTrigger001"
    private const val ASSISTANT = "M-fakeApprovalAssistant1"
    private const val REPLY = "M-fakeApprovalReply00001"
    private const val RESULT = "M-fakeApprovalResult0001"

    override fun build(): Scenario {
        val captured = ThreadScript.from(Capture.load("s15-approvals"))
        val script = ThreadScript(captured.version, captured.history, captured.greeting, captured.burst, listOf(gatedTurn()) + captured.turns, captured.exchanges)
        return Scenario(listOf(FakeThread(GATE_THREAD, listingFor(GATE_THREAD, null, script.version), gateStatus(), script)))
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
                TurnStep.Emit(300, message("assistant", REPLY, json("type" to "text", "text" to "Removed the build directory."))),
                TurnStep.Emit(50, state("idle", REPLY)),
            ),
            parks = false,
        )
    }
}
