package com.agentifiers.amp.notify

import com.agentifiers.amp.model.Answerability
import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.GateResponse
import com.agentifiers.amp.model.GateSource
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.PluginGate
import com.agentifiers.amp.model.PluginOutcome
import com.agentifiers.amp.model.ReadOnlyReason
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ToolApprovalGate
import com.agentifiers.amp.model.ToolCallId
import com.agentifiers.amp.model.ToolName
import com.agentifiers.amp.model.UnsupportedGate
import com.agentifiers.amp.model.UnsupportedWhy
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class KeepAliveAndGateButtonsTest {
    private val thread = ThreadId.parse("T-01a16738-2ec2-7354-98e9-688bc413f079")!!
    private val at = Instant.parse("2026-10-23T05:00:00Z")

    private fun inputs(visible: Int = 0, working: Set<ThreadId> = emptySet(), push: Boolean = false, left: Duration = 30.minutes, network: Boolean = true) =
        KeepAliveInputs(visible, working, push, left, network)

    @Test
    fun keepAlivePolicyTable() {
        assertEquals(KeepAliveMode.WatchService, keepAlivePolicy(inputs(visible = 1, working = setOf(thread))))
        assertEquals(KeepAliveMode.WatchService, keepAlivePolicy(inputs(working = setOf(thread), push = true)))
        assertEquals(KeepAliveMode.ForegroundUi, keepAlivePolicy(inputs(visible = 1, working = setOf(thread), left = Duration.ZERO)))
        assertEquals(KeepAliveMode.ForegroundUi, keepAlivePolicy(inputs(visible = 2)))
        assertEquals(KeepAliveMode.PushDriven, keepAlivePolicy(inputs(working = setOf(thread), push = true, network = false)))
        assertEquals(KeepAliveMode.Polling, keepAlivePolicy(inputs()))
    }

    private fun approval(summary: String) = ToolApprovalGate(
        GateId("approval-TU-pushGateShellCall00001"), at, Answerability.Answerable, GateSource.BuiltIn,
        ToolCallId("TU-pushGateShellCall00001"), ToolName("shell_command"), summary, JsonValue.Null,
    )

    @Test
    fun aShortCommandGetsAllowDenyOpen() {
        val buttons = gateButtons(approval("rm -rf build"))
        assertEquals(listOf("Allow", "Deny", "Open"), buttons.map { it.label })
        val allow = (buttons[0] as GateButton.Answer).response as GateResponse.Approval
        assertEquals(true to "TU-pushGateShellCall00001", allow.accepted to allow.call.raw)
        assertEquals(false, ((buttons[1] as GateButton.Answer).response as GateResponse.Approval).accepted)
        assertEquals("Bash: rm -rf build", gateText(approval("rm -rf build")))
    }

    @Test
    fun aCommandTooLongToReadInFullGetsReviewInsteadOfAllow() {
        val long = (1..8).joinToString("\n") { "echo line $it" }
        assertEquals(listOf("Review", "Deny", "Open"), gateButtons(approval(long)).map { it.label })
    }

    @Test
    fun secretInputsUnsupportedAndReadOnlyGatesNeverAnswerFromTheShade() {
        val secret = PluginGate.SecretInput(GateId("p-1"), at, Answerability.Answerable, GateSource.Plugin("p"), "API key", "", "Save")
        assertEquals(listOf("Open"), gateButtons(secret).map { it.label })
        val unsupported = UnsupportedGate(GateId("u-1"), at, Answerability.Answerable, GateSource.BuiltIn, "MCP question", UnsupportedWhy.UnknownKind)
        assertEquals(listOf("Answer on web", "Open"), gateButtons(unsupported).map { it.label })
        val readOnly = approval("rm -rf build").copy(answerability = Answerability.ReadOnly(ReadOnlyReason.AnsweredElsewhere))
        assertEquals(listOf("Open"), gateButtons(readOnly).map { it.label })
    }

    @Test
    fun aPluginConfirmAnswersItsBoolean() {
        val confirm = PluginGate.Confirm(
            GateId("p-4bca076e-1d84-48d2-9b25-6323cc208118-2"), at, Answerability.Answerable, GateSource.Plugin("executor-plugin"),
            "Spike confirm", "Allow the spike to continue?", null,
        )
        val buttons = gateButtons(confirm)
        assertEquals(listOf("Allow", "Deny", "Open"), buttons.map { it.label })
        assertEquals(PluginOutcome.Result(JsonValue.Bool(true)), ((buttons[0] as GateButton.Answer).response as GateResponse.PluginAnswer).outcome)
        assertEquals("Spike confirm\nAllow the spike to continue?", gateText(confirm))
    }
}
