package com.agentifiers.amp.sync.internal.terminal

import com.agentifiers.amp.model.ExecutorStatus
import com.agentifiers.amp.model.TerminalSessionKey
import com.agentifiers.amp.model.UnavailableReason
import com.agentifiers.amp.sync.api.FileStaging
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.RelayPayload
import com.agentifiers.amp.sync.api.TerminalEvent
import com.agentifiers.amp.sync.api.TerminalFailure
import com.agentifiers.amp.sync.api.TerminalSession
import com.agentifiers.amp.sync.api.TerminalStatus
import com.agentifiers.amp.sync.api.TerminalViewer
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.string
import com.agentifiers.amp.vt.ScreenSnapshot
import com.agentifiers.amp.vt.VtEngine
import com.agentifiers.amp.vt.VtEvent
import com.agentifiers.amp.vt.VtKey
import com.agentifiers.amp.vt.VtMods
import com.agentifiers.amp.vt.VtMouse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private data class GridSize(val cols: Int, val rows: Int)

/** A relay stream and the coroutine reading it. */
private class Channel(val stream: RelayStream) {
    lateinit var reader: Job
}

/** Where the session's channel is (terminal.md, "Session lifecycle"). Only [Opening] and [Ready] hold a channel. */
private sealed interface Phase {
    /** No channel. [shown] is the status meanwhile; [gap] means output was missed and the next screen follows a gap. */
    data class Idle(val shown: TerminalStatus, val gap: Boolean) : Phase

    class Opening(val channel: Channel, val size: GridSize, val shown: TerminalStatus, val deadline: Job) : Phase

    class Ready(val channel: Channel, var sent: GridSize) : Phase {
        var quiet: Job? = null
        var resizing: Job? = null
    }

    /** A retryable exit with a viewer present: reopens when [timer] fires. */
    class Backoff(val timer: Job) : Phase

    data class Failed(val failure: TerminalFailure) : Phase

    data object Unavailable : Phase

    data object Closed : Phase
}

/**
 * One `amp-terminal-v1` session over the relay with its VT engine. Every field below is confined to [confined], so
 * relay output, input, viewer changes and timers apply one at a time in arrival order.
 */
internal class RelayTerminal(
    override val key: TerminalSessionKey?,
    private val sessions: TerminalSessions,
    parent: CoroutineScope,
) : TerminalSession {
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + confined)
    private val engine = VtEngine(DEFAULT_COLS, DEFAULT_ROWS)

    private val mutableScreen = MutableStateFlow(engine.snapshot())
    override val screen: StateFlow<ScreenSnapshot> = mutableScreen.asStateFlow()
    private val mutableStatus = MutableStateFlow<TerminalStatus>(TerminalStatus.Opening)
    override val status: StateFlow<TerminalStatus> = mutableStatus.asStateFlow()
    private val mutableEvents = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<TerminalEvent> = mutableEvents.asSharedFlow()
    private val mutableStaging = MutableStateFlow<FileStaging?>(null)
    override val staging: StateFlow<FileStaging?> = mutableStaging.asStateFlow()

    private var phase: Phase = Phase.Idle(TerminalStatus.Opening, gap = false)
    private val viewers = ArrayList<Viewer>()
    private var driver: Viewer? = null
    private var linger: Job? = null
    private var held: AutoCloseable? = null
    private var noExecutor = false
    private val queued = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var publishing: Job? = null
    private var lastPublishMs = 0L
    private val stagingOne = Mutex()

    init {
        scope.launch { sessions.relay.live.collect { reconcile() } }
        scope.launch {
            sessions.state.map { it.executor == ExecutorStatus.None || it.executor is ExecutorStatus.OrbOffline }.distinctUntilChanged().collect {
                noExecutor = it
                reconcile()
            }
        }
    }

    override fun attachViewer(windowId: String, cols: Int, rows: Int): TerminalViewer {
        require(cols > 0 && rows > 0) { "terminal size ${cols}x$rows" }
        val viewer = Viewer(GridSize(cols, rows))
        scope.launch {
            if (phase == Phase.Closed) return@launch
            viewers += viewer
            if (driver == null) driver = viewer
            linger?.cancel()
            linger = null
            syncHold()
            sizeChanged()
            reconcile()
        }
        return viewer
    }

    override fun key(key: VtKey, mods: VtMods) {
        scope.launch { input(engine.encodeKey(key, mods)) }
    }

    override fun text(s: String) {
        scope.launch { input(s.encodeToByteArray()) }
    }

    override fun paste(s: String) {
        scope.launch { input(engine.encodePaste(s)) }
    }

    override fun mouse(event: VtMouse) {
        scope.launch { engine.encodeMouse(event)?.let(::input) }
    }

    override fun focus(focused: Boolean) {
        scope.launch { engine.encodeFocus(focused)?.let(::input) }
    }

    override suspend fun stageFile(name: String, mediaType: String, bytes: Flow<ByteArray>, size: Long): Outcome<String> = stagingOne.withLock {
        if (size > MAX_FILE_BYTES) return Outcome.Refused("$name is larger than 512 MB")
        try {
            mutableStaging.value = FileStaging(name, FileStaging.Phase.Uploading)
            val url = when (val uploaded = sessions.upload.upload(sessions.thread, name, mediaType, size, bytes) {}) {
                is Outcome.Ok -> uploaded.value
                else -> return uploaded
            }
            mutableStaging.value = FileStaging(name, FileStaging.Phase.Saving)
            val path = "$STAGING_DIR/${UUID.randomUUID()}/${cleanFileName(name, mediaType)}"
            val inserted = if (sessions.saveOnExecutor(url, path)) path else url
            withContext(confined) { input(engine.encodePaste(inserted)) }
            Outcome.Ok(inserted)
        } finally {
            mutableStaging.value = null
        }
    }

    override fun retry() {
        scope.launch {
            val current = phase
            if (current is Phase.Failed || current is Phase.Idle && current.shown is TerminalStatus.Exited) {
                transition(Phase.Idle(TerminalStatus.Opening, gap = false))
                reconcile()
            }
        }
    }

    override fun close() {
        sessions.forget(this)
        scope.launch {
            linger?.cancel()
            leave(phase)
            viewers.clear()
            driver = null
            transition(Phase.Closed)
            scope.cancel()
        }
    }

    /** Opens a channel when one is wanted and possible; closes it when the thread has no executor. Idempotent. */
    private fun reconcile() {
        if (phase == Phase.Closed) return
        if (noExecutor) {
            if (phase != Phase.Unavailable) {
                leave(phase)
                transition(Phase.Unavailable)
            }
            return
        }
        if (phase == Phase.Unavailable) transition(Phase.Idle(TerminalStatus.Opening, gap = false))
        val idle = phase as? Phase.Idle ?: return
        if (viewers.isNotEmpty() && sessions.relay.live.value) open(idle)
    }

    private fun open(idle: Phase.Idle) {
        if (idle.gap) {
            engine.markReconnected()
            schedulePublish()
        }
        val size = currentSize()
        val channel = Channel(sessions.relay.open(SERVICE))
        val deadline = scope.launch {
            delay(READY_TIMEOUT_MS)
            val opening = phase as? Phase.Opening ?: return@launch
            if (opening.channel !== channel) return@launch
            leave(opening)
            transition(Phase.Failed(TerminalFailure.NoResponse))
        }
        val shown = if (idle.shown == TerminalStatus.Reconnecting) TerminalStatus.Reconnecting else TerminalStatus.Opening
        transition(Phase.Opening(channel, size, shown, deadline))
        channel.reader = scope.launch {
            channel.stream.incoming.collect { onPayload(channel, it) }
            onEnd(channel, channel.stream.end.await())
        }
        val open = buildJsonObject {
            put("type", "open")
            put("cols", size.cols)
            put("rows", size.rows)
            key?.let { put("sessionKey", it.raw) }
        }
        channel.stream.send(RelayPayload.Text(open.toString()))
    }

    private fun onPayload(channel: Channel, payload: RelayPayload) {
        if (channelOf(phase) !== channel) return
        when (payload) {
            is RelayPayload.Binary -> output(payload.bytes)
            is RelayPayload.Text -> {
                val control = runCatching { AmpJson.parseToJsonElement(payload.json) }.getOrNull() as? JsonObject ?: return
                when (control.string("type")) {
                    "ready" -> (phase as? Phase.Opening)?.let(::ready)
                    "exit" -> exited(control.string("error"))
                }
            }
        }
    }

    private fun onEnd(channel: Channel, end: RelayEnd) {
        if (channelOf(phase) !== channel) return
        when (end) {
            is RelayEnd.Exited -> exited(end.error)
            is RelayEnd.Dropped -> {
                leave(phase)
                transition(Phase.Idle(TerminalStatus.Reconnecting, gap = true))
                reconcile()
            }
            RelayEnd.Closed -> Unit
        }
    }

    private fun ready(opening: Phase.Opening) {
        opening.deadline.cancel()
        val ready = Phase.Ready(opening.channel, opening.size)
        transition(ready)
        while (queued.isNotEmpty()) transmit(ready.channel, queued.removeFirst())
        queuedBytes = 0
        if (currentSize() != ready.sent) sendResize(ready)
        ready.quiet = scope.launch {
            delay(QUIET_NOTICE_MS)
            ready.quiet = null
            engine.notice(QUIET_NOTICE)
            schedulePublish()
        }
    }

    /** Sends `client_executor_relay_close` and classifies the `exit` error (terminal.md, "Exit and close"). */
    private fun exited(error: String?) {
        leave(phase)
        when (val verdict = classifyExit(error, sessions.executor)) {
            is ExitVerdict.Final -> transition(Phase.Failed(verdict.failure))
            ExitVerdict.Retry -> if (viewers.isEmpty()) {
                transition(Phase.Idle(TerminalStatus.Exited(error), gap = false))
            } else {
                transition(
                    Phase.Backoff(
                        scope.launch {
                            delay(RETRY_DELAY_MS)
                            transition(Phase.Idle(TerminalStatus.Reconnecting, gap = false))
                            reconcile()
                        },
                    ),
                )
            }
        }
    }

    private fun output(bytes: ByteArray) {
        (phase as? Phase.Ready)?.let { ready ->
            ready.quiet?.cancel()
            ready.quiet = null
        }
        engine.feed(bytes)
        val replies = engine.takeReplies()
        if (replies.isNotEmpty()) input(replies)
        for (event in engine.takeEvents()) {
            when (event) {
                VtEvent.Bell -> mutableEvents.tryEmit(TerminalEvent.Bell)
                is VtEvent.ClipboardWrite -> mutableEvents.tryEmit(TerminalEvent.ClipboardWrite(event.text))
                is VtEvent.Title -> Unit
            }
        }
        schedulePublish()
    }

    /** One ordered byte queue: sent now when ready, held up to [MAX_QUEUED_BYTES] while a channel is coming. */
    private fun input(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        when (val current = phase) {
            is Phase.Ready -> transmit(current.channel, bytes)
            is Phase.Idle, is Phase.Opening, is Phase.Backoff ->
                if (queuedBytes + bytes.size > MAX_QUEUED_BYTES) {
                    mutableEvents.tryEmit(TerminalEvent.InputDropped)
                } else {
                    queued.addLast(bytes)
                    queuedBytes += bytes.size
                }
            is Phase.Failed, Phase.Unavailable, Phase.Closed -> Unit
        }
    }

    private fun transmit(channel: Channel, bytes: ByteArray) {
        if (bytes.size <= INPUT_CHUNK) {
            channel.stream.send(RelayPayload.Binary(bytes))
            return
        }
        for (start in bytes.indices step INPUT_CHUNK) {
            channel.stream.send(RelayPayload.Binary(bytes.copyOfRange(start, minOf(start + INPUT_CHUNK, bytes.size))))
        }
    }

    /** The driving viewer's size goes to the engine now and to the PTY at most once per [RESIZE_INTERVAL_MS], after ready. */
    private fun sizeChanged() {
        val size = currentSize()
        if (size.cols != engine.cols || size.rows != engine.rows) {
            engine.resize(size.cols, size.rows)
            schedulePublish()
        }
        val ready = phase as? Phase.Ready ?: return
        if (ready.resizing != null || ready.sent == size) return
        ready.resizing = scope.launch {
            delay(RESIZE_INTERVAL_MS)
            ready.resizing = null
            if (phase === ready && currentSize() != ready.sent) sendResize(ready)
        }
    }

    private fun sendResize(ready: Phase.Ready) {
        val size = currentSize()
        ready.sent = size
        val resize = buildJsonObject {
            put("type", "resize")
            put("cols", size.cols)
            put("rows", size.rows)
        }
        ready.channel.stream.send(RelayPayload.Text(resize.toString()))
    }

    private fun currentSize(): GridSize = (driver ?: viewers.lastOrNull())?.size ?: GridSize(engine.cols, engine.rows)

    private fun detach(viewer: Viewer) {
        if (!viewers.remove(viewer)) return
        if (driver === viewer) driver = viewers.lastOrNull()
        if (viewers.isNotEmpty()) return sizeChanged()
        syncHold()
        linger = scope.launch {
            delay(LINGER_MS)
            linger = null
            if (channelOf(phase) == null) return@launch
            leave(phase)
            transition(Phase.Idle(TerminalStatus.Reconnecting, gap = true))
        }
    }

    /** Stops [from]'s timers and closes its channel, if it has one. */
    private fun leave(from: Phase) {
        when (from) {
            is Phase.Opening -> from.deadline.cancel()
            is Phase.Ready -> {
                from.quiet?.cancel()
                from.resizing?.cancel()
            }
            is Phase.Backoff -> from.timer.cancel()
            is Phase.Idle, is Phase.Failed, Phase.Unavailable, Phase.Closed -> Unit
        }
        channelOf(from)?.let { channel ->
            channel.reader.cancel()
            channel.stream.close()
        }
    }

    private fun transition(next: Phase) {
        phase = next
        when (next) {
            is Phase.Idle -> mutableStatus.value = next.shown
            is Phase.Opening -> mutableStatus.value = next.shown
            is Phase.Ready -> mutableStatus.value = TerminalStatus.Ready
            is Phase.Backoff -> mutableStatus.value = TerminalStatus.Reconnecting
            is Phase.Failed -> mutableStatus.value = TerminalStatus.Failed(next.failure)
            Phase.Unavailable -> mutableStatus.value = TerminalStatus.Unavailable(UnavailableReason.NoExecutor)
            Phase.Closed -> Unit
        }
        syncHold()
    }

    /** The thread socket stays open while a channel is open or a viewer is attached (terminal.md, "Exit and close"). */
    private fun syncHold() {
        val wanted = phase != Phase.Closed && (viewers.isNotEmpty() || channelOf(phase) != null)
        if (wanted && held == null) held = sessions.hold()
        if (!wanted) {
            held?.close()
            held = null
        }
    }

    /** At most one snapshot per [FRAME_MS], so a burst of output costs one publish. */
    private fun schedulePublish() {
        if (publishing != null) return
        val wait = (lastPublishMs + FRAME_MS - nowMs()).coerceAtLeast(0)
        publishing = scope.launch {
            delay(wait)
            publishing = null
            lastPublishMs = nowMs()
            mutableScreen.value = engine.snapshot()
        }
    }

    private fun channelOf(phase: Phase): Channel? = when (phase) {
        is Phase.Opening -> phase.channel
        is Phase.Ready -> phase.channel
        else -> null
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    private inner class Viewer(var size: GridSize) : TerminalViewer {
        private val closed = AtomicBoolean(false)

        override fun resize(cols: Int, rows: Int) {
            require(cols > 0 && rows > 0) { "terminal size ${cols}x$rows" }
            scope.launch {
                if (this@Viewer !in viewers) return@launch
                size = GridSize(cols, rows)
                sizeChanged()
            }
        }

        override fun focused() {
            scope.launch {
                if (this@Viewer !in viewers) return@launch
                driver = this@Viewer
                sizeChanged()
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) scope.launch { detach(this@Viewer) }
        }
    }

    private companion object {
        const val SERVICE = "amp-terminal-v1"
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        const val INPUT_CHUNK = 49_152
        const val MAX_QUEUED_BYTES = 196_608
        const val READY_TIMEOUT_MS = 10_000L
        const val QUIET_NOTICE_MS = 15_000L
        const val QUIET_NOTICE = "[No terminal output received. The terminal connection may be unavailable.]"
        const val RETRY_DELAY_MS = 3_000L
        const val RESIZE_INTERVAL_MS = 100L
        const val LINGER_MS = 60_000L
        const val FRAME_MS = 16L
        const val MAX_FILE_BYTES = 536_870_912L
        const val STAGING_DIR = "/tmp/amp-terminal-files"
    }
}
