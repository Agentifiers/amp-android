package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.GATE_THREAD
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.gateStatus
import com.agentifiers.amp.fake.listingFor
import com.agentifiers.amp.fake.runnerListing
import com.agentifiers.amp.fake.runnerStatus

/**
 * Two threads for the outbox's delivery classes: the S5 runner thread, idle at version 28, whose first send plays a
 * turn with tool gaps and whose second parks on `sleep 60`; and the S19 thread, whose connect burst carries the
 * recorded agent error, so a Retry's guard holds there and fails on the runner thread. Recorded gaps play up to
 * 1.5 s, so the first turn lasts long enough for a flow to queue and steer into it.
 */
internal object Outbox : ScenarioSpec {
    override val name = "outbox"

    override fun build(): Scenario {
        val runner = ThreadScript.from(Capture.load("s05-steer-interrupt"))
        val gate = ThreadScript.from(Capture.load("s19-files"))
        return Scenario(
            listOf(
                FakeThread(RUNNER_THREAD, runnerListing(), runnerStatus(), runner),
                FakeThread(GATE_THREAD, listingFor(GATE_THREAD, null, gate.version), gateStatus(), gate),
            ),
            frameGapCapMs = 1_500,
        )
    }
}
