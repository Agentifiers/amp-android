package com.agentifiers.amp.fake

import com.agentifiers.amp.fake.scenarios.Workspace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.WebSocket
import okio.ByteString
import java.time.Instant
import java.util.concurrent.ScheduledFuture

/** What the thread's agent is doing. Each state holds exactly what the next transition needs. */
private sealed interface Activity {
    data object Idle : Activity

    /** [next] is the index of the step to play; [ids] maps captured ids to the ids this run uses. */
    class Playing(val turn: Turn, val next: Int, val ids: Map<String, String>, val timer: ScheduledFuture<*>?) : Activity

    class Gated(val turn: Turn, val next: Int, val ids: Map<String, String>, val toolCallId: String) : Activity

    data object Parked : Activity
}

private class QueuedSend(val params: JsonObject, val steer: Boolean) {
    val messageId: String = params.string("messageId")!!
}

/**
 * One fake thread actor. It keeps the thread's event log, its connect burst, its queue, and the agent's
 * [Activity], and plays scripted turns against them. Every method runs on the [World] loop.
 */
internal class ThreadActor(val seed: FakeThread, private val world: World) {
    val id: String get() = seed.id
    private val sockets = LinkedHashSet<WebSocket>()
    private val log = ArrayList<Event>()
    private var greeting: Event? = seed.script.greeting
    private val burst = ArrayList(seed.script.burst)
    private val queue = ArrayList<QueuedSend>()
    private val exchanges = ExchangeBook(seed.script.exchanges)
    private val plugins = seed.plugin?.let { PluginDialogs(it, this, world) }
    private val terminals = Terminals(this, world)
    private val desktops = Desktops(this, world)
    private val voice = seed.voice?.let { VoiceCalls(it, this, world) }
    private var activity: Activity = Activity.Idle
    private var turnsStarted = 0
    private var lastAssistantId: String? = null
    private var pendingTool: String? = null
    private var lastAssistantText: String? = null
    private var agentMode = "low"

    var lastSeq: Long = seed.script.version
        private set
    var title: String? = seed.listing.string("title")
        private set
    var archived: Boolean = seed.listing.bool("archived") == true
    var pinned: Boolean = seed.listing.bool("pinned") == true

    init {
        log += seed.script.history
        seed.script.burst.lastOrNull { it.event.type == "thread_title" }?.event?.body?.string("title")?.let { title = it }
    }

    /** Every `message_added` in the log, latest version per `messageId`, in first-seen order. */
    fun messages(): List<JsonObject> {
        val byId = LinkedHashMap<String, JsonObject>()
        for (event in log) {
            if (event.type != "message_added") continue
            val message = event.body.obj("message") ?: continue
            byId[message.string("messageId") ?: continue] = message
        }
        return byId.values.toList()
    }

    // ── sockets ───────────────────────────────────────────────────────────────

    /** A client outside this fake, such as an ampcode.com tab, has the thread open. */
    var openElsewhere: Boolean = false

    val socketCount: Int get() = sockets.size

    /** Amp sends no push for a thread while any client has it open (S23, S23b). */
    val heldOpen: Boolean get() = sockets.isNotEmpty() || openElsewhere

    fun attach(socket: WebSocket) {
        sockets += socket
    }

    fun detach(socket: WebSocket) {
        sockets -= socket
        terminals.drop(socket)
        desktops.drop(socket)
        voice?.drop(socket)
    }

    fun receive(socket: WebSocket, text: String) {
        val frame = runCatching { FakeJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        val method = frame.string("method") ?: return
        val id = frame.string("id")
        val params = frame.obj("params") ?: JsonObject(emptyMap())
        when (method) {
            "client_resume" -> resume(socket, id, params.long("version") ?: 0)
            "client_append_user_msg" -> respond(socket, id, append(params))
            "client_cancel" -> {
                cancel()
                respond(socket, id, OK)
            }
            "client_tool_approval_response" -> {
                approve(params.string("toolCallId"), params.bool("accepted") == true)
                respond(socket, id, OK)
            }
            "client_set_thread_title" -> {
                params.string("title")?.let { emit(Event("thread_title", json("title" to it, "source" to "manual"))) }
                respond(socket, id, OK)
            }
            "client_upsert_notification_subscription" -> {
                world.push.subscribe(this, params)
                respond(socket, id, OK)
            }
            "client_remove_queued_msg" -> {
                removeQueued(params.string("queuedMessageId"))
                respond(socket, id, OK)
            }
            "client_steer_queued_msg" -> {
                steerQueued(params.string("queuedMessageId"))
                respond(socket, id, OK)
            }
            "client_reorder_queued_msgs" -> {
                reorderQueue(params.array("queuedMessageIds").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content })
                respond(socket, id, OK)
            }
            "client_spawn_executor" -> spawn(socket, id, params)
            "client_mark_thread_unread", "client_mark_message_read" -> {
                if (method == "client_mark_message_read") world.readMarks += ReadMark(this.id, params.string("messageId"))
                world.statusChanged(this, "hasUnreadMessages" to JsonPrimitive(method == "client_mark_thread_unread"))
                respond(socket, id, OK)
            }
            "client_set_thread_open" -> {
                openFor(params.long("ttlSeconds"))
                respond(socket, id, OK)
            }
            "client_update_thread_feature", "client_dismiss_recap", "client_add_thread_comment" -> respond(socket, id, OK)
            "executor_plugin_message" -> plugins?.receive(params.obj("message")) ?: replay(socket, id, method, params)
            "client_executor_relay_open", "client_executor_relay_data", "client_executor_relay_close" ->
                if (desktops.claims(method, params)) desktops.control(socket, method, params) else terminals.control(socket, method, params)
            "client_puck_voice_start", "client_puck_voice_audio", "client_puck_voice_stop" -> voice?.receive(socket, id, method, params) ?: replay(socket, id, method, params)
            else -> seed.workspace?.answer(method, params)?.let { filesystem(socket, id, params.string("requestId"), it) }
                ?: replay(socket, id, method, params)
        }
    }

    /** `thread_open_state` is a snapshot, so the next connect burst repeats the newest one. */
    private fun openFor(ttlSeconds: Long?) {
        val previous = burst.lastOrNull { it.event.type == "thread_open_state" }?.event?.body?.long("openStateSeq") ?: 0
        val expires = ttlSeconds?.let { Instant.now().plusSeconds(it).toString() }
        emit(Event("thread_open_state", json("openExpiresAt" to expires, "openStateSeq" to previous + 1)))
    }

    fun receive(socket: WebSocket, bytes: ByteString) {
        if (!desktops.binary(bytes)) terminals.binary(socket, bytes)
    }

    fun send(socket: WebSocket, frame: JsonObject) {
        socket.send(world.served(frame))
    }

    fun sendBinary(socket: WebSocket, bytes: ByteString) {
        socket.send(bytes)
    }

    private fun respond(socket: WebSocket, id: String?, result: JsonObject) {
        if (id != null) send(socket, json("jsonrpc" to "2.0", "id" to id, "result" to result))
    }

    /** The receipt first, then the `*_result` to the requesting socket only (`captures/s19-files.jsonl:34-36`). */
    private fun filesystem(socket: WebSocket, id: String?, requestId: String?, answer: Workspace.Answer) {
        when (answer) {
            Workspace.Answer.InvalidParams ->
                if (id != null) send(socket, json("jsonrpc" to "2.0", "id" to id, "error" to INVALID_PARAMS))
            is Workspace.Answer.Result -> {
                respond(socket, id, OK)
                send(socket, Event(answer.type, json("requestId" to requestId) + answer.body).live())
            }
        }
    }

    /**
     * `client_resume{version}`: pong, observers, Sequenced events after [version] as bare frames, the burst, then the
     * answer. The burst re-sends the standing `error_set` even when nothing is replayed, as Amp does
     * (`s17-resume-cursor.jsonl:145-169`), so a client that resumes at the grant's `threadVersion` still learns the error.
     */
    private fun resume(socket: WebSocket, id: String?, version: Long) {
        socket.send("pong")
        greeting?.let { send(socket, it.live()) }
        val replayed = log.filter { (it.seq ?: 0) > version }
        replayed.forEach { send(socket, it.bare()) }
        burst.forEach { send(socket, it.render()) }
        log.lastOrNull { it.type == "error_set" || it.type == "error_cleared" }?.takeIf { it.type == "error_set" }?.let { send(socket, it.bare()) }
        respond(
            socket, id,
            json("replayMessageCount" to replayed.size, "storedEventCount" to log.size, "replayThroughSeq" to lastSeq),
        )
    }

    /** A recorded answer for this request, or a plain receipt; a request with `requestId` also gets a `*_result`. */
    private fun replay(socket: WebSocket, id: String?, method: String, params: JsonObject) {
        if (!ParamShapes.accepts(method, params)) {
            if (id != null) send(socket, json("jsonrpc" to "2.0", "id" to id, "error" to INVALID_PARAMS))
            return
        }
        val requestId = params.string("requestId")
        val exchange = exchanges.find(method, params - "requestId", byMethodAlone = requestId == null)
        if (exchange == null) {
            respond(socket, id, OK)
            if (requestId != null) {
                val result = method.replace(Regex("_v\\d+$"), "") + "_result"
                val error = json("code" to "NOT_RECORDED", "message" to "fake-amp has no recording of $method with these params")
                send(socket, Event(result, json("requestId" to requestId, "ok" to false, "error" to error)).live())
            }
            return
        }
        for (reply in exchange.replies) when (reply) {
            is Reply.Response -> respond(socket, id, reply.result)
            is Reply.Failure -> if (id != null) send(socket, json("jsonrpc" to "2.0", "id" to id, "error" to reply.error))
            is Reply.Notify ->
                if (requestId != null && reply.event.body.string("requestId") != null) {
                    send(socket, Event(reply.event.type, reply.event.body + mapOf("requestId" to JsonPrimitive(requestId))).live())
                } else {
                    emit(reply.event)
                }
        }
    }

    // ── events ────────────────────────────────────────────────────────────────

    /** Stamps Sequenced events with the next `seq`, logs them, updates the burst, and sends to every socket. */
    fun emit(event: Event) {
        var e = event
        if (e.type in SEQUENCED) {
            val seq = maxOf(lastSeq + 1, e.seq ?: 0)
            e = e.withSeq(seq)
            lastSeq = seq
            log += e
        }
        updateBurst(e)
        observe(e)
        val frame = world.served(e.live())
        sockets.forEach { it.send(frame) }
    }

    fun updateBurst(event: Event) {
        if (event.type == "observers") greeting = greeting?.let { event }
        val at = burst.indexOfFirst { it.event.slot == event.slot }
        when {
            at >= 0 -> burst[at] = BurstFrame(event, burst[at].live)
            event.type in SNAPSHOT -> burst += BurstFrame(event, live = false)
        }
    }

    private fun observe(event: Event) {
        when (event.type) {
            "agent_state" -> {
                event.body.string("agentMode")?.let { agentMode = it }
                event.body.string("state")?.let { world.statusChanged(this, "state" to JsonPrimitive(it)) }
            }
            "thread_title" -> title = event.body.string("title") ?: title
            "delta" -> lastAssistantId = event.body.string("messageId") ?: lastAssistantId
            "message_added" -> {
                val message = event.body.obj("message") ?: return
                val content = message.array("content").orEmpty().mapNotNull { it as? JsonObject }
                when (message.string("role")) {
                    "assistant" -> {
                        lastAssistantId = message.string("messageId")
                        pendingTool = content.lastOrNull { it.string("type") == "tool_use" }?.string("id")
                        content.filter { it.string("type") == "text" }.mapNotNull { it.string("text") }.takeIf { it.isNotEmpty() }
                            ?.let { lastAssistantText = it.joinToString("\n\n") }
                    }
                    "user" -> if (content.any { it.string("type") == "tool_result" }) pendingTool = null
                }
            }
        }
    }

    private fun userMessage(params: JsonObject, steer: Boolean): JsonObject {
        val now = Instant.now()
        val meta = if (steer) json("sentAt" to now.toEpochMilli(), "steer" to true) else json("sentAt" to now.toEpochMilli())
        return json(
            "threadId" to id,
            "role" to "user",
            "content" to (params["content"] ?: JsonArray(emptyList())),
            "meta" to meta,
            "userState" to (params["userState"] ?: json()),
            "readAt" to null,
            "messageId" to params.string("messageId"),
            "createdAt" to now.toString(),
        )
    }

    // ── sends, turns, steering, cancel ────────────────────────────────────────

    /**
     * `client_append_user_msg`. A resend of a known `messageId` repeats its stored frame and starts nothing (S16).
     * While the agent works, the message queues, and a steer joins the turn at the next tool boundary (S5).
     */
    fun append(params: JsonObject): JsonObject {
        val messageId = params.string("messageId") ?: return OK
        val known = log.lastOrNull { e ->
            (e.type == "message_added" && e.body.obj("message")?.string("messageId") == messageId) ||
                (e.type == "queued_message_added" && e.body.obj("message")?.obj("queuedMessage")?.string("messageId") == messageId)
        }
        if (known != null) {
            val frame = world.served(known.live())
            sockets.forEach { it.send(frame) }
            return OK
        }
        val steer = params.bool("steer") == true
        if (activity == Activity.Idle) {
            startTurn(params)
        } else {
            val send = QueuedSend(params, steer)
            queue += send
            val queued = json(
                "batchId" to messageId,
                "batchKind" to "singleton",
                "batchState" to "closed",
                "steer" to steer,
                "queuedMessage" to json(
                    "role" to "user",
                    "messageId" to messageId,
                    "content" to (params["content"] ?: JsonArray(emptyList())),
                    "userState" to (params["userState"] ?: json()),
                    "createdAt" to Instant.now().toString(),
                ),
            )
            emit(Event("queued_message_added", json("message" to queued)))
        }
        return OK
    }

    private fun startTurn(params: JsonObject) {
        val messageId = params.string("messageId")!!
        params.string("agentMode")?.let { agentMode = it }
        emit(Event("message_added", json("message" to userMessage(params, steer = false))))
        world.statusChanged(this, "lastUserMessageAt" to JsonPrimitive(Instant.now().toString()), "hasUnreadMessages" to JsonPrimitive(false))
        val turns = seed.script.turns
        if (turns.isEmpty()) {
            emit(Event("agent_state", json("state" to "idle", "agentMode" to agentMode)))
            return
        }
        val turn = turns[turnsStarted % turns.size]
        val fresh = turnsStarted >= turns.size
        turnsStarted++
        val ids = HashMap<String, String>()
        ids[turn.trigger] = messageId
        if (fresh) {
            turn.steps.asSequence().filterIsInstance<TurnStep.Emit>().flatMap { it.event.body.strings() }
                .filter { AMP_ID.matches(it) && it !in ids }
                .forEach { ids[it] = mintId(it.substringBefore('-')) }
        }
        play(turn, 0, ids)
    }

    private fun play(turn: Turn, next: Int, ids: Map<String, String>) {
        if (next >= turn.steps.size) {
            activity = if (turn.parks) Activity.Parked else Activity.Idle
            if (!turn.parks) turnEnded()
            return
        }
        val step = turn.steps[next]
        val timer = world.schedule(world.gap(step.gapMs)) { advance() }
        activity = Activity.Playing(turn, next, ids, timer)
    }

    private fun advance() {
        val playing = activity as? Activity.Playing ?: return
        when (val step = playing.turn.steps[playing.next]) {
            is TurnStep.Emit -> {
                val event = step.event.mapStrings { playing.ids[it] ?: it }
                emit(event)
                if (event.type == "message_added" && isToolResult(event)) deliverSteers()
                play(playing.turn, playing.next + 1, playing.ids)
            }
            is TurnStep.AwaitApproval -> {
                val item = step.item.mapStrings { playing.ids[it] ?: it }
                emit(Event("tool_approval_queue", json("approvals" to listOf(item))))
                world.statusChanged(this, "state" to JsonPrimitive("waiting_for_approval"))
                activity = Activity.Gated(playing.turn, playing.next + 1, playing.ids, item.string("toolCallId")!!)
            }
        }
    }

    private fun isToolResult(event: Event): Boolean {
        val message = event.body.obj("message") ?: return false
        return message.string("role") == "user" && message.array("content").orEmpty().any { (it as? JsonObject)?.string("type") == "tool_result" }
    }

    private fun deliverSteers() {
        val steers = queue.filter { it.steer }
        queue.removeAll(steers.toSet())
        for (steer in steers) {
            emit(Event("queued_message_dequeued", json("queuedMessageId" to steer.messageId)))
            emit(Event("message_added", json("message" to userMessage(steer.params, steer = true))))
        }
    }

    /** A turn another client started: the user actor reports it working until [finishElsewhere] ends it. */
    fun startElsewhere() {
        world.statusChanged(this, "state" to JsonPrimitive("working"), "currentTurnStartedAt" to JsonPrimitive(Instant.now().toString()))
    }

    /** A turn run from another client ends with the assistant saying [text], as Amp ends one. Returns the pushes sent. */
    fun finishElsewhere(text: String): Int {
        val message = json(
            "threadId" to id, "role" to "assistant", "content" to listOf(json("type" to "text", "text" to text)),
            "state" to json("type" to "complete"), "messageId" to mintId("M"), "createdAt" to Instant.now().toString(),
        )
        emit(Event("message_added", json("message" to message)))
        emit(Event("agent_state", json("state" to "idle", "agentMode" to agentMode)))
        return turnEnded()
    }

    private fun turnEnded(): Int {
        world.statusChanged(
            this,
            "state" to JsonPrimitive("idle"),
            "hasUnreadMessages" to JsonPrimitive(true),
            "lastTurnEndedAt" to JsonPrimitive(Instant.now().toString()),
            "latestAssistantMessageID" to JsonPrimitive(lastAssistantId),
        )
        val sent = world.push.turnEnded(this, lastAssistantId, lastAssistantText)
        startQueued()
        return sent
    }

    private fun startQueued() {
        val next = queue.removeFirstOrNull() ?: return
        emit(Event("queued_message_dequeued", json("queuedMessageId" to next.messageId)))
        startTurn(next.params)
    }

    // ── queue edits and spawning (read from the CLI, not observed; scripted) ──

    /** `client_remove_queued_msg`: the message leaves the queue as `queued_message_removed{queuedMessageId}`. */
    private fun removeQueued(queuedId: String?) {
        val send = queue.firstOrNull { it.messageId == queuedId } ?: return
        queue.remove(send)
        emit(Event("queued_message_removed", json("queuedMessageId" to send.messageId)))
    }

    /** `client_steer_queued_msg`: the message joins the running turn at its next tool boundary, as a steer does. */
    private fun steerQueued(queuedId: String?) {
        val index = queue.indexOfFirst { it.messageId == queuedId }
        if (index < 0) return
        queue[index] = QueuedSend(queue[index].params, steer = true)
        emitQueueSnapshot()
    }

    /** `client_reorder_queued_msgs`: the named order first, then anything the client did not name. */
    private fun reorderQueue(order: List<String>) {
        val byId = queue.associateBy { it.messageId }
        val reordered = order.mapNotNull { byId[it] } + queue.filter { it.messageId !in order }
        queue.clear()
        queue += reordered
        emitQueueSnapshot()
    }

    private fun emitQueueSnapshot() {
        emit(Event("queued_messages", json("messages" to queue.map { queuedFrame(it) })))
    }

    private fun queuedFrame(send: QueuedSend): JsonObject = json(
        "batchId" to send.messageId,
        "batchKind" to "singleton",
        "batchState" to "closed",
        "steer" to send.steer,
        "queuedMessage" to json(
            "role" to "user",
            "messageId" to send.messageId,
            "content" to (send.params["content"] ?: JsonArray(emptyList())),
            "userState" to (send.params["userState"] ?: json()),
        ),
    )

    /**
     * `client_spawn_executor`: the receipt, then the S4 progression (`captures/s04-live-thread.jsonl:33`, `:41`, `:42`)
     * with this request's id as `spawnId`. A scenario with a spawn refusal answers a JSON-RPC error instead.
     */
    private fun spawn(socket: WebSocket, id: String?, params: JsonObject) {
        world.spawnRefusal?.let { message ->
            if (id != null) send(socket, json("jsonrpc" to "2.0", "id" to id, "error" to json("code" to -32000, "message" to message)))
            return
        }
        respond(socket, id, OK)
        val orb = seed.orb
        if (orb != null && params.string("executorDemand") != null) return wake(orb, params.string("requestId"))
        executorStarted(params.string("requestId"), if (params.obj("executor")?.string("type") == "runner") "local-client" else "orb")
    }

    /** The S4 spawn progression with [spawnId], also what a thread `createRemoteExecutorThread` made shows. */
    fun executorStarted(spawnId: String?, type: String) {
        emit(Event("executor_status", json("spawnId" to spawnId, "status" to "starting", "message" to "Spawn requested")))
        emit(Event("observers", json("count" to 1, "observers" to listOf(json("userId" to USER_ID, "name" to USER_ID)), "hasExecutor" to true, "executorType" to type)))
        emit(Event("executor_status", json("spawnId" to spawnId, "status" to "running", "message" to "Executor connected")))
    }

    /**
     * An orb wake in the frames `captures/s21-orb-wake.jsonl:37-48` recorded: `{ok:true}`, `starting` with
     * `environment_recovering` and `operation:"recovering"`, then the executor connected. No capture records a failed
     * wake, so [OrbScript.failure] ends it with an `executor_status` failure for the wake's `spawnId`, as the web reads one.
     */
    private fun wake(orb: OrbScript, spawnId: String?) {
        world.orbWakes += id
        emit(Event("executor_status", orbStatus(spawnId, "starting", "Executor disconnected, restarting…", "environment_recovering", "recovering")))
        emit(Event("executor_status", orbStatus(spawnId, "starting", "Reconnecting to orb", "environment_recovering", "recovering")))
        world.schedule(orb.wakeMs) {
            if (orb.failure != null) {
                emit(Event("executor_status", orbStatus(spawnId, "failed", orb.failure, "orb_resume_failed", "idle")))
                return@schedule
            }
            emit(Event("observers", json("count" to 1, "observers" to listOf(json("userId" to USER_ID, "name" to USER_ID)), "hasExecutor" to true, "executorType" to "sandbox")))
            emit(Event("executor_status", orbStatus(spawnId, "running", "Executor connected", "executor_connected", "idle")))
        }
    }

    private fun orbStatus(spawnId: String?, status: String, message: String, reasonCode: String, operation: String) = json(
        "spawnId" to spawnId, "status" to status, "message" to message,
        "details" to json("reasonCode" to reasonCode, "executionEnvironment" to json("provider" to "orb-provider", "operation" to operation)),
    )

    /** `client_cancel`, shaped as S5 recorded it (`captures/s05-steer-interrupt.jsonl:109-113`). */
    fun cancel() {
        val current = activity
        if (current == Activity.Idle) return
        (current as? Activity.Playing)?.timer?.cancel(false)
        activity = Activity.Idle
        pendingTool?.let { tool ->
            val result = json("type" to "tool_result", "toolUseID" to tool, "run" to json("status" to "cancelled", "reason" to "User canceled"))
            val message = json(
                "threadId" to id, "role" to "user", "content" to listOf(result), "readAt" to null,
                "messageId" to mintId("M"), "createdAt" to Instant.now().toString(),
            )
            emit(Event("message_added", json("message" to message)))
        }
        emit(Event("agent_state", json("state" to "idle", "messageId" to lastAssistantId, "agentMode" to agentMode)))
        emit(Event("tool_approval_queue", json("approvals" to emptyList<Any>())))
        emit(Event("cancelled", json("messageId" to lastAssistantId)))
        emit(Event("agent_state", json("state" to "idle", "agentMode" to agentMode)))
        world.statusChanged(this, "state" to JsonPrimitive("idle"), "lastTurnEndedAt" to JsonPrimitive(Instant.now().toString()))
        startQueued()
    }

    private fun approve(toolCallId: String?, accepted: Boolean) {
        val gated = activity as? Activity.Gated ?: return
        if (gated.toolCallId != toolCallId) return
        emit(Event("tool_approval_queue", json("approvals" to emptyList<Any>())))
        if (accepted) {
            world.statusChanged(this, "state" to JsonPrimitive("running_tools"))
            play(gated.turn, gated.next, gated.ids)
            return
        }
        val result = json("type" to "tool_result", "toolUseID" to toolCallId, "run" to json("status" to "rejected-by-user"))
        val message = json(
            "threadId" to id, "role" to "user", "content" to listOf(result), "readAt" to null,
            "messageId" to mintId("M"), "createdAt" to Instant.now().toString(),
        )
        emit(Event("message_added", json("message" to message)))
        emit(Event("agent_state", json("state" to "idle", "agentMode" to agentMode)))
        activity = Activity.Idle
        turnEnded()
    }

    private companion object {
        val OK = json("ok" to true)
        val INVALID_PARAMS = json("code" to -32602, "message" to "Invalid method params")
    }
}

/** Recorded socket answers. Exact params first, consumed in order with the last one repeating. */
internal class ExchangeBook(private val exchanges: List<SocketExchange>) {
    private val used = HashMap<Pair<String, JsonObject>, Int>()

    fun find(method: String, params: JsonObject, byMethodAlone: Boolean): SocketExchange? {
        val exact = exchanges.filter { it.method == method && it.params == params }
        if (exact.isNotEmpty()) {
            val key = method to params
            val index = used.getOrDefault(key, 0)
            used[key] = index + 1
            return exact[minOf(index, exact.size - 1)]
        }
        return if (byMethodAlone) exchanges.lastOrNull { it.method == method } else null
    }
}
