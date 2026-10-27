package com.agentifiers.amp.debug

import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.api.SeedDelivery
import com.agentifiers.amp.sync.api.SeedResult
import com.agentifiers.amp.sync.api.seedOutbox
import org.json.JSONObject

/**
 * `seed-outbox {threadId, delivery, commands, inFlight?}`: stores outbox commands of one delivery class
 * (`replayable`, `at-most-once`, `guarded`) for a thread, as if dispatched while offline. Each command is an
 * outbox row's JSON without `v`. `inFlight: true` seeds them as sent with their answer lost. Ephemeral commands
 * never reach the outbox table, so `ephemeral` is refused.
 */
object SeedOutbox : AppEventHandler {
    override val name = "seed-outbox"

    private val deliveries = mapOf(
        "replayable" to SeedDelivery.Replayable,
        "at-most-once" to SeedDelivery.AtMostOnce,
        "guarded" to SeedDelivery.Guarded,
    )

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        val raw = payload.requireString("threadId")
        val thread = ThreadId.parse(raw) ?: throw BadPayload("\"$raw\" is not a thread id")
        val name = payload.requireString("delivery")
        if (name == "ephemeral") throw BadPayload("Ephemeral commands never reach the outbox table, so they cannot be seeded")
        val delivery = deliveries[name] ?: throw BadPayload("unknown delivery \"$name\"; known: ${deliveries.keys.joinToString()}")
        val array = payload.optJSONArray("commands") ?: throw BadPayload("missing \"commands\"")
        if (array.length() == 0) throw BadPayload("\"commands\" is empty")
        val commands = (0 until array.length()).map { i -> array.optJSONObject(i)?.toString() ?: throw BadPayload("command $i is not an object") }
        return when (val result = app.engines.seedOutbox(thread, commands, delivery, payload.optBoolean("inFlight", false))) {
            is SeedResult.Seeded -> "seeded ${result.count} $name command(s) for ${thread.raw}"
            is SeedResult.Refused -> throw BadPayload(result.reason)
        }
    }
}
