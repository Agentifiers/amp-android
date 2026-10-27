package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.obj
import com.agentifiers.amp.fake.runnerListing
import com.agentifiers.amp.fake.runnerStatus
import com.agentifiers.amp.fake.string

/**
 * S13: `resolveThreadPortalURL` and `mintPortalLoginURL` answer as recorded, with the onamp.dev host served by this
 * server; the login URL redirects to the portal root, which serves a small page. The thread's workspace holds
 * `.amp/portals/web.json` linking the recorded portal URL. It stays the onamp.dev URL, as a real manifest would,
 * so its `-p8765` host label still names the port; the app reaches it through `mintPortalLoginURL`, which this
 * server rebases.
 */
internal object Portal : ScenarioSpec {
    override val name = "portal"

    override fun build(): Scenario {
        val script = ThreadScript.from(Capture.load("s08-terminal"))
        val url = Capture.load("s13-portal").line(2).frame!!.obj("body")!!.obj("result")!!.string("url")!!
        val manifest = json("links" to listOf(json("label" to "Fake portal", "url" to url, "note" to "The S13 portal on port 8765")))
        val workspace = s19Workspace().apply { put(".amp/portals/web.json", manifest.toString().encodeToByteArray()) }
        return Scenario(listOf(FakeThread(RUNNER_THREAD, runnerListing(), runnerStatus(), script, workspace = workspace)))
    }
}
