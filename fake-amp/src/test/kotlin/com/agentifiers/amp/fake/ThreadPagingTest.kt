package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadPagingTest {
    private val long = "T-0a0b0c0d-0000-7000-8000-000000000001"
    private val blocks = "T-0a0b0c0d-0000-7000-8000-000000000003"

    private fun JsonObject.messages(): List<JsonObject> = array("messages")!!.map { it as JsonObject }

    private fun JsonObject.firstPromptText(): String? = messages().firstNotNullOfOrNull { message ->
        message.array("content")!!.map { it as JsonObject }.firstOrNull { it.string("type") == "text" }
            ?.takeIf { message.string("role") == "user" }?.string("text")
    }

    @Test
    fun `thread-long serves the last 50 of 300 messages and pages back to the first`() = FakeAmp.start("thread-long").use { server ->
        val wire = Wire(server)
        val initial = wire.internal("getThreadInitialTranscriptPage", json("thread" to long)).obj("result")!!
        assertEquals(50, initial.messages().size)
        assertEquals(250L, initial.long("firstMessageIndex"))
        assertEquals(true, initial.bool("hasMoreBefore"))
        assertEquals(initial.messages().first().string("protocolMessageID"), initial.string("firstMessageID"))
        assertEquals("[13] Also, after that, run `python3 --version` and include the result.", initial.firstPromptText())
        assertEquals("Long transcript", initial.obj("thread")!!.string("title"))

        val second = wire.internal("getThreadTranscriptPage", json("thread" to long, "before" to 250, "anchorID" to initial.string("firstMessageID")))
            .obj("result")!!
        assertEquals("ok", second.string("status"))
        assertEquals(50, second.messages().size)
        assertEquals(200L, second.long("firstMessageIndex"))
        assertEquals(true, second.bool("hasMoreBefore"))
        assertEquals("[11] Rename add to add_numbers in calc.py and run it", second.firstPromptText())

        val stale = wire.internal("getThreadTranscriptPage", json("thread" to long, "before" to 250, "anchorID" to second.string("firstMessageID")))
        assertEquals(json("status" to "stale"), stale.obj("result"))

        val all = ArrayList(initial.messages())
        var page = initial
        while (page.bool("hasMoreBefore") == true) {
            val before = page.long("firstMessageIndex")!!
            page = wire.internal("getThreadTranscriptPage", json("thread" to long, "before" to before, "anchorID" to page.string("firstMessageID"))).obj("result")!!
            all.addAll(0, page.messages())
        }
        assertEquals(0L, page.long("firstMessageIndex"))
        assertEquals(300, all.map { it.string("protocolMessageID") }.toSet().size)
        assertEquals("[1] Rename add to add_numbers in calc.py and run it", page.firstPromptText())
        val contentBlocks = all.flatMap { message -> message.array("content")!!.map { it as JsonObject } }
        val toolUses = contentBlocks.filter { it.string("type") == "tool_use" }.map { it.string("id") }
        val toolResults = contentBlocks.filter { it.string("type") == "tool_result" }.map { it.string("toolUseID") }
        assertEquals(toolUses.size, toolUses.toSet().size)
        assertEquals(toolUses.toSet(), toolResults.toSet())
    }

    @Test
    fun `thread-blocks serves every row kind and replays recap, compaction, and the error on connect`() = FakeAmp.start("thread-blocks").use { server ->
        val wire = Wire(server)
        val page = wire.internal("getThreadInitialTranscriptPage", json("thread" to blocks)).obj("result")!!
        val messages = page.messages()
        assertEquals(listOf("user", "assistant", "info", "user", "assistant", "user", "assistant"), messages.map { it.string("role") })
        assertEquals(false, page.bool("hasMoreBefore"))
        val image = messages[0].array("content")!!.map { it as JsonObject }.first { it.string("type") == "image" }
        val imageUrl = image.obj("source")!!.string("url")!!
        assertEquals(server.ampBaseUrl + "/user-content/attachments/663bcafedd386d0a3aa98d0023f54e8738665bbc173a7741629735b1d543674c-spike-blue.png", imageUrl)
        val authed = wire.http.newCall(Request.Builder().url(imageUrl).header("Authorization", "Bearer test-token").build()).execute()
            .use { Triple(it.code, it.header("Content-Type"), it.body.bytes().size) }
        assertEquals(Triple(200, "image/png", 185), authed)
        assertEquals(401, wire.http.newCall(Request.Builder().url(imageUrl).build()).execute().use { it.code })
        assertEquals(
            listOf("thinking", "redacted_thinking", "text"),
            messages[1].array("content")!!.map { (it as JsonObject).string("type") },
        )
        assertEquals(true, messages[3].obj("meta")!!.bool("steer"))
        assertEquals("cancelled", messages[4].obj("state")!!.string("type"))

        val socket = wire.threadSocket(blocks)
        val resume = socket.request("client_resume", json("version" to 0))
        val frames = socket.readUntil { it.string("id") == resume }
        val error = frames.last { it.isEvent("error_set") }.params()
        assertEquals(8L, error.long("seq"))
        assertEquals("MODEL_PROVIDER_ERROR", error.obj("error")!!.string("code"))
        val recap = frames.single { it.isEvent("thread_recap") }.params()
        assertEquals(3L, recap.long("recapSeq"))
        assertEquals("M-Blocks0000000000000007", recap.obj("recap")!!.string("messageID"))
        assertEquals("M-Blocks0000000000000004", frames.single { it.isEvent("compaction_complete") }.params().string("cutMessageId"))
        assertEquals(8L, frames.last().obj("result")!!.long("replayThroughSeq"))
        socket.close()
    }

    @Test
    fun `thread-blocks re-sends the standing error when a resume at the page version replays nothing`() = FakeAmp.start("thread-blocks").use { server ->
        val wire = Wire(server)
        val version = wire.internal("getThreadInitialTranscriptPage", json("thread" to blocks)).obj("result")!!.obj("thread")!!.obj("data")!!.long("v")!!
        val socket = wire.threadSocket(blocks)
        val resume = socket.request("client_resume", json("version" to version))
        val frames = socket.readUntil { it.string("id") == resume }
        assertEquals(0L, frames.last().obj("result")!!.long("replayMessageCount"))
        val error = frames.single { it.isEvent("error_set") }.params()
        assertEquals(8L, error.long("seq"))
        assertEquals("400 unknown provider for model zhipuai/glm-5.3-flash", error.obj("error")!!.string("message"))
        socket.close()
    }
}
