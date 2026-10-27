package com.agentifiers.amp.sync.internal

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.sync.api.SignInStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Socket

/** The browser method's 127.0.0.1 listener against local clients that are not the browser. */
class LoopbackListenerTest {
    private val browser = OkHttpClient()

    private fun portOf(step: SignInStep.OpenBrowser): Int = step.url.toHttpUrl().queryParameter("port")!!.toInt()

    private fun CoroutineScope.visit(url: String, after: Long, page: (Int) -> Unit): Job = launch(Dispatchers.IO) {
        delay(after)
        page(browser.newCall(Request.Builder().url(url).build()).execute().use { it.code })
    }

    @Test
    fun `a client that trickles its request is cut off, and the browser's callback still signs in`() = FakeAmp.start("auth").use { server ->
        runBlocking {
            val app = EngineHarness(server)
            app.ready()
            val clients = mutableListOf<Job>()
            var page: Int? = null

            val steps = withTimeout(40_000) {
                app.signIn(app.engines.browser(app.environment)) { step ->
                    if (step is SignInStep.OpenBrowser) {
                        val trickler = Socket("127.0.0.1", portOf(step))
                        clients += launch(Dispatchers.IO) {
                            trickler.use { socket -> runCatching { repeat(60) { socket.getOutputStream().write('G'.code); delay(1_000) } } }
                        }
                        clients += visit(step.url, after = 500) { page = it }
                    }
                }
            }
            clients.forEach(Job::cancel)

            assertEquals("fake-1@example.com", (steps.last() as SignInStep.SignedIn).account.email)
            assertEquals(200, page)
        }
    }

    @Test
    fun `a request head over 16 KiB is answered 431 at once, and the browser's callback still signs in`() = FakeAmp.start("auth").use { server ->
        runBlocking {
            val app = EngineHarness(server)
            app.ready()
            val clients = mutableListOf<Job>()
            var refused: String? = null
            var page: Int? = null

            val steps = app.signIn(app.engines.browser(app.environment)) { step ->
                if (step is SignInStep.OpenBrowser) {
                    val oversized = Socket("127.0.0.1", portOf(step))
                    clients += launch(Dispatchers.IO) {
                        oversized.use { socket ->
                            socket.soTimeout = 5_000
                            socket.getOutputStream().write(("GET /callback?" + "a".repeat(16 * 1024 - 13)).encodeToByteArray())
                            refused = socket.getInputStream().bufferedReader().readLine()
                        }
                    }
                    clients += visit(step.url, after = 500) { page = it }
                }
            }
            clients.joinAll()

            assertEquals("HTTP/1.1 431 Request Header Fields Too Large", refused)
            assertEquals("fake-1@example.com", (steps.last() as SignInStep.SignedIn).account.email)
            assertEquals(200, page)
        }
    }

    @Test
    fun `cancelling a browser sign-in closes the connection the listener is reading`() = FakeAmp.start("auth").use { server ->
        runBlocking {
            val app = EngineHarness(server)
            app.ready()
            var stalled: Socket? = null
            val attempt = launch {
                app.signIn(app.engines.browser(app.environment)) { step ->
                    if (step is SignInStep.OpenBrowser) {
                        stalled = Socket("127.0.0.1", portOf(step)).apply { getOutputStream().write("GET /callb".encodeToByteArray()) }
                    }
                }
            }
            withTimeout(10_000) { while (stalled == null) delay(50) }
            delay(500)

            attempt.cancel()
            val read = withContext(Dispatchers.IO) {
                stalled!!.use { socket ->
                    socket.soTimeout = 5_000
                    runCatching { socket.getInputStream().read() }
                }
            }

            assertEquals(Result.success(-1), read)
        }
    }
}
