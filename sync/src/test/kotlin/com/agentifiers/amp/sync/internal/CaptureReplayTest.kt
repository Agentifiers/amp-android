package com.agentifiers.amp.sync.internal

import com.agentifiers.amp.model.AgentActivity
import com.agentifiers.amp.model.Drift
import com.agentifiers.amp.model.EarlierHistory
import com.agentifiers.amp.model.ExecutorStatus
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.PromptOrigin
import com.agentifiers.amp.model.QueuedMessage
import com.agentifiers.amp.model.QueuedMessageId
import com.agentifiers.amp.model.Seq
import com.agentifiers.amp.model.Step
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ToolCallId
import com.agentifiers.amp.model.ToolRun
import com.agentifiers.amp.model.TranscriptView
import com.agentifiers.amp.model.TurnEnd
import com.agentifiers.amp.model.UserBlock
import com.agentifiers.amp.sync.internal.thread.ThreadFold
import com.agentifiers.amp.sync.internal.thread.ThreadReducer
import com.agentifiers.amp.sync.internal.thread.project
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.DecodedMessage
import com.agentifiers.amp.sync.internal.wire.FrameParser
import com.agentifiers.amp.sync.internal.wire.InboundFrame
import com.agentifiers.amp.sync.internal.wire.ThreadFact
import com.agentifiers.amp.sync.internal.wire.WireDecoders
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.collections.immutable.toPersistentMap

class CaptureReplayTest {
    private val thread = ThreadId.parse("T-01a16702-3202-73fb-8f52-e4498ccd3721")!!

    /** The text frames a capture's thread socket received, in order, as the server sent them. */
    private fun frames(capture: String): List<String> =
        File(System.getProperty("amp.captures"), "$capture.jsonl").readLines().mapNotNull { line ->
            val entry = AmpJson.parseToJsonElement(line) as JsonObject
            val channel = (entry["ch"] as JsonPrimitive).content
            if ((entry["dir"] as JsonPrimitive).content != "recv" || !channel.startsWith("thread")) return@mapNotNull null
            val data = entry["data"] as JsonObject
            when {
                "rawText" in data -> (data["rawText"] as JsonPrimitive).content
                "binary" in data -> null
                else -> data.toString()
            }
        }

    private fun facts(capture: String): List<ThreadFact> =
        frames(capture).flatMap(FrameParser::parse).filterIsInstance<InboundFrame.Event>().map(WireDecoders::decode)

    private fun replay(facts: List<ThreadFact>, from: ThreadFold = ThreadFold.empty()) = facts.fold(from, ThreadReducer::reduce)

    @Test
    fun liveTurnFoldsToIdleWithOneHumanPromptAndFourAssistantReplies() {
        val fold = replay(facts("s04-live-thread"))
        val messages = fold.messages.values.toList()

        assertEquals(AgentActivity.Idle, fold.live.activity)
        assertEquals(Seq(26), fold.cursor)
        assertEquals("Rename add to add_numbers", fold.title)
        assertEquals(8, messages.size)
        assertEquals(listOf("Rename add to add_numbers in calc.py and run it"), messages.filterIsInstance<DecodedMessage.User>().mapNotNull { it.text })
        assertEquals(3, messages.count { it is DecodedMessage.User && it.blocks.isEmpty() })
        assertEquals(4, messages.count { it is DecodedMessage.Assistant })
        assertEquals(Drift.None, fold.drift)
        assertTrue(fold.live.streaming.isEmpty())
        assertEquals(ExecutorStatus.Attached("local-client", "running", "Executor connected"), project(thread, fold, Link.Live, persistentListOf(), persistentListOf()).executor)
    }

    @Test
    fun liveTurnIsOneTurnWhoseThreeCallsFormOneStepGroupBeforeTheReply() {
        val state = project(thread, replay(facts("s04-live-thread")), Link.Live, persistentListOf(), persistentListOf())
        val turn = (state.transcript as TranscriptView.Ready).turns.single()

        assertEquals(listOf(UserBlock.Text("Rename add to add_numbers in calc.py and run it")), turn.prompt!!.blocks)
        assertEquals(TurnEnd.Done, turn.end)
        assertEquals(listOf("Thinking", "Tools", "Thinking", "Text"), turn.steps.map { it::class.simpleName })
        val tools = turn.steps.filterIsInstance<Step.Tools>().single()
        assertEquals(listOf("shell_command", "create_file", "shell_command"), tools.calls.map { it.name.raw })
        assertEquals("cat calc.py", tools.calls[0].input.value["command"].string)
        val first = tools.calls[0].run as ToolRun.Done
        assertEquals(JsonValue.Num(0.0), first.result["exitCode"])
        assertEquals(3, tools.summary.finished)
        val reply = turn.steps.filterIsInstance<Step.Text>().single().markdown
        assertTrue(reply, reply.startsWith("Done. Renamed `add` to `add_numbers`"))
    }

    @Test
    fun replayingTheSameCaptureTwiceEqualsReplayingItOnce() {
        val facts = facts("s04-live-thread")
        val once = replay(facts)
        val twice = replay(facts, once)

        assertEquals(once.messages, twice.messages)
        assertEquals(once.cursor, twice.cursor)
        assertEquals(once.queue, twice.queue)
    }

    @Test
    fun steeredMessageIsQueuedThenDeliveredAndInterruptCancelsTheTool() {
        val facts = facts("s05-steer-interrupt")
        val steer = MessageId("M-0o1C7SNoog8K2WNwCtkLQ8")
        val queuedAt = facts.indexOfFirst { it is ThreadFact.QueuedAdded }

        val whileQueued = replay(facts.take(queuedAt + 1))
        assertEquals(listOf(QueuedMessage(QueuedMessageId(steer.raw), "Also, after that, run `python3 --version` and include the result.", steer = true)), whileQueued.queue.values.toList())
        assertEquals(steer, ThreadReducer.acknowledged(facts[queuedAt]))

        val fold = replay(facts)
        val state = project(thread, fold, Link.Live, persistentListOf(), persistentListOf())
        val turns = (state.transcript as TranscriptView.Ready).turns

        assertEquals(AgentActivity.Idle, fold.live.activity)
        assertEquals(Seq(58), fold.cursor)
        assertEquals(18, fold.messages.size)
        assertEquals(4, turns.size)
        assertEquals(listOf(steer), turns.mapNotNull { it.prompt }.filter { it.origin == PromptOrigin.Steer }.map { it.id })
        assertTrue(fold.queue.isEmpty())
        val cancelled = turns.flatMap { it.steps }.filterIsInstance<Step.Tools>().flatMap { it.calls }.single { it.id == ToolCallId("TU-034kiAipjhq4O1ePl9FUmI") }
        assertEquals(ToolRun.Cancelled("User canceled"), cancelled.run)
        assertEquals(TurnEnd.Interrupted, turns.last().end)
        assertEquals(Drift.None, fold.drift)
    }

    @Test
    fun historyPageKeysMessagesByProtocolIdSoTheSocketReplayUpsertsTheSameRows() {
        val page = File(System.getProperty("amp.captures"), "s09-transcript.jsonl").readLines()
            .map { AmpJson.parseToJsonElement(it) as JsonObject }
            .single { (it["n"] as JsonPrimitive).content == "2" }
            .let { ((it["data"] as JsonObject)["body"] as JsonObject)["result"] as JsonObject }
        val paged = ThreadFold.fromHistoryPage(page)!!

        assertEquals(Seq(64), paged.cursor)
        assertEquals("Rename add to add_numbers", paged.title)
        assertEquals("low", paged.agentMode)
        assertEquals(20, paged.messages.size)

        val s04 = facts("s04-live-thread")
        assertEquals(replay(s04).messages.keys.toList(), paged.messages.keys.take(8))
        val resumed = replay(s04, paged)
        assertEquals(20, resumed.messages.size)
        assertEquals(paged.messages.keys.toList(), resumed.messages.keys.toList())
    }

    @Test
    fun anOlderPageGoesInFrontAndAStaleAnswerIsNotAPage() {
        val lines = File(System.getProperty("amp.captures"), "s09-transcript.jsonl").readLines().map { AmpJson.parseToJsonElement(it) as JsonObject }
        fun result(n: Int) = lines.single { (it["n"] as JsonPrimitive).content == "$n" }.let { ((it["data"] as JsonObject)["body"] as JsonObject)["result"] as JsonObject }
        val tail = ThreadFold.fromHistoryPage(result(2))!!.let { it.copy(messages = it.messages.toList().drop(5).toMap().toPersistentMap(), window = it.window.copy(firstIndex = 5, firstId = MessageId("M-034ki9Ehb0FB95zHEI1KBV"), hasMoreBefore = true)) }
        assertEquals(EarlierHistory.Available, (project(thread, tail, Link.Live, persistentListOf(), persistentListOf()).transcript as TranscriptView.Ready).earlier)

        val paged = tail.prepend(result(8))!!

        assertEquals(20, paged.messages.size)
        assertEquals(MessageId("M-Dhm6ABrxGfqFPd1YyU6N01"), paged.messages.keys.first())
        assertEquals(EarlierHistory.None, (project(thread, paged, Link.Live, persistentListOf(), persistentListOf()).transcript as TranscriptView.Ready).earlier)
        assertEquals(null, tail.prepend(result(10)))
    }
}
