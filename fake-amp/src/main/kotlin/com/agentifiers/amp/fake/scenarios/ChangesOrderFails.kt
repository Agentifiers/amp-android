package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec

/** The `changes` scenario with every intelligent order answered `result{status:"failure", error}`. */
internal object ChangesOrderFails : ScenarioSpec {
    override val name = "changes-order-fails"

    override fun build(): Scenario = Changes.scenario(orderFails = true)
}
