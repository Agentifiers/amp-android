package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Types that carry `seq`, are replayed after `version` on resume, and are stored in the event log (transport.md, event classes). */
internal val SEQUENCED = setOf(
    "message_added", "error_set", "error_cleared", "queued_message_added", "queued_message_dequeued", "cancelled",
    "message_updated", "queued_message_removed", "thread_truncated",
)

/** Types with no `seq` that every connect burst repeats; the newest frame replaces the aspect. */
internal val SNAPSHOT = setOf(
    "observers", "thread_title", "thread_open_state", "thread_settings", "thread_features", "queued_messages",
    "tool_approval_queue", "background_work", "background_subagents", "compacting_subagents", "environment_update",
    "executor_status", "executor_health", "agent_state",
)

/** One thread actor event. [body] is the JSON-RPC `params`, or the bare frame minus `type`; `seq` sits inside it. */
internal class Event(val type: String, val body: JsonObject) {
    val seq: Long? get() = body.long("seq")

    /** The burst slot this event fills: one per type, and one per plugin event name for `plugin_message`. */
    val slot: String get() = if (type == "plugin_message") "plugin_message:" + body.obj("message")?.string("event") else type

    fun live(): JsonObject = json("jsonrpc" to "2.0", "method" to type, "params" to body)

    fun bare(): JsonObject = JsonObject(mapOf("type" to JsonPrimitive(type)) + body)

    fun withSeq(seq: Long): Event = Event(type, body + mapOf("seq" to JsonPrimitive(seq)))

    fun mapStrings(rewrite: (String) -> String): Event = Event(type, body.mapStrings(rewrite))

    companion object {
        /** A JSON-RPC notification or a bare `{type}` frame. Responses and relay records are not events. */
        fun of(frame: JsonObject): Event? = when {
            "jsonrpc" in frame -> if ("id" in frame) null else frame.string("method")?.let { Event(it, frame.obj("params") ?: JsonObject(emptyMap())) }
            else -> frame.string("type")?.let { Event(it, frame - "type") }
        }
    }
}

/** A connect-burst frame and whether the actor sent it as a JSON-RPC notification or a bare frame. */
internal class BurstFrame(val event: Event, val live: Boolean) {
    fun render(): JsonObject = if (live) event.live() else event.bare()
}

internal sealed interface Reply {
    class Response(val result: JsonObject) : Reply
    class Failure(val error: JsonObject) : Reply
    class Notify(val event: Event) : Reply
}

/** A recorded socket request: its method, its params without `requestId`, and what came back in order. */
internal class SocketExchange(val method: String, val params: JsonObject, val replies: List<Reply>)

internal sealed interface TurnStep {
    val gapMs: Long

    class Emit(override val gapMs: Long, val event: Event) : TurnStep

    /** The turn waits here until `client_tool_approval_response` answers [item]. */
    class AwaitApproval(override val gapMs: Long, val item: JsonObject) : TurnStep
}

/**
 * One assistant turn, played after the user message that [trigger] named in the capture. It ends on its last step,
 * an `agent_state` of `idle` or `error`. A turn that [parks] instead stops on a tool that never finishes, so only
 * `client_cancel` ends it.
 */
internal class Turn(val trigger: String, val steps: List<TurnStep>, val parks: Boolean)

/** What one recorded thread socket teaches the fake: history, the connect burst, assistant turns, and request answers. */
internal class ThreadScript(
    val version: Long,
    val history: List<Event>,
    val greeting: Event?,
    val burst: List<BurstFrame>,
    val turns: List<Turn>,
    val exchanges: List<SocketExchange>,
) {
    fun plus(turns: List<Turn> = emptyList(), exchanges: List<SocketExchange> = emptyList()): ThreadScript =
        ThreadScript(version, history, greeting, burst, this.turns + turns, this.exchanges + exchanges)

    fun mapStrings(rewrite: (String) -> String): ThreadScript = ThreadScript(
        version,
        history.map { it.mapStrings(rewrite) },
        greeting?.mapStrings(rewrite),
        burst.map { BurstFrame(it.event.mapStrings(rewrite), it.live) },
        turns.map { turn ->
            val steps = turn.steps.map { step ->
                when (step) {
                    is TurnStep.Emit -> TurnStep.Emit(step.gapMs, step.event.mapStrings(rewrite))
                    is TurnStep.AwaitApproval -> TurnStep.AwaitApproval(step.gapMs, step.item.mapStrings(rewrite))
                }
            }
            Turn(rewrite(turn.trigger), steps, turn.parks)
        },
        exchanges.map { exchange ->
            val replies = exchange.replies.map { reply ->
                when (reply) {
                    is Reply.Response -> Reply.Response(reply.result.mapStrings(rewrite))
                    is Reply.Failure -> Reply.Failure(reply.error.mapStrings(rewrite))
                    is Reply.Notify -> Reply.Notify(reply.event.mapStrings(rewrite))
                }
            }
            SocketExchange(exchange.method, exchange.params.mapStrings(rewrite), replies)
        },
    )

    companion object {
        private val STATEFUL = setOf("client_resume", "client_append_user_msg", "client_cancel")
        private val TURN_ENDS = setOf("idle", "error")

        fun from(capture: Capture, channel: String = "thread"): ThreadScript {
            val lines = capture.channel(channel).filter { it.dir != Dir.Note }
            val resumeAt = lines.indexOfFirst { it.dir == Dir.Send && it.frame?.string("method") == "client_resume" }
            require(resumeAt >= 0) { "${capture.name} has no client_resume on $channel" }
            val resumeId = lines[resumeAt].frame!!.string("id")
            val connectEnd = (resumeAt + 1 until lines.size).first { lines[it].frame?.string("id") == resumeId }

            var greeting: Event? = null
            val history = ArrayList<Event>()
            val burst = LinkedHashMap<String, BurstFrame>()
            for (line in lines.subList(resumeAt + 1, connectEnd)) {
                val frame = line.frame ?: continue
                val event = Event.of(frame) ?: continue
                when {
                    greeting == null && event.type == "observers" && "jsonrpc" in frame -> greeting = event
                    event.type in SEQUENCED && event.seq != null -> history += event
                    event.type == "tool_progress" -> Unit
                    else -> burst[event.slot] = BurstFrame(event, live = "jsonrpc" in frame)
                }
            }
            val resumed = lines[connectEnd].frame!!.obj("result")
            val version = resumed?.long("replayThroughSeq") ?: history.maxOfOrNull { it.seq!! } ?: 0

            val rest = lines.subList(connectEnd + 1, lines.size)
            return ThreadScript(version, history, greeting, burst.values.toList(), turnsOf(rest), exchangesOf(rest))
        }

        private fun turnsOf(lines: List<CaptureLine>): List<Turn> = lines.indices.mapNotNull { start ->
            val send = lines[start].takeIf { it.dir == Dir.Send }?.frame ?: return@mapNotNull null
            if (send.string("method") != "client_append_user_msg") return@mapNotNull null
            val params = send.obj("params")!!
            if (params.bool("steer") == true) return@mapNotNull null
            val trigger = params.string("messageId")!!
            val steps = ArrayList<TurnStep>()
            var previousAt = lines[start].atMs
            var parks = true
            for (line in lines.subList(start + 1, lines.size)) {
                val frame = line.frame ?: continue
                if (line.dir == Dir.Send) {
                    if (frame.string("method") == "client_cancel") break
                    continue
                }
                val event = Event.of(frame) ?: continue
                if (!belongsToTurn(event, trigger)) continue
                steps += TurnStep.Emit((line.atMs - previousAt).coerceAtLeast(0), event)
                previousAt = line.atMs
                if (event.type == "agent_state" && event.body.string("state") in TURN_ENDS) {
                    parks = false
                    break
                }
            }
            Turn(trigger, steps, parks)
        }

        /** Queue and steer frames are rebuilt from the live queue, and the trigger's own echo from the client's message. */
        private fun belongsToTurn(event: Event, trigger: String): Boolean {
            if (event.type == "queued_message_added" || event.type == "queued_message_dequeued") return false
            if (event.type.endsWith("_result")) return false
            if (event.type == "message_added") {
                val message = event.body.obj("message")
                if (message?.string("messageId") == trigger) return false
                if (message?.obj("meta")?.bool("steer") == true) return false
            }
            return true
        }

        private fun exchangesOf(lines: List<CaptureLine>): List<SocketExchange> = lines.indices.mapNotNull { start ->
            val send = lines[start].takeIf { it.dir == Dir.Send }?.frame ?: return@mapNotNull null
            val method = send.string("method") ?: return@mapNotNull null
            if (method in STATEFUL) return@mapNotNull null
            val id = send.string("id")
            val params = send.obj("params") ?: JsonObject(emptyMap())
            val requestId = params.string("requestId")
            val replies = ArrayList<Reply>()
            for (line in lines.subList(start + 1, lines.size)) {
                if (line.dir == Dir.Send && line.frame?.get("binary") == null) break
                val frame = line.frame?.takeIf { line.dir == Dir.Recv && "binary" !in it } ?: continue
                if (id != null && frame.string("id") == id) {
                    frame.obj("result")?.let { replies += Reply.Response(it) }
                    frame.obj("error")?.let { replies += Reply.Failure(it) }
                    continue
                }
                val event = Event.of(frame) ?: continue
                val related = if (requestId != null) event.body.string("requestId") == requestId else true
                if (related) replies += Reply.Notify(event)
            }
            SocketExchange(method, params - "requestId", replies)
        }
    }
}
