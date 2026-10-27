package com.agentifiers.amp.sync.internal.push

import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.internal.net.Outcome
import com.agentifiers.amp.sync.internal.net.SocketSignal
import com.agentifiers.amp.sync.internal.net.ThreadSocket
import com.agentifiers.amp.sync.internal.thread.ThreadDeps
import com.agentifiers.amp.sync.internal.wire.FrameParser
import com.agentifiers.amp.sync.internal.wire.InboundFrame
import com.agentifiers.amp.sync.internal.wire.RivetNumber
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal sealed interface ShortOpen {
    class Ready(val session: ShortSession) : ShortOpen
    data object Unauthorized : ShortOpen
    data class Failed(val reason: String) : ShortOpen
}

/**
 * A thread socket that lives for one background job: grant, connect, `client_resume` from the grant's
 * `threadVersion` (so no history replays), then a few requests, then close. One coroutine reads it, so every
 * frame is handled in arrival order and [events] holds the burst and everything after it.
 */
internal class ShortSession private constructor(private val socket: ThreadSocket, private val deps: ThreadDeps) : AutoCloseable {
    val events = ArrayList<InboundFrame.Event>()
    private var ended = false

    /** The response to [method], or null when the socket ended or [timeoutMs] passed first. */
    suspend fun request(method: String, params: JsonObject, timeoutMs: Long = TIMEOUT_MS): InboundFrame.Response? {
        deps.log.frame('>', method)
        val id = socket.request(method, params) ?: return null
        var answer: InboundFrame.Response? = null
        readUntil(timeoutMs) { frame -> (frame is InboundFrame.Response && frame.id == id).also { if (it) answer = frame as InboundFrame.Response } }
        return answer
    }

    /** The first event after this call that matches [predicate], or null on timeout or end. */
    suspend fun awaitEvent(timeoutMs: Long = TIMEOUT_MS, predicate: (InboundFrame.Event) -> Boolean): InboundFrame.Event? {
        var found: InboundFrame.Event? = null
        readUntil(timeoutMs) { frame -> (frame is InboundFrame.Event && predicate(frame)).also { if (it) found = frame as InboundFrame.Event } }
        return found
    }

    private suspend fun readUntil(timeoutMs: Long, done: (InboundFrame) -> Boolean) {
        if (ended) return
        withTimeoutOrNull(timeoutMs) {
            for (signal in socket.signals) {
                when (signal) {
                    is SocketSignal.Ended -> {
                        ended = true
                        return@withTimeoutOrNull
                    }
                    is SocketSignal.Binary -> Unit
                    is SocketSignal.Text -> for (frame in FrameParser.parse(signal.text)) {
                        if (frame is InboundFrame.Event) events += frame
                        if (done(frame)) return@withTimeoutOrNull
                    }
                }
            }
        }
    }

    override fun close() = socket.close()

    companion object {
        private const val TIMEOUT_MS = 20_000L

        suspend fun open(deps: ThreadDeps, thread: ThreadId): ShortOpen {
            if (deps.endpoint.rivetPublishableToken.isBlank()) return ShortOpen.Failed("This build has no Rivet token.")
            val grant = when (val result = deps.http.threadGrant(thread)) {
                is Outcome.Ok -> result.value
                Outcome.Unauthorized -> return ShortOpen.Unauthorized
                is Outcome.Failed -> return ShortOpen.Failed(result.reason)
            }
            val session = ShortSession(ThreadSocket.open(deps.client, deps.endpoint, thread, grant.wsToken, deps.clientVersion), deps)
            val resumed = session.request("client_resume", buildJsonObject { put("version", RivetNumber.encode(grant.threadVersion ?: 0)) })
            if (resumed == null || resumed.error != null) {
                session.close()
                return ShortOpen.Failed(resumed?.error?.message ?: "the thread socket did not resume")
            }
            return ShortOpen.Ready(session)
        }
    }
}
