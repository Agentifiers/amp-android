package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec

/**
 * Two Puck conversations and one working thread from [FleetOrganize]. A send in a Puck conversation plays the scripted
 * Puck reply; `openPuckThread{archiveThreadID}` archives that conversation and starts "New conversation".
 */
internal object PuckChat : ScenarioSpec {
    override val name = "puck-chat"

    private val KEEP = setOf(FleetOrganize.id(1), FleetOrganize.id(37), FleetOrganize.id(38))

    override fun build(): Scenario = FleetOrganize.scenario(FleetOrganize.threads(FakeAmp.RECORDED_AT).filter { it.id in KEEP })
}
