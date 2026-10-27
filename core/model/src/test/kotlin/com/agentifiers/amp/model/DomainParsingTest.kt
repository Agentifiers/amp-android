package com.agentifiers.amp.model

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Instant

class DomainParsingTest {
    @Test
    fun threadQueryKeepsUnknownKeysInText() {
        val query = ThreadQuery.parse("label:infra -archived:true fix http://x.dev/a flaky")
        assertEquals(
            listOf(QueryFilter.Key("label", "infra", negated = false), QueryFilter.Key("archived", "true", negated = true)),
            query.filters,
        )
        assertEquals("fix http://x.dev/a flaky", query.text)
        assertEquals("label:infra -archived:true fix http://x.dev/a flaky", query.render())
    }

    @Test
    fun elicitUrlAcceptsHttpsAndLocalhostOnly() {
        assertEquals("https://example.com/auth?x=1", SafeElicitUrl.parse("https://example.com/auth?x=1")?.url)
        assertEquals("http://localhost:8080/cb", SafeElicitUrl.parse("http://localhost:8080/cb")?.url)
        assertNull(SafeElicitUrl.parse("http://example.com/"))
        assertNull(SafeElicitUrl.parse("javascript:alert(1)"))
        assertNull(SafeElicitUrl.parse("http://localhost.evil.com/"))
    }

    @Test
    fun terminalSessionKeyShape() {
        assertEquals("tab-2", TerminalSessionKey.parse("tab-2")?.raw)
        assertNull(TerminalSessionKey.parse("tab 2"))
        assertNull(TerminalSessionKey.parse("x".repeat(129)))
    }

    @Test
    fun pluginConfirmAnswersTheMeasuredBoolean() {
        val gate = PluginGate.Confirm(GateId("p-1"), Instant.fromEpochSeconds(0), Answerability.Answerable, GateSource.Plugin("spike"), "Spike confirm", "Allow?", null)
        assertEquals(GateResponse.PluginAnswer(GateId("p-1"), PluginOutcome.Result(JsonValue.Bool(true))), gate.respond(confirmed = true, file = null))
    }

    @Test
    fun readOnlyGateHasNoResponder() {
        val gate = ToolApprovalGate(
            GateId("TU-1"), Instant.fromEpochSeconds(0), Answerability.ReadOnly(ReadOnlyReason.ContributorCannotApprove), GateSource.BuiltIn,
            ToolCallId("TU-1"), ToolName("Bash"), "rm -rf build", JsonValue.Null,
        )
        assertThrows(IllegalStateException::class.java) { gate.approve() }
    }

    @Test
    fun choiceAnswerIsOneStringPerQuestionKey() {
        val gate = ChoiceGate(
            GateId("TU-2"), Instant.fromEpochSeconds(0), Answerability.Answerable, GateSource.BuiltIn, ToolCallId("TU-2"),
            persistentListOf(
                ChoiceQuestion("db", "Which database?", persistentListOf("pg", "sqlite"), multi = false, allowOther = true),
                ChoiceQuestion("os", "Which targets?", persistentListOf("linux", "macos", "windows"), multi = true, allowOther = false),
            ),
        )
        val answer = gate.answer(mapOf("os" to listOf("linux", "macos")), mapOf("db" to "duckdb")) as GateResponse.Approval
        assertEquals(JsonValue.Str("duckdb"), answer.askAnswers["db"])
        assertEquals(JsonValue.Str("linux, macos"), answer.askAnswers["os"])
    }
}
