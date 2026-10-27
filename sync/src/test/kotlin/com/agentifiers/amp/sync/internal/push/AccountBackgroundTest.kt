package com.agentifiers.amp.sync.internal.push

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.fake.FakeAmpServer
import com.agentifiers.amp.fake.PushDelivery
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.HumanGate
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ToolApprovalGate
import com.agentifiers.amp.store.Outbox
import com.agentifiers.amp.store.OutboxRow
import com.agentifiers.amp.store.OutboxRowState
import com.agentifiers.amp.store.PushSubRow
import com.agentifiers.amp.store.PushSubscriptions
import com.agentifiers.amp.store.WatchList
import com.agentifiers.amp.store.WatchRow
import com.agentifiers.amp.sync.api.FleetSignal
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.PushChannel
import com.agentifiers.amp.sync.api.PushEvent
import com.agentifiers.amp.sync.internal.changes.MemoryDatabase
import com.agentifiers.amp.sync.internal.attachments.AttachmentUploader
import com.agentifiers.amp.sync.internal.fleet.FleetEngine
import com.agentifiers.amp.sync.internal.fleet.MemoryLedger
import com.agentifiers.amp.sync.internal.fleet.MemoryWatchList
import com.agentifiers.amp.sync.internal.fleet.Organizer
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.FixedBearer
import com.agentifiers.amp.sync.internal.net.NetLog
import com.agentifiers.amp.sync.internal.net.Outcome as NetOutcome
import com.agentifiers.amp.sync.internal.thread.ThreadDeps
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.KeyAgreement

/** [AccountBackground] against `:fake-amp` over real HTTP and WebSocket, with software push keys in place of the Keystore. */
class AccountBackgroundTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outbox = MemoryOutbox()
    private val subscriptions = MemorySubscriptions()
    private var fake: FakeAmpServer? = null
    private val receiver = MockWebServer()
    private val deployedRelay = MockWebServer()

    @After
    fun stop() {
        scope.cancel()
        fake?.close()
        receiver.close()
        deployedRelay.close()
    }

    @Test
    fun aRepliedTurnEndsInAThreadIdlePushThatDecryptsWithTheAccountKeys() = runBlocking {
        val background = start("push")
        receiver.start()
        val endpoint = receiver.url("/push/up-test").toString()
        assertEquals(Outcome.Ok(endpoint), background.ensurePushRegistration(PushChannel.UnifiedPush(endpoint)))
        assertEquals(Outcome.Ok(Unit), background.subscribe(RUNNER))
        assertEquals(listOf(RUNNER.raw), subscriptions.rows.map { it.threadId })

        val sent = background.quickReply(RUNNER, "Rename add to add_numbers") as Outcome.Ok
        assertEquals(listOf(sent.value.raw), outbox.acknowledged)
        assertEquals(emptyList<OutboxRow>(), outbox.rows.values.filter { it.messageId !in outbox.acknowledged })

        val post = receiver.takeRequest(30, TimeUnit.SECONDS)!!
        assertEquals("aes128gcm", post.headers["Content-Encoding"])
        val event = background.decodePush(post.body!!.toByteArray()) as PushEvent.ThreadIdle
        assertEquals(RUNNER, event.thread)
        assertEquals("Rename add to add_numbers", event.title)
        assertTrue(event.tag, event.tag.startsWith("amp-thread-${RUNNER.raw}-idle-M-"))
    }

    @Test
    fun aThreadStartedElsewhereIsSubscribedByOneReconcileAndItsTurnPushes() = runBlocking {
        val background = start("push")
        receiver.start()
        val endpoint = receiver.url("/push/up-test").toString()
        assertEquals(Outcome.Ok(endpoint), background.ensurePushRegistration(PushChannel.UnifiedPush(endpoint)))
        assertEquals(Outcome.Ok(1), background.reconcilePushSubscriptions())

        fake!!.startThreadElsewhere(ELSEWHERE.raw, "Started on the web")
        assertEquals(Outcome.Ok(1), background.reconcilePushSubscriptions())
        assertEquals(Outcome.Ok(0), background.reconcilePushSubscriptions())

        awaitNoSockets(ELSEWHERE)
        assertEquals(1, fake!!.finishTurnElsewhere(ELSEWHERE.raw, "Done elsewhere."))
        val post = receiver.takeRequest(30, TimeUnit.SECONDS)!!
        val event = background.decodePush(post.body!!.toByteArray()) as PushEvent.ThreadIdle
        assertEquals(ELSEWHERE, event.thread)
        assertEquals("Started on the web", event.title)
        assertEquals("Done elsewhere.", event.body)
        val summary = (background.recentStatuses(kotlin.time.Instant.fromEpochMilliseconds(0)) as Outcome.Ok).value.single { it.id == ELSEWHERE }
        assertEquals(event.tag, "amp-thread-${ELSEWHERE.raw}-idle-${summary.lastReply!!.raw}")
    }

    @Test
    fun noPushComesWhileAnotherClientHoldsTheThreadOpen() = runBlocking {
        val background = start("push")
        receiver.start()
        val endpoint = receiver.url("/push/up-test").toString()
        assertEquals(Outcome.Ok(endpoint), background.ensurePushRegistration(PushChannel.UnifiedPush(endpoint)))
        fake!!.startThreadElsewhere(ELSEWHERE.raw, "Started on the web")
        assertEquals(Outcome.Ok(2), background.reconcilePushSubscriptions())
        awaitNoSockets(ELSEWHERE)

        fake!!.holdOpenElsewhere(ELSEWHERE.raw, open = true)
        assertEquals(0, fake!!.finishTurnElsewhere(ELSEWHERE.raw, "Done while open in a browser."))
        fake!!.holdOpenElsewhere(ELSEWHERE.raw, open = false)
        assertEquals(1, fake!!.finishTurnElsewhere(ELSEWHERE.raw, "Done with no client."))
        val event = background.decodePush(receiver.takeRequest(30, TimeUnit.SECONDS)!!.body!!.toByteArray()) as PushEvent.ThreadIdle
        assertEquals("Done with no client.", event.body)
        assertEquals(1, receiver.requestCount)
    }

    @Test
    fun aNewEndpointResubscribesEveryRecentThread() = runBlocking {
        val background = start("push")
        receiver.start()
        val first = receiver.url("/push/a").toString()
        assertEquals(Outcome.Ok(first), background.ensurePushRegistration(PushChannel.UnifiedPush(first)))
        assertEquals(Outcome.Ok(1), background.reconcilePushSubscriptions())

        val second = receiver.url("/push/b").toString()
        assertEquals(Outcome.Ok(second), background.ensurePushRegistration(PushChannel.UnifiedPush(second)))
        assertEquals(Outcome.Ok(1), background.reconcilePushSubscriptions())
    }

    @Test
    fun aBuildWithNoRelayRefusesTheRelayChannelAndRegistersUnifiedPush() = runBlocking {
        val background = start("push")
        assertEquals(Outcome.Refused("This build has no push relay."), background.ensurePushRegistration(PushChannel.Relay("fcm-token")))
        assertEquals(Outcome.Refused("This device has no push registration."), background.reconcilePushSubscriptions())

        receiver.start()
        val endpoint = receiver.url("/push/up-test").toString()
        assertEquals(Outcome.Ok(endpoint), background.ensurePushRegistration(PushChannel.UnifiedPush(endpoint)))
        assertEquals(Outcome.Ok(1), background.reconcilePushSubscriptions())
    }

    @Test
    fun anAccountOnALocalServerNeverCallsTheRelay() = runBlocking {
        deployedRelay.start()
        val background = start("push", relayUrl = deployedRelay.url("/"), ownServerPush = true)
        val delivered = CopyOnWriteArrayList<PushDelivery>()
        fake!!.onPush = { delivered += it }
        val pushUrl = "${fake!!.ampBaseUrl}/push/A-0123456789abcdef"

        assertEquals(Outcome.Ok(pushUrl), background.ensurePushRegistration(PushChannel.Relay("fcm-token")))
        assertEquals(0, deployedRelay.requestCount)

        assertEquals(Outcome.Ok(Unit), background.subscribe(RUNNER))
        awaitNoSockets(RUNNER)
        assertEquals(1, fake!!.finishTurnElsewhere(RUNNER.raw, "Done on this machine."))
        assertEquals(listOf(pushUrl), delivered.map { it.endpoint })
        assertEquals("Done on this machine.", (background.decodePush(delivered.single().body) as PushEvent.ThreadIdle).body)
        assertEquals(0, deployedRelay.requestCount)
    }

    @Test
    fun aDeviceWithNoPushChannelRefusesRegistration() = runBlocking {
        assertEquals(Outcome.Refused("This device has no push channel."), start("push").ensurePushRegistration(PushChannel.None))
    }

    @Test
    fun aDeviceWithNoPushChannelStillRegistersAnAccountOnALocalServer() = runBlocking {
        val background = start("push", ownServerPush = true)
        assertEquals(Outcome.Ok("${fake!!.ampBaseUrl}/push/A-0123456789abcdef"), background.ensurePushRegistration(PushChannel.None))
        assertEquals(Outcome.Ok(1), background.reconcilePushSubscriptions())
    }

    @Test
    fun allowSettlesTheGateAndASecondAnswerIsRefusedAsAnsweredElsewhere() = runBlocking {
        val background = start("push-gates")
        background.quickReply(GATE, "clean up") as Outcome.Ok
        val gate = awaitGate(background)
        assertEquals("rm -rf build", gate.summary)
        assertEquals("shell_command", gate.tool.raw)

        assertEquals(Outcome.Ok(Unit), background.answer(GATE, gate.approve()))
        assertEquals(Outcome.Refused("Answered elsewhere"), background.answer(GATE, gate.approve()))
        awaitTranscript("Removed the build directory.")
    }

    @Test
    fun denySettlesTheGateAsRejectedByTheUser() = runBlocking {
        val background = start("push-gates")
        background.quickReply(GATE, "clean up") as Outcome.Ok
        val gate = awaitGate(background)
        assertEquals(Outcome.Ok(Unit), background.answer(GATE, gate.deny(null)))
        assertEquals(emptyList<HumanGate>(), (background.peekGates(GATE) as Outcome.Ok).value.toList())
        awaitTranscript("\"status\":\"rejected-by-user\"")
    }

    @Test
    fun peekingAndMarkingReadOnASleepingOrbThreadNeverWakesIt() = runBlocking {
        val background = start("orb-asleep")
        assertEquals(emptyList<HumanGate>(), (background.peekGates(RUNNER) as Outcome.Ok).value.toList())
        background.markRead(RUNNER, MessageId("M-Dhm6ABrxGfqFPd1YyU6N01"))
        delay(1_000)
        assertEquals(emptyList<String>(), fake!!.orbWakes)
    }

    @Test
    fun theWatchSocketReportsTheThreadWorkingThenDone() = runBlocking {
        val background = start("push-gates")
        val signals = mutableListOf<FleetSignal.StatusChanged>()
        val watching = scope.launch { background.watchFleet().collect { signals += it as FleetSignal.StatusChanged } }
        delay(500)
        background.quickReply(GATE, "clean up") as Outcome.Ok
        background.answer(GATE, awaitGate(background).approve())
        withTimeout(20_000) { while (signals.none { it.summary.id == GATE && it.summary.status == FleetStatus.Done }) delay(100) }
        watching.cancel()
        val statuses = signals.filter { it.summary.id == GATE }.map { it.summary.status }
        val runs = statuses.filterIndexed { i, status -> i == 0 || statuses[i - 1] != status }
        assertEquals(listOf(FleetStatus.Error, FleetStatus.Working, FleetStatus.Idle, FleetStatus.Done), runs)
    }

    private suspend fun awaitGate(background: AccountBackground): ToolApprovalGate = withTimeout(20_000) {
        var gate: ToolApprovalGate? = null
        while (gate == null) {
            gate = (background.peekGates(GATE) as Outcome.Ok).value.filterIsInstance<ToolApprovalGate>().firstOrNull()
            if (gate == null) delay(200)
        }
        gate
    }

    private lateinit var http: AmpHttp

    private suspend fun awaitTranscript(text: String) = withTimeout(20_000) {
        while (!transcript().contains(text)) delay(200)
    }

    /** A short socket closes after its job; until the fake sees the close, the thread counts as open and gets no push. */
    private suspend fun awaitNoSockets(thread: ThreadId) = withTimeout(10_000) {
        while (fake!!.socketCount(thread.raw) > 0) delay(50)
    }

    private suspend fun transcript(): String {
        val page = http.internal("getThreadInitialTranscriptPage", buildJsonObject { put("thread", GATE.raw) })
        return (page as NetOutcome.Ok).value.toString()
    }

    private fun start(scenario: String, relayUrl: HttpUrl? = null, ownServerPush: Boolean = false): AccountBackground {
        val server = FakeAmp.start(scenario, frameGapCapMs = 20).also { fake = it }
        val client = OkHttpClient()
        val endpoint = Endpoint(server.ampBaseUrl.toHttpUrl(), server.actorsBaseUrl.toHttpUrl(), "fake-rivet-publishable")
        http = AmpHttp(client, endpoint, FixedBearer("fake-amp-accepts-any-bearer"))
        val rejected: () -> Unit = { error("the fake rejected the token") }
        val uploader = AttachmentUploader(client, endpoint, FixedBearer("fake-amp-accepts-any-bearer"))
        val watched = WatchedThreads(MemoryWatchList(), scope)
        val fleet = FleetEngine(http, scope, rejected, watched, MemoryLedger(), MutableStateFlow(emptySet()), dispatch = { _, _ -> error("this test dispatches no organize write") }, threadState = { error("this test views no thread") })
        val deps = ThreadDeps(client, endpoint, http, "test", NetLog(false), rejected, MemoryDatabase(outbox), uploader, Organizer(http, fleet, scope, rejected), fleet::self)
        val relay = relayUrl?.let { PushRelayClient(client, it) }
        val ownPushUrl = if (ownServerPush) endpoint.ownPushUrl(ACCOUNT) else null
        return AccountBackground(deps, outbox, subscriptions, MemorySecrets(), relay, ownPushUrl, watched, MutableStateFlow(persistentSetOf()))
    }

    private class MemorySecrets : PushSecrets {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        private val keys = PushKeys(WebPushDecryptor.uncompressed(pair.public as ECPublicKey), ByteArray(16) { it.toByte() }) { peer ->
            KeyAgreement.getInstance("ECDH").run {
                init(pair.private)
                doPhase(peer, true)
                generateSecret()
            }
        }
        private var registration: PushRegistration? = null
        override fun keys() = keys
        override fun registration() = registration
        override fun saveRegistration(registration: PushRegistration?) {
            this.registration = registration
        }
        override fun wipe() {
            registration = null
        }
    }

    private class MemoryOutbox : Outbox {
        val rows = LinkedHashMap<String, OutboxRow>()
        val acknowledged = mutableListOf<String>()
        override fun observe(threadId: String): Flow<List<OutboxRow>> = flowOf(rows.values.filter { it.threadId == threadId })
        override suspend fun rows(threadId: String): List<OutboxRow> = rows.values.filter { it.threadId == threadId }
        override suspend fun enqueue(row: OutboxRow) {
            rows.putIfAbsent(row.intentId, row)
        }
        override suspend fun mark(intentId: String, state: OutboxRowState, reason: String?) {
            rows[intentId]?.let { rows[intentId] = it.copy(state = state, reason = reason) }
        }
        override suspend fun acknowledge(messageIds: List<String>) {
            acknowledged += messageIds
            rows.values.removeAll { it.messageId in messageIds }
        }
        override suspend fun discard(intentId: String) {
            rows.remove(intentId)
        }
        override suspend fun pendingAcrossThreads(): List<OutboxRow> = rows.values.toList()
    }

    private class MemorySubscriptions : PushSubscriptions {
        val rows = mutableListOf<PushSubRow>()
        override suspend fun isSubscribed(threadId: String, endpointHash: String) = rows.any { it.threadId == threadId && it.endpointHash == endpointHash }
        override suspend fun record(row: PushSubRow) {
            rows += row
        }
        override suspend fun forgetEndpoint(endpointHash: String) {
            rows.removeAll { it.endpointHash == endpointHash }
        }
    }


    private companion object {
        val ACCOUNT = AccountId("A-0123456789abcdef")
        val RUNNER = ThreadId.parse("T-01a16702-3202-73fb-8f52-e4498ccd3721")!!
        val GATE = ThreadId.parse("T-01a16738-2ec2-7354-98e9-688bc413f079")!!
        val ELSEWHERE = ThreadId.parse("T-01a1a000-0e15-7e00-8000-00000000e15e")!!
    }
}
