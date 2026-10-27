package com.agentifiers.amp.sync.api

import com.agentifiers.amp.model.Command
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.internal.DefaultEngines
import com.agentifiers.amp.sync.internal.outbox.OutboxCodec
import com.agentifiers.amp.sync.internal.wire.AmpJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The delivery classes the outbox table can hold. Ephemeral commands never reach it, so they cannot be seeded. */
public enum class SeedDelivery { Replayable, AtMostOnce, Guarded }

public sealed interface SeedResult {
    public data class Seeded(val count: Int) : SeedResult
    public data class Refused(val reason: String) : SeedResult
}

/**
 * Debug builds only: stores [commands] in [thread]'s outbox as if dispatched while offline. Each command is an
 * outbox row's JSON without its version field, such as `{"type":"Send","messageId":"M-…","mode":"Auto","text":"hi","attachments":[]}`,
 * and must be of the [delivery] class. With [inFlight] each was already sent and lost its answer.
 */
public suspend fun AmpEngines.seedOutbox(thread: ThreadId, commands: List<String>, delivery: SeedDelivery, inFlight: Boolean): SeedResult {
    val decoded = commands.map { raw ->
        val fields = try {
            AmpJson.parseToJsonElement(raw) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return SeedResult.Refused("a command is not a JSON object")
        val command = OutboxCodec.decode(JsonObject(fields + ("v" to JsonPrimitive(OutboxCodec.VERSION))).toString())
            ?: return SeedResult.Refused("not an outbox command: $raw")
        val matches = when (delivery) {
            SeedDelivery.Replayable -> command is Command.Replayable
            SeedDelivery.AtMostOnce -> command is Command.AtMostOnce
            SeedDelivery.Guarded -> command is Command.Guarded
        }
        if (!matches) return SeedResult.Refused("${fields["type"]} is not $delivery")
        command
    }
    val count = (this as DefaultEngines).seedOutbox(thread, decoded, inFlight) ?: return SeedResult.Refused("no account is signed in")
    return SeedResult.Seeded(count)
}
