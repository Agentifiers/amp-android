package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.BurstFrame
import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.Event
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.element
import com.agentifiers.amp.fake.plus
import com.agentifiers.amp.fake.runnerListing
import com.agentifiers.amp.fake.runnerStatus

/**
 * The S8 runner thread with `relayDesktopAvailable:true` in its observers, so an `amp-desktop-v1` relay channel
 * streams the scripted desktop: a looping H.264 test pattern, Opus audio, two displays, and control hand-off.
 */
internal object Desktop : ScenarioSpec {
    override val name = "desktop"

    override fun build(): Scenario {
        val s08 = ThreadScript.from(Capture.load("s08-terminal"))
        val script = ThreadScript(
            version = s08.version,
            history = s08.history,
            greeting = s08.greeting?.let(::withDesktop),
            burst = s08.burst.map { if (it.event.type == "observers") BurstFrame(withDesktop(it.event), it.live) else it },
            turns = s08.turns,
            exchanges = s08.exchanges,
        )
        return Scenario(listOf(FakeThread(RUNNER_THREAD, runnerListing(), runnerStatus(), script)))
    }

    private fun withDesktop(observers: Event) = Event(observers.type, observers.body + mapOf("relayDesktopAvailable" to element(true)))
}
