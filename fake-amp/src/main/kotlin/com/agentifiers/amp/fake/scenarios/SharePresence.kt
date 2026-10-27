package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.BurstFrame
import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.Event
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.USER_ID
import com.agentifiers.amp.fake.element
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.plus
import com.agentifiers.amp.fake.runnerListing
import com.agentifiers.amp.fake.runnerStatus

/**
 * The S5 runner thread shared with the workspace while a teammate watches and types. Every capture had one observer,
 * so Ada Lovelace's `observers` entry and `prompt_drafts` draft are scripted in the captured frame shapes.
 */
internal object SharePresence : ScenarioSpec {
    override val name = "share-presence"

    override fun build(): Scenario {
        val captured = ThreadScript.from(Capture.load("s05-steer-interrupt"))
        val observers = listOf(json("userId" to USER_ID, "name" to USER_ID), json("userId" to FleetOrganize.TEAMMATE, "name" to "Ada Lovelace"))
        fun watched(event: Event) = Event("observers", event.body + mapOf("count" to element(2), "observers" to element(observers), "hasExecutor" to element(true)))
        val drafts = Event(
            "prompt_drafts",
            json("drafts" to listOf(json("connectionId" to "conn-ada", "userId" to FleetOrganize.TEAMMATE, "text" to "Can you also check the relay?"))),
        )
        val burst = captured.burst.map { frame ->
            when (frame.event.type) {
                "observers" -> BurstFrame(watched(frame.event), frame.live)
                "prompt_drafts" -> BurstFrame(drafts, frame.live)
                else -> frame
            }
        }
        val script = ThreadScript(captured.version, captured.history, captured.greeting?.let(::watched), burst, captured.turns, captured.exchanges)
        val listing = runnerListing() + mapOf("meta" to json("visibility" to "thread_workspace_shared", "sharedGroupIDs" to emptyList<Any>()))
        return Scenario(listOf(FakeThread(RUNNER_THREAD, listing, runnerStatus(), script)))
    }
}
