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
 * S11 on the runner thread: the socket accepts the push subscription, any send (socket or `/request/rpc`) plays the
 * S4 turn, and its end sends the S11 `thread_idle` payload as encrypted Web Push to every subscription of the thread.
 */
internal object Push : ScenarioSpec {
    override val name = "push"

    override fun build(): Scenario {
        val captured = ThreadScript.from(Capture.load("s11-push"))
        val script = captured.plus(turns = ThreadScript.from(Capture.load("s04-live-thread")).turns)
        return Scenario(listOf(FakeThread(RUNNER_THREAD, runnerListing(), runnerStatus(), script)))
    }
}
