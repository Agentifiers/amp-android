package com.agentifiers.amp.sync.internal.fleet

import com.agentifiers.amp.model.IntentId
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Outcome
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A thread actor request made on the thread's own socket, which the engine holds open for it. */
internal fun interface SocketCaller {
    /** Null on success, else why it failed. */
    suspend fun call(method: String, params: JsonObject): String?
}

/**
 * Delivers organize and sharing writes. The Sidebar shows each change at once ([FleetEngine.expect]) and
 * reconciles with the next read after the write settles ([FleetEngine.settled]).
 */
internal class Organizer(
    private val http: AmpHttp,
    private val fleet: FleetEngine,
    private val scope: CoroutineScope,
    private val onUnauthorized: () -> Unit,
) {
    /** False when [intent] is not an organize write, so the caller routes it elsewhere. */
    fun dispatch(thread: ThreadId, id: IntentId, intent: ThreadIntent, socket: SocketCaller): Boolean {
        val request = organizeRequest(intent, thread) ?: return false
        fleet.expect(thread, id, intent)
        scope.launch {
            val failure = when (request) {
                is WireRequest.Internal -> internal(request.method, request.params)
                is WireRequest.Socket -> socket.call(request.method, request.params)
                is WireRequest.BugReport -> bugReport(thread, request)
            }
            fleet.settled(thread, id, intent, failure)
        }
        return true
    }

    private suspend fun internal(method: String, params: JsonObject): String? = when (val result = http.internal(method, params)) {
        is Outcome.Ok -> null
        Outcome.Unauthorized -> "Amp signed this account out.".also { onUnauthorized() }
        is Outcome.Failed -> result.reason
    }

    private suspend fun bugReport(thread: ThreadId, request: WireRequest.BugReport): String? {
        val opened = http.internal("openCustomBugReport", buildJsonObject { put("threadID", thread.raw) })
        val reportId = when (opened) {
            is Outcome.Ok -> (opened.value as? JsonObject)?.string("bugReportID") ?: return "Amp did not return a bug report id."
            Outcome.Unauthorized -> return "Amp signed this account out.".also { onUnauthorized() }
            is Outcome.Failed -> return opened.reason
        }
        return internal(
            "attachBugReportDiagnostics",
            buildJsonObject {
                put("bugReportID", reportId)
                put("threadID", thread.raw)
                putJsonArray("files") {}
                putJsonObject("metadata") {
                    put("description", request.text)
                    put("includeDiagnostics", request.includeDiagnostics)
                }
            },
        )
    }
}
