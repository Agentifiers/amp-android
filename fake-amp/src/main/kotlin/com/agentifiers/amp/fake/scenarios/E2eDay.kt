package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.element
import com.agentifiers.amp.fake.listingFor
import com.agentifiers.amp.fake.plus
import com.agentifiers.amp.fake.runnerStatus

/**
 * One account for `flows/e2e-day.ad`: the thread-tools gallery, the approvals gate thread, and the changes runner
 * thread, whose relay answers the terminal as every thread's does. The gate thread gets push-gates' title, because
 * the flow injects its push for that thread. S12 recorded its attachment on the runner thread under the same
 * generated title, so its script moves to [ATTACHMENT_THREAD] and [ATTACHMENT_TITLE].
 */
internal object E2eDay : ScenarioSpec {
    override val name = "e2e-day"

    const val ATTACHMENT_THREAD = "T-e2ed0000-0000-7000-8000-000000000012"
    const val ATTACHMENT_TITLE = "What color is this image"

    override fun build(): Scenario {
        val gate = Approvals.build().threads.single()
        val titledGate = FakeThread(gate.id, listingFor(gate.id, PushGates.TITLE, gate.script.version), gate.status, gate.script)
        val script = AttachmentsScenario.build().threads.single().script.mapStrings {
            if (it == CAPTURED_TITLE) ATTACHMENT_TITLE else it.replace(RUNNER_THREAD, ATTACHMENT_THREAD)
        }
        val status = runnerStatus() + mapOf("threadId" to element(ATTACHMENT_THREAD), "title" to element(ATTACHMENT_TITLE))
        val attachment = FakeThread(ATTACHMENT_THREAD, listingFor(ATTACHMENT_THREAD, ATTACHMENT_TITLE, script.version), status, script)
        return Scenario(ThreadTools.build().threads + titledGate + Changes.build().threads + attachment)
    }

    private const val CAPTURED_TITLE = "Rename add to add_numbers"
}
