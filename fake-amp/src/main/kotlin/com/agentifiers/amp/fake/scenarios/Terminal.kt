package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.runnerListing
import com.agentifiers.amp.fake.runnerStatus

/** S8 on the runner thread: a relay channel answers `ready`, draws the recorded Orb banner, echoes input, and answers `pwd`. */
internal object Terminal : ScenarioSpec {
    override val name = "terminal"

    override fun build(): Scenario {
        val script = ThreadScript.from(Capture.load("s08-terminal"))
        return Scenario(listOf(FakeThread(RUNNER_THREAD, runnerListing(), runnerStatus(), script)))
    }
}
