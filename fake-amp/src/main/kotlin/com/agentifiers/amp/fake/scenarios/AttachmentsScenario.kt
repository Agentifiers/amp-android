package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.runnerListing
import com.agentifiers.amp.fake.runnerStatus

/** S12: the resumable upload against this server, Bearer-only attachment URLs, and the recorded reply to an image send. */
internal object AttachmentsScenario : ScenarioSpec {
    override val name = "attachments"

    override fun build(): Scenario {
        val script = ThreadScript.from(Capture.load("s12-attachment"))
        return Scenario(listOf(FakeThread(RUNNER_THREAD, runnerListing(), runnerStatus(), script)))
    }
}
