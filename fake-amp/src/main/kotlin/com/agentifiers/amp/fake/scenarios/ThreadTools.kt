package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.Event
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.array
import com.agentifiers.amp.fake.element
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.listingFor
import com.agentifiers.amp.fake.obj
import com.agentifiers.amp.fake.plus
import com.agentifiers.amp.fake.runnerStatus
import com.agentifiers.amp.fake.string
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * One thread with a call for every hand-built tool card, then `code_exec` and the unknown `read_thread_changes`
 * for the generic card. The shell and create calls are the S4 blocks, `edit_file` and the unknown tool come
 * from a thread export from the Amp CLI; the rest use the tool shapes the web renders.
 */
internal object ThreadTools : ScenarioSpec {
    override val name = "thread-tools"

    const val THREAD = "T-0a0b0c0d-0000-7000-8000-000000000002"
    private const val TITLE = "Tool card gallery"
    private const val SCRATCH = "/private/tmp/amp-scratch"
    private val START = Instant.parse("2026-10-23T04:50:00Z")

    /** A 16x16 orange PNG. */
    private const val PNG =
        "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAFklEQVR42mP4H+VMEmIY1TCqYfhqAADECZwQYE/cowAAAABJRU5ErkJggg=="

    private class Call(val name: String, val input: JsonObject, val run: JsonObject, val extra: Map<String, Any?> = emptyMap())

    override fun build(): Scenario {
        val s04 = Capture.load("s04-live-thread")
        fun s04Block(line: Int, type: String): JsonObject = Event.of(s04.line(line).frame!!)!!.body.obj("message")!!
            .array("content")!!.map { it as JsonObject }.first { it.string("type") == type }
        val shell = s04Block(60, "tool_use")
        val create = s04Block(75, "tool_use")

        val calls = listOf(
            Call("shell_command", shell.obj("input")!!, s04Block(68, "tool_result").obj("run")!!),
            Call("create_file", create.obj("input")!!, s04Block(83, "tool_result").obj("run")!!),
            Call(
                "edit_file",
                json("path" to "$SCRATCH/calc.py", "new_str" to "    return a + b", "old_str" to "    return a - b"),
                json(
                    "result" to json(
                        "diff" to "```diff\n--- $SCRATCH/calc.py\toriginal\n+++ $SCRATCH/calc.py\tmodified\n@@ -1,2 +1,2 @@\n def add(a, b):\n-    return a - b\n+    return a + b\n```",
                        "lineRange" to listOf(2, 2),
                    ),
                    "status" to "done",
                    "progress" to json(),
                ),
            ),
            Call(
                "Read",
                json("path" to "$SCRATCH/calc.py", "read_range" to listOf(1, 2)),
                done(json("absolutePath" to "$SCRATCH/calc.py", "content" to "def add_numbers(a, b):\n    return a + b\n")),
            ),
            Call(
                "Task",
                json(
                    "description" to "Check for other callers",
                    "prompt" to "Search $SCRATCH for any remaining callers of `add` and report each file and line.",
                ),
                done("No other callers.\n\n- `calc.py` defines `add_numbers` only\n- nothing imports `add`"),
            ),
            Call(
                "read_thread",
                json("threadID" to THREAD, "goal" to "Recall which tools the gallery covers"),
                done("The gallery thread shows one call per tool card, then two calls for the generic card."),
            ),
            Call(
                "read_web_page",
                json("url" to "https://ampcode.com/manual", "objective" to "Find the list of built-in tools"),
                done("# Owner's Manual\n\nAmp ships with built-in tools for reading, editing, and running code."),
            ),
            Call(
                "painter",
                json("prompt" to "A small orange square"),
                done(listOf(json("type" to "image", "mimeType" to "image/png", "data" to PNG, "width" to 16, "height" to 16))),
            ),
            Call(
                "ask_user_choice",
                json("question" to "Keep the old name as an alias?", "options" to listOf("Yes", "No")),
                done("User selected option 2: No"),
                extra = mapOf("userInput" to json("accepted" to true)),
            ),
            Call(
                "todo_write",
                json(
                    "todos" to listOf(
                        json("id" to "1", "content" to "Rename add to add_numbers", "status" to "completed"),
                        json("id" to "2", "content" to "Run calc.py", "status" to "in_progress"),
                        json("id" to "3", "content" to "Update the README", "status" to "todo"),
                    ),
                ),
                done("Updated the TODO list"),
            ),
            Call(
                "upload_thread_file",
                json("path" to "$SCRATCH/calc.py"),
                done(json("remotePath" to "calc.py")),
            ),
            Call(
                "code_exec",
                json("code" to "const r = await shell_command({ command: 'ls', workdir: '$SCRATCH' })\nreturn r.output"),
                done(listOf(json("type" to "text", "text" to "calc.py\n"))),
            ),
            Call(
                "read_thread_changes",
                json("thread" to "T-01a166e9-3fa0-77ca-b925-383e88ba52ac", "destination" to ".amp/in/recovered"),
                json(
                    "error" to json(
                        "message" to "Unknown tool: read_thread_changes. Neither the server nor the connected executor provides a tool with this name.",
                    ),
                    "status" to "error",
                ),
            ),
        )

        val prompt = message(
            1, "user", json("type" to "text", "text" to "Show me one call of every tool card."),
            extra = mapOf("meta" to json("sentAt" to START.toEpochMilli())),
        )
        val steps = calls.flatMapIndexed { index, call ->
            val n = 2 + index * 2
            val use = json("type" to "tool_use", "id" to toolId(index + 1), "name" to call.name, "blockState" to "complete", "complete" to true, "input" to call.input)
            val result = json("type" to "tool_result", "toolUseID" to toolId(index + 1), "run" to call.run) + call.extra.mapValues { element(it.value) }
            listOf(assistant(n, use, stopReason = "tool_use"), message(n + 1, "user", result))
        }
        val reply = assistant(
            2 + calls.size * 2,
            json("type" to "text", "text" to "That is every tool card: ten hand-built families and the generic card.", "blockState" to "complete"),
            stopReason = "end_turn",
        )
        val history = (listOf(prompt) + steps + reply).mapIndexed { index, message ->
            Event("message_added", json("message" to message)).withSeq(index + 1L)
        }

        val capture = ThreadScript.from(s04)
        val burst = capture.burst.filter { it.event.type == "observers" || it.event.type == "agent_state" }
        val script = ThreadScript(history.size.toLong(), history, capture.greeting, burst, emptyList(), emptyList())
        val status = runnerStatus() + mapOf("threadId" to element(THREAD), "title" to element(TITLE), "latestAssistantMessageID" to element(reply.string("messageId")))
        return Scenario(listOf(FakeThread(THREAD, listingFor(THREAD, TITLE, script.version), status, script)))
    }

    private fun done(result: Any): JsonObject = json("status" to "done", "result" to result)

    private fun id(n: Int): String = "M-ToolsGallery%010d".format(n)

    private fun toolId(n: Int): String = "TU-ToolsGallery%010d".format(n)

    private fun at(n: Int): String = START.plusSeconds(10L * n).toString()

    private fun message(n: Int, role: String, vararg content: JsonObject, extra: Map<String, Any?> = emptyMap()): JsonObject =
        json("threadId" to THREAD, "role" to role, "content" to content.toList(), "readAt" to null, "messageId" to id(n), "createdAt" to at(n)) +
            extra.mapValues { element(it.value) }

    private fun assistant(n: Int, vararg content: JsonObject, stopReason: String): JsonObject =
        message(n, "assistant", *content, extra = mapOf("state" to json("type" to "complete", "stopReason" to stopReason)))
}
