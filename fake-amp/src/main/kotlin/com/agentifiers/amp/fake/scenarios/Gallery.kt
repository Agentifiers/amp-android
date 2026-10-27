package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Attachments
import com.agentifiers.amp.fake.BurstFrame
import com.agentifiers.amp.fake.Event
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.StoredAttachment
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.element
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.listingFor
import com.agentifiers.amp.fake.plus
import com.agentifiers.amp.fake.runnerStatus
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Amp's `:::gallery` container directive. [DONE]'s last reply has the shape of the user's real message: prose, a
 * `:::gallery carousel` of attachment images one per line with alt text, then prose. Its first reply holds a grid
 * whose second image the server does not have, an unknown `:::note` directive, and a `:::` mid-line. [STREAMING]'s
 * delta stops inside a carousel whose closing fence has not arrived.
 */
internal object Gallery : ScenarioSpec {
    override val name = "gallery"

    const val DONE = "T-0a0b0c0d-0000-7000-8000-000000000051"
    const val STREAMING = "T-0a0b0c0d-0000-7000-8000-000000000052"
    private val START = Instant.parse("2026-10-26T15:00:00Z")

    private val images = listOf("gallery-1.png", "gallery-2.png", "gallery-3.png").associateWith { file ->
        StoredAttachment("image/png", Gallery::class.java.getResourceAsStream("/attachments/$file")!!.use { it.readBytes() })
    }

    private fun url(file: String): String = "https://ampcode.com${Attachments.CONTENT_PATH}/${Attachments.key(images.getValue(file).bytes, file)}"

    private val CAROUSEL get() = ":::gallery carousel\n" +
        "![Sidebar with the fleet sections](${url("gallery-1.png")})\n" +
        "![Thread pane with the transcript](${url("gallery-2.png")})\n" +
        "![Changes drawer with a staged diff](${url("gallery-3.png")})\n" +
        ":::"

    private val GRID get() = "Here are the two layouts side by side.\n\n" +
        ":::gallery grid\n" +
        "![Light theme](${url("gallery-1.png")})\n" +
        "![Dark theme](https://ampcode.com${Attachments.CONTENT_PATH}/0000-missing.png)\n" +
        ":::\n\n" +
        ":::note\n" +
        "**Heads up:** the dark theme screenshot was taken before the rebuild.\n" +
        ":::\n\n" +
        "The ratio is 1:::2 at most."

    override fun build(): Scenario = Scenario(
        listOf(
            thread(
                DONE, "Screens gallery",
                listOf(
                    user(DONE, 1, "Show me both themes."),
                    assistant(DONE, 2, GRID),
                    user(DONE, 3, "Now the three main screens."),
                    assistant(DONE, 4, "The app now has three screens.\n\n$CAROUSEL\n\nThe sidebar opens on the left of each one."),
                ),
                listOf(BurstFrame(Event("agent_state", json("state" to "idle", "agentMode" to "smart")), live = true)),
            ),
            thread(
                STREAMING, "Gallery, streaming",
                listOf(user(STREAMING, 1, "Screenshot the sidebar.")),
                listOf(
                    BurstFrame(Event("agent_state", json("state" to "streaming", "agentMode" to "smart")), live = true),
                    BurstFrame(
                        Event(
                            "delta",
                            json(
                                "messageId" to id(STREAMING, 2),
                                "role" to "assistant",
                                "blocks" to listOf(
                                    json(
                                        "type" to "text",
                                        "text" to "Here is the sidebar.\n\n:::gallery carousel\n![Sidebar with the fleet sections](${url("gallery-1.png")})\n",
                                        "blockState" to "streaming",
                                    ),
                                ),
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
        images.mapKeys { (file, image) -> Attachments.key(image.bytes, file) },
    )

    private fun thread(thread: String, title: String, messages: List<JsonObject>, burst: List<BurstFrame>): FakeThread {
        val history = messages.mapIndexed { index, message -> Event("message_added", json("message" to message)).withSeq(index + 1L) }
        val script = ThreadScript(history.size.toLong(), history, null, burst, emptyList(), emptyList())
        val status = runnerStatus() + mapOf("threadId" to element(thread), "title" to element(title))
        return FakeThread(thread, listingFor(thread, title, script.version), status, script)
    }

    private fun id(thread: String, n: Int): String = "M-Gallery${thread.takeLast(2)}%014d".format(n)

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
