package com.agentifiers.amp.sync.api

import com.agentifiers.amp.model.Answerability
import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.GateResponse
import com.agentifiers.amp.model.GateSource
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.PluginGate
import com.agentifiers.amp.model.PluginOutcome
import com.agentifiers.amp.model.ToolApprovalGate
import com.agentifiers.amp.model.ToolCallId
import com.agentifiers.amp.model.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Instant

class GateTokensTest {
    private val approval = ToolApprovalGate(
        GateId("approval-TU-fakeApprovalShell00001"), Instant.parse("2026-10-23T05:00:00Z"), Answerability.Answerable, GateSource.BuiltIn,
        ToolCallId("TU-fakeApprovalShell00001"), ToolName("shell_command"), "rm -rf build", JsonValue.Null,
    )

    @Test
    fun anApprovalTokenRebuildsTheSameAnswer() {
        val allow = GateTokens.decode(GateTokens.encode(approval.approve())) as GateResponse.Approval
        assertEquals(ToolCallId("TU-fakeApprovalShell00001"), allow.call)
        assertEquals(true, allow.accepted)
        val deny = GateTokens.decode(GateTokens.encode(approval.deny("not now"))) as GateResponse.Approval
        assertEquals(false, deny.accepted)
        assertEquals("not now", deny.denyFeedback)
        assertEquals(GateId("approval-TU-fakeApprovalShell00001"), deny.gate)
    }

    @Test
    fun aPluginConfirmTokenCarriesTheBooleanResult() {
        val confirm = PluginGate.Confirm(
            GateId("p-4bca076e-1d84-48d2-9b25-6323cc208118-2"), Instant.parse("2026-10-23T05:32:17Z"), Answerability.Answerable,
            GateSource.Plugin("executor-plugin"), "Spike confirm", "Allow the spike to continue?", null,
        )
        val answer = GateTokens.decode(GateTokens.encode(confirm.respond(confirmed = true, file = null))) as GateResponse.PluginAnswer
        assertEquals(GateId("p-4bca076e-1d84-48d2-9b25-6323cc208118-2"), answer.gate)
        assertEquals(PluginOutcome.Result(JsonValue.Bool(true)), answer.outcome)
    }

    @Test
    fun aTokenFromAnotherVersionOrGarbageDecodesToNull() {
        val token = GateTokens.encode(approval.approve())
        assertEquals("1.", token.take(2))
        assertNull(GateTokens.decode("2." + token.drop(2)))
        assertNull(GateTokens.decode("1.%%%"))
        assertNull(GateTokens.decode("1.e30"))
    }
}
