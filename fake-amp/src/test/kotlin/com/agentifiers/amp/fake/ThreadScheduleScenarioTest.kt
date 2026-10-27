package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ThreadScheduleScenarioTest {
    private val servedAt = Instant.parse("2026-11-02T08:00:00Z")

    @Test
    fun `the schedules are served the same distance from the fake's start as from the recording`() = FakeAmp.start("thread-schedule", servedAt = servedAt).use { server ->
        assertEquals(
            listOf(
                listOf("automation-00000001", "T-01a16702-3202-73fb-8f52-e4498ccd3721", true, null, "2026-11-02T08:05:59Z", null),
                listOf("automation-00000002", "T-01a16738-2ec2-7354-98e9-688bc413f079", false, "user-paused", null, "2026-11-02T05:50:00Z"),
            ),
            rows(Wire(server)),
        )
    }

    @Test
    fun `pausing clears the next run and enabling puts it back`() = FakeAmp.start("thread-schedule", servedAt = servedAt).use { server ->
        val wire = Wire(server)
        wire.internal("updateAutomation", json("automationID" to "automation-00000001", "enabled" to false))
        assertEquals(listOf("automation-00000001", "T-01a16702-3202-73fb-8f52-e4498ccd3721", false, "user-paused", null, null), rows(wire)[0])
        wire.internal("updateAutomation", json("automationID" to "automation-00000001", "enabled" to true))
        assertEquals(listOf("automation-00000001", "T-01a16702-3202-73fb-8f52-e4498ccd3721", true, null, "2026-11-02T08:05:59Z", null), rows(wire)[0])
        assertEquals(true, wire.internal("deleteAutomation", json("automationID" to "automation-00000002")).bool("ok"))
        assertEquals(listOf("automation-00000001"), rows(wire).map { it[0] })
    }

    private fun rows(wire: Wire) = wire.internal("listAutomations").obj("result")!!.array("automations")!!.map { row ->
        (row as JsonObject).let { listOf(it.string("id"), it.string("targetThreadID"), it.bool("enabled"), it.string("disabledReason"), it.string("nextRunAt"), it.string("lastRunAt")) }
    }
}
