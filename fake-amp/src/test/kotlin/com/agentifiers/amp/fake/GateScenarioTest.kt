package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GateScenarioTest {
    @Test
    fun `gate-plugins echoes the elicitation answer it received in the notice`() = FakeAmp.start("gate-plugins").use { server ->
        val socket = Wire(server).threadSocket(GATE_THREAD)
        val resume = socket.request("client_resume", json("version" to 30))
        socket.readUntil { it.string("id") == resume }

        socket.notify("executor_plugin_message", pluginRequest("cmd-1", "command.execute", json("pluginName" to "linear", "commandId" to "mcp-question", "threadID" to GATE_THREAD)))
        val asked = socket.readUntil { pluginEvent(it) == "plugin.ui.request_added" }.last().pluginData().obj("request")!!
        assertEquals("elicitation/create", asked.string("method"))
        assertEquals("File a Linear issue for this crash?", asked.obj("params")!!.string("message"))

        val answer = json("action" to "accept", "content" to json("title" to "Crash on launch", "points" to 3, "urgent" to true, "team" to "app"))
        socket.notify("executor_plugin_message", pluginRequest("respond-1", "plugin.ui.respond", json("requestId" to asked.string("requestId"), "result" to answer)))
        val notice = socket.readUntil { pluginEvent(it) == "ui.notify" }.last().pluginData()
        assertEquals(
            """mcp-question answered {"action":"accept","content":{"title":"Crash on launch","points":3,"urgent":true,"team":"app"}}""",
            notice.string("message"),
        )
        socket.close()
    }

    @Test
    fun `gate-choice stops on an ask_user_choice approval and finishes the turn once it is accepted`() = FakeAmp.start("gate-choice").use { server ->
        val socket = Wire(server).threadSocket(GATE_THREAD)
        val resume = socket.request("client_resume", json("version" to 0))
        socket.readUntil { it.string("id") == resume }

        socket.request("client_append_user_msg", json("content" to listOf(json("type" to "text", "text" to "set it up")), "messageId" to "M-androidGateChoice000001"))
        val queue = socket.readUntil { it.isEvent("tool_approval_queue") && it.params().array("approvals")!!.isNotEmpty() }.last()
        val item = queue.params().array("approvals")!!.single() as JsonObject
        assertEquals("ask_user_choice" to "TU-fakeAskUserChoice00001", item.string("toolName") to item.string("toolCallId"))

        socket.request(
            "client_tool_approval_response",
            json("toolCallId" to "TU-fakeAskUserChoice00001", "accepted" to true, "input" to json("askAnswers" to json("Which database should the service use?" to "sqlite"))),
        )
        val reply = socket.readUntil { it.isEvent("message_added") && it.params().obj("message")!!.string("messageId") == "M-fakeChoiceReply0000001" }.last()
        assertEquals("Thanks, I have your choices.", (reply.params().obj("message")!!.array("content")!!.single() as JsonObject).string("text"))
        socket.close()
    }

    private fun pluginRequest(id: String, method: String, params: JsonObject) =
        json("message" to json("type" to "request", "id" to id, "method" to method, "params" to params))

    private fun pluginEvent(frame: JsonObject): String? = if (frame.isEvent("plugin_message")) frame.params().obj("message")?.string("event") else null

    private fun JsonObject.pluginData(): JsonObject = params().obj("message")!!.obj("data")!!
}
