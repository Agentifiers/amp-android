package com.agentifiers.amp.sync.internal.thread

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.fake.FakeAmpServer
import com.agentifiers.amp.model.ExecutorStatus
import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.sync.api.ViewerToken
import com.agentifiers.amp.sync.internal.attachments.AttachmentUploader
import com.agentifiers.amp.sync.internal.fleet.testThreadDeps
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.FixedBearer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.FrameParser
import com.agentifiers.amp.sync.internal.wire.InboundFrame
import com.agentifiers.amp.sync.internal.wire.WireDecoders
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** The orb wake against the fake `orb-asleep` and `orb-wake-fails` scenarios, over real sockets. */
class OrbWakeTest {
    private val thread = ThreadId.parse("T-01a16702-3202-73fb-8f52-e4498ccd3721")!!

    @Test
    fun `the recorded frames read as asleep, then waking without this app's wake, then connected`() {
        assertEquals(ExecutorStatus.OrbAsleep, executorAfter("s21-orb-sleep"))
        assertEquals(ExecutorStatus.OrbWaking, executorAfter("s21-orb-wake", through = 39))
        assertEquals(ExecutorStatus.Attached("sandbox", "running", "Executor connected"), executorAfter("s21-orb-wake"))
    }

    @Test
    fun `a runner that went offline still reads as no executor`() {
        assertEquals(ExecutorStatus.None, executorAfter("s09-transcript"))
    }

    private fun executorAfter(capture: String, through: Int = Int.MAX_VALUE): ExecutorStatus {
        val frames = File(System.getProperty("amp.captures"), "$capture.jsonl").readLines().mapNotNull { line ->
            val entry = AmpJson.parseToJsonElement(line) as JsonObject
            val n = (entry["n"] as JsonPrimitive).content.toInt()
            val recv = (entry["dir"] as JsonPrimitive).content == "recv" && (entry["ch"] as JsonPrimitive).content.startsWith("thread")
            (entry["data"] as JsonObject).takeIf { recv && n <= through && "binary" !in it && "rawText" !in it }?.toString()
        }
        val facts = frames.flatMap(FrameParser::parse).filterIsInstance<InboundFrame.Event>().map(WireDecoders::decode)
        return project(thread, facts.fold(ThreadFold.empty(), ThreadReducer::reduce), Link.Live, persistentListOf(), persistentListOf()).executor
    }

    @Test
    fun `a shown thread wakes its sleeping orb once, across two windows, and the executor connects`() = withEngine("orb-asleep") { server, engine ->
        val first = engine.attach(ViewerToken("window-1"))
        val second = engine.attach(ViewerToken("window-2"))
        assertEquals(ExecutorStatus.OrbWaking, engine.awaitState("the wake") { it.executor == ExecutorStatus.OrbWaking }.executor)
        val connected = engine.awaitState("the orb") { (it.executor as? ExecutorStatus.Attached)?.status == "running" }
        assertEquals(ExecutorStatus.Attached("sandbox", "running", "Executor connected"), connected.executor)
        assertEquals(listOf(thread.raw), server.orbWakes)
        first.close()
        second.close()
    }

    @Test
    fun `a failed wake shows the reason, and Retry sends one more`() = withEngine("orb-wake-fails") { server, engine ->
        val lease = engine.attach(ViewerToken("window-1"))
        val failed = engine.awaitState("the failure") { it.executor is ExecutorStatus.OrbWakeFailed }
        assertEquals(ExecutorStatus.OrbWakeFailed("The orb could not resume."), failed.executor)
        assertEquals(listOf(thread.raw), server.orbWakes)

        engine.connectLive()
        engine.awaitState("the retry") { it.executor == ExecutorStatus.OrbWaking }
        engine.awaitState("the second failure") { it.executor is ExecutorStatus.OrbWakeFailed }
        assertEquals(listOf(thread.raw, thread.raw), server.orbWakes)
        lease.close()
    }

    @Test
    fun `a wake that does not connect within the timeout fails`() = withEngine("orb-asleep", wakeTimeoutMs = 500) { _, engine ->
        val lease = engine.attach(ViewerToken("window-1"))
        val failed = engine.awaitState("the timeout") { it.executor is ExecutorStatus.OrbWakeFailed }
        assertEquals(ExecutorStatus.OrbWakeFailed("The orb did not come back online within 2 minutes."), failed.executor)
        lease.close()
    }

    @Test
    fun `a background hold on the socket never wakes the orb`() = withEngine("orb-asleep") { server, engine ->
        val hold = engine.hold()
        val live = engine.awaitState("the socket") { it.link == Link.Live && it.executor != ExecutorStatus.Unknown }
        delay(1_000)
        assertEquals(ExecutorStatus.OrbAsleep, live.executor)
        assertEquals(ExecutorStatus.OrbAsleep, engine.state.value.executor)
        assertEquals(emptyList<String>(), server.orbWakes)
        hold.close()
    }

    private fun ThreadEngine.awaitState(what: String, predicate: (ThreadState) -> Boolean): ThreadState = runBlocking {
        withTimeout(20_000) { state.first(predicate) }
    }

    private fun withEngine(scenario: String, wakeTimeoutMs: Long = 120_000, block: suspend (FakeAmpServer, ThreadEngine) -> Unit) =
        FakeAmp.start(scenario, frameGapCapMs = 20).use { server ->
            val client = OkHttpClient()
            val endpoint = Endpoint(server.ampBaseUrl.toHttpUrl(), server.actorsBaseUrl.toHttpUrl(), "pk_fake")
            val http = AmpHttp(client, endpoint, FixedBearer("fake-amp-accepts-any-bearer"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val deps = testThreadDeps(client, endpoint, http, AttachmentUploader(client, endpoint, FixedBearer("fake-amp-accepts-any-bearer")), scope)
            val engine = ThreadEngine(thread, deps, scope, wakeTimeoutMs = wakeTimeoutMs)
            try {
                runBlocking { withTimeout(30_000) { block(server, engine) } }
            } finally {
                scope.cancel()
            }
        }
}
