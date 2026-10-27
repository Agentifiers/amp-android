package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.listingFor
import com.agentifiers.amp.fake.runnerStatus

/** The S4 brand-new runner thread: empty history, and each send streams the recorded turn (three tool calls, then a reply). */
internal object LiveTurn : ScenarioSpec {
    override val name = "live-turn"

    override fun build(): Scenario {
        val script = ThreadScript.from(Capture.load("s04-live-thread"))
        return Scenario(listOf(FakeThread(RUNNER_THREAD, listingFor(RUNNER_THREAD, null, script.version), runnerStatus(), script)))
    }
}
