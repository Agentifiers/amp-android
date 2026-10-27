package com.agentifiers.amp.sync.internal.terminal

import com.agentifiers.amp.sync.api.RelayPayload
import com.agentifiers.amp.sync.internal.net.NetLog
import com.agentifiers.amp.sync.internal.net.ThreadSocket
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.util.UUID

internal sealed interface RelayEnd {
    /** The actor sent `client_executor_relay_exit`, with its optional `error`, such as "Unsupported executor relay service". */
    data class Exited(val error: String?) : RelayEnd

    /** The thread socket ended under the stream. The actor does not replay relay messages, so the stream is gone. */
    data class Dropped(val cause: String) : RelayEnd

    data object Closed : RelayEnd
}

/** One relay channel. [incoming] has a single collector and completes when [end] does. */
internal interface RelayStream : AutoCloseable {
    val channelId: String
    val incoming: Flow<RelayPayload>
    val end: Deferred<RelayEnd>

    /** False when the stream has ended or the socket refused the frame. */
    fun send(payload: RelayPayload): Boolean

    /** Sends `client_executor_relay_close` once, unless the socket already dropped the stream. */
    override fun close()
}

/**
 * The executor relay on one thread socket (terminal.md, "Relay wire format"). Service-agnostic: it opens channels,
 * frames payloads, and routes output by channel id. Calls arrive from the thread's loop and from stream owners on
 * other dispatchers, so every field is guarded by [lock].
 */
internal class RelayMux(private val log: NetLog) {
    private val lock = Any()
    private var socket: ThreadSocket? = null
    private val streams = HashMap<String, Stream>()
    private val reassembler = RelayReassembler()
    private val mutableLive = MutableStateFlow(false)

    /** True while the thread socket is resumed and a stream can open. */
    val live: StateFlow<Boolean> = mutableLive.asStateFlow()

    fun open(service: String): RelayStream {
        val stream = Stream("relay-${UUID.randomUUID()}")
        val opened = synchronized(lock) {
            val target = socket ?: return@synchronized false
            streams[stream.channelId] = stream
            log.frame('>', "client_executor_relay_open", service)
            target.notify(
                "client_executor_relay_open",
                buildJsonObject {
                    put("channelId", stream.channelId)
                    put("service", service)
                    put("binaryFrames", true)
                },
            )
        }
        if (!opened) {
            synchronized(lock) {
                streams.remove(stream.channelId)
                stream.closeOwed = false
            }
            stream.finish(RelayEnd.Dropped("not connected"))
        }
        return stream
    }

    /** The thread socket resumed. */
    fun connected(target: ThreadSocket) {
        synchronized(lock) { socket = target }
        mutableLive.value = true
    }

    /** The thread socket ended: every open stream ends [RelayEnd.Dropped]. */
    fun disconnected(cause: String) {
        val ended = synchronized(lock) {
            socket = null
            reassembler.clear()
            streams.values.toList().onEach { it.closeOwed = false }.also { streams.clear() }
        }
        mutableLive.value = false
        ended.forEach { it.finish(RelayEnd.Dropped(cause)) }
    }

    fun onBinary(bytes: ByteArray) {
        val frame = RelayFrame.decode(bytes)?.takeIf { it.kind == RelayKind.FromExecutor } ?: return
        log.relay('<', frame.kind.wire.toString(), frame.flags, frame.channelId, frame.payload.size)
        val (stream, message) = synchronized(lock) {
            val stream = streams[frame.channelId] ?: return
            stream.actorSendsBinary = true
            stream to (reassembler.accept(frame) ?: return)
        }
        stream.deliver(message.binary, message.payload)
    }

    /** True when [type] is relay output or exit, which this mux consumed. */
    fun onEvent(type: String, body: JsonObject): Boolean {
        if (type != OUTPUT && type != EXIT) return false
        val channelId = body.string("channelId") ?: return true
        if (type == OUTPUT) {
            val data = body.string("dataBase64")?.decodeBase64() ?: return true
            val binary = body.bool("binary") == true
            log.relay('<', OUTPUT, if (binary) 1 else 0, channelId, data.size)
            synchronized(lock) { streams[channelId] }?.deliver(binary, data.toByteArray())
        } else {
            log.relay('<', EXIT, 0, channelId, 0)
            val stream = synchronized(lock) {
                reassembler.clear(channelId)
                streams.remove(channelId)
            }
            stream?.finish(RelayEnd.Exited(body.string("error")))
        }
        return true
    }

    private fun transmit(stream: Stream, binary: Boolean, payload: ByteArray): Boolean = synchronized(lock) {
        val target = socket?.takeIf { streams[stream.channelId] === stream } ?: return false
        if (stream.actorSendsBinary) {
            RelayFrame.chunks(RelayKind.ToExecutor, binary, stream.channelId, payload).all { frame ->
                log.relay('>', frame.kind.wire.toString(), frame.flags, frame.channelId, frame.payload.size)
                target.sendBinary(frame.encode())
            }
        } else {
            log.relay('>', DATA, if (binary) 1 else 0, stream.channelId, payload.size)
            target.notify(
                DATA,
                buildJsonObject {
                    put("channelId", stream.channelId)
                    put("dataBase64", payload.toByteString().base64())
                    put("binary", binary)
                },
            )
        }
    }

    private fun closeStream(stream: Stream) {
        synchronized(lock) {
            if (!stream.closeOwed) return
            stream.closeOwed = false
            streams.remove(stream.channelId)
            reassembler.clear(stream.channelId)
            socket?.let { target ->
                log.frame('>', CLOSE)
                target.notify(CLOSE, buildJsonObject { put("channelId", stream.channelId) })
            }
        }
        stream.finish(RelayEnd.Closed)
    }

    private inner class Stream(override val channelId: String) : RelayStream {
        private val inbox = Channel<RelayPayload>(Channel.UNLIMITED)
        override val incoming: Flow<RelayPayload> = inbox.receiveAsFlow()
        override val end = CompletableDeferred<RelayEnd>()

        /** Set once the actor sends this channel a binary relay frame; from then on this side sends frames too. */
        var actorSendsBinary = false
        /** Cleared once `client_executor_relay_close` went out, or when the socket that carried the channel ended. */
        var closeOwed = true

        override fun send(payload: RelayPayload): Boolean = when (payload) {
            is RelayPayload.Binary -> transmit(this, binary = true, payload.bytes)
            is RelayPayload.Text -> transmit(this, binary = false, payload.json.encodeToByteArray())
        }

        override fun close() = closeStream(this)

        fun deliver(binary: Boolean, bytes: ByteArray) {
            inbox.trySend(if (binary) RelayPayload.Binary(bytes) else RelayPayload.Text(bytes.decodeToString()))
        }

        fun finish(how: RelayEnd) {
            end.complete(how)
            inbox.close()
        }
    }

    private companion object {
        const val DATA = "client_executor_relay_data"
        const val OUTPUT = "client_executor_relay_output"
        const val EXIT = "client_executor_relay_exit"
        const val CLOSE = "client_executor_relay_close"
    }
}
