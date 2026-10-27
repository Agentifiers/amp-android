package com.agentifiers.amp.sync.internal.portals

import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Outcome
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.agentifiers.amp.sync.api.Outcome as ApiOutcome

/** `/api/internal` as the public [ApiOutcome]: [read] picks the answer out of `result`, and null means the shape drifted. */
internal suspend fun <T> AmpHttp.rpc(method: String, params: JsonObject, read: (JsonElement) -> T?): ApiOutcome<T> =
    when (val outcome = internal(method, params)) {
        is Outcome.Ok -> read(outcome.value)?.let { ApiOutcome.Ok(it) } ?: ApiOutcome.Failed(EngineError.Drift(method))
        Outcome.Unauthorized -> ApiOutcome.Failed(EngineError.Unauthorized)
        is Outcome.Failed -> ApiOutcome.Failed(engineError(outcome))
    }

/** No status means the request never got an answer. Otherwise AmpHttp's reason is `code: message` when Amp named one. */
internal fun engineError(failed: Outcome.Failed): EngineError {
    val status = failed.status ?: return EngineError.Offline
    return EngineError.Server(failed.reason.substringBefore(": ", "HTTP $status"), failed.reason.substringAfter(": "))
}
