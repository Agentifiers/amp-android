package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ScenarioTest {

    @Test
    fun `plugin-dialog runs the S15b confirm then input and finishes the command`() = FakeAmp.start("plugin-dialog").use { server ->
        val socket = Wire(server).threadSocket(GATE_THREAD)
        val resume = socket.request("client_resume", json("version" to 30))
        socket.readUntil { it.string("id") == resume }

        socket.notify("executor_plugin_message", pluginRequest("cmd-1", "command.execute", json("pluginName" to "spike-dialog", "commandId" to "spike-dialog", "threadID" to GATE_THREAD)))
        val confirm = socket.readUntil { pluginEvent(it) == "plugin.ui.request_added" }.last().pluginData().obj("request")!!
        assertEquals("ui.confirm", confirm.string("method"))
        assertEquals("Allow the spike to continue?", confirm.obj("params")!!.obj("options")!!.string("message"))
        assertEquals(json("kind" to "command", "id" to "cmd-1"), confirm.obj("invocation"))

        socket.notify("executor_plugin_message", pluginRequest("respond-1", "plugin.ui.respond", json("requestId" to confirm.string("requestId"), "result" to true)))
        val settled = socket.readUntil { pluginEvent(it) == "plugin.ui.request_settled" }.last().pluginData().obj("request")!!
        assertEquals("responded" to JsonPrimitive(true), settled.string("status") to settled["response"])
        val input = socket.readUntil { pluginEvent(it) == "plugin.ui.request_added" }.last().pluginData().obj("request")!!
        assertEquals("Spike input", input.obj("params")!!.obj("options")!!.string("title"))

        socket.notify("executor_plugin_message", pluginRequest("respond-2", "plugin.ui.respond", json("requestId" to input.string("requestId"), "result" to "hello from android")))
        val notice = socket.readUntil { pluginEvent(it) == "ui.notify" }.last().pluginData()
        assertEquals("spike-dialog confirmed=true text=hello from android", notice.string("message"))
        val done = socket.readUntil { it.isEvent("plugin_message") && it.params().obj("message")!!.string("id") == "cmd-1" }.last()
        assertEquals(json("ok" to true), done.params().obj("message")!!.obj("result"))
        socket.close()
    }

    @Test
    fun `terminal answers ready, echoes typed input, and prints the working directory for pwd`() = FakeAmp.start("terminal").use { server ->
        val socket = Wire(server).threadSocket(RUNNER_THREAD)
        val resume = socket.request("client_resume", json("version" to 64))
        socket.readUntil { it.string("id") == resume }
        val channel = "relay-d4435c1d-ee77-4032-9bb7-c38d27a64587"
        socket.notify("client_executor_relay_open", json("channelId" to channel, "service" to "amp-terminal-v1", "binaryFrames" to true))
        val open = Base64.getEncoder().encodeToString("""{"type":"open","cols":80,"rows":24}""".toByteArray())
        socket.notify("client_executor_relay_data", json("channelId" to channel, "dataBase64" to open, "binary" to false))

        val ready = RelayFrame.decode(socket.readBytesUntil { it.isNotEmpty() }.first())!!
        assertEquals(Triple(2, false, channel), Triple(ready.kind, ready.binary, ready.channelId))
        assertEquals("""{"type":"ready"}""", ready.payload.utf8())

        socket.sendBytes(RelayFrame(1, true, channel, "pwd\r".encodeUtf8()).encode())
        val output = socket.readBytesUntil { frames -> screen(frames).contains("/private/tmp/amp-scratch\r\n") }
        assertTrue(screen(output).contains("pwd\r\n/private/tmp/amp-scratch\r\n"))
        socket.close()
    }

    @Test
    fun `push sends the S11 thread_idle payload as aes128gcm Web Push when a turn ends with no client connected`() {
        val sink = MockWebServer().apply { start() }
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val auth = ByteArray(16) { it.toByte() }
        val p256dh = uncompressed(keys.public as ECPublicKey)
        FakeAmp.start("push", frameGapCapMs = 5).use { server ->
            val socket = Wire(server).threadSocket(RUNNER_THREAD)
            val resume = socket.request("client_resume", json("version" to 78))
            socket.readUntil { it.string("id") == resume }
            val endpoint = sink.url("/push/device-1").toString()
            val subscription = json("endpoint" to endpoint, "keys" to json("auth" to b64(auth), "p256dh" to b64(p256dh)))
            val upsert = socket.request("client_upsert_notification_subscription", json("subscription" to subscription, "threadURL" to "${server.ampBaseUrl}/threads/$RUNNER_THREAD"))
            assertEquals(json("ok" to true), socket.readUntil { it.string("id") == upsert }.last().obj("result"))

            assertEquals(0, server.finishTurnElsewhere(RUNNER_THREAD, "Ended while this socket held the thread open."))
            socket.close()
            val closed = System.nanoTime()
            while (server.socketCount(RUNNER_THREAD) > 0 && System.nanoTime() - closed < 5_000_000_000) Thread.sleep(20)

            assertEquals(1, server.finishTurnElsewhere(RUNNER_THREAD, "Done. Renamed `add` to `add_numbers`."))
            val delivered = sink.takeRequest(15, TimeUnit.SECONDS)!!
            assertEquals("aes128gcm", delivered.headers["Content-Encoding"])
            val payload = Wire.parse(decrypt(delivered.body!!.toByteArray(), keys.private, p256dh, auth))
            assertEquals("thread_idle", payload.string("kind"))
            assertEquals(RUNNER_THREAD, payload.string("threadId"))
            assertEquals("Rename add to add_numbers", payload.string("title"))
            assertEquals("Done. Renamed `add` to `add_numbers`.", payload.string("body"))
            assertEquals(endpoint, server.pushes.single().endpoint)
        }
        sink.close()
    }

    @Test
    fun `attachments upload through the resumable flow and need Bearer to read back`() = FakeAmp.start("attachments").use { server ->
        val wire = Wire(server)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        val fields = json("jobID" to "job-1", "mediaType" to "image/png", "sizeBytes" to png.size, "filename" to "blue.png", "threadID" to RUNNER_THREAD)
        val upload = Wire.parse(wire.post("/api/attachments/resumable", fields + mapOf("action" to JsonPrimitive("prepare"))).second).string("uploadURL")!!
        val put = wire.http.newCall(Request.Builder().url(upload).put(png.toRequestBody("image/png".toMediaType())).build()).execute().use { Wire.parse(it.body.string()) }
        val complete = fields + mapOf("action" to JsonPrimitive("complete"), "generation" to put["generation"]!!)
        val url = Wire.parse(wire.post("/api/attachments/resumable", complete).second).string("url")!!
        assertTrue(url, url.startsWith("${server.ampBaseUrl}/user-content/attachments/") && url.endsWith("-blue.png"))

        val anonymous = wire.http.newCall(Request.Builder().url(url).build()).execute().use { it.code }
        val read = wire.http.newCall(Request.Builder().url(url).header("Authorization", "Bearer test-token").build()).execute().use { it.code to it.body.bytes().toList() }
        assertEquals(401, anonymous)
        assertEquals(200 to png.toList(), read)
    }

    @Test
    fun `approvals stops the turn on a queued approval until the client accepts it`() = FakeAmp.start("approvals", frameGapCapMs = 10).use { server ->
        val socket = Wire(server).threadSocket(GATE_THREAD)
        val resume = socket.request("client_resume", json("version" to 0))
        socket.readUntil { it.string("id") == resume }
        socket.request("client_append_user_msg", json("content" to listOf(json("type" to "text", "text" to "clean up")), "messageId" to "M-androidFakeTest00000011"))
        val queue = socket.readUntil { it.isEvent("tool_approval_queue") && it.params().array("approvals")!!.isNotEmpty() }.last()
        val item = queue.params().array("approvals")!!.single() as JsonObject
        assertEquals("shell_command", item.string("toolName"))

        socket.request("client_tool_approval_response", json("toolCallId" to item.string("toolCallId"), "accepted" to true))
        val rest = socket.readUntil { it.isEvent("agent_state") && it.params().string("state") == "idle" }
        assertEquals(emptyList<Any>(), rest.first { it.isEvent("tool_approval_queue") }.params().array("approvals"))
        val reply = rest.last { it.isEvent("message_added") }.message()!!.array("content")!!.single() as JsonObject
        assertEquals("Removed the build directory.", reply.string("text"))
        socket.close()
    }

    @Test
    fun `changes answers git requests with the S7 recordings and a four-file snapshot`() = FakeAmp.start("changes").use { server ->
        val socket = Wire(server).threadSocket(RUNNER_THREAD)
        val resume = socket.request("client_resume", json("version" to 64))
        socket.readUntil { it.string("id") == resume }

        socket.request("client_git_command", json("requestId" to "req-head", "operation" to json("type" to "head")))
        val head = socket.readUntil { it.isEvent("client_git_command_result") }.last().params()
        assertEquals("req-head" to "4bd66138fb8e40058af10a28e7512761f31a0de3\n", head.string("requestId") to head.string("stdout"))

        socket.request(
            "client_git_diff_snapshot",
            json("requestId" to "req-snap", "baseRevision" to "4bd66138fb8e40058af10a28e7512761f31a0de3", "headRevision" to "worktree", "includeUntracked" to true, "content" to "renderable"),
        )
        val snapshot = socket.readUntil { it.isEvent("client_git_diff_snapshot_result") }.last().params().obj("snapshot")!!
        assertEquals(
            listOf("__pycache__/calc.cpython-312.pyc", "calc.py", "notes/plan.md", "legacy.py"),
            snapshot.array("files")!!.map { (it as JsonObject).string("path") },
        )
        socket.close()
    }

    private fun pluginRequest(id: String, method: String, params: JsonObject) =
        json("message" to json("type" to "request", "id" to id, "method" to method, "params" to params))

    private fun pluginEvent(frame: JsonObject): String? = if (frame.isEvent("plugin_message")) frame.params().obj("message")?.string("event") else null

    private fun JsonObject.pluginData(): JsonObject = params().obj("message")!!.obj("data")!!

    private fun screen(frames: List<okio.ByteString>): String =
        frames.mapNotNull { RelayFrame.decode(it) }.filter { it.binary }.joinToString("") { it.payload.utf8() }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun uncompressed(key: ECPublicKey): ByteArray {
        fun fixed(v: BigInteger) = v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray().let { ByteArray(32 - it.size) + it }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    /** RFC 8291 receiver side, written from the RFC, independent of the fake's sender. */
    private fun decrypt(body: ByteArray, private: java.security.PrivateKey, uaPublic: ByteArray, auth: ByteArray): String {
        val buffer = ByteBuffer.wrap(body)
        val salt = ByteArray(16).also { buffer.get(it) }
        buffer.int
        val asPublic = ByteArray(buffer.get().toInt()).also { buffer.get(it) }
        val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val spec = (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey).params
        val point = ECPoint(BigInteger(1, asPublic.copyOfRange(1, 33)), BigInteger(1, asPublic.copyOfRange(33, 65)))
        val sender = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, spec))
        val secret = KeyAgreement.getInstance("ECDH").apply { init(private); doPhase(sender, true) }.generateSecret()
        fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
        val ikm = hmac(hmac(auth, secret), "WebPush: info".toByteArray() + byteArrayOf(0) + uaPublic + asPublic + byteArrayOf(1))
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0, 1)).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0, 1)).copyOf(12)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)) }.doFinal(ciphertext)
        return String(plain, 0, plain.indexOfLast { it == 2.toByte() })
    }
}
