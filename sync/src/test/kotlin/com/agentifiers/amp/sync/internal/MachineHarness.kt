package com.agentifiers.amp.sync.internal

import com.agentifiers.amp.sync.internal.fleet.testThreadDeps
import com.agentifiers.amp.fake.FakeAmpServer
import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.api.ViewerToken
import com.agentifiers.amp.sync.internal.attachments.AttachmentUploader
import com.agentifiers.amp.sync.internal.changes.MemoryDatabase
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.FixedBearer
import com.agentifiers.amp.sync.internal.net.NetLog
import com.agentifiers.amp.sync.internal.thread.ThreadDeps
import com.agentifiers.amp.sync.internal.thread.ThreadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** The account wiring DefaultEngines.Session builds, pointed at a running fake, with no Android context. */
internal class MachineHarness(server: FakeAmpServer) : AutoCloseable {
    val client = OkHttpClient()
    private val endpoint = Endpoint(server.ampBaseUrl.toHttpUrl(), server.actorsBaseUrl.toHttpUrl(), "pk-test")
    val http = AmpHttp(client, endpoint, FixedBearer(TOKEN))
    val uploader = AttachmentUploader(client, endpoint, FixedBearer(TOKEN))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val deps = testThreadDeps(client, endpoint, http, uploader, scope)

    /** A thread engine holding one lease, so its socket opens as it would for a visible pane. */
    fun thread(raw: String): ThreadEngine = ThreadEngine(ThreadId.parse(raw)!!, deps, scope).also { it.attach(ViewerToken("test-window")) }

    suspend fun live(thread: ThreadEngine) {
        withTimeout(20_000) { thread.state.first { it.link == Link.Live } }
    }

    override fun close() {
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        const val TOKEN = "test-token"
        const val GATE_THREAD = "T-01a16738-2ec2-7354-98e9-688bc413f079"
        const val RUNNER_THREAD = "T-01a16702-3202-73fb-8f52-e4498ccd3721"
    }
}
