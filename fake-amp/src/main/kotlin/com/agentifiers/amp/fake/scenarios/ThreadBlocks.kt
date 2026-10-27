package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Attachments
import com.agentifiers.amp.fake.BurstFrame
import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.Event
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.StoredAttachment
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
 * One thread holding every transcript row the thread view draws: the S12 image message, thinking and redacted
 * thinking, a Markdown reply, an `info` shell row, a steer, a cancelled reply, a recap, a compaction divider, and
 * the S15 `MODEL_PROVIDER_ERROR`.
 */
internal object ThreadBlocks : ScenarioSpec {
    override val name = "thread-blocks"

    const val THREAD = "T-0a0b0c0d-0000-7000-8000-000000000003"
    private const val TITLE = "Block gallery"
    private val START = Instant.parse("2026-10-23T04:40:00Z")

    private val PLAN = """
        ## Plan

        - Rename `add` to `add_numbers` in `calc.py`
          - Keep the signature `(a, b)`
        - Run the module

        1. Read `calc.py`
        2. Edit the definition
        3. Run `python3 calc.py`

        | File | Change |
        | --- | --- |
        | `calc.py` | renamed `add` |
        | `README.md` | unchanged |

        ```kotlin
        fun addNumbers(a: Int, b: Int): Int = a + b
        ```

        Details on [Amp](https://ampcode.com/).
    """.trimIndent()

    override fun build(): Scenario {
        val png = StoredAttachment("image/png", ThreadBlocks::class.java.getResourceAsStream("/attachments/spike-blue.png")!!.use { it.readBytes() })
        val pngKey = Attachments.key(png.bytes, "spike-blue.png")
        val imageMessage = Event.of(Capture.load("s12-attachment").line(69).frame!!)!!
        val imageId = imageMessage.body.obj("message")!!.string("messageId")
        val capturedUrl = imageMessage.body.obj("message")!!.array("content")!!.map { it as JsonObject }
            .first { it.string("type") == "image" }.obj("source")!!.string("url")
        val rewrites = mapOf(
            RUNNER_THREAD to THREAD,
            imageId to id(1),
            capturedUrl to "https://ampcode.com${Attachments.CONTENT_PATH}/$pngKey",
        )
        val steerId = id(4)
        val replyId = id(7)
        val messages = listOf(
            imageMessage.mapStrings { rewrites[it] ?: it }.body.obj("message")!!,
            assistant(
                2,
                json("type" to "thinking", "thinking" to "A plan, then the code, then a link to the docs.", "signature" to "", "blockState" to "complete"),
                json("type" to "redacted_thinking", "data" to "x"),
                json("type" to "text", "text" to PLAN, "blockState" to "complete"),
            ),
            message(
                3, "info",
                json(
                    "type" to "manual_bash_invocation",
                    "args" to json("cmd" to "ls", "args" to listOf("-la"), "cwd" to "/private/tmp/amp-scratch"),
                    "toolRun" to json("status" to "done", "result" to json("output" to "calc.py\n", "exitCode" to 0)),
                ),
            ),
            user(4, "Also run the tests after the rename.", steer = true),
            assistant(5, json("type" to "text", "text" to "Running the tests now.", "blockState" to "complete"), state = "cancelled"),
            user(6, "Rename add to add_numbers and verify it."),
            assistant(7, json("type" to "text", "text" to "Renamed `add` to `add_numbers` in `calc.py` and verified `add_numbers(2, 3)` returns `5`.", "blockState" to "complete")),
        )
        val error = Event.of(Capture.load("s15-approvals").line(82).frame!!)!!
        val history = messages.map { Event("message_added", json("message" to it)) }.plus(error)
            .mapIndexed { index, event -> event.withSeq(index + 1L) }

        val capture = ThreadScript.from(Capture.load("s09-transcript"))
        val burst = capture.burst.mapNotNull { frame ->
            when (frame.event.type) {
                "observers" -> frame
                "agent_state" -> BurstFrame(Event("agent_state", frame.event.body + mapOf("messageId" to element(replyId))), frame.live)
                else -> null
            }
        } + listOf(
            Event("thread_recap", json("recap" to json("text" to "Goal: rename add. Latest outcome: renamed and verified.", "messageID" to replyId, "generatedAt" to at(8)), "recapSeq" to 3)),
            Event("compaction_complete", json("cutMessageId" to steerId)),
        ).map { BurstFrame(it, live = false) }

        val script = ThreadScript(history.size.toLong(), history, capture.greeting, burst, emptyList(), emptyList())
        val status = runnerStatus() + mapOf("threadId" to element(THREAD), "title" to element(TITLE), "latestAssistantMessageID" to element(replyId))
        return Scenario(listOf(FakeThread(THREAD, listingFor(THREAD, TITLE, script.version), status, script)), mapOf(pngKey to png))
    }

    private fun id(n: Int): String = "M-Blocks%016d".format(n)

    private fun at(n: Int): String = START.plusSeconds(30L * n).toString()

    private fun message(n: Int, role: String, vararg content: JsonObject, extra: Map<String, Any?> = emptyMap()): JsonObject =
        json("threadId" to THREAD, "role" to role, "content" to content.toList(), "readAt" to null, "messageId" to id(n), "createdAt" to at(n)) +
            extra.mapValues { element(it.value) }

    private fun user(n: Int, text: String, steer: Boolean = false): JsonObject {
        val sentAt = START.plusSeconds(30L * n).toEpochMilli()
        val meta = if (steer) json("sentAt" to sentAt, "steer" to true) else json("sentAt" to sentAt)
        return message(n, "user", json("type" to "text", "text" to text), extra = mapOf("meta" to meta))
    }

    private fun assistant(n: Int, vararg content: JsonObject, state: String = "complete"): JsonObject =
        message(n, "assistant", *content, extra = mapOf("state" to json("type" to state)))
}
