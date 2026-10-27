package com.agentifiers.amp.sync.internal.settings

import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.agentifiers.amp.sync.api.Outcome as ApiOutcome
import com.agentifiers.amp.sync.internal.net.Outcome as NetOutcome

/** The caller's workspace from `getUserInfo.team`; [name] is the namespace the OAuth client methods take. */
internal data class Team(val id: String, val name: String)

/** Internal RPC for settings: every answer is parsed here, so callers see model values or an [EngineError]. */
internal class SettingsRpc(private val http: AmpHttp) {
    suspend fun <T> read(method: String, params: JsonObject = NO_PARAMS, parse: JsonObject.() -> T): ApiOutcome<T> =
        when (val answer = answerOf(method, http.internal(method, params))) {
            is Answer.Applied -> try {
                ApiOutcome.Ok((answer.result as? JsonObject ?: shape()).parse())
            } catch (_: ShapeException) {
                ApiOutcome.Failed(EngineError.Drift(method))
            }
            is Answer.NotApplied -> ApiOutcome.Failed(answer.error)
            is Answer.MaybeApplied -> ApiOutcome.Failed(answer.error)
        }

    suspend fun write(method: String, params: JsonObject): ApiOutcome<Unit> =
        when (val answer = answerOf(method, http.internal(method, params))) {
            is Answer.Applied -> ApiOutcome.Ok(Unit)
            is Answer.NotApplied -> ApiOutcome.Failed(answer.error)
            is Answer.MaybeApplied -> ApiOutcome.Unknown
        }

    suspend fun team(): ApiOutcome<Team?> = read("getUserInfo") { obj("team")?.let { Team(it.need("id"), it.need("name")) } }

    private companion object {
        val NO_PARAMS = JsonObject(emptyMap())
    }
}

private sealed interface Answer {
    data class Applied(val result: JsonElement) : Answer
    data class NotApplied(val error: EngineError) : Answer
    data class MaybeApplied(val error: EngineError) : Answer
}

/**
 * AmpHttp folds `{ok:false, error:{code, message}}` into the reason "code: message", a body without `ok` into
 * "unexpected response from <method>", and an IOException into a null status. This is the one place that unfolds it.
 */
private fun answerOf(method: String, outcome: NetOutcome<JsonElement>): Answer = when (outcome) {
    is NetOutcome.Ok -> Answer.Applied(outcome.value)
    NetOutcome.Unauthorized -> Answer.NotApplied(EngineError.Unauthorized)
    is NetOutcome.Failed -> when (val status = outcome.status) {
        null -> Answer.MaybeApplied(EngineError.Offline)
        else -> if (outcome.reason == "unexpected response from $method") {
            Answer.MaybeApplied(if (status in 200..299) EngineError.Drift(method) else EngineError.Server("HTTP $status", "HTTP $status"))
        } else {
            val (code, message) = outcome.reason.split(": ", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            Answer.NotApplied(EngineError.Server(code, message))
        }
    }
}

private class ShapeException : RuntimeException(null, null, false, false)

internal fun shape(): Nothing = throw ShapeException()

internal fun JsonObject.need(key: String): String = string(key) ?: shape()

internal fun JsonObject.flag(key: String): Boolean = bool(key) ?: shape()

internal fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray ?: shape()).map { it as? JsonObject ?: shape() }

internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray ?: shape()).map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: shape() }
