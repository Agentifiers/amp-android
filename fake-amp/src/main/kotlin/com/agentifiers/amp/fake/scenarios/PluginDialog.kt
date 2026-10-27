package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.GATE_THREAD
import com.agentifiers.amp.fake.PluginDialogs
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.gateStatus
import com.agentifiers.amp.fake.listingFor

/** S15b: running the `spike-dialog` command opens a `ui.confirm`, then a `ui.input`, and the user actor shows the waiting dialog. */
internal object PluginDialog : ScenarioSpec {
    override val name = "plugin-dialog"

    override fun build(): Scenario {
        val script = ThreadScript.from(Capture.load("s15b-plugin-dialog"))
        return Scenario(
            listOf(FakeThread(GATE_THREAD, listingFor(GATE_THREAD, null, script.version), gateStatus(), script, PluginDialogs.spikeDialog())),
        )
    }
}
