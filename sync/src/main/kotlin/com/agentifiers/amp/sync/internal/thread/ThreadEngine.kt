package com.agentifiers.amp.sync.internal.thread

import com.agentifiers.amp.model.AttachmentDraft
import com.agentifiers.amp.model.Command
import com.agentifiers.amp.model.DesktopTarget
import com.agentifiers.amp.model.Draft
import com.agentifiers.amp.model.EarlierHistory
import com.agentifiers.amp.model.ExecutorChoice
import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.Guard
import com.agentifiers.amp.model.IntentId
import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.OutboxEntry
import com.agentifiers.amp.model.OutboxState
import com.agentifiers.amp.model.PendingSend
import com.agentifiers.amp.model.SendMode
import com.agentifiers.amp.model.SendState
import com.agentifiers.amp.model.Seq
import com.agentifiers.amp.model.StopReason
import com.agentifiers.amp.model.TerminalSessionKey
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.TranscriptView
import com.agentifiers.amp.model.UploadState
import com.agentifiers.amp.model.UserId
import com.agentifiers.amp.store.AccountDatabase
import com.agentifiers.amp.store.Outbox
import com.agentifiers.amp.sync.api.Changes
import com.agentifiers.amp.sync.api.Machine
import com.agentifiers.amp.sync.api.Portals
import com.agentifiers.amp.sync.api.RelayChannelFactory
import com.agentifiers.amp.sync.api.TerminalSession
import com.agentifiers.amp.sync.api.ThreadHandle
import com.agentifiers.amp.sync.api.ViewerToken
import com.agentifiers.amp.sync.internal.attachments.AttachmentUploader
import com.agentifiers.amp.sync.internal.catalog.spawnParams
import com.agentifiers.amp.sync.internal.changes.ChangesEngine
import com.agentifiers.amp.sync.internal.changes.WORKSPACE_MAYBE_CHANGED
import com.agentifiers.amp.sync.internal.changes.changesWorkflowOf
import com.agentifiers.amp.sync.internal.files.FilesEngine
import com.agentifiers.amp.sync.internal.fleet.Organizer
import com.agentifiers.amp.sync.internal.gates.Capability
import com.agentifiers.amp.sync.internal.gates.GateDesk
import com.agentifiers.amp.sync.internal.gates.GateReply
import com.agentifiers.amp.sync.internal.gates.GateRequest
import com.agentifiers.amp.sync.internal.gates.GateWire
import com.agentifiers.amp.sync.internal.gates.line
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.NetLog
import com.agentifiers.amp.sync.internal.net.Outcome
import com.agentifiers.amp.sync.internal.net.SocketSignal
import com.agentifiers.amp.sync.internal.net.ThreadSocket
import com.agentifiers.amp.sync.internal.outbox.CommandQueue
import com.agentifiers.amp.sync.internal.outbox.QueuedCommand
import com.agentifiers.amp.sync.internal.outbox.Route
import com.agentifiers.amp.sync.internal.outbox.Settlement
import com.agentifiers.amp.sync.internal.outbox.echoOf
import com.agentifiers.amp.sync.internal.outbox.routeOf
import com.agentifiers.amp.sync.internal.portals.PortalsEngine
import com.agentifiers.amp.sync.internal.puck.voice.VoiceTap
import com.agentifiers.amp.sync.internal.relay.DesktopChannels
import com.agentifiers.amp.sync.internal.terminal.AttachmentUpload
import com.agentifiers.amp.sync.internal.terminal.RelayMux
import com.agentifiers.amp.sync.internal.terminal.TerminalSessions
import com.agentifiers.amp.sync.internal.wire.FrameParser
import com.agentifiers.amp.sync.internal.wire.InboundFrame
import com.agentifiers.amp.sync.internal.wire.RivetNumber
import com.agentifiers.amp.sync.internal.wire.RpcError
import com.agentifiers.amp.sync.internal.wire.ThreadFact
import com.agentifiers.amp.sync.internal.wire.WireDecoders
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.long
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import java.net.URI
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

internal class ThreadDeps(
    val client: OkHttpClient,
    val endpoint: Endpoint,
    val http: AmpHttp,
    val clientVersion: String,
    val log: NetLog,
    val onUnauthorized: () -> Unit,
    val database: AccountDatabase,
    val attachments: AttachmentUploader,
    val organizer: Organizer,
    /** The signed-in user, left out of presence. Null until the user actor answers once. */
    val self: () -> UserId?,
)

/** How a [ThreadEngine.call] settled. */
internal sealed interface Call {
    /** The `client_*_result` body. Its `ok` may still be false with an `error{code, message}` (s19-files.jsonl:73). */
    data class Done(val body: JsonObject) : Call

    /** The JSON-RPC response was an error, such as `-32602` for a ranged read (s19-files.jsonl:59). */
    data class Rejected(val error: RpcError) : Call

    /** No live socket within [ThreadEngine.LIVE_WAIT_MS]; nothing was sent. */
    data object NotLive : Call

    /** Sent, but the socket dropped or the result never came. The request may or may not have applied. */
    data object Lost : Call
}

private sealed interface Reply {
    data class Ok(val result: JsonObject?) : Reply
    data class Error(val error: RpcError) : Reply
    data object Lost : Reply
}

private sealed interface SessionEnd {
    data class Dropped(val cause: String, val wasLive: Boolean) : SessionEnd
    data class Stop(val reason: StopReason) : SessionEnd
    data object Unauthorized : SessionEnd
}

/** How a `client_spawn_executor` ended. Amp creates the thread first, so a failed spawn still leaves the thread. */
internal sealed interface Spawned {
    data object Ok : Spawned
    data class Failed(val reason: String) : Spawned
}


/**
 * One thread: paged history first, then the actor socket resumed from the history's version, folded by
 * [ThreadReducer]. All mutable state is confined to one single-threaded dispatcher, so frames, commands and
 * lease changes apply one at a time.
 */
internal class ThreadEngine(
    override val id: ThreadId,
    private val deps: ThreadDeps,
    private val scope: CoroutineScope,
    upload: AttachmentUpload = AttachmentUpload(deps.attachments::upload),
    private val wakeTimeoutMs: Long = WAKE_TIMEOUT_MS,
) : ThreadHandle {
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val mutableState = MutableStateFlow(ThreadState.initial(id))
    override val state: StateFlow<ThreadState> = mutableState.asStateFlow()

    private var fold: ThreadFold? = null
    private var historyError: String? = null
    /** A page in flight or the last page's failure; null defers to the fold's window. */
    private var earlier: EarlierHistory? = null
    private var link: Link = Link.Closed

    private val queue = CommandQueue(id, deps.database.outbox)
    private val mutableSettlements = MutableSharedFlow<Settlement>(extraBufferCapacity = 64)

    /** Every command that reached a final outcome, for code that shows a write before it lands. */
    val settlements: SharedFlow<Settlement> = mutableSettlements.asSharedFlow()

    /** A socket command was dispatched with no window showing the thread, so the engine holds the socket itself. */
    private var holding = false
    private val retryNow = Channel<Unit>(Channel.CONFLATED)

    /** Gate answers, plugin commands and other one-shot calls, kept in memory until they settle. */
    private var gateEntries: PersistentList<OutboxEntry> = persistentListOf()
    private var socket: ThreadSocket? = null
    @Volatile internal var voiceTap: VoiceTap? = null
    private val awaiting = HashMap<String, CompletableDeferred<Reply>>()
    private val results = HashMap<String, CompletableDeferred<Call>>()
    private var leases = 0
    /** Leases from STARTED windows. Only these wake a sleeping orb; a background hold never does. */
    private var viewers = 0
    private var wake: OrbWake = OrbWake.Idle
    private var waking: Job? = null
    private var connection: Job? = null
    private var closing: Job? = null
    internal val relay = RelayMux(deps.log)
    /** `meta.changesWorkflow` for the Ship and Push sends this engine made, kept across resends. */
    private val workflows = ConcurrentHashMap<MessageId, String>()
    private val terminals = TerminalSessions(id, relay, state, ::hold, upload, scope)
    private val desk = GateDesk()
    private val heldAnswers = ArrayList<HeldAnswer>()

    /** The Ship and Push sends this engine made, so the Changes pane can refuse a second Ship. */
    internal fun changesWorkflowIds(): Set<MessageId> = workflows.keys.toSet()
    private val workspaceSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** `executor_workspace_maybe_changed`: the Changes pane refreshes on it. */
    internal val workspaceChanged: SharedFlow<Unit> = workspaceSignals.asSharedFlow()

    override val machine: Machine = object : Machine {
        override val changes: Changes by lazy { ChangesEngine(this@ThreadEngine, deps, scope) }
        override fun terminal(session: TerminalSessionKey?): TerminalSession = terminals.session(session)
        override val files: FilesEngine = FilesEngine(this@ThreadEngine, scope)
        override val portals: Portals = PortalsEngine(id, deps.http, files, scope)
        override fun desktop(target: DesktopTarget): RelayChannelFactory = DesktopChannels(this@ThreadEngine, target)
    }

    /** Declared after every field it reads: it starts on another thread. */
    private val loaded: Job = scope.launch(confined) {
        queue.load()
        drain()
    }

    override fun attach(token: ViewerToken): AutoCloseable = lease(viewer = true)

    /** Keeps the socket open like a viewer lease: open relay channels and attached terminal viewers hold it. */
    internal fun hold(): AutoCloseable = lease(viewer = false)

    private fun lease(viewer: Boolean): AutoCloseable {
        scope.launch(confined) {
            acquire()
            if (viewer) {
                viewers++
                publish()
            }
        }
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) scope.launch(confined) {
                if (viewer) viewers--
                release()
            }
        }
    }

    /** Aborts the live socket as a network drop would, so reconnect and resume run. Debug builds only call it. */
    fun dropSocket() {
        scope.launch(confined) { socket?.abort("dropped by drop-connection") }
    }

    /** Connects now: opens the socket for a shown thread that has none, ends a reconnect backoff early, and retries a failed orb wake. */
    override fun connectLive() {
        scope.launch(confined) {
            if (leases == 0) return@launch
            if (wake is OrbWake.Failed) {
                wake = OrbWake.Idle
                if (link == Link.Live) sendWake()
                publish()
            }
            if (connection?.isActive != true) connection = scope.launch(confined) { run() } else retryNow.trySend(Unit)
        }
    }


    override fun loadEarlier() {
        scope.launch(confined) { fetchEarlier() }
    }

    /** `getThreadTranscriptPage{before, anchorID}` (S9): an internal RPC, so paging never opens a socket or wakes an orb. */
    private suspend fun fetchEarlier() {
        val window = fold?.window ?: return
        if (earlier == EarlierHistory.Loading || !window.hasMoreBefore) return
        earlier = EarlierHistory.Loading
        publish()
        val params = buildJsonObject {
            put("thread", id.raw)
            put("before", window.firstIndex)
            window.firstId?.let { put("anchorID", it.raw) }
        }
        earlier = when (val page = deps.http.internal("getThreadTranscriptPage", params)) {
            is Outcome.Ok -> {
                val body = page.value as? JsonObject
                if (body?.string("status") == "stale") reloadTail() else body?.let { fold?.prepend(it) }?.let { fold = it; null } ?: EarlierHistory.Failed("Amp sent a page this app cannot read.")
            }
            Outcome.Unauthorized -> return deps.onUnauthorized()
            is Outcome.Failed -> EarlierHistory.Failed(page.reason)
        }
        publish()
    }

    /** A stale answer means the thread changed under the loaded window, so the window restarts from the tail. */
    private suspend fun reloadTail(): EarlierHistory? =
        when (val page = deps.http.internal("getThreadInitialTranscriptPage", buildJsonObject { put("thread", id.raw) })) {
            is Outcome.Ok -> (page.value as? JsonObject)?.let(ThreadFold::fromHistoryPage)?.let { initial -> fold = fold?.reloadedFrom(initial); null }
                ?: EarlierHistory.Failed("Amp sent a page this app cannot read.")
            Outcome.Unauthorized -> null.also { deps.onUnauthorized() }
            is Outcome.Failed -> EarlierHistory.Failed(page.reason)
        }

    override fun dispatch(intent: ThreadIntent): IntentId {
        val id = IntentId.mint()
        when (intent) {
            is ThreadIntent.Send -> {
                if (intent.draft.attachments.any { it.upload !is UploadState.Ready }) {
                    scope.launch(confined) { settleGate(id, "Send", OutboxState.Rejected("Attachments are still uploading.")) }
                } else {
                    submit(id, intent)
                }
            }
            ThreadIntent.Interrupt, is ThreadIntent.EditMessage, is ThreadIntent.SteerQueued, is ThreadIntent.RemoveQueued,
            is ThreadIntent.ReorderQueue, ThreadIntent.Retry, ThreadIntent.DismissError, is ThreadIntent.React,
            ThreadIntent.RetryFailedSetup, ThreadIntent.RestartOrbProcesses, is ThreadIntent.SetNetRoute,
            is ThreadIntent.SetPortalHostname -> submit(id, intent)
            is ThreadIntent.Answer -> scope.launch(confined) {
                answer(HeldAnswer(id, intent.response.gate, intent.response.line) { GateWire.request(intent.response, it) })
            }
            is ThreadIntent.AnswerSecret -> scope.launch(confined) {
                answer(HeldAnswer(id, intent.response.gate, "Answered by you") { GateWire.request(intent.response, it) })
            }
            is ThreadIntent.RunPluginCommand -> scope.launch(confined) { runPluginCommand(id, intent) }
            is ThreadIntent.RunChangesAction -> {
                changesWorkflowOf(intent.action)?.let { workflows[intent.messageId] = it }
                submit(id, intent)
            }
            is ThreadIntent.Rename, is ThreadIntent.Pin, is ThreadIntent.Color, is ThreadIntent.SetLabels, is ThreadIntent.Snooze,
            is ThreadIntent.Archive, ThreadIntent.Delete, is ThreadIntent.MarkRead, ThreadIntent.MarkUnread, ThreadIntent.DismissRecap,
            is ThreadIntent.SetFeature, is ThreadIntent.SetVisibility, is ThreadIntent.OpenToCollaborators, is ThreadIntent.Comment,
            is ThreadIntent.ReportBug -> deps.organizer.dispatch(this.id, id, intent, ::held)
        }
        return id
    }

    override fun resend(intent: IntentId) {
        scope.launch(confined) {
            loaded.join()
            val item = queue.find(intent) ?: return@launch
            val settled = item.state is OutboxState.Rejected || item.state == OutboxState.Unknown || item.state is OutboxState.Dropped
            if (item.command == null || !settled) return@launch
            queue.set(intent, OutboxState.Queued)
            drain()
        }
    }

    override fun discard(intent: IntentId) {
        scope.launch(confined) {
            loaded.join()
            queue.remove(intent)
            gateEntries = gateEntries.removeAll { it.id == intent }
            releaseHoldIfIdle()
            publish()
        }
    }

    /**
     * Debug `seed-outbox`: stores [commands] as if dispatched earlier. With [inFlight] they were already sent when the
     * app lost their answers, so each follows its delivery class's lost-answer rule before draining.
     */
    suspend fun seed(commands: List<Command.Durable>, inFlight: Boolean): Int = withContext(confined) {
        loaded.join()
        commands.forEach { queue.submit(IntentId.mint(), it, if (inFlight) OutboxState.Sending(1) else OutboxState.Queued) }
        queue.recoverInFlight()
        if (link == Link.Live) drain() else publish()
        commands.size
    }

    /**
     * `client_spawn_executor` on this thread's socket, holding the socket while it runs (S4,
     * `captures/s04-live-thread.jsonl:30-31`). Amp answers `{ok:true}` and then streams `executor_status`; the
     * answer is the outcome.
     */
    suspend fun spawn(executor: ExecutorChoice): Spawned {
        val params = spawnParams(executor) ?: return Spawned.Ok
        withContext(confined) { acquire() }
        try {
            val connected = withTimeoutOrNull(SPAWN_CONNECT_MS) { state.first { it.link == Link.Live || it.link is Link.Stopped } }
            if (connected?.link != Link.Live) return Spawned.Failed("Couldn't connect to the new thread.")
            return withContext(confined) {
                when (val answer = await(request("client_spawn_executor", params))) {
                    is Reply.Ok -> refusalOf(answer.result)?.let(Spawned::Failed) ?: Spawned.Ok
                    is Reply.Error -> Spawned.Failed(answer.error.message)
                    Reply.Lost -> Spawned.Failed("Amp didn't answer the spawn request.")
                }
            }
        } finally {
            withContext(NonCancellable + confined) { release() }
        }
    }

    /**
     * One socket request for a write made while nobody views the thread: holds the socket like a lease, waits for it
     * to go live, sends, and releases. Null on success, else why it failed.
     */
    private suspend fun held(method: String, params: JsonObject): String? {
        withContext(confined) {
            leases++
            closing?.cancel()
            if (connection?.isActive != true) connection = scope.launch(confined) { run() }
        }
        try {
            val ready = withTimeoutOrNull(HOLD_TIMEOUT_MS) { state.first { it.link == Link.Live || it.link is Link.Stopped } }
            val link = ready?.link
            if (link is Link.Stopped) return link.reason.explanation()
            if (link != Link.Live) return "Could not connect to the thread."
            return withContext(confined) {
                when (val answer = await(request(method, params))) {
                    is Reply.Ok -> null
                    is Reply.Error -> answer.error.message
                    Reply.Lost -> "The thread did not answer."
                }
            }
        } finally {
            withContext(NonCancellable + confined) { release() }
        }
    }

    private fun acquire() {
        leases++
        closing?.cancel()
        if (connection?.isActive != true) connection = scope.launch(confined) { run() }
    }

    private fun release() {
        leases--
        if (leases > 0) return
        closing = scope.launch(confined) {
            delay(SOCKET_GRACE_MS)
            connection?.cancel()
            connection = null
            if (link !is Link.Stopped) link = Link.Closed
            waking?.cancel()
            wake = OrbWake.Idle
            publish()
        }
    }

    private suspend fun run() {
        if (deps.endpoint.rivetPublishableToken.isBlank()) return stop(StopReason.MissingRivetToken)
        var attempt = 0
        while (fold == null) {
            when (val page = deps.http.internal("getThreadInitialTranscriptPage", buildJsonObject { put("thread", id.raw) })) {
                is Outcome.Ok -> {
                    historyError = null
                    fold = (page.value as? JsonObject)?.let(ThreadFold::fromHistoryPage) ?: unreadablePage()
                }
                Outcome.Unauthorized -> return deps.onUnauthorized()
                is Outcome.Failed -> historyError = page.reason
            }
            publish()
            if (fold == null) backoff(++attempt)
        }
        attempt = 0
        while (true) {
            when (val end = session()) {
                is SessionEnd.Stop -> return stop(end.reason)
                SessionEnd.Unauthorized -> return deps.onUnauthorized()
                is SessionEnd.Dropped -> {
                    attempt = if (end.wasLive) 1 else attempt + 1
                    link = Link.Reconnecting(attempt, end.cause)
                    publish()
                    backoff(attempt)
                }
            }
        }
    }

    /** A stopped thread never connects, so socket commands waiting for it are refused with the reason. */
    private suspend fun stop(reason: StopReason) {
        link = Link.Stopped(reason)
        queue.items.filter { it.isWaiting() && it.route() is Route.ThreadSocket }
            .forEach { settle(it, OutboxState.Rejected(reason.explanation())) }
        releaseHoldIfIdle()
        publish()
    }

    /** Waits out the backoff, or less when [connectLive] asks to retry now. */
    private suspend fun backoff(attempt: Int) {
        withTimeoutOrNull(backoffMs(attempt)) { retryNow.receive() }
    }

    private suspend fun session(): SessionEnd {
        link = Link.Connecting
        publish()
        val grant = when (val result = deps.http.threadGrant(id)) {
            is Outcome.Ok -> result.value
            Outcome.Unauthorized -> return SessionEnd.Unauthorized
            is Outcome.Failed -> return if (result.status == 409) SessionEnd.Stop(StopReason.Archived) else SessionEnd.Dropped(result.reason, false)
        }
        desk.viewer = Capability.ofWsToken(grant.wsToken)
        val opened = ThreadSocket.open(deps.client, deps.endpoint, id, grant.wsToken, deps.clientVersion)
        socket = opened
        var resumeDeadline: Job? = null
        try {
            link = Link.Resuming
            publish()
            val resumeId = requestOn(opened, "client_resume", buildJsonObject { put("version", RivetNumber.encode(cursor().value)) })
                ?: return SessionEnd.Dropped("send failed", false)
            resumeDeadline = scope.launch(confined) {
                delay(REQUEST_TIMEOUT_MS)
                opened.abort("resume timed out")
            }
            for (signal in opened.signals) {
                when (signal) {
                    is SocketSignal.Ended -> return SessionEnd.Dropped(signal.cause, link == Link.Live)
                    is SocketSignal.Binary -> {
                        relay.onBinary(signal.bytes)
                        continue
                    }
                    is SocketSignal.Text -> for (frame in FrameParser.parse(signal.text)) {
                        if (frame is InboundFrame.Response && frame.id == resumeId) {
                            if (frame.error != null) return SessionEnd.Dropped(frame.error.message, false)
                            resumeDeadline.cancel()
                            onResumed(frame.result)
                            relay.connected(opened)
                        } else {
                            onFrame(frame)
                        }
                    }
                }
                publish()
            }
            return SessionEnd.Dropped("socket closed", link == Link.Live)
        } finally {
            resumeDeadline?.cancel()
            relay.disconnected("thread socket ended")
            opened.close()
            socket = null
            voiceTap?.onSocketEnded()
            awaiting.values.forEach { it.complete(Reply.Lost) }
            awaiting.clear()
            results.values.forEach { it.complete(Call.Lost) }
            results.clear()
            desk.socketEnded()
            fold = fold?.endLive()
            withContext(NonCancellable) {
                queue.items.filter { it.state is OutboxState.Sending && it.command?.let(::echoOf) != null }
                    .forEach { queue.set(it.id, OutboxState.Queued) }
            }
            publish()
        }
    }

    /** The resume response comes after the replay and the connect burst, so the fold is caught up. */
    private suspend fun onResumed(result: JsonObject?) {
        link = Link.Live
        result?.long("replayThroughSeq")?.let { through -> fold = fold?.let { it.copy(cursor = maxOf(it.cursor, Seq(through))) } }
        drain()
        val held = heldAnswers.toList()
        heldAnswers.clear()
        held.forEach { scope.launch(confined) { answer(it) } }
    }

    private suspend fun onFrame(frame: InboundFrame) {
        when (frame) {
            is InboundFrame.Event -> if (voiceTap?.onEvent(frame) != true && !relay.onEvent(frame.type, frame.body)) {
                deps.log.frame('<', frame.type, frame.seq?.let { "seq=${it.value}" }.orEmpty())
                apply(WireDecoders.decode(frame))
                if (frame.type == WORKSPACE_MAYBE_CHANGED) workspaceSignals.tryEmit(Unit)
                terminals.onEvent(frame.type, frame.body)
            }
            is InboundFrame.Response -> awaiting.remove(frame.id)?.complete(frame.error?.let(Reply::Error) ?: Reply.Ok(frame.result))
            is InboundFrame.Result -> {
                deps.log.frame('<', frame.type)
                results.remove(frame.requestId)?.complete(Call.Done(frame.body))
            }
            InboundFrame.Pong -> Unit
            is InboundFrame.Unparseable -> apply(ThreadFact.Malformed("unparseable"))
        }
    }

    private suspend fun apply(fact: ThreadFact) {
        val before = fold
        fold = before?.let { ThreadReducer.reduce(it, fact) }
        if (fact is ThreadFact.Gate && before != null) desk.observe(fact.fact, before.gates, fold!!.gates)
        val echo = ThreadReducer.acknowledged(fact) ?: return
        queue.acknowledge(echo).forEach { item -> item.command?.let { mutableSettlements.tryEmit(Settlement(id, item.id, it, null)) } }
        releaseHoldIfIdle()
    }

    private fun submit(intentId: IntentId, intent: ThreadIntent) {
        scope.launch(confined) {
            loaded.join()
            val earlier = (intent as? ThreadIntent.Send)?.let { queue.findByEcho(it.messageId) }
            if (earlier != null) {
                queue.set(earlier.id, OutboxState.Queued)
            } else {
                queue.submit(intentId, intent)
            }
            drain()
        }
    }

    /**
     * Delivers every queued command in dispatch order. Internal RPC needs no socket. Socket commands wait for a live
     * socket, and the engine holds one open for them when no window does. Guards are read only while live, which
     * is after `client_resume` has folded the replay and the connect burst.
     */
    private suspend fun drain() {
        for (item in queue.items) {
            if (!item.isWaiting()) continue
            val command = item.command ?: continue
            when (val route = item.route()) {
                null -> settle(item, OutboxState.Rejected("This version of the app can't send ${CommandQueue.previewOf(command)} yet."))
                is Route.Internal -> sendInternal(item, command, route)
                is Route.ThreadSocket -> {
                    val live = link == Link.Live && socket != null
                    when {
                        live -> {
                            val guard = (command as? Command.Guarded)?.guard ?: (command as? Command.Ephemeral)?.guard
                            val current = fold
                            if (guard != null && current != null && !holds(guard, current)) settle(item, OutboxState.Dropped(guard)) else transmit(item, command, route)
                        }
                        command == ThreadIntent.Interrupt -> settle(item, OutboxState.Rejected("Interrupt failed: Not connected to the thread."))
                        link is Link.Stopped -> settle(item, OutboxState.Rejected((link as Link.Stopped).reason.explanation()))
                        else -> {
                            if (item.state !is OutboxState.WaitingForNetwork) queue.set(item.id, OutboxState.WaitingForNetwork(Clock.System.now()))
                            if (leases == 0 && !holding) {
                                holding = true
                                acquire()
                            }
                        }
                    }
                }
            }
        }
        releaseHoldIfIdle()
        publish()
    }

    private suspend fun transmit(item: QueuedCommand, command: Command, route: Route.ThreadSocket) {
        queue.set(item.id, OutboxState.Sending(item.attempt() + 1))
        val reply = request(route.method, route.params)
        scope.launch(confined) {
            val answer = await(reply)
            val current = queue.find(item.id) ?: return@launch
            when (answer) {
                is Reply.Ok -> {
                    val refusal = refusalOf(answer.result)
                    when {
                        refusal != null -> settle(current, OutboxState.Rejected(refusal))
                        echoOf(command) == null -> settle(current, null)
                    }
                }
                is Reply.Error -> settle(current, OutboxState.Rejected(answer.error.message))
                Reply.Lost -> lost(current, command)
            }
            releaseHoldIfIdle()
            publish()
        }
    }

    private suspend fun sendInternal(item: QueuedCommand, command: Command, route: Route.Internal) {
        queue.set(item.id, OutboxState.Sending(item.attempt() + 1))
        scope.launch(confined) {
            val current = queue.find(item.id) ?: return@launch
            when (val result = deps.http.internal(route.method, route.params)) {
                is Outcome.Ok -> settle(current, null)
                Outcome.Unauthorized -> {
                    queue.set(current.id, OutboxState.Queued)
                    deps.onUnauthorized()
                }
                is Outcome.Failed -> when {
                    !result.reached -> retryLater(current)
                    result.status == null -> lost(current, command)
                    else -> settle(current, OutboxState.Rejected(result.reason))
                }
            }
            publish()
        }
    }

    /** The answer never came: Replayable and Guarded commands go again, an AtMostOnce one becomes Unknown. */
    private suspend fun lost(item: QueuedCommand, command: Command) {
        val next = CommandQueue.afterLostAnswer(command)
        if (next != OutboxState.Queued) return settle(item, next)
        if (item.route() is Route.Internal) return retryLater(item)
        queue.set(item.id, next)
        if (link == Link.Live && socket != null) drain()
    }

    /** Internal RPC has no socket to wait for, so it waits for the network on a backoff timer. */
    private suspend fun retryLater(item: QueuedCommand) {
        val attempt = item.attempt()
        queue.set(item.id, OutboxState.WaitingForNetwork(Clock.System.now()))
        scope.launch(confined) {
            delay(backoffMs(attempt))
            drain()
        }
    }

    /** The gate path settles in memory: in-flight entries stay, and only the latest outcome is kept for the notice. */
    private fun settleGate(id: IntentId, preview: String, state: OutboxState) {
        val inFlight = gateEntries.filter { it.id != id && (it.state is OutboxState.Sending || it.state is OutboxState.WaitingForNetwork) }
        gateEntries = (inFlight + OutboxEntry(id, preview, state)).toPersistentList()
        publish()
    }

    private suspend fun settle(item: QueuedCommand, result: OutboxState?) {
        if (result == null) queue.remove(item.id) else queue.set(item.id, result)
        item.command?.let { mutableSettlements.tryEmit(Settlement(id, item.id, it, result)) }
    }

    private fun releaseHoldIfIdle() {
        if (!holding) return
        val busy = queue.items.any { it.route() is Route.ThreadSocket && (it.isWaiting() || it.state is OutboxState.Sending) }
        if (busy) return
        holding = false
        release()
    }

    private fun QueuedCommand.isWaiting() = state == OutboxState.Queued || state is OutboxState.WaitingForNetwork

    private fun QueuedCommand.attempt() = (state as? OutboxState.Sending)?.attempt ?: 0

    private fun QueuedCommand.route(): Route? = command?.let { routeOf(it, this@ThreadEngine.id, fold?.agentMode ?: DEFAULT_AGENT_MODE) }

    /** Amp also refuses with `{ok:false, error}` inside a successful JSON-RPC result. */
    private fun refusalOf(result: JsonObject?): String? {
        if (result?.bool("ok") != false) return null
        val error = result.obj("error")
        return error?.string("message") ?: error?.string("code") ?: "Amp refused it."
    }

    /** A JSON-RPC request on the live socket for a side channel (Puck voice, desktop). */
    internal suspend fun requestLive(method: String, params: JsonObject): Outcome<JsonObject?> = withContext(confined) {
        if (link != Link.Live) return@withContext Outcome.Failed(null, "Not connected to the thread.")
        when (val answer = await(request(method, params))) {
            is Reply.Ok -> Outcome.Ok(answer.result)
            is Reply.Error -> Outcome.Failed(null, answer.error.message)
            Reply.Lost -> Outcome.Failed(null, "The thread connection was lost.")
        }
    }

    /** A JSON-RPC notification on the live socket, in call order; dropped while no socket is live. */
    internal fun notifyLive(method: String, params: JsonObject) {
        scope.launch(confined) { if (link == Link.Live) socket?.notify(method, params) }
    }

    private fun request(method: String, params: JsonObject): CompletableDeferred<Reply> {
        val reply = CompletableDeferred<Reply>()
        val id = socket?.let { requestOn(it, method, params) }
        if (id == null) reply.complete(Reply.Lost) else awaiting[id] = reply
        return reply
    }

    private fun requestOn(target: ThreadSocket, method: String, params: JsonObject): String? {
        deps.log.frame('>', method)
        return target.request(method, params)
    }

    /** Requests without a `requestId` settle on their response, or as lost after the web's 120 s timeout. */
    private suspend fun await(reply: CompletableDeferred<Reply>): Reply =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) { reply.await() } ?: Reply.Lost.also { awaiting.values.remove(reply) }

    /**
     * A request that carries a `requestId` (client_filesystem_*, client_git_*). Its JSON-RPC response only
     * acknowledges receipt; the data arrives in the `client_*_result` notification with the same `requestId`
     * (s19-files.jsonl:34-36). Waits up to [LIVE_WAIT_MS] for a live socket, then sends once. Never retried.
     */
    internal suspend fun call(method: String, params: JsonObject): Call {
        withTimeoutOrNull(LIVE_WAIT_MS) { state.first { it.link == Link.Live } } ?: return Call.NotLive
        val requestId = UUID.randomUUID().toString()
        return withContext(confined) {
            if (link != Link.Live) return@withContext Call.NotLive
            val result = CompletableDeferred<Call>()
            results[requestId] = result
            val ack = request(method, JsonObject(params + ("requestId" to JsonPrimitive(requestId))))
            val watcher = scope.launch(confined) {
                when (val answer = await(ack)) {
                    is Reply.Error -> result.complete(Call.Rejected(answer.error))
                    Reply.Lost -> result.complete(Call.Lost)
                    is Reply.Ok -> Unit
                }
            }
            try {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) { result.await() } ?: Call.Lost
            } finally {
                watcher.cancel()
                results.remove(requestId)
            }
        }
    }

    private fun cursor(): Seq = fold?.cursor ?: Seq.Zero

    /** Resuming at version 0 replays the whole history (S9), so an unreadable page costs only a longer replay. */
    private fun unreadablePage(): ThreadFold = ThreadReducer.reduce(ThreadFold.empty(), ThreadFact.Malformed("getThreadInitialTranscriptPage"))

    private fun publish() {
        fold?.let { steerWake(it.executor) }
        val pending = queue.pending()
        val outbox = queue.entries().addAll(gateEntries)
        mutableState.value = fold?.let { desk.decorate(project(id, it, link, pending, outbox, earlier, deps.self(), wake), it.gates) } ?: ThreadState.initial(id).copy(
            transcript = historyError?.let(TranscriptView::Failed) ?: TranscriptView.Loading,
            pending = pending,
            outbox = outbox,
            link = link,
        )
    }

    /**
     * One wake per sleep while a STARTED window shows the thread, as the web's open thread page does: the web's
     * `client_spawn_executor{executorDemand:"optional"}` (`987da778.js` `pane-refresh`). It ends when the executor
     * connects, fails on a refusal or an `executor_status` failure for its `spawnId`, and times out after the web's
     * 120 s (`Gv=12e4`). A reconnect keeps it; only Retry or a fresh open after the socket closed sends another.
     */
    private fun steerWake(executor: ExecutorFacts) {
        val current = wake
        when {
            executor.connected -> if (current != OrbWake.Idle) {
                waking?.cancel()
                wake = OrbWake.Idle
            }
            current is OrbWake.Sent -> if (executor.status == "failed" && executor.spawnId == current.requestId) {
                waking?.cancel()
                wake = OrbWake.Failed(executor.message ?: WAKE_REFUSED)
            }
            current == OrbWake.Idle && viewers > 0 && link == Link.Live && executor.orbAsleep -> sendWake()
        }
    }

    private fun sendWake() {
        val requestId = "spawn_${UUID.randomUUID()}"
        wake = OrbWake.Sent(requestId)
        val reply = request("client_spawn_executor", buildJsonObject {
            put("requestId", requestId)
            put("executorDemand", "optional")
        })
        waking = scope.launch(confined) {
            val refusal = withTimeoutOrNull(wakeTimeoutMs) {
                when (val answer = reply.await()) {
                    is Reply.Ok -> refusalOf(answer.result)?.let { return@withTimeoutOrNull it }
                    is Reply.Error -> return@withTimeoutOrNull answer.error.message
                    Reply.Lost -> Unit
                }
                awaitCancellation()
            }
            if (wake != OrbWake.Sent(requestId)) return@launch
            wake = OrbWake.Failed(refusal ?: WAKE_TIMED_OUT)
            publish()
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val base = minOf(BACKOFF_MAX_MS, BACKOFF_BASE_MS shl minOf(maxOf(attempt, 1) - 1, 4))
        return (base * Random.nextDouble(0.8, 1.2)).toLong()
    }

    private fun StopReason.explanation() = when (this) {
        StopReason.Archived -> "This thread is archived."
        StopReason.MissingRivetToken -> "This build has no Rivet token."
    }

    /** An answer waiting for its gate check and a live socket. Memory only, so a secret never outlives the process. */
    private class HeldAnswer(val id: IntentId, val gate: GateId, val line: String, val build: (attempt: Int) -> GateRequest)

    /** Guarded: before every attempt the gate must still be pending in the caught-up fold, else it drops as answered elsewhere. */
    private suspend fun answer(held: HeldAnswer) {
        if (fold?.gates?.contains(held.gate) != true) return settleGate(held.id, ANSWER, OutboxState.Dropped(Guard.GatePending(held.gate)))
        if (link != Link.Live) {
            heldAnswers += held
            return settleGate(held.id, ANSWER, OutboxState.WaitingForNetwork(Clock.System.now()))
        }
        settleGate(held.id, ANSWER, OutboxState.Sending(attempt = 1))
        desk.sending(held.gate, held.line)
        when (val reply = gateCall(held.build(desk.nextAttempt()), held.gate, REQUEST_TIMEOUT_MS)) {
            GateReply.Accepted -> {
                gateEntries = gateEntries.removeAll { it.id == held.id }
                publish()
            }
            is GateReply.Refused -> {
                desk.refused(held.gate)
                settleGate(held.id, ANSWER, OutboxState.Rejected("Amp didn't take the answer: ${reply.reason}"))
            }
            GateReply.Lost -> {
                desk.unsent(held.gate)
                heldAnswers += held
                settleGate(held.id, ANSWER, OutboxState.WaitingForNetwork(Clock.System.now()))
            }
        }
    }

    /** AtMostOnce: running a command twice runs it twice, so a lost reply waits for the user. The reply comes after the command's dialogs (S15b :74). */
    private suspend fun runPluginCommand(id: IntentId, intent: ThreadIntent.RunPluginCommand) {
        if (link != Link.Live) return settleGate(id, intent.commandId, OutboxState.Rejected("${intent.commandId} didn't run: not connected to the thread."))
        settleGate(id, intent.commandId, OutboxState.Sending(attempt = 1))
        val request = GateWire.runCommand(this.id, intent.plugin, intent.commandId, "command-${UUID.randomUUID()}")
        when (val reply = gateCall(request, gate = null, timeoutMs = null)) {
            GateReply.Accepted -> {
                gateEntries = gateEntries.removeAll { it.id == id }
                publish()
            }
            is GateReply.Refused -> settleGate(id, intent.commandId, OutboxState.Rejected("${intent.commandId} failed: ${reply.reason}"))
            GateReply.Lost -> settleGate(id, intent.commandId, OutboxState.Unknown)
        }
    }

    private suspend fun gateCall(request: GateRequest, gate: GateId?, timeoutMs: Long?): GateReply = when (request) {
        is GateRequest.Socket -> when (val reply = await(request(request.method, request.params))) {
            is Reply.Ok -> GateReply.Accepted
            is Reply.Error -> GateReply.Refused(reply.error.message)
            Reply.Lost -> GateReply.Lost
        }
        is GateRequest.Plugin -> {
            val reply = desk.expectReply(request.replyId, gate)
            deps.log.frame('>', request.method)
            if (socket?.notify(request.method, request.params) != true) {
                desk.forget(request.replyId)
                GateReply.Lost
            } else {
                (if (timeoutMs == null) reply.await() else withTimeoutOrNull(timeoutMs) { reply.await() })
                    ?: GateReply.Lost.also { desk.forget(request.replyId) }
            }
        }
        is GateRequest.Internal -> when (val result = deps.http.internal(request.method, request.params)) {
            is Outcome.Ok -> GateReply.Accepted
            Outcome.Unauthorized -> GateReply.Refused("signed out").also { deps.onUnauthorized() }
            is Outcome.Failed -> GateReply.Refused(result.reason)
        }
    }

    private companion object {
        const val SOCKET_GRACE_MS = 30_000L
        const val REQUEST_TIMEOUT_MS = 120_000L
        const val HOLD_TIMEOUT_MS = 30_000L
        /** How long a machine request waits for the thread socket to come live before it gives up unsent. */
        const val LIVE_WAIT_MS = 30_000L
        const val SPAWN_CONNECT_MS = 30_000L
        const val WAKE_TIMEOUT_MS = 120_000L
        const val WAKE_TIMED_OUT = "The orb did not come back online within 2 minutes."
        const val WAKE_REFUSED = "Amp could not wake the orb."
        const val BACKOFF_BASE_MS = 3_000L
        const val BACKOFF_MAX_MS = 30_000L
        /** Only used if the history page omits the thread's mode. */
        const val DEFAULT_AGENT_MODE = "smart"
        const val ANSWER = "Answer"
    }
}
