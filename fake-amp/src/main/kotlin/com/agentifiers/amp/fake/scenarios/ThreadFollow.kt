package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript

/**
 * thread-long's transcript, where each send streams the S4 turn at the end of a long list, beside the tool gallery,
 * so a flow can follow a turn, scroll, and switch threads.
 */
internal object ThreadFollow : ScenarioSpec {
    override val name = "thread-follow"

    override fun build(): Scenario {
        val turns = ThreadScript.from(Capture.load("s04-live-thread")).mapStrings { if (it == RUNNER_THREAD) ThreadLong.THREAD else it }.turns
        val long = ThreadLong.build().threads.single()
        val following = FakeThread(long.id, long.listing, long.status, long.script.plus(turns), historyPageSize = long.historyPageSize)
        return Scenario(listOf(following) + ThreadTools.build().threads)
    }
}
