package com.agentifiers.amp.sync.internal

import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.FixedBearer
import com.agentifiers.amp.sync.internal.net.Outcome
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class AmpHttpTest {
    @Test
    fun responseBodyIsNeverReadOnTheCallingThread() {
        val readers = ConcurrentLinkedQueue<Thread>()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType = "application/json".toMediaType()
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = object : ForwardingSource(Buffer().writeUtf8("""{"ok":true,"result":"alice"}""")) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    readers += Thread.currentThread()
                    return super.read(sink, byteCount)
                }
            }.buffer()
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        val base = "https://ampcode.test/".toHttpUrl()
        val http = AmpHttp(client, Endpoint(base, base, "pk"), FixedBearer("token"))
        lateinit var fakeMain: Thread
        val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main").also { thread -> fakeMain = thread } }.asCoroutineDispatcher()

        val outcome = main.use { runBlocking(it) { http.internal("getUserInfo", JsonObject(emptyMap())) } }

        assertEquals(Outcome.Ok(JsonPrimitive("alice")), outcome)
        assertEquals(false, readers.any { it === fakeMain })
    }
}
