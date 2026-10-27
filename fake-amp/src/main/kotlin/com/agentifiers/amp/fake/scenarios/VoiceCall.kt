package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.VoiceScript
import com.agentifiers.amp.fake.element
import com.agentifiers.amp.fake.listingFor
import com.agentifiers.amp.fake.plus
import com.agentifiers.amp.fake.runnerListing
import com.agentifiers.amp.fake.runnerStatus

/**
 * A Puck conversation with a scripted voice call, and the runner thread Puck sends the caller to. No capture shows
 * how `listThreads` marks a Puck thread; its row carries `agentMode:"puck"`, the field the conversation list reads.
 */
internal object VoiceCall : ScenarioSpec {
    override val name = "voice-call"

    /** The Puck conversation. Not a captured id. */
    const val PUCK_THREAD = "T-01a16800-9c4c-7a11-8000-00000000c0de"

    override fun build(): Scenario {
        val empty = ThreadScript.from(Capture.load("s04-live-thread"))
        val voice = VoiceScript(transcript = "Your runner thread finished its checks.", clip = VoiceScript.tone(), navigateTo = RUNNER_THREAD)
        val puckStatus = runnerStatus() + mapOf("threadId" to element(PUCK_THREAD))
        val puck = FakeThread(PUCK_THREAD, listingFor(PUCK_THREAD, "Puck", empty.version) + mapOf("agentMode" to element("puck")), puckStatus, empty, voice = voice)
        val runner = FakeThread(RUNNER_THREAD, runnerListing(), runnerStatus(), empty)
        return Scenario(listOf(puck, runner))
    }
}
