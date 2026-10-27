package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.runnerListing
import com.agentifiers.amp.fake.runnerStatus

/**
 * S5 on the runner thread at version 28: the first send plays a turn whose tools leave room to steer, and the second
 * send starts `sleep 60`, which only `client_cancel` ends.
 */
internal object SteerInterrupt : ScenarioSpec {
    override val name = "steer-interrupt"

    override fun build(): Scenario {
        val script = ThreadScript.from(Capture.load("s05-steer-interrupt"))
        return Scenario(listOf(FakeThread(RUNNER_THREAD, runnerListing(), runnerStatus(), script)))
    }
}
