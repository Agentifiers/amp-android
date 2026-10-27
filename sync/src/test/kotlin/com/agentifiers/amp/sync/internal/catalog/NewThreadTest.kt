package com.agentifiers.amp.sync.internal.catalog

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.fake.FakeAmpServer
import com.agentifiers.amp.model.Draft
import com.agentifiers.amp.model.ExecutorChoice
import com.agentifiers.amp.model.ExecutorStatus
import com.agentifiers.amp.model.OrbProvider
import com.agentifiers.amp.model.OrbSize
import com.agentifiers.amp.model.RunnerId
import com.agentifiers.amp.model.SendMode
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.TurnEnd
import com.agentifiers.amp.model.UserBlock
import com.agentifiers.amp.model.TranscriptView
import com.agentifiers.amp.model.WorktreeChoice
import com.agentifiers.amp.store.Outbox
import com.agentifiers.amp.store.OutboxRow
import com.agentifiers.amp.store.OutboxRowState
import com.agentifiers.amp.sync.api.ViewerToken
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.FixedBearer
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.NetLog
import com.agentifiers.amp.sync.internal.net.Outcome
import com.agentifiers.amp.sync.internal.thread.Spawned
import com.agentifiers.amp.sync.internal.thread.ThreadDeps
import com.agentifiers.amp.sync.internal.thread.ThreadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import com.agentifiers.amp.sync.internal.wire.array
import com.agentifiers.amp.sync.internal.wire.string
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.agentifiers.amp.sync.internal.CuttableClient
import com.agentifiers.amp.sync.internal.fleet.testThreadDeps
import com.agentifiers.amp.sync.internal.attachments.AttachmentUploader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** New threads, their executors, and the catalog, against :fake-amp's `newthread` scenarios over real sockets. */
class NewThreadTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: FakeAmpServer
    private lateinit var http: AmpHttp
    private lateinit var deps: ThreadDeps
    private val sockets = CuttableClient()
    private val client = sockets.client

    @After
    fun tearDown() {
        scope.cancel()
        sockets.cut()
        server.close()
    }

    private fun start(scenario: String) {
        server = FakeAmp.start(scenario, frameGapCapMs = 20)
        val endpoint = Endpoint(server.ampBaseUrl.toHttpUrl(), server.actorsBaseUrl.toHttpUrl(), "pk-test")
        http = AmpHttp(client, endpoint, FixedBearer("fake-token"))
        deps = testThreadDeps(client, endpoint, http, AttachmentUploader(client, endpoint, FixedBearer("fake-token")), scope) { error("unauthorized") }
    }

    private fun created(mode: String): ThreadEngine = runBlocking {
        val thread = (http.createThread(mode) as Outcome.Ok<ThreadId>).value
        ThreadEngine(thread, deps, scope)
    }

    private fun ThreadEngine.awaitState(what: String, predicate: (ThreadState) -> Boolean): ThreadState = runBlocking {
        try {
            withTimeout(20_000) { state.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what; last state: ${state.value}", e)
        }
    }

    @Test
    fun aRunnerSpawnOnANewThreadRunsAndTheThreadKeepsItsMode() {
        start("newthread")
        val engine = created("high")
        val spawned = runBlocking { engine.spawn(ExecutorChoice.Runner(RunnerId("android-spike"), "/private/tmp/amp-scratch", WorktreeChoice.InPlace, null)) }
        assertEquals(Spawned.Ok, spawned)

        engine.attach(ViewerToken("test-window"))
        engine.dispatch(ThreadIntent.Send(Draft.text("Rename add to add_numbers in calc.py and run it"), SendMode.Auto))
        val replied = engine.awaitState("the reply") { s ->
            (s.transcript as? TranscriptView.Ready)?.turns?.any { it.end == TurnEnd.Done } == true
        }
        assertEquals("high", replied.mode)
        assertEquals(ExecutorStatus.Attached("local-client", "running", "Executor connected"), replied.executor)
        assertEquals(
            listOf("Rename add to add_numbers in calc.py and run it"),
            (replied.transcript as TranscriptView.Ready).turns.mapNotNull { it.prompt }.flatMap { p -> p.blocks.filterIsInstance<UserBlock.Text>().map { it.markdown } },
        )
    }

    @Test
    fun anOrbSpawnReportsAnOrbExecutor() {
        start("newthread")
        val engine = created("low")
        val spawned = runBlocking { engine.spawn(ExecutorChoice.Orb(null, OrbSize("a1.small"), OrbProvider.Amp)) }
        assertEquals(Spawned.Ok, spawned)
        engine.attach(ViewerToken("test-window"))
        val running = engine.awaitState("the orb to run") { it.executor is ExecutorStatus.Attached && (it.executor as ExecutorStatus.Attached).status == "running" }
        assertEquals(ExecutorStatus.Attached("orb", "running", "Executor connected"), running.executor)
        assertEquals("low", running.mode)
    }

    @Test
    fun aRefusedSpawnLeavesTheThreadAndSaysWhy() {
        start("newthread-spawn-fails")
        val engine = created("low")
        val spawned = runBlocking { engine.spawn(ExecutorChoice.Runner(RunnerId("android-spike"), "/private/tmp/amp-scratch", WorktreeChoice.InPlace, null)) }
        assertEquals(Spawned.Failed("Runner android-spike is not connected."), spawned)
        val listed = runBlocking { http.internal("listThreads", JsonObject(emptyMap())) } as Outcome.Ok
        assertEquals(listOf(engine.id.raw), (listed.value as JsonObject).array("threads")!!.map { (it as JsonObject).string("id") })
    }

    @Test
    fun theCatalogListsTheOnlineRunnerWithItsModesAndFallsBackToTheDial() {
        start("newthread")
        val catalog = CatalogEngine(http, scope) { error("unauthorized") }
        runBlocking { catalog.refresh() }

        val runner = catalog.runners.value.single()
        assertEquals(RunnerId("android-spike"), runner.id)
        assertEquals("devbox.local", runner.hostname)
        assertEquals(listOf("/private/tmp/amp-scratch"), runner.directories)
        assertEquals(true, runner.canCreateWorktree)
        assertEquals(false, runner.shared)
        assertEquals(34, runner.modes.size)
        assertEquals("gpt-6-astra-low" to "GPT-6 Astra Low", runner.modes.first().let { it.key to it.display })
        assertEquals(listOf("low", "medium", "high", "ultra"), catalog.modes.value.map { it.key })
        assertEquals(listOf("a1.tiny", "a1.small", "a1.medium", "a1.large", "a1.xxlarge", "a1.3xlarge"), catalog.orbSizes.value.map { it.key })
    }
}
