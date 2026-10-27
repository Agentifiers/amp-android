package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.WebSocket
import java.io.Closeable
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The fake account's whole state: threads, user actor sockets, statuses, attachments, and push subscriptions.
 * Every read and write runs on one loop thread, so HTTP handlers, socket callbacks, and turn timers never race.
 */
internal class World(
    private val scenario: Scenario,
    val origin: String,
    private val gapCapMs: Long,
    private val fixtureTime: FixtureTime,
) : Closeable {
    private val loop = Executors.newSingleThreadScheduledExecutor { Thread(it, "fake-amp").apply { isDaemon = true } }
    private val threads = LinkedHashMap<String, ThreadActor>()
    private val statuses = HashMap<String, JsonObject>()
    private val userSockets = LinkedHashMap<WebSocket, MutableSet<String>>()
    private val tokens = HashSet<String>()
    private val recorded: List<InternalExchange> = CaptureIndex.names.flatMap { Capture.load(it).internalExchanges() }
    private val recordedUsed = HashMap<Pair<String, JsonObject>, Int>()
    val push = PushHub(this)
    val attachments = Attachments(origin)
    private val organize = Organize(this)

    /** Every relay control payload the fake shell received, oldest first. */
    val terminalControls = ArrayList<String>()

    /** Every `client_mark_message_read` a thread received, oldest first. */
    val readMarks = ArrayList<ReadMark>()

    /** The thread id of every orb wake (`client_spawn_executor` with `executorDemand`) a thread received, oldest first. */
    val orbWakes = ArrayList<String>()

    /** The params of every `createRemoteExecutorThread` received, oldest first. */
    val remoteExecutorThreads = ArrayList<JsonObject>()

    val web: WebApp? = scenario.web?.let { WebApp(it, scenario.orbProviders) }

    init {
        scenario.threads.forEach { add(it) }
        scenario.attachments.forEach(attachments::seed)
    }

    fun add(seed: FakeThread): ThreadActor {
        val actor = ThreadActor(seed, this)
        threads[seed.id] = actor
        statuses[seed.id] = seed.status
        return actor
    }

    fun thread(id: String?): ThreadActor? = threads[id]

    fun delete(id: String) {
        threads.remove(id)
        statuses.remove(id)
    }

    fun <T> sync(block: () -> T): T = try {
        loop.submit(Callable(block)).get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    fun post(block: () -> Unit) {
        if (!loop.isShutdown) loop.execute { reported(block) }
    }

    fun schedule(delayMs: Long, block: () -> Unit): ScheduledFuture<*> = loop.schedule({ reported(block) }, delayMs, TimeUnit.MILLISECONDS)

    /** The loop's executor would swallow a failure; a fake that silently stops answering is worse than a loud one. */
    private fun reported(block: () -> Unit) = try {
        block()
    } catch (e: Exception) {
        System.err.println("fake-amp: ${e.stackTraceToString()}")
    }

    /** Captured gaps between frames, capped so a recorded 15 s tool run plays in [gapCapMs]. */
    fun gap(capturedMs: Long): Long = minOf(capturedMs, scenario.frameGapCapMs ?: gapCapMs)

    /** Cancels the tasks [shutdownNow][java.util.concurrent.ExecutorService.shutdownNow] drops, so no [sync] caller waits on them forever. */
    override fun close() {
        loop.shutdownNow().forEach { (it as? Future<*>)?.cancel(false) }
    }

    // ── tokens ────────────────────────────────────────────────────────────────

    /** Fake tokens carry the canary `AMPCANARY` so a leak check can find them in logs (auth-and-security.md:353). */
    fun mintToken(kind: String): String = "fake-$kind-AMPCANARY-${UUID.randomUUID()}".also { tokens += it }

    fun knows(token: String?): Boolean = token != null && token in tokens

    // ── HTTP control plane ────────────────────────────────────────────────────

    /** `POST /api/thread-actors`. Without `threadId` it creates a thread that plays the S4 live turn. */
    fun threadGrant(body: JsonObject): Pair<Int, JsonObject> {
        val requested = body.string("threadId")
        val actor = if (requested == null) add(newThread("T-" + UUID.randomUUID(), null, body.string("agentMode"))) else threads[requested]
            ?: return 404 to json("error" to "Thread not found", "code" to "THREAD_NOT_FOUND")
        if (actor.archived) return 409 to json("error" to "This thread is archived", "code" to "THREAD_ARCHIVED")
        val template = Capture.load("s04-live-thread").responseBody("thread-actors")
        return 201 to template + mapOf(
            "threadId" to element(actor.id),
            "threadVersion" to element(actor.lastSeq),
            "agentMode" to element(body.string("agentMode") ?: template.string("agentMode")),
            "wsToken" to element(mintToken("wsToken")),
        )
    }

    /** A thread another client (the CLI or the web) created under [id] and [title], as [threadGrant] creates one. */
    fun startThreadElsewhere(id: String, title: String) {
        statusChanged(add(newThread(id, title, null)))
    }

    /** The listing keeps the mode the create call asked for, as Amp's thread header does (`thread.data.agentMode`). */
    private fun newThread(id: String, title: String?, agentMode: String?): FakeThread {
        val listing = listingFor(id, title, 0).let { row -> agentMode?.let { row + mapOf("agentMode" to element(it)) } ?: row }
        return FakeThread(id, listing, runnerStatus() + mapOf("threadId" to element(id), "title" to element(title)), scenario.newThread())
    }

    val spawnRefusal: String? get() = scenario.spawnRefusal

    fun userGrant(identity: FakeIdentity): JsonObject = json("poolName" to "k8s", "userId" to identity.userId, "wsToken" to mintToken("userToken"))

    /** The scenario's sign-in rules. Called on the loop, like every other read. */
    val auth: FakeAuth get() = scenario.auth

    /**
     * `POST /api/internal?<method>`: scenario-scripted methods, organize answers, live answers for the thread
     * methods, then recorded answers.
     */
    fun internal(method: String, params: JsonObject, identity: FakeIdentity): JsonObject {
        scenario.methods?.answer(method, params, identity)?.let { return it }
        if (method == "getUserInfo") return json("ok" to true, "result" to userInfo(identity))
        if (method == "createRemoteExecutorThread") return createRemoteExecutorThread(params)
        organize.answer(method, params)?.let { return it }
        live(method, params)?.let { return json("ok" to true, "result" to it) }
        scenario.internal[method]?.let { return json("ok" to true, "result" to it) }
        val exact = recorded.filter { it.method == method && it.params == params }
        val exchange = if (exact.isNotEmpty()) {
            val key = method to params
            val index = recordedUsed.getOrDefault(key, 0).also { recordedUsed[key] = it + 1 }
            exact[minOf(index, exact.size - 1)]
        } else {
            recorded.lastOrNull { it.method == method }
        }
        return exchange?.body?.let(::rebase) ?: notFound("No method $method")
    }

    private fun live(method: String, params: JsonObject): JsonElement? {
        val thread = threads[params.string("thread") ?: params.string("threadID")]
        return when (method) {
            "listThreads" -> {
                val ids = params.array("threadIDs")?.mapNotNull { (it as? JsonPrimitive)?.content }
                val offset = params.long("offset")?.toInt() ?: 0
                val limit = params.long("limit")?.toInt() ?: Int.MAX_VALUE
                json("threads" to threads.values.filter { ids == null || it.id in ids }.drop(offset).take(limit).map(::listing))
            }
            "getThreadInitialTranscriptPage" -> thread?.let(::initialPage)
            "getThreadMessagesPage" -> thread?.let { messagesPage(it, params.long("fromIndex")?.toInt() ?: 0, params.long("limit")?.toInt() ?: 50) }
            "getThreadTranscriptPage" -> thread?.let { transcriptPage(it, params.long("before")?.toInt() ?: 0, params.string("anchorID")) }
            "getThreadTail" -> thread?.let { tail(it, params.long("limit")?.toInt() ?: 6) }
            "archiveThread" -> thread?.let {
                it.archived = params.bool("archived") == true
                statusChanged(it, "archived" to JsonPrimitive(it.archived))
                json()
            }
            else -> null
        }
    }

    /**
     * `createRemoteExecutorThread`, the call behind `amp -ox --orb-provider <name>`: a thread with its first message and
     * its executor started. `orbProvider` is `"builtin" | {configID} | {name}` (web chunk `1476a75e.js`) and resolves
     * against the scenario's providers. No capture records the call, so a provider the workspace lacks is refused
     * before any thread exists, in words this fake chose.
     */
    private fun createRemoteExecutorThread(params: JsonObject): JsonObject {
        remoteExecutorThreads += params
        if (params.array("content").isNullOrEmpty()) return json("ok" to false, "error" to json("code" to "invalid-params", "message" to "content is required"))
        val provider = params["orbProvider"]
        val known = when (provider) {
            null -> true
            is JsonPrimitive -> provider.isString && provider.content == "builtin"
            is JsonObject -> scenario.orbProviders.any {
                it.configID == provider.string("configID") || (scenario.web?.providerNames != false && it.name == provider.string("name"))
            }
            else -> false
        }
        if (!known) return notFound("No orb provider ${provider?.let { (it as? JsonObject)?.string("name") ?: it.toString() }} in this workspace.")
        val actor = add(newThread("T-" + UUID.randomUUID(), null, params.string("agentMode")))
        statusChanged(actor)
        actor.executorStarted("spawn-" + UUID.randomUUID(), "orb")
        actor.append(if (params.string("messageId") == null) params + mapOf("messageId" to element("M-" + UUID.randomUUID().toString().replace("-", ""))) else params)
        return json("ok" to true, "result" to json("threadID" to actor.id, "url" to "$origin/threads/${actor.id}", "executorType" to "sandbox", "agentMode" to params.string("agentMode")))
    }

    private fun listing(thread: ThreadActor): JsonObject = thread.seed.listing + mapOf(
        "title" to element(thread.title),
        "v" to element(thread.lastSeq),
        "archived" to element(thread.archived),
        "pinned" to element(thread.pinned),
        "messageCount" to element(thread.messages().size),
    ) + organize.listing(thread)

    private fun threadHeader(thread: ThreadActor): JsonObject {
        val row = listing(thread)
        val data = json(
            "v" to thread.lastSeq, "id" to thread.id, "env" to row["env"], "meta" to row["meta"], "title" to thread.title,
            "pinned" to thread.pinned, "created" to row["created"], "agentMode" to row["agentMode"], "messages" to emptyList<Any>(),
            "updatedAt" to Instant.now().toString(), "creatorUserID" to row["creatorUserID"], "multiplayerExpiresAt" to null,
        )
        return json("id" to thread.id, "title" to thread.title, "updatedAt" to Instant.now().toString(), "creatorUserID" to row["creatorUserID"], "data" to data)
    }

    /** History pages key messages by `protocolMessageID` and number them from 1 in `messageId` (S9). */
    private fun pageMessages(thread: ThreadActor): List<JsonObject> = thread.messages().mapIndexed { index, message ->
        (message - "threadId") + mapOf(
            "messageId" to element(index + 1),
            "protocolMessageID" to element(message.string("messageId")),
            "protocolMessageVersion" to element(0),
        )
    }

    /** Up to [FakeThread.historyPageSize] messages ending just before [end], in the S9 page fields (`captures/s09-transcript.jsonl:2,8`). */
    private fun pageEndingAt(thread: ThreadActor, messages: List<JsonObject>, end: Int): JsonObject {
        val start = thread.seed.historyPageSize?.let { maxOf(0, end - it) } ?: 0
        val page = messages.subList(start, end)
        return json(
            "messages" to page, "hasMoreBefore" to (start > 0),
            "firstMessageID" to page.firstOrNull()?.string("protocolMessageID"), "firstMessageIndex" to start,
        )
    }

    private fun initialPage(thread: ThreadActor): JsonObject {
        val messages = pageMessages(thread)
        return json("thread" to threadHeader(thread)) + pageEndingAt(thread, messages, messages.size)
    }

    private fun messagesPage(thread: ThreadActor, from: Int, limit: Int): JsonObject {
        val messages = pageMessages(thread)
        val page = messages.drop(from).take(limit)
        val next = (from + page.size).takeIf { it < messages.size }
        return json("messages" to page, "nextMessageIndex" to next)
    }

    /** A wrong `anchorID` for `before` answers `status:"stale"` (`captures/s09-transcript.jsonl:10`). */
    private fun transcriptPage(thread: ThreadActor, before: Int, anchor: String?): JsonObject {
        val messages = pageMessages(thread)
        if (messages.getOrNull(before)?.string("protocolMessageID") != anchor) return json("status" to "stale")
        return json("status" to "ok") + pageEndingAt(thread, messages, minOf(before, messages.size))
    }

    private fun tail(thread: ThreadActor, limit: Int): JsonObject {
        val messages = pageMessages(thread)
        return json("thread" to threadHeader(thread), "messages" to messages.takeLast(limit), "hasMoreBefore" to (messages.size > limit))
    }

    /** Captured answers point at ampcode.com and onamp.dev; served answers point back at this server. */
    fun rebase(body: JsonObject): JsonObject = body.mapStrings { s ->
        PORTAL_HOST.replace(s.replace(AMP_ORIGIN, origin)) { "$origin/portal/${it.groupValues[1]}/" }
    }

    // ── thread actor RPC over HTTP ────────────────────────────────────────────

    /** `POST .../threadActor/request/rpc`: only `client_append_user_msg` is allowed (S18). */
    fun statelessRpc(thread: ThreadActor, frame: JsonObject): JsonObject {
        val id = frame.string("id")
        val method = frame.string("method")
        val params = frame.obj("params")
        fun error(code: Int, message: String) = json("jsonrpc" to "2.0", "id" to id, "error" to json("code" to code, "message" to message))
        if (method != "client_append_user_msg") return error(-32601, "Method not found: $method")
        if (params?.string("messageId") == null || params.array("content") == null) return error(-32602, "Invalid method params")
        return json("jsonrpc" to "2.0", "id" to id, "result" to thread.append(params))
    }

    // ── user actor ────────────────────────────────────────────────────────────

    fun attachUser(socket: WebSocket) {
        userSockets[socket] = HashSet()
        socket.send(userFrame("Init", json("actorId" to "fake-user-actor", "connectionId" to UUID.randomUUID().toString())))
    }

    fun detachUser(socket: WebSocket) {
        userSockets.remove(socket)
    }

    fun receiveUser(socket: WebSocket, text: String) {
        val body = runCatching { (FakeJson.parseToJsonElement(text) as? JsonObject)?.obj("body") }.getOrNull() ?: return
        val value = body.obj("val") ?: return
        when (body.string("tag")) {
            "SubscriptionRequest" -> {
                val name = value.string("eventName") ?: return
                val subscriptions = userSockets[socket] ?: return
                if (value.bool("subscribe") == false) subscriptions -= name else subscriptions += name
            }
            "ActionRequest" -> {
                val id = (value["id"] as? JsonPrimitive)?.longOrNull ?: return
                val output = userAction(value.string("name") ?: return, value.array("args") ?: JsonArray(emptyList()))
                socket.send(userFrame("ActionResponse", json("id" to listOf("\$BigInt", id.toString()), "output" to output)))
            }
        }
    }

    /** A user actor action, over the socket or the stateless path. */
    fun userAction(name: String, args: JsonArray): JsonElement? {
        val arg = args.firstOrNull() as? JsonObject
        scenario.actions[name]?.let { return it }
        return when (name) {
            "getRecentThreads" -> JsonArray(threads.keys.mapNotNull { statuses[it] }.take(arg?.long("limit")?.toInt() ?: Int.MAX_VALUE))
            "setThreadPinned" -> threads[arg?.string("threadId")]?.let { thread ->
                thread.pinned = arg?.bool("pinned") == true
                statusChanged(thread, "pinned" to JsonPrimitive(thread.pinned))
                JsonPrimitive(true)
            }
            else -> CaptureIndex.names.asSequence().map { Capture.load(it) }
                .mapNotNull { c -> c.channel("userActor.action.$name").lastOrNull { it.dir == Dir.Recv }?.frame?.obj("body")?.get("output") }
                .lastOrNull()
        }
    }

    /** Merges [fields] into the thread's summary and sends `threadStatusUpdated` to subscribed user sockets. */
    fun statusChanged(thread: ThreadActor, vararg fields: Pair<String, JsonElement>) {
        val updated = (statuses[thread.id] ?: json()) + fields.toMap() + mapOf(
            "title" to element(thread.title),
            "updatedAt" to element(Instant.now().toString()),
        )
        statuses[thread.id] = updated
        val frame = userFrame("Event", json("name" to "threadStatusUpdated", "args" to listOf(updated)))
        userSockets.filterValues { "threadStatusUpdated" in it }.keys.forEach { it.send(frame) }
    }

    private fun userFrame(tag: String, value: JsonObject): String = served(json("body" to json("tag" to tag, "val" to value)))

    /**
     * Every JSON body and text frame the fake sends goes out through here, with fixture times moved to today.
     * Attachment URLs, in image blocks and inside message text, point at this server, which serves them; other
     * ampcode.com links stay as written.
     */
    fun served(frame: JsonObject): String =
        fixtureTime.served(frame).mapStrings { it.replace(AMP_ATTACHMENTS, "$origin${Attachments.CONTENT_PATH}/") }.toString()

    companion object {
        private val PORTAL_HOST = Regex("https://([a-z0-9-]+)\\.onamp\\.dev/")
        private const val AMP_ORIGIN = "https://ampcode.com"
        private const val AMP_ATTACHMENTS = AMP_ORIGIN + Attachments.CONTENT_PATH + "/"

        /** `getUserInfo` was observed live but never captured (transport.md, methods table), so this answer is scripted. */
        fun userInfo(identity: FakeIdentity) = json(
            "id" to identity.userId,
            "email" to identity.email,
            "username" to identity.username,
            "displayName" to identity.displayName,
        )

        fun notFound(message: String) = json("ok" to false, "error" to json("code" to "not-found", "message" to message))
    }
}

/** The capture names the build bundled, from the index the copy task writes next to them. */
internal object CaptureIndex {
    val names: List<String> by lazy {
        val stream = CaptureIndex::class.java.getResourceAsStream("/captures/index.txt") ?: error("captures/index.txt is not bundled in :fake-amp")
        stream.bufferedReader().readLines().filter { it.isNotBlank() }
    }
}
