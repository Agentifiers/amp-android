package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class E2eDayScenarioTest {

    @Test
    fun `e2e-day lists the tool, gate, changes and attachment threads`() = FakeAmp.start("e2e-day").use { server ->
        val rows = Wire(server).internal("listThreads", json("limit" to 50)).obj("result")!!.array("threads")!!.map { it as JsonObject }
        assertEquals(
            listOf(
                "T-0a0b0c0d-0000-7000-8000-000000000002" to "Tool card gallery",
                "T-01a16738-2ec2-7354-98e9-688bc413f079" to "Clean the build directory",
                "T-01a16702-3202-73fb-8f52-e4498ccd3721" to "Rename add to add_numbers",
                "T-e2ed0000-0000-7000-8000-000000000012" to "What color is this image",
            ),
            rows.map { it.string("id") to it.string("title") },
        )
    }

    @Test
    fun `the attachment thread replays the S12 image reply under its own id`() = FakeAmp.start("e2e-day", frameGapCapMs = 10).use { server ->
        val socket = Wire(server).threadSocket("T-e2ed0000-0000-7000-8000-000000000012")
        val resume = socket.request("client_resume", json("version" to 0))
        socket.readUntil { it.string("id") == resume }
        socket.request("client_append_user_msg", json("content" to listOf(json("type" to "text", "text" to "What single color fills the attached image?")), "messageId" to "M-androidFakeTest00000031"))
        val turn = socket.readUntil { it.isEvent("agent_state") && it.params().string("state") == "idle" }
        val reply = turn.last { it.isEvent("message_added") }.message()!!
        assertEquals("T-e2ed0000-0000-7000-8000-000000000012", reply.string("threadId"))
        assertEquals("blue", (reply.array("content")!!.single() as JsonObject).string("text"))
        socket.close()
    }
}
