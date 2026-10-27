package com.agentifiers.amp.sync.internal.relay

import com.agentifiers.amp.model.DesktopTarget
import com.agentifiers.amp.sync.api.DesktopStream
import com.agentifiers.amp.sync.api.RelayChannel
import com.agentifiers.amp.sync.api.RelayChannelFactory
import com.agentifiers.amp.sync.api.RelayPayload
import com.agentifiers.amp.sync.internal.thread.ThreadEngine
import com.agentifiers.amp.sync.internal.terminal.RelayEnd
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * Machine.desktop over the actor relay. All three Waymote streams open the same
 * relay service, name their path in a `{type:"connect"}` text payload, and wait for the executor's `{type:"open"}`.
 * Only then does :waymote get the channel, so it sees Waymote frames and nothing of the relay.
 */
internal class DesktopChannels(private val engine: ThreadEngine, private val target: DesktopTarget) : RelayChannelFactory {
    /** Throws [IOException] with a readable reason when the socket or the executor refuses the stream. */
    override suspend fun open(stream: DesktopStream): RelayChannel {
        val lease = engine.hold()
        try {
            awaitLive()
            val relay = engine.relay.open(target.service)
            if (!relay.send(RelayPayload.Text(connect(stream.path)))) {
                relay.close()
                throw IOException("The thread connection dropped before the desktop answered.")
            }
            val answer = try {
                withTimeout(OPEN_TIMEOUT_MS) { relay.incoming.firstOrNullPayload() }
            } catch (e: TimeoutCancellationException) {
                relay.close()
                throw IOException("The desktop did not answer within ${OPEN_TIMEOUT_MS / 1000} seconds.")
            }
            if (answer?.type() != "open") {
                relay.close()
                throw IOException(refusal(answer, relay.end.takeIf { it.isCompleted }?.getCompleted()))
            }
            if (stream == DesktopStream.Control) {
                engine.notifyLive("client_desktop_viewing", buildJsonObject { put("display", target.display) })
            }
            return object : RelayChannel {
                override val incoming: Flow<RelayPayload> = relay.incoming
                override fun send(payload: RelayPayload) {
                    relay.send(payload)
                }

                override fun close() {
                    relay.close()
                    lease.close()
                }
            }
        } catch (e: Throwable) {
            lease.close()
            throw e
        }
    }

    private suspend fun awaitLive() {
        try {
            withTimeout(OPEN_TIMEOUT_MS) { engine.relay.live.first { it } }
        } catch (e: TimeoutCancellationException) {
            throw IOException("The thread is not connected, so the desktop cannot open.")
        }
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 10_000L

        val DesktopTarget.service: String
            get() = when (this) {
                DesktopTarget.Orb -> "amp-desktop-v1"
                DesktopTarget.Mac -> "amp-mac-desktop-v1"
            }

        val DesktopTarget.display: String
            get() = when (this) {
                DesktopTarget.Orb -> "orb"
                DesktopTarget.Mac -> "mac"
            }

        val DesktopStream.path: String
            get() = when (this) {
                DesktopStream.Video -> "/stream"
                DesktopStream.Audio -> "/audio"
                DesktopStream.Control -> "/control"
            }

        fun connect(path: String): String = buildJsonObject {
            put("type", "connect")
            put("path", path)
        }.toString()

        suspend fun Flow<RelayPayload>.firstOrNullPayload(): RelayPayload? = try {
            first()
        } catch (e: NoSuchElementException) {
            null
        }

        fun RelayPayload.type(): String? =
            (this as? RelayPayload.Text)?.let { runCatching { AmpJson.parseToJsonElement(it.json) as? JsonObject }.getOrNull() }?.string("type")

        fun refusal(answer: RelayPayload?, end: RelayEnd?): String = when {
            end is RelayEnd.Exited ->
                end.error?.let { "The executor refused the desktop: $it" } ?: "The executor closed the desktop stream."
            end is RelayEnd.Dropped -> "The thread connection dropped: ${end.cause}"
            answer == null -> "The desktop stream ended before it opened."
            else -> "The desktop answered something other than open."
        }
    }
}
