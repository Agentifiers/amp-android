package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.GATE_THREAD
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.element
import com.agentifiers.amp.fake.gateStatus
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.listingFor
import com.agentifiers.amp.fake.plus
import com.agentifiers.amp.fake.runnerListing
import com.agentifiers.amp.fake.runnerStatus

/**
 * A fleet for the home-screen widget. The S5 runner thread is idle until a send, and its first send starts the recorded
 * `sleep 60`, so it stays working until Interrupt. The S19 thread waits on a plugin confirm: its user actor summary
 * carries the `action-required` indicator S15b's fake dialog raises (scripted, not recorded at rest).
 */
internal object WidgetFleet : ScenarioSpec {
    override val name = "widget-fleet"

    const val DIALOG_TITLE = "Deploy the preview build?"

    override fun build(): Scenario {
        val recorded = ThreadScript.from(Capture.load("s05-steer-interrupt"))
        val runner = ThreadScript(
            recorded.version,
            recorded.history,
            recorded.greeting,
            recorded.burst,
            recorded.turns.filter { it.parks },
            recorded.exchanges,
        )
        val gate = ThreadScript.from(Capture.load("s19-files"))
        val waiting = gateStatus() + mapOf(
            "state" to element("idle"),
            "indicator" to json("kind" to "action-required", "description" to DIALOG_TITLE, "observedAt" to "2026-10-23T05:32:34.036Z"),
        )
        return Scenario(
            listOf(
                FakeThread(RUNNER_THREAD, runnerListing(), runnerStatus(), runner),
                FakeThread(GATE_THREAD, listingFor(GATE_THREAD, "Waiting on a confirm", gate.version), waiting, gate),
            ),
        )
    }
}
