package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.OrbScript
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript

/**
 * The `changes` thread with its orb asleep: the connect burst carries the `observers` and `executor_status` frames
 * `captures/s21-orb-sleep.jsonl` recorded on a sleeping orb, and the wake brings it back after [WAKE_MS], so the
 * Changes pane loads its S7 git answers on its own.
 */
internal object OrbAsleep : ScenarioSpec {
    override val name = "orb-asleep"

    override fun build(): Scenario = asleep(OrbScript(WAKE_MS))

    fun asleep(orb: OrbScript): Scenario {
        val thread = Changes.build().threads.single()
        val script = thread.script
        val slept = ThreadScript.from(Capture.load("s21-orb-sleep")).burst.filter { it.event.type == "executor_status" || it.event.type == "observers" }
        val observers = slept.first { it.event.type == "observers" }.event
        val burst = script.burst.filter { it.event.type != "executor_status" && it.event.type != "observers" } + slept
        val asleep = ThreadScript(script.version, script.history, observers, burst, script.turns, script.exchanges)
        return Scenario(listOf(FakeThread(thread.id, thread.listing, thread.status, asleep, orb = orb)))
    }

    private const val WAKE_MS = 8_000L
}

/** [OrbAsleep] where the wake fails: Amp answers the wake, then reports the orb could not resume. */
internal object OrbWakeFails : ScenarioSpec {
    override val name = "orb-wake-fails"

    override fun build(): Scenario = OrbAsleep.asleep(OrbScript(wakeMs = 3_000L, failure = "The orb could not resume."))
}
