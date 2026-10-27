package com.agentifiers.amp.sync.internal.puck.voice

import com.agentifiers.amp.sync.internal.fleet.testThreadDeps
import com.agentifiers.amp.sync.internal.attachments.AttachmentUploader
import com.agentifiers.amp.sync.internal.changes.MemoryDatabase
import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.fake.FakeAmpServer
import com.agentifiers.amp.model.Drift
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.api.PuckVoiceState
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.FixedBearer
import com.agentifiers.amp.sync.internal.net.NetLog
import com.agentifiers.amp.sync.internal.thread.ThreadDeps
import com.agentifiers.amp.sync.internal.thread.ThreadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.Collections

class PuckVoiceEngineTest {
    private val server: FakeAmpServer = FakeAmp.start("voice-call", frameGapCapMs = 10)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val thread = ThreadEngine(PUCK, deps(server), scope)
    private val voice = PuckVoiceEngine(thread, scope)
    private val states = Collections.synchronizedList(ArrayList<PuckVoiceState>())

    init {
        scope.launch(Dispatchers.Unconfined) { voice.state.collect { states += it } }
    }

    @After
    fun close() {
        scope.cancel()
        server.close()
    }

    @Test
    fun `a call hears the scripted answer, plays the whole clip, follows Puck to the runner thread, and ends on stop`() = runBlocking {
        withTimeout(30_000) {
            val heard = java.io.ByteArrayOutputStream()
            scope.launch { voice.downlink.collect { synchronized(heard) { heard.write(it) } } }
            val destination = scope.async { voice.navigation.first() }

            voice.start()
            voice.state.first { it == PuckVoiceState.Live(userSpeaking = false, puckSpeaking = false, transcriptTail = "") }
            voice.uplink(pcm(4_800) { 8_000 })
            voice.uplink(ByteArray(14_400))

            assertEquals(PaneDestination.Thread(RUNNER), destination.await())
            val answered = PuckVoiceState.Live(userSpeaking = false, puckSpeaking = false, transcriptTail = TRANSCRIPT)
            voice.state.first { it == answered }
            while (synchronized(heard) { heard.size() } < 48_000) delay(10)
            val clip = synchronized(heard) { heard.toByteArray() }
            assertEquals(48_000, clip.size)
            assertEquals("1fd3d223670af8f0f30dbea8a2cfafe8dbf947c58a1f5d11e928798270877956", sha256(clip))

            voice.stop()
            assertEquals(PuckVoiceState.Ended(null), voice.state.value)
            assertEquals(
                listOf(
                    PuckVoiceState.Idle,
                    PuckVoiceState.Connecting,
                    PuckVoiceState.Live(userSpeaking = false, puckSpeaking = false, transcriptTail = ""),
                    PuckVoiceState.Live(userSpeaking = true, puckSpeaking = false, transcriptTail = ""),
                    PuckVoiceState.Live(userSpeaking = false, puckSpeaking = false, transcriptTail = ""),
                    PuckVoiceState.Thinking(transcriptTail = ""),
                    PuckVoiceState.Thinking(transcriptTail = TRANSCRIPT),
                    PuckVoiceState.Live(userSpeaking = false, puckSpeaking = true, transcriptTail = TRANSCRIPT),
                    answered,
                    PuckVoiceState.Ended(null),
                ),
                states.toList(),
            )
            assertEquals(Drift.None, thread.state.value.drift)
        }
    }

    @Test
    fun `a dropped socket ends the call with a reason`() = runBlocking {
        withTimeout(30_000) {
            voice.start()
            voice.state.first { it is PuckVoiceState.Live }
            thread.dropSocket()
            assertEquals(PuckVoiceState.Ended("The connection to Puck dropped."), voice.state.first { it is PuckVoiceState.Ended })
        }
    }

    private fun pcm(bytes: Int, sample: (Int) -> Int): ByteArray = ByteArray(bytes).also { out ->
        for (i in 0 until bytes / 2) {
            val value = sample(i)
            out[2 * i] = value.toByte()
            out[2 * i + 1] = (value shr 8).toByte()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val PUCK = ThreadId.parse("T-01a16800-9c4c-7a11-8000-00000000c0de")!!
        val RUNNER = ThreadId.parse("T-01a16702-3202-73fb-8f52-e4498ccd3721")!!
        const val TRANSCRIPT = "Your runner thread finished its checks."

        fun deps(server: FakeAmpServer): ThreadDeps {
            val client = OkHttpClient()
            val endpoint = Endpoint(server.ampBaseUrl.toHttpUrl(), server.actorsBaseUrl.toHttpUrl(), "pk")
            return testThreadDeps(client, endpoint, AmpHttp(client, endpoint, FixedBearer("test-token")), AttachmentUploader(client, endpoint, FixedBearer("test-token")), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        }
    }
}
