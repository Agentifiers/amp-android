package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.array
import com.agentifiers.amp.fake.obj
import com.agentifiers.amp.fake.string
import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.GATE_THREAD
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.element
import com.agentifiers.amp.fake.gateStatus
import com.agentifiers.amp.fake.listingFor
import com.agentifiers.amp.fake.recentStatus
import com.agentifiers.amp.fake.runnerListing
import com.agentifiers.amp.fake.runnerStatus
import com.agentifiers.amp.fake.plus

/**
 * Three threads with three statuses: the S4 runner thread done with unread replies and its tool calls in history
 * (S5 connect), the S19 thread in the error state S15b recorded, and the idle design probe from S2.
 */
internal object FleetBasic : ScenarioSpec {
    override val name = "fleet-basic"

    const val PROBE_THREAD = "T-01a166e9-3fa0-77ca-b925-383e88ba52ac"

    override fun build(): Scenario {
        val runner = ThreadScript.from(Capture.load("s05-steer-interrupt"))
        val gate = ThreadScript.from(Capture.load("s19-files"))
        val probe = ThreadScript.from(Capture.load("s04-live-thread"))
        val probeListing = Capture.load("s02-threads").responseBody("internal.listThreads").let { body ->
            body.obj("result")!!.array("threads")!!.map { it as kotlinx.serialization.json.JsonObject }.first { it.string("id") == PROBE_THREAD }
        }
        return Scenario(
            listOf(
                FakeThread(RUNNER_THREAD, runnerListing(), runnerStatus() + mapOf("hasUnreadMessages" to element(true)), runner),
                FakeThread(GATE_THREAD, listingFor(GATE_THREAD, null, gate.version), gateStatus(), gate),
                FakeThread(PROBE_THREAD, probeListing, recentStatus(PROBE_THREAD), probe),
            ),
        )
    }
}
