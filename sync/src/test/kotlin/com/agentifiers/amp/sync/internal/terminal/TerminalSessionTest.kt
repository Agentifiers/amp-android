package com.agentifiers.amp.sync.internal.terminal

import com.agentifiers.amp.sync.internal.fleet.testThreadDeps
import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.fake.FakeAmpServer
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.TerminalSession
import com.agentifiers.amp.sync.api.TerminalStatus
import com.agentifiers.amp.sync.internal.changes.MemoryDatabase
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.FixedBearer
import com.agentifiers.amp.sync.internal.net.NetLog
import com.agentifiers.amp.sync.internal.thread.ThreadDeps
import com.agentifiers.amp.sync.internal.attachments.AttachmentUploader
import com.agentifiers.amp.sync.internal.thread.ThreadEngine
import com.agentifiers.amp.vt.ScreenSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionTest {
    private val server: FakeAmpServer = FakeAmp.start("terminal")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun thread(upload: AttachmentUpload = AttachmentUpload { _, _, _, _, _, _ -> Outcome.Refused("no upload in this test") }): ThreadEngine {
        val client = OkHttpClient()
        val endpoint = Endpoint(server.ampBaseUrl.toHttpUrl(), server.actorsBaseUrl.toHttpUrl(), "test-rivet-token")
        val deps = testThreadDeps(client, endpoint, AmpHttp(client, endpoint, FixedBearer("test-token")), AttachmentUploader(client, endpoint, FixedBearer("test-token")), scope) { error("unauthorized") }
        return ThreadEngine(ThreadId.parse(RUNNER_THREAD)!!, deps, scope, upload)
    }

    private fun ScreenSnapshot.all(): String = (history + lines).joinToString("\n") { it.text }

    private suspend fun TerminalSession.awaitScreen(match: (String) -> Boolean): String =
        withTimeout(10_000) { screen.first { match(it.all()) } }.all()

    private suspend fun TerminalSession.awaitStatus(expected: TerminalStatus) {
        withTimeout(10_000) { status.first { it == expected } }
    }

    @Test
    fun opensToReadyAndRunsPwd() = runBlocking<Unit> {
        val terminal = thread().machine.terminal(null)
        terminal.attachViewer("window-1", 80, 24)
        terminal.awaitStatus(TerminalStatus.Ready)

        terminal.text("pwd\r")

        assertTrue(terminal.awaitScreen { it.contains("pwd\n/private/tmp/amp-scratch") }.contains("pwd\n/private/tmp/amp-scratch"))
        assertEquals(listOf("""{"type":"open","cols":80,"rows":24}"""), server.terminalControls)
    }

    @Test
    fun resizesWithinOneHundredMillisecondsSendOneResizeWithTheLastSize() = runBlocking<Unit> {
        val terminal = thread().machine.terminal(null)
        val viewer = terminal.attachViewer("window-1", 80, 24)
        terminal.awaitStatus(TerminalStatus.Ready)

        viewer.resize(90, 30)
        viewer.resize(100, 32)
        viewer.resize(120, 40)
        delay(400)
        terminal.text("stty size\r")

        terminal.awaitScreen { it.contains("\n40 120") }
        assertEquals(listOf("""{"type":"resize","cols":120,"rows":40}"""), server.terminalControls.filter { it.contains("resize") })
    }

    @Test
    fun exitReconnectsAfterThreeSecondsWhileAViewerIsPresent() = runBlocking<Unit> {
        val terminal = thread().machine.terminal(null)
        terminal.attachViewer("window-1", 80, 24)
        terminal.awaitStatus(TerminalStatus.Ready)
        val statuses = scope.async { terminal.status.take(3).toList() }
        delay(50)

        val exited = System.nanoTime()
        terminal.text("exit\r")
        val seen = withTimeout(10_000) { statuses.await() }
        val tookMs = (System.nanoTime() - exited) / 1_000_000

        assertEquals(listOf(TerminalStatus.Ready, TerminalStatus.Reconnecting, TerminalStatus.Ready), seen)
        assertTrue("reopened after $tookMs ms", tookMs in 3_000..6_000)
        assertEquals(2, server.terminalControls.count { it.startsWith("""{"type":"open"""") })
    }

    @Test
    fun inputTypedBeforeReadyArrivesInOrderAfterReady() = runBlocking<Unit> {
        val terminal = thread().machine.terminal(null)
        terminal.attachViewer("window-1", 80, 24)
        terminal.text("echo one\r")
        terminal.text("echo two\r")
        assertEquals(TerminalStatus.Opening, terminal.status.value)

        val text = terminal.awaitScreen { it.contains("\ntwo\n") }

        assertTrue(text, text.indexOf("echo one\none\n") in 0 until text.indexOf("echo two\ntwo\n"))
    }

    @Test
    fun aDroppedSocketReopensTheSameSessionAndMarksTheGap() = runBlocking<Unit> {
        val engine = thread()
        val terminal = engine.machine.terminal(null)
        terminal.attachViewer("window-1", 80, 24)
        terminal.awaitStatus(TerminalStatus.Ready)

        engine.dropSocket()
        terminal.awaitStatus(TerminalStatus.Reconnecting)
        terminal.awaitStatus(TerminalStatus.Ready)

        assertTrue(terminal.awaitScreen { it.contains("[Reconnected]") }.contains("[Reconnected]"))
        assertEquals(2, server.terminalControls.count { it == """{"type":"open","cols":80,"rows":24}""" })
    }

    @Test
    fun stagedFileIsSavedOnTheExecutorAndItsPathPasted() = runBlocking<Unit> {
        val url = "${server.ampBaseUrl}/attachments/a1"
        val terminal = thread { _, _, _, _, _, _ -> Outcome.Ok(url) }.machine.terminal(null)
        terminal.attachViewer("window-1", 80, 24)
        terminal.awaitStatus(TerminalStatus.Ready)

        val staged = terminal.stageFile("my photo (1).png", "image/png", flowOf(byteArrayOf(1, 2, 3)), 3)

        val path = (staged as Outcome.Ok).value
        assertTrue(path, Regex("/tmp/amp-terminal-files/[0-9a-f-]{36}/my-photo-1-.png").matches(path))
        assertEquals(
            """{"program":"amp","args":["files","get","$url","--output","$path"],"cwd":"/private/tmp/amp-scratch"}""",
            server.terminalControls.last(),
        )
        terminal.awaitScreen { it.contains(path) }
    }

    @Test
    fun aFileOverFiveHundredTwelveMegabytesIsRefused() = runBlocking<Unit> {
        val terminal = thread().machine.terminal(null)

        assertEquals(Outcome.Refused("huge.bin is larger than 512 MB"), terminal.stageFile("huge.bin", "application/octet-stream", emptyFlow(), 536_870_913))
    }

    private companion object {
        const val RUNNER_THREAD = "T-01a16702-3202-73fb-8f52-e4498ccd3721"
    }
}
