package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.AMP_ID
import com.agentifiers.amp.fake.BurstFrame
import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.Event
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.array
import com.agentifiers.amp.fake.element
import com.agentifiers.amp.fake.listingFor
import com.agentifiers.amp.fake.obj
import com.agentifiers.amp.fake.plus
import com.agentifiers.amp.fake.runnerStatus
import com.agentifiers.amp.fake.strings
import com.agentifiers.amp.fake.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * A 300-message runner thread served 50 messages a page: the 20 messages of the S9 connect (tool calls, thinking,
 * a steer, a cancel, replies) repeated 15 times. Copy k gets its own ids and prefixes every human text with `[k] `,
 * so a flow can tell which copy is on screen.
 */
internal object ThreadLong : ScenarioSpec {
    override val name = "thread-long"

    const val THREAD = "T-0a0b0c0d-0000-7000-8000-000000000001"
    private const val TITLE = "Long transcript"
    private const val COPIES = 15

    override fun build(): Scenario {
        val capture = ThreadScript.from(Capture.load("s09-transcript"))
        val messages = capture.history.filter { it.type == "message_added" }
        val idIndex = messages.asSequence().flatMap { it.body.strings() }.filter { AMP_ID.matches(it) }.distinct()
            .withIndex().associate { (index, id) -> id to index }

        fun rewrite(copy: Int): (String) -> String = { s ->
            idIndex[s]?.let { "%s-Long%02d%016d".format(s.substringBefore('-'), copy, it) } ?: if (s == RUNNER_THREAD) THREAD else s
        }

        val history = (1..COPIES).flatMap { copy -> messages.map { it.mapStrings(rewrite(copy)).labelled(copy) } }
            .mapIndexed { index, event -> event.withSeq(index + 1L) }
        val burst = capture.burst.filter { it.event.type == "observers" || it.event.type == "agent_state" }
            .map { BurstFrame(it.event.mapStrings(rewrite(COPIES)), it.live) }
        val lastReply = history.last().body.obj("message")!!.string("messageId")
        val script = ThreadScript(history.size.toLong(), history, capture.greeting, burst, emptyList(), emptyList())
        val status = runnerStatus() + mapOf("threadId" to element(THREAD), "title" to element(TITLE), "latestAssistantMessageID" to element(lastReply))
        return Scenario(listOf(FakeThread(THREAD, listingFor(THREAD, TITLE, script.version), status, script, historyPageSize = 50)))
    }

    private fun Event.labelled(copy: Int): Event {
        val message = body.obj("message")!!
        if (message.string("role") != "user") return this
        val content = message.array("content")!!.map { block ->
            block as JsonObject
            if (block.string("type") == "text") block + mapOf("text" to element("[$copy] " + block.string("text"))) else block
        }
        return Event(type, body + mapOf("message" to message + mapOf("content" to JsonArray(content))))
    }
}
