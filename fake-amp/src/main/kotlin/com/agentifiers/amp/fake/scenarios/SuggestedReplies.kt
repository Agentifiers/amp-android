package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.BurstFrame
import com.agentifiers.amp.fake.Event
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.element
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.listingFor
import com.agentifiers.amp.fake.plus
import com.agentifiers.amp.fake.runnerStatus
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Two threads whose agent ended its reply with a `<suggested-replies>` block. [DONE] is idle, and its last reply ends
 * with the block from the user's phone (two lines bold a lead). [STREAMING] is mid-stream: its delta stops inside an
 * unclosed block. A send to [DONE] echoes the user message and goes idle, so a tapped reply shows as the next prompt.
 */
internal object SuggestedReplies : ScenarioSpec {
    override val name = "suggested-replies"

    const val DONE = "T-0a0b0c0d-0000-7000-8000-000000000044"
    const val STREAMING = "T-0a0b0c0d-0000-7000-8000-000000000045"
    private val START = Instant.parse("2026-10-26T09:00:00Z")

    private const val PROSE = "Both apps now deploy from the console. Sign-in still points at the preview hostname, so a dev deploy " +
        "signs you in to the wrong app."

    const val BLOCK = "<suggested-replies>\n" +
        "**Register a fixed console hostname** for sign-in and deploy dev\n" +
        "Commit and push everything to both repos\n" +
        "**Tunnel the apps database** too\n" +
        "</suggested-replies>"

    private const val STREAMED = "Deploying the console now. Two follow-ups are worth doing next.\n\n" +
        "<suggested-replies>\n**Register a fixed console hostname** for sign"

    override fun build(): Scenario = Scenario(
        listOf(
            thread(
                DONE, "Example project website redesign",
                listOf(
                    user(DONE, 1, "Move both apps to the console deploy and tell me what is left."),
                    assistant(DONE, 2, "$PROSE\n\n$BLOCK"),
                ),
                listOf(BurstFrame(Event("agent_state", json("state" to "idle", "agentMode" to "smart")), live = true)),
            ),
            thread(
                STREAMING, "Console deploy, streaming",
                listOf(user(STREAMING, 1, "Deploy the console and suggest what to do next.")),
                listOf(
                    BurstFrame(Event("agent_state", json("state" to "streaming", "agentMode" to "smart")), live = true),
                    BurstFrame(
                        Event(
                            "delta",
                            json(
                                "messageId" to id(STREAMING, 2),
                                "role" to "assistant",
                                "blocks" to listOf(json("type" to "text", "text" to STREAMED, "blockState" to "streaming")),
                                "blockIndex" to 0,
                                "contentMode" to "snapshot",
                                "state" to "generating",
                            ),
                        ),
                        live = true,
                    ),
                ),
            ),
        ),
    )

    private fun thread(thread: String, title: String, messages: List<JsonObject>, burst: List<BurstFrame>): FakeThread {
        val history = messages.mapIndexed { index, message -> Event("message_added", json("message" to message)).withSeq(index + 1L) }
        val script = ThreadScript(history.size.toLong(), history, null, burst, emptyList(), emptyList())
        val status = runnerStatus() + mapOf("threadId" to element(thread), "title" to element(title))
        return FakeThread(thread, listingFor(thread, title, script.version), status, script)
    }

    private fun id(thread: String, n: Int): String = "M-Replies${thread.takeLast(2)}%014d".format(n)

    private fun message(thread: String, n: Int, role: String, text: String, extra: Map<String, Any?>): JsonObject =
        json(
            "threadId" to thread, "role" to role, "content" to listOf(json("type" to "text", "text" to text)), "readAt" to null,
            "messageId" to id(thread, n), "createdAt" to START.plusSeconds(30L * n).toString(),
        ) + extra.mapValues { element(it.value) }

    private fun user(thread: String, n: Int, text: String): JsonObject =
        message(thread, n, "user", text, mapOf("meta" to json("sentAt" to START.plusSeconds(30L * n).toEpochMilli())))

    private fun assistant(thread: String, n: Int, text: String): JsonObject =
        message(thread, n, "assistant", text, mapOf("state" to json("type" to "complete", "stopReason" to "end_turn")))
}
