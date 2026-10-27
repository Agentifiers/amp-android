package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAmpTest {

    @Test
    fun `getUserInfo and listThreads answer over HTTP with the recorded values`() = FakeAmp.start("fleet-basic").use { server ->
        val wire = Wire(server)

        val user = wire.internal("getUserInfo")
        assertEquals(true, user.bool("ok"))
        assertEquals("user_01EXAMPLE00000000000000000", user.obj("result")!!.string("id"))

        val threads = wire.internal("listThreads", json("limit" to 50)).obj("result")!!.array("threads")!!.map { it as JsonObject }
        assertEquals(
            listOf("T-01a16702-3202-73fb-8f52-e4498ccd3721", "T-01a16738-2ec2-7354-98e9-688bc413f079", "T-01a166e9-3fa0-77ca-b925-383e88ba52ac"),
            threads.map { it.string("id") },
        )
        val probe = threads[2]
        assertEquals("android-design-probe", probe.string("title"))
        assertEquals("devbox.local", probe.obj("env")!!.obj("initial")!!.string("hostname"))
        assertEquals(false, probe.bool("archived"))
    }

    @Test
    fun `fleet-basic reports three statuses through the user actor`() = FakeAmp.start("fleet-basic").use { server ->
        val wire = Wire(server)
        val (_, grant) = wire.post("/api/user-actor-credentials", json("capability" to "read"))
        val token = Wire.parse(grant).string("wsToken")!!
        val (status, body) = wire.post(
            "/actors/gateway/userActor/action/getRecentThreads?rvt-key=user_01EXAMPLE00000000000000000",
            json("args" to listOf(json("limit" to 50))),
            mapOf("x-rivet-encoding" to "json", "x-rivet-conn-params" to json("type" to "user", "wsToken" to token).toString()),
        )
        assertEquals(200, status)
        val output = Wire.parse(body).array("output")!!.map { it as JsonObject }
        assertEquals(
            listOf("idle" to true, "error" to false, "idle" to false),
            output.map { it.string("state") to (it.bool("hasUnreadMessages") == true) },
        )
    }

    @Test
    fun `a send gets message_added with the client's messageId and then the recorded assistant text`() =
        FakeAmp.start("live-turn", frameGapCapMs = 10).use { server ->
            val socket = Wire(server).threadSocket(RUNNER_THREAD)
            assertEquals("rivet", socket.protocol.poll(5, java.util.concurrent.TimeUnit.SECONDS))
            val resume = socket.request("client_resume", json("version" to 0))
            socket.readUntil { it.string("id") == resume }

            val messageId = "M-androidFakeTest00000001"
            val content = listOf(json("type" to "text", "text" to "Rename add to add_numbers in calc.py and run it"))
            socket.request("client_append_user_msg", json("content" to content, "messageId" to messageId, "agentMode" to "low"))

            val added = socket.readUntil { it.isEvent("message_added") }.last().message()!!
            assertEquals(messageId, added.string("messageId"))
            assertEquals("user", added.string("role"))

            val firstText = socket.readUntil { frame ->
                frame.isEvent("delta") && frame.params().array("blocks")?.any { (it as JsonObject).string("type") == "text" } == true
            }.last()
            assertEquals("Done", (firstText.params().array("blocks")!!.first() as JsonObject).string("text"))

            val reply = socket.readUntil { it.isEvent("message_added") && it.message()?.string("role") == "assistant" }.last().message()!!
            assertEquals(
                "Done. Renamed `add` to `add_numbers` in [calc.py](/private/tmp/amp-scratch/calc.py) and ran it. The module itself prints nothing on execution (it only defines the function), so I also imported `add_numbers(2, 3)` to confirm it works — it returned `5` as expected.",
                (reply.array("content")!!.last() as JsonObject).string("text"),
            )
            socket.readUntil { it.isEvent("agent_state") && it.params().string("state") == "idle" }
            socket.close()
        }

    @Test
    fun `client_cancel during a turn produces the S5 cancellation`() = FakeAmp.start("steer-interrupt", frameGapCapMs = 20).use { server ->
        val socket = Wire(server).threadSocket(RUNNER_THREAD)
        val resume = socket.request("client_resume", json("version" to 28))
        socket.readUntil { it.string("id") == resume }

        socket.request("client_append_user_msg", append("M-androidFakeTest00000002", "first"))
        socket.readUntil { it.isEvent("agent_state") && it.params().string("state") == "idle" }
        socket.request("client_append_user_msg", append("M-androidFakeTest00000003", "second"))
        socket.readUntil { it.isEvent("agent_state") && it.params().string("state") == "running_tools" }

        socket.request("client_cancel", json())
        val frames = socket.readUntil { it.isEvent("cancelled") }
        val result = frames.first { it.isEvent("message_added") }.message()!!.array("content")!!.first() as JsonObject
        assertEquals("TU-034kiAipjhq4O1ePl9FUmI", result.string("toolUseID"))
        assertEquals(json("status" to "cancelled", "reason" to "User canceled"), result.obj("run"))
        assertEquals("M-034kiAfy1rKdVQ87sMxBdO", frames.last().params().string("messageId"))
        assertEquals("idle", socket.readUntil { it.isEvent("agent_state") }.last().params().string("state"))
        socket.close()
    }

    @Test
    fun `a steer queues with the client's messageId and joins the turn at the next tool result`() =
        FakeAmp.start("steer-interrupt", frameGapCapMs = 300).use { server ->
            val socket = Wire(server).threadSocket(RUNNER_THREAD)
            val resume = socket.request("client_resume", json("version" to 28))
        socket.readUntil { it.string("id") == resume }
            socket.request("client_append_user_msg", append("M-androidFakeTest00000004", "first"))
            socket.readUntil { it.isEvent("agent_state") && it.params().string("state") == "running_tools" }

            val steer = "M-androidFakeTest00000005"
            socket.request("client_append_user_msg", append(steer, "Also run python3 --version") + mapOf("steer" to JsonPrimitive(true)))
            val queued = socket.readUntil { it.isEvent("queued_message_added") }.last()
            assertEquals(steer, queued.message()!!.obj("queuedMessage")!!.string("messageId"))

            val delivered = socket.readUntil { it.isEvent("message_added") && it.message()?.string("messageId") == steer }
            val boundary = delivered.indexOfFirst { it.isEvent("queued_message_dequeued") }
            assertEquals(steer, delivered[boundary].params().string("queuedMessageId"))
            assertEquals("tool_result", (delivered[boundary - 1].message()!!.array("content")!!.first() as JsonObject).string("type"))
            assertEquals(true, delivered.last().message()!!.obj("meta")!!.bool("steer"))
            socket.close()
        }

    @Test
    fun `resume from a version replays only later facts`() = FakeAmp.start("steer-interrupt").use { server ->
        val socket = Wire(server).threadSocket(RUNNER_THREAD)
        val resume = socket.request("client_resume", json("version" to 20))
        val frames = socket.readUntil { it.string("id") == resume }
        val replayed = frames.filter { "type" in it && it.string("type") == "message_added" }.map { it.long("seq") }
        assertEquals(listOf(24L, 26L), replayed)
        assertEquals(28L, frames.last().obj("result")!!.long("replayThroughSeq"))
        socket.close()
    }

    @Test
    fun `an unknown internal method answers not-found`() = FakeAmp.start("fleet-basic").use { server ->
        val answer = Wire(server).internal("noSuchMethodAnywhere")
        assertEquals(false, answer.bool("ok"))
        assertEquals("not-found", answer.obj("error")!!.string("code"))
    }

    @Test
    fun `every scenario starts, answers, and closes`() {
        assertEquals(
            listOf(
                "fleet-basic", "live-turn", "steer-interrupt", "plugin-dialog", "approvals", "gate-plugins", "gate-choice",
                "changes", "changes-order-fails", "changes-large", "changes-oversize-snapshot", "changes-oversize-order", "changes-ahead", "changes-no-repo", "orb-asleep", "orb-wake-fails", "terminal", "files", "attachments", "portal", "push", "push-gates", "thread-long", "thread-blocks", "thread-tools",
                "thread-follow", "suggested-replies", "gallery", "widget-fleet", "voice-call", "desktop",
                "fleet-organize", "share-presence", "puck-chat", "auth", "settings", "thread-schedule",
                "newthread", "newthread-spawn-fails", "newthread-self-hosted", "newthread-self-hosted-web", "newthread-self-hosted-web-gone", "outbox", "e2e-day",
            ),
            FakeAmp.scenarios,
        )
        for (name in FakeAmp.scenarios) {
            val server = FakeAmp.start(name)
            val threads = Wire(server).internal("listThreads", json("limit" to 50))
            assertEquals(name, true, threads.bool("ok"))
            server.close()
            val refused = runCatching { Wire(server).http.newCall(Request.Builder().url(server.ampBaseUrl + "/api/internal?listThreads").build()).execute() }
            assertTrue("$name still answers after close", refused.isFailure)
        }
        val unknown = assertThrows(IllegalArgumentException::class.java) { FakeAmp.start("nope") }
        assertTrue(unknown.message!!.contains("fleet-basic, live-turn"))
    }

    @Test
    fun `stateless rpc accepts only client_append_user_msg`() = FakeAmp.start("live-turn", frameGapCapMs = 10).use { server ->
        val wire = Wire(server)
        val token = Wire.parse(wire.post("/api/thread-actors", json("threadId" to RUNNER_THREAD)).second).string("wsToken")!!
        val headers = mapOf("x-rivet-conn-params" to json("wsToken" to token, "transport" to "json-rpc").toString())
        val path = "/actors/gateway/threadActor/request/rpc?rvt-key=$RUNNER_THREAD"

        val cancel = Wire.parse(wire.post(path, json("jsonrpc" to "2.0", "id" to "one-shot-0", "method" to "client_cancel", "params" to json()), headers).second)
        assertEquals(json("code" to -32601, "message" to "Method not found: client_cancel"), cancel.obj("error"))

        val send = json("jsonrpc" to "2.0", "id" to "one-shot-1", "method" to "client_append_user_msg", "params" to append("M-androidFakeTest00000006", "from a notification"))
        assertEquals(json("ok" to true), Wire.parse(wire.post(path, send, headers).second).obj("result"))
        val page = wire.internal("getThreadInitialTranscriptPage", json("thread" to RUNNER_THREAD)).obj("result")!!
        assertEquals("M-androidFakeTest00000006", (page.array("messages")!!.first() as JsonObject).string("protocolMessageID"))
    }

    private fun append(messageId: String, text: String) = json(
        "content" to listOf(json("type" to "text", "text" to text)),
        "messageId" to messageId,
        "agentMode" to "low",
        "userState" to json("currentlyVisibleFiles" to emptyList<Any>(), "timeZone" to "America/Los_Angeles"),
    )
}
