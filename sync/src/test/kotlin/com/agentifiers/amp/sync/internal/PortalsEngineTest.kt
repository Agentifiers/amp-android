package com.agentifiers.amp.sync.internal

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.model.Portal
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.internal.MachineHarness.Companion.RUNNER_THREAD
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalsEngineTest {

    @Test
    fun manifestListsThePortalAndLoginUrlsPointAtTheFakePortalHost() = runBlocking {
        FakeAmp.start("portal").use { server ->
            MachineHarness(server).use { harness ->
                val portals = harness.thread(RUNNER_THREAD).machine.portals
                val headless = "${server.ampBaseUrl}/portal/t-exampleportal000000000000-p8765/__amp_auth/headless?code="

                val byPort = (portals.loginUrl(8765) as Outcome.Ok).value
                assertTrue(byPort, byPort.startsWith(headless))

                val listed = withTimeout(20_000) { portals.list.first { it.isNotEmpty() } }
                val recorded = "https://t-exampleportal000000000000-p8765.onamp.dev/"
                assertEquals(listOf(Portal(recorded, "Fake portal", null, "The S13 portal on port 8765", 8765)), listed)

                val byUrl = (portals.loginUrlFor(recorded) as Outcome.Ok).value
                assertTrue(byUrl, byUrl.startsWith(headless))
            }
        }
    }
}
