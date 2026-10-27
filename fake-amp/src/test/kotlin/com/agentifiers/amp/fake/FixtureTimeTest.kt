package com.agentifiers.amp.fake

import com.agentifiers.amp.fake.scenarios.FleetBasic
import com.agentifiers.amp.fake.scenarios.FleetOrganize
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class FixtureTimeTest {
    private val servedAt = Instant.parse("2026-11-05T12:00:00Z")

    private fun listed(server: FakeAmpServer): Map<String, JsonObject> =
        Wire(server).internal("listThreads", json("limit" to 100)).obj("result")!!.array("threads")!!
            .map { it as JsonObject }.associateBy { it.string("id")!! }

    @Test
    fun `fleet-basic served thirteen days after its recording is as old as it was when recorded`() =
        FakeAmp.start("fleet-basic", servedAt = servedAt).use { server ->
            val threads = listed(server)
            val runner = threads.getValue(RUNNER_THREAD).long("userLastInteractedAt")!!
            val probe = threads.getValue(FleetBasic.PROBE_THREAD).long("userLastInteractedAt")!!

            assertEquals(1_793_876_524_730, runner)
            assertEquals(1_793_874_226_622, probe)
            assertEquals(Duration.parse("PT57M55.27S"), Duration.between(Instant.ofEpochMilli(runner), servedAt))
            assertEquals(Duration.parse("PT1H36M13.378S"), Duration.between(Instant.ofEpochMilli(probe), servedAt))
        }

    @Test
    fun `recorded ISO times keep their format and move by the same offset`() =
        FakeAmp.start("fleet-basic", servedAt = servedAt).use { server ->
            val (_, grant) = Wire(server).post("/api/user-actor-credentials", json("capability" to "read"))
            val token = Wire.parse(grant).string("wsToken")!!
            val (_, body) = Wire(server).post(
                "/actors/gateway/userActor/action/getRecentThreads?rvt-key=$USER_ID",
                json("args" to listOf(json("limit" to 50))),
                mapOf("x-rivet-encoding" to "json", "x-rivet-conn-params" to json("type" to "user", "wsToken" to token).toString()),
            )
            val runner = Wire.parse(body).array("output")!!.map { it as JsonObject }.first { it.string("threadId") == RUNNER_THREAD }

            assertEquals("2026-11-05T11:02:07.629Z", runner.string("lastTurnEndedAt"))
            assertEquals("2026-11-05T11:02:04.730Z", runner.string("lastUserMessageAt"))
        }

    @Test
    fun `an archived fleet-organize thread stays 400 hours old`() =
        FakeAmp.start("fleet-organize", servedAt = servedAt).use { server ->
            val archived = listed(server).getValue(FleetOrganize.id(34))

            assertEquals("Archived notes", archived.string("title"))
            assertEquals(1_792_440_000_000, archived.long("userLastInteractedAt"))
        }

    @Test
    fun `a server started at the recording moment serves recorded times verbatim`() =
        FakeAmp.start("fleet-basic", servedAt = FakeAmp.RECORDED_AT).use { server ->
            assertEquals(1_792_730_224_730, listed(server).getValue(RUNNER_THREAD).long("userLastInteractedAt"))
        }

    @Test
    fun `a fractional epoch-millisecond time keeps its digits`() {
        val served = FixtureTime(servedAt).served(json("mtimeMs" to BigDecimal("1792729569472.0803"), "size" to 41))

        assertEquals("""{"mtimeMs":1793875869472.0803,"size":41}""", served.toString())
    }
}
