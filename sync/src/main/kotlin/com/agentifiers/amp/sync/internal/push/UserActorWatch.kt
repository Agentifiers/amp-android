package com.agentifiers.amp.sync.internal.push

import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.sync.api.FleetSignal
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.ThreadSocket
import com.agentifiers.amp.sync.internal.net.UserGrant
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.array
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.time.Instant

/** One thread summary from `getRecentThreads` or `threadStatusUpdated` (S2, S14, `transport.md` user actor). */
internal object UserActorStatus {
    /** A plugin dialog or approval waiting on a person, from the summary's `indicator` (S15b). */
    val NeedsYou = FleetStatus.Reported("action-required")

    fun summaryOf(json: JsonObject): ThreadSummary? {
        val id = json.string("threadId")?.let(ThreadId::parse) ?: return null
        val status = if (json.obj("indicator")?.string("kind") == "action-required") {
            NeedsYou
        } else {
            statusOf(json.string("state"), json.bool("hasUnreadMessages") == true)
        }
        return ThreadSummary(
            id, json.string("title"), status, json.obj("workspace")?.string("hostname"),
            lastReply = json.string("latestAssistantMessageID")?.let(::MessageId),
            lastTurnEndedAt = json.string("lastTurnEndedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }

    private fun statusOf(state: String?, unread: Boolean): FleetStatus = when (state) {
        null -> FleetStatus.Inactive
        "idle" -> if (unread) FleetStatus.Done else FleetStatus.Idle
        "working", "streaming", "tool_use", "running_tools", "waiting_for_approval" -> FleetStatus.Working
        "compacting" -> FleetStatus.Compacting
        "error" -> FleetStatus.Error
        else -> FleetStatus.Reported(state)
    }
}

/**
 * The user actor socket the Watch service and the foreground turn watch hold: subscribes to `threadStatusUpdated`
 * and reports each summary that changed against the last one it saw for that thread, including a new
 * `latestAssistantMessageID` under an unchanged status, which Amp sends just after the idle that ends a turn (S14).
 * The flow ends when the socket does; the collector reconnects.
 */
internal class UserActorWatch(private val client: OkHttpClient, private val endpoint: Endpoint) {
    fun signals(grant: UserGrant, known: Map<ThreadId, ThreadSummary>): Flow<FleetSignal> = callbackFlow {
        val last = HashMap(known)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val subscribe = buildJsonObject {
                    putJsonObject("body") {
                        put("tag", "SubscriptionRequest")
                        putJsonObject("val") {
                            put("eventName", "threadStatusUpdated")
                            put("subscribe", true)
                        }
                    }
                }
                webSocket.send(subscribe.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val body = try {
                    (AmpJson.parseToJsonElement(text) as? JsonObject)?.obj("body")
                } catch (_: SerializationException) {
                    null
                } ?: return
                val value = body.obj("val") ?: return
                if (body.string("tag") != "Event" || value.string("name") != "threadStatusUpdated") return
                val update = value.array("args")?.firstOrNull() as? JsonObject ?: return
                val summary = UserActorStatus.summaryOf(update) ?: return
                val previous = last.put(summary.id, summary)
                if (previous != summary) trySend(FleetSignal.StatusChanged(summary, previous?.status ?: FleetStatus.Inactive))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                channel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                channel.close()
            }
        }
        val params = buildJsonObject {
            put("type", "user")
            put("wsToken", grant.wsToken)
        }
        val protocols = listOf("rivet", "rivet_encoding.json", "rivet_skip_ready_wait", "rivet_conn_params.${ThreadSocket.encodeURIComponent(params.toString())}")
        val request = Request.Builder()
            .url(endpoint.gateway("userActor", "connect", grant.userId))
            .header("Sec-WebSocket-Protocol", protocols.joinToString(", "))
            .build()
        val socket = client.newWebSocket(request, listener)
        awaitClose { socket.close(1000, null) }
    }
}
