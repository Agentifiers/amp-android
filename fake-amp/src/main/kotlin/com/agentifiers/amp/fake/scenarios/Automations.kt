package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.fake.FakeIdentity
import com.agentifiers.amp.fake.GATE_THREAD
import com.agentifiers.amp.fake.InternalMethods
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.World
import com.agentifiers.amp.fake.bool
import com.agentifiers.amp.fake.element
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.minus
import com.agentifiers.amp.fake.plus
import com.agentifiers.amp.fake.string
import kotlinx.serialization.json.JsonObject

/**
 * `listAutomations`, `updateAutomation`, and `deleteAutomation` over one account's automations, shaped like the CLI's
 * zod table and the live answer in `spikes/protocol/captures/s25-thread-schedule.jsonl`. Pausing clears `nextRunAt`
 * and sets `disabledReason:"user-paused"`, as the web's own pause does (chunk fa551f92.js); enabling puts the seeded
 * next run back.
 */
internal class AutomationBook(seed: List<JsonObject>) {
    private val rows = LinkedHashMap(seed.associateBy { it.string("id")!! })
    private val scheduled = seed.associate { it.string("id")!! to it["nextRunAt"]!! }

    fun answer(method: String, params: JsonObject): JsonObject? = when (method) {
        "listAutomations" -> ok(json("automations" to rows.values.map { it - "prompt" + mapOf("targetThreadTitle" to element(null)) }))
        "updateAutomation" -> update(params)
        "deleteAutomation" -> params.string("automationID")?.let { rows.remove(it) }?.let { ok(json()) } ?: World.notFound("Automation not found")
        else -> null
    }

    private fun update(params: JsonObject): JsonObject {
        val id = params.string("automationID")
        val current = rows[id] ?: return World.notFound("Automation not found")
        val enabled = params.bool("enabled")
        val updated = current + listOfNotNull(
            params.string("title")?.let { "title" to element(it) },
            enabled?.let { "enabled" to element(it) },
            enabled?.let { "disabledReason" to element(if (it) null else "user-paused") },
            enabled?.let { "nextRunAt" to if (it) scheduled.getValue(id!!) else element(null) },
            "updatedAt" to element("2026-10-23T12:00:00.000Z"),
        ).toMap()
        rows[id!!] = updated
        return ok(updated)
    }

    private fun ok(result: JsonObject) = json("ok" to true, "result" to result)

    companion object {
        fun automation(
            id: String,
            title: String,
            thread: String,
            label: String,
            enabled: Boolean,
            nextRunAt: String?,
            lastRunAt: String?,
            disabledReason: String? = if (enabled) null else "user-paused",
        ) = json(
            "id" to id, "title" to title, "prompt" to "Run the $title routine and report what changed.", "targetThreadID" to thread,
            "trigger" to json("provider" to "schedule", "schedule" to "0 9 * * *", "scheduleLabel" to label),
            "runMode" to "existing", "agentMode" to null, "enabled" to enabled, "disabledReason" to disabledReason,
            "nextRunAt" to nextRunAt, "lastRunAt" to lastRunAt, "lastRunError" to null,
            "createdAt" to "2026-09-30T10:00:00.000Z", "updatedAt" to "2026-10-22T09:00:05.000Z",
        )
    }
}

/**
 * FleetBasic's threads with two schedules. The runner thread's next run is 5 minutes 59 seconds after the fake starts,
 * so its line reads "Next run in 5m" for the first 59 seconds and "Next run in 4m" for the minute after. The gate
 * thread's schedule is paused, and its last run was 2 hours 10 minutes before the fake started.
 */
internal object ThreadSchedule : ScenarioSpec {
    override val name = "thread-schedule"

    const val ROLLOUT = "automation-00000001"
    const val DIGEST = "automation-00000002"

    override fun build(): Scenario = Scenario(FleetBasic.build().threads, methods = ScheduleMethods())

    private class ScheduleMethods : InternalMethods {
        private val book = AutomationBook(listOf(
            AutomationBook.automation(ROLLOUT, "Rollout check", RUNNER_THREAD, "Every 15 minutes", true, at(5 * 60 + 59), null),
            AutomationBook.automation(DIGEST, "Morning digest", GATE_THREAD, "Every day at 9:00", false, null, at(-(2 * 3600 + 10 * 60))),
        ))

        override fun answer(method: String, params: JsonObject, identity: FakeIdentity): JsonObject? = book.answer(method, params)
    }

    /** A fixture time [seconds] from [FakeAmp.RECORDED_AT]; the fake serves it the same distance from its start. */
    private fun at(seconds: Long): String = FakeAmp.RECORDED_AT.plusSeconds(seconds).toString()
}
