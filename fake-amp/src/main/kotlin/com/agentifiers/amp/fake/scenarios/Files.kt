package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.GATE_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.gateStatus
import com.agentifiers.amp.fake.listingFor

/**
 * S19 on a live workspace seeded with the recorded listing and `calc.py`: listings and reads answer as recorded, a
 * ranged read is rejected with `-32602`, and writes land so later reads and listings see them. A second write to the
 * same path without `overwrite` fails with `ALREADY_EXISTS`.
 */
internal object Files : ScenarioSpec {
    override val name = "files"

    override fun build(): Scenario {
        val script = ThreadScript.from(Capture.load("s19-files"))
        return Scenario(listOf(FakeThread(GATE_THREAD, listingFor(GATE_THREAD, null, script.version), gateStatus(), script, workspace = s19Workspace())))
    }
}
