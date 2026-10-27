package com.agentifiers.amp.fake

import com.agentifiers.amp.fake.scenarios.SCENARIOS
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.CompletableFuture

/** The fake Amp server's entry point: pick a scenario by name and get a running server on 127.0.0.1. */
public object FakeAmp {
    public val scenarios: List<String> get() = SCENARIOS.map { it.name }

    /**
     * The moment every capture and scenario time is authored against: the spike session's last capture closed at
     * 05:33:56Z (`captures/s15c-plugin-dialog-detached.jsonl`). A server started with `servedAt` here serves every
     * fixture time verbatim.
     */
    public val RECORDED_AT: Instant = Instant.parse("2026-10-23T05:35:00Z")

    /**
     * Starts [scenario] on [port] (0 picks a free one). Recorded gaps between frames longer than [frameGapCapMs]
     * play in [frameGapCapMs], so a turn that took a minute live streams in seconds. Fixture times are served as if
     * [RECORDED_AT] were [servedAt].
     */
    @JvmOverloads
    public fun start(scenario: String, port: Int = 0, frameGapCapMs: Long = 400, servedAt: Instant = Instant.now()): FakeAmpServer {
        val spec = SCENARIOS.firstOrNull { it.name == scenario }
            ?: throw IllegalArgumentException("Unknown fake-amp scenario \"$scenario\". Known scenarios: ${scenarios.joinToString()}")
        return FakeAmpServer(spec.build(), port, frameGapCapMs, servedAt)
    }
}

/** A running fake. Point the app's server origin at [ampBaseUrl]; Rivet traffic goes to [actorsBaseUrl]. */
public class FakeAmpServer internal constructor(scenario: Scenario, port: Int, frameGapCapMs: Long, servedAt: Instant) : Closeable {
    private val server = MockWebServer()
    private val world: World

    /** Replaces `https://ampcode.com`. */
    public val ampBaseUrl: String

    /** Replaces `https://ampcode.com/actors`. */
    public val actorsBaseUrl: String

    /** Every Web Push sent so far, oldest first. */
    public val pushes: List<PushDelivery> get() = world.push.deliveries.toList()

    /** Every terminal control payload received (`open`, `resize`, `amp-exec-v1` commands), oldest first. */
    public val terminalControls: List<String> get() = world.sync { world.terminalControls.toList() }

    /** Every `client_mark_message_read` received, oldest first. */
    public val readMarks: List<ReadMark> get() = world.sync { world.readMarks.toList() }

    /** The thread id of every orb wake received, oldest first. */
    public val orbWakes: List<String> get() = world.sync { world.orbWakes.toList() }

    /** The params of every `createRemoteExecutorThread` received, oldest first. */
    public val remoteExecutorThreads: List<JsonObject> get() = world.sync { world.remoteExecutorThreads.toList() }

    /**
     * The bytes stored at [path] in [threadId]'s workspace, resolved as `client_filesystem_*` paths are, or null when
     * the thread has no workspace or no such file.
     */
    public fun workspaceFile(threadId: String, path: String): ByteArray? = world.sync { world.thread(threadId)?.seed?.workspace?.bytes(path) }

    /**
     * Takes every Web Push to an endpoint under `<ampBaseUrl>/push/` instead of an HTTP POST, on the fake's loop
     * thread. Such a delivery is still recorded in [pushes].
     */
    public var onPush: ((PushDelivery) -> Unit)?
        get() = world.push.onPush
        set(value) {
            world.push.onPush = value
        }

    /** Adds [threadId] titled [title] as the CLI or web would create it, so `getRecentThreads` lists it. */
    public fun startThreadElsewhere(threadId: String, title: String): Unit = world.sync { world.startThreadElsewhere(threadId, title) }

    /** Starts a turn on [threadId] from another client, so the user actor reports it working. */
    public fun startTurnElsewhere(threadId: String): Unit = world.sync {
        requireNotNull(world.thread(threadId)) { "No thread $threadId" }.startElsewhere()
    }

    /**
     * Ends a turn on [threadId] whose last assistant message is [text], as Amp would. Returns how many pushes it sent:
     * none while any client holds the thread open (S23).
     */
    public fun finishTurnElsewhere(threadId: String, text: String): Int = world.sync {
        requireNotNull(world.thread(threadId)) { "No thread $threadId" }.finishElsewhere(text)
    }

    /** Another client, such as an ampcode.com tab, opens [threadId] or closes it. It counts as a connected client. */
    public fun holdOpenElsewhere(threadId: String, open: Boolean): Unit = world.sync {
        requireNotNull(world.thread(threadId)) { "No thread $threadId" }.openElsewhere = open
    }

    /** How many sockets to this fake have [threadId] open now. */
    public fun socketCount(threadId: String): Int = world.sync { world.thread(threadId)?.socketCount ?: 0 }

    /** How many push subscriptions [threadId] holds. */
    public fun subscriptionCount(threadId: String): Int = world.sync { world.push.subscriptionCount(threadId) }

    /**
     * The world needs the bound port, so it is built after the server starts listening. A request that arrives
     * meanwhile waits for it here: MockWebServer's default dispatcher would block that request forever, and its
     * close would then give up waiting.
     */
    init {
        val routes = CompletableFuture<Routes>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = routes.get().dispatch(request)
        }
        server.start(InetAddress.getByName("127.0.0.1"), port)
        ampBaseUrl = "http://127.0.0.1:${server.port}"
        actorsBaseUrl = "$ampBaseUrl/actors"
        world = World(scenario, ampBaseUrl, frameGapCapMs, FixtureTime(servedAt))
        routes.complete(Routes(world))
    }

    /** The world goes first, so request threads waiting on its loop are released before the server waits for them. */
    override fun close() {
        world.close()
        server.close()
    }
}

public data class ReadMark(val threadId: String, val messageId: String?)
