package com.agentifiers.amp.sync.internal.outbox

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.fake.FakeAmpServer
import com.agentifiers.amp.model.AccountCommand
import com.agentifiers.amp.model.AgentActivity
import com.agentifiers.amp.model.Draft
import com.agentifiers.amp.model.Guard
import com.agentifiers.amp.model.IntentId
import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.OutboxState
import com.agentifiers.amp.model.QueuedMessageId
import com.agentifiers.amp.model.SecretTarget
import com.agentifiers.amp.model.SendMode
import com.agentifiers.amp.model.SendState
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.TranscriptView
import com.agentifiers.amp.model.UserBlock
import com.agentifiers.amp.store.Outbox
import com.agentifiers.amp.store.OutboxRow
import com.agentifiers.amp.store.OutboxRowState
import com.agentifiers.amp.sync.api.ViewerToken
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.FixedBearer
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.NetLog
import com.agentifiers.amp.sync.internal.thread.ThreadDeps
import com.agentifiers.amp.sync.internal.thread.ThreadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.agentifiers.amp.sync.internal.CuttableClient
import com.agentifiers.amp.sync.internal.attachments.AttachmentUploader
import com.agentifiers.amp.sync.internal.changes.MemoryDatabase
import com.agentifiers.amp.sync.internal.fleet.testThreadDeps
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.random.Random

/**
 * Each delivery class against :fake-amp over real sockets. "Offline" is the fake server being down: commands are
 * dispatched while nothing listens on its port, then the server starts on that port and the engine reconnects.
 */
class OutboxDrainTest {
    private val runner = ThreadId.parse("T-01a16702-3202-73fb-8f52-e4498ccd3721")!!
    private val gate = ThreadId.parse("T-01a16738-2ec2-7354-98e9-688bc413f079")!!
    private val port = portOutsideEphemeralRange()
    private val store = MemoryOutbox()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: FakeAmpServer? = null
    private val sockets = CuttableClient()
    private val client = sockets.client

    @After
    fun tearDown() {
        scope.cancel()
        sockets.cut()
        server?.close()
    }

    private fun engine(thread: ThreadId): ThreadEngine {
        val base = "http://127.0.0.1:$port"
        val endpoint = Endpoint(base.toHttpUrl(), "$base/actors".toHttpUrl(), "pk-test")
        val deps = testThreadDeps(client, endpoint, AmpHttp(client, endpoint, FixedBearer("fake-token")), AttachmentUploader(client, endpoint, FixedBearer("fake-token")), scope, MemoryDatabase(store)) { error("unauthorized") }
        return ThreadEngine(thread, deps, scope)
    }

    private fun online(engine: ThreadEngine) {
        server = FakeAmp.start("outbox", port = port, frameGapCapMs = 20)
        engine.connectLive()
    }

    private fun ThreadEngine.awaitState(what: String, predicate: (ThreadState) -> Boolean): ThreadState = runBlocking {
        try {
            withTimeout(20_000) { state.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what; last state: ${state.value}", e)
        }
    }

    private fun ThreadState.userTexts(): List<String> =
        (transcript as? TranscriptView.Ready)?.turns.orEmpty().mapNotNull { it.prompt }.flatMap { p -> p.blocks.filterIsInstance<UserBlock.Text>().map { it.markdown } }

    @Test
    fun replayableSendWaitsOutTheOfflinePeriodAndIsDeliveredOnce() {
        val engine = engine(runner)
        engine.dispatch(ThreadIntent.Send(Draft.text("queued while offline"), SendMode.Auto, MessageId("M-offline0000000000000001")))
        val waiting = engine.awaitState("the send to wait for the network") { it.pending.singleOrNull()?.state == SendState.Waiting && it.transcript is TranscriptView.Failed }
        assertEquals(listOf("queued while offline"), waiting.pending.map { it.text })
        assertEquals(listOf(OutboxRowState.WaitingNetwork), store.rows.values.map { it.state })

        online(engine)

        val delivered = engine.awaitState("the echo") { it.pending.isEmpty() && "queued while offline" in it.userTexts() }
        assertEquals(1, delivered.userTexts().count { it == "queued while offline" })
        assertEquals(emptyMap<String, OutboxRow>(), store.rows)
    }

    @Test
    fun replayableSendThatLostItsAnswerIsSentAgainAndLeavesOneCopy() {
        val engine = engine(runner)
        val send = ThreadIntent.Send(Draft.text("sent before the crash"), SendMode.Auto, MessageId("M-inflight000000000000001"))
        runBlocking { engine.seed(listOf(send), inFlight = true) }
        assertEquals(listOf(OutboxRowState.Queued), store.rows.values.map { it.state })

        server = FakeAmp.start("outbox", port = port, frameGapCapMs = 20)
        engine.attach(ViewerToken("test-window"))

        val delivered = engine.awaitState("the echo") { it.pending.isEmpty() && "sent before the crash" in it.userTexts() }
        assertEquals(1, delivered.userTexts().count { it == "sent before the crash" })
        assertEquals(emptyMap<String, OutboxRow>(), store.rows)
    }

    @Test
    fun atMostOnceCommandQueuedOfflineIsSentOnceWhenTheNetworkReturns() {
        val engine = engine(runner)
        val id = engine.dispatch(ThreadIntent.RestartOrbProcesses)
        engine.awaitState("the command to wait for the network") { it.outbox.singleOrNull()?.state is OutboxState.WaitingForNetwork }

        server = FakeAmp.start("outbox", port = port, frameGapCapMs = 20)

        val settled = engine.awaitState("Amp's answer") { it.outbox.singleOrNull()?.state is OutboxState.Rejected }
        assertEquals(OutboxState.Rejected("not-found: No method restartOrbProcesses"), settled.outbox.single().state)
        assertEquals(id.raw, store.rows.values.single().intentId)
        assertEquals("not-found: No method restartOrbProcesses", store.rows.values.single().reason)
    }

    @Test
    fun atMostOnceCommandThatLostItsAnswerBecomesUnknownAndIsNeverResent() {
        val engine = engine(runner)
        runBlocking { engine.seed(listOf(ThreadIntent.Interrupt), inFlight = true) }
        server = FakeAmp.start("outbox", port = port, frameGapCapMs = 20)
        engine.attach(ViewerToken("test-window"))

        val live = engine.awaitState("the live socket") { it.link == Link.Live }
        assertEquals(OutboxState.Unknown, live.outbox.single().state)
        assertEquals(listOf(OutboxRowState.Unknown), store.rows.values.map { it.state })
    }

    @Test
    fun guardedCommandIsDroppedAfterResumeWhenItsGuardNoLongerHolds() {
        val engine = engine(runner)
        engine.dispatch(ThreadIntent.SteerQueued(QueuedMessageId("M-goneFromTheQueue0000001")))
        engine.dispatch(ThreadIntent.Retry)
        engine.awaitState("both commands to wait") { s -> s.outbox.size == 2 && s.outbox.all { it.state is OutboxState.WaitingForNetwork } }

        online(engine)

        val dropped = engine.awaitState("both guards to fail") { s -> s.outbox.size == 2 && s.outbox.all { it.state is OutboxState.Dropped } }
        assertEquals(
            listOf(OutboxState.Dropped(Guard.QueuedStillPresent(QueuedMessageId("M-goneFromTheQueue0000001"))), OutboxState.Dropped(Guard.AgentFailed)),
            dropped.outbox.map { it.state },
        )
        assertEquals(listOf(OutboxRowState.Dropped, OutboxRowState.Dropped), store.rows.values.map { it.state })
    }

    @Test
    fun guardedCommandIsDeliveredWhenItsGuardStillHoldsAfterResume() {
        val engine = engine(gate)
        engine.dispatch(ThreadIntent.Retry)
        engine.awaitState("the retry to wait") { it.outbox.singleOrNull()?.state is OutboxState.WaitingForNetwork }

        online(engine)

        val delivered = engine.awaitState("the retry to be delivered") { it.link == Link.Live && it.outbox.isEmpty() }
        assertEquals(AgentActivity.Failed, delivered.activity)
        assertEquals(emptyMap<String, OutboxRow>(), store.rows)
    }

    @Test
    fun ephemeralCommandNeverReachesTheOutboxTable() = runBlocking {
        val queue = CommandQueue(runner, store)
        val secret = AccountCommand.SetSecret(SecretTarget.Personal, "OPENAI_API_KEY", "sk-not-a-real-key", secret = true)
        queue.submit(IntentId("I-secret"), secret)
        queue.set(IntentId("I-secret"), OutboxState.Unknown)

        assertEquals(emptyMap<String, OutboxRow>(), store.rows)
        assertEquals(listOf(IntentId("I-secret") to OutboxState.Unknown), queue.entries().map { it.id to it.state })
    }

    @Test
    fun sendOverTheRowCapIsRefusedAndNeverReachesTheOutboxTable() = runBlocking {
        val queue = CommandQueue(runner, store)
        queue.submit(IntentId("I-large"), ThreadIntent.Send(Draft.text("a".repeat(1_048_576)), SendMode.Auto, MessageId("M-tooLargeSend00000000001")))

        assertEquals(emptyMap<String, OutboxRow>(), store.rows)
        assertEquals(listOf(SendState.Failed("Too large to send. Keep it under 1 MB.")), queue.pending().map { it.state })
    }

    @Test
    fun sendUnderTheRowCapIsStored() = runBlocking {
        val queue = CommandQueue(runner, store)
        queue.submit(IntentId("I-fits"), ThreadIntent.Send(Draft.text("a".repeat(1_000_000)), SendMode.Auto, MessageId("M-fitsSend000000000000001")))

        assertEquals(listOf("I-fits"), store.rows.keys.toList())
        assertEquals(listOf(SendState.Waiting), queue.pending().map { it.state })
    }
}

/**
 * A free port below the ephemeral range. The fake starts on it only after an offline stretch, and a port 0 hands out
 * comes from the ephemeral range, where any socket opened meanwhile can take it and the fake's bind then fails.
 */
private fun portOutsideEphemeralRange(): Int {
    repeat(100) {
        val candidate = Random.nextInt(20_000, 30_000)
        val free = runCatching { ServerSocket(candidate, 0, InetAddress.getByName("127.0.0.1")).close() }.isSuccess
        if (free) return candidate
    }
    error("no free port in 20000-29999")
}

/** The `outbox` table's contract, in memory: rows keyed by intent id, insertion order kept. */
private class MemoryOutbox : Outbox {
    val rows = LinkedHashMap<String, OutboxRow>()
    private val changes = MutableStateFlow(0)

    override fun observe(threadId: String): Flow<List<OutboxRow>> = changes.map { snapshot(threadId) }

    override suspend fun rows(threadId: String): List<OutboxRow> = snapshot(threadId)

    override suspend fun enqueue(row: OutboxRow) = edit { rows.putIfAbsent(row.intentId, row) }

    override suspend fun mark(intentId: String, state: OutboxRowState, reason: String?) = edit {
        rows[intentId]?.let { rows[intentId] = it.copy(state = state, reason = reason, attempts = it.attempts + if (state == OutboxRowState.Sending) 1 else 0) }
    }

    override suspend fun acknowledge(messageIds: List<String>) = edit { rows.values.removeAll { it.messageId in messageIds } }

    override suspend fun discard(intentId: String) = edit { rows.remove(intentId) }

    override suspend fun pendingAcrossThreads(): List<OutboxRow> = synchronized(rows) {
        rows.values.filter { it.state in listOf(OutboxRowState.Queued, OutboxRowState.Sending, OutboxRowState.WaitingNetwork) }
    }

    private fun snapshot(threadId: String) = synchronized(rows) { rows.values.filter { it.threadId == threadId } }

    private fun edit(block: () -> Unit) {
        synchronized(rows) { block() }
        changes.value++
    }
}
