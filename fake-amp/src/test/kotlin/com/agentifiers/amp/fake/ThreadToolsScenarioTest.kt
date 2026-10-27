package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadToolsScenarioTest {

    @Test
    fun `thread-tools serves one call per tool card, each answered, then the final reply`() = FakeAmp.start("thread-tools").use { server ->
        val page = Wire(server).internal("getThreadInitialTranscriptPage", json("thread" to "T-0a0b0c0d-0000-7000-8000-000000000002")).obj("result")!!
        assertEquals("Tool card gallery", page.obj("thread")!!.string("title"))
        val messages = page.array("messages")!!.map { it as JsonObject }
        val blocks = messages.flatMap { message -> message.array("content")!!.map { it as JsonObject } }
        val uses = blocks.filter { it.string("type") == "tool_use" }

        assertEquals(
            listOf(
                "shell_command", "create_file", "edit_file", "Read", "Task", "read_thread", "read_web_page", "painter",
                "ask_user_choice", "todo_write", "upload_thread_file", "code_exec", "read_thread_changes",
            ),
            uses.map { it.string("name") },
        )
        assertEquals("TU-ToolsGallery0000000001", uses.first().string("id"))
        assertEquals("cat calc.py", uses.first().obj("input")!!.string("command"))

        val results = blocks.filter { it.string("type") == "tool_result" }
        assertEquals(uses.map { it.string("id") }, results.map { it.string("toolUseID") })
        assertEquals(
            "Unknown tool: read_thread_changes. Neither the server nor the connected executor provides a tool with this name.",
            results.last().obj("run")!!.obj("error")!!.string("message"),
        )

        val assistants = messages.filter { it.string("role") == "assistant" }
        assertEquals(List(13) { "tool_use" } + "end_turn", assistants.map { it.obj("state")!!.string("stopReason") })
        assertEquals(28, messages.size)
    }
}
