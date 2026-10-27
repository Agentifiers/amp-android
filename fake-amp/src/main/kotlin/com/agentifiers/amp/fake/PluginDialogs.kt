package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A plugin command's dialogs, in the order the command opens them, and the notice it shows once all are answered. */
internal class PluginCommand(val dialogs: List<JsonObject>, val notice: (List<JsonElement>) -> String)

internal class PluginScript(val commands: Map<String, PluginCommand>)

/**
 * Plugin UI dialogs over `executor_plugin_message`, as S15b recorded them (`captures/s15b-plugin-dialog.jsonl:63-74`):
 * `command.execute` opens the command's first dialog, each `plugin.ui.respond` settles one and opens the next, and the
 * command answers `{ok:true}` after the last. The user actor's `indicator` names the waiting dialog.
 */
internal class PluginDialogs(private val script: PluginScript, private val actor: ThreadActor, private val world: World) {
    private class Run(val invocationId: String, val command: PluginCommand, var opened: Int = 0, val answers: MutableList<JsonElement> = ArrayList())

    private val pending = LinkedHashMap<String, Pair<Run, JsonObject>>()
    private val runPrefix = "p-${UUID.randomUUID()}"
    private var nextRequest = 2

    fun receive(message: JsonObject?) {
        if (message?.string("type") != "request") return
        val id = message.string("id") ?: return
        val params = message.obj("params") ?: return
        when (message.string("method")) {
            "command.execute" -> {
                val command = script.commands[params.string("commandId")]
                if (command == null) {
                    respond(id, json("ok" to false, "error" to json("message" to "Unknown command ${params.string("commandId")}")))
                    return
                }
                open(Run(id, command))
            }
            "plugin.ui.respond" -> settle(id, params)
        }
    }

    private fun open(run: Run) {
        if (run.opened == run.command.dialogs.size) {
            event("ui.notify", json("message" to run.command.notice(run.answers)))
            respond(run.invocationId, json("ok" to true))
            world.statusChanged(actor, "indicator" to JsonNull)
            return
        }
        val now = httpDate()
        val requestId = "$runPrefix-${nextRequest++}"
        val request = run.command.dialogs[run.opened] + mapOf(
            "requestId" to element(requestId),
            "invocation" to json("kind" to "command", "id" to run.invocationId),
            "status" to element("pending"),
            "createdAt" to element(now),
            "updatedAt" to element(now),
        )
        run.opened++
        pending[requestId] = run to request
        event("plugin.ui.request_added", json("request" to request))
        snapshot()
        val title = request.obj("params")?.obj("options")?.string("title")
        world.statusChanged(actor, "indicator" to json("kind" to "action-required", "description" to title, "observedAt" to now))
    }

    private fun settle(respondId: String, params: JsonObject) {
        val requestId = params.string("requestId") ?: return
        val (run, request) = pending.remove(requestId) ?: return
        val error = params["error"]
        val answer = params["result"] ?: JsonNull
        val settled = request + mapOf(
            "status" to element(if (error != null) "errored" else "responded"),
            "updatedAt" to element(httpDate()),
        ) + if (error != null) mapOf("error" to error) else mapOf("response" to answer)
        event("plugin.ui.request_settled", json("request" to settled))
        respond(respondId, json("status" to "accepted", "state" to settled))
        snapshot()
        run.answers += answer
        open(run)
    }

    private fun event(name: String, data: JsonObject) {
        actor.emit(Event("plugin_message", json("message" to json("type" to "event", "event" to name, "data" to data))))
    }

    private fun respond(id: String, result: JsonObject) {
        actor.emit(Event("plugin_message", json("message" to json("type" to "response", "id" to id, "result" to result))))
    }

    /** `plugin.ui.snapshot` lists every pending dialog again on each connect (S15c). */
    private fun snapshot() {
        val requests = pending.values.map { it.second }
        actor.updateBurst(Event("plugin_message", json("message" to json("type" to "event", "event" to "plugin.ui.snapshot", "data" to json("requests" to requests)))))
    }

    private fun httpDate(): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))

    companion object {
        /** The S15b `spike-dialog` command: a `ui.confirm`, then a `ui.input`. */
        fun spikeDialog(): PluginScript {
            val capture = Capture.load("s15b-plugin-dialog")
            fun dialog(n: Int): JsonObject {
                val request = capture.line(n).frame!!.obj("params")!!.obj("message")!!.obj("data")!!.obj("request")!!
                return request - "requestId" - "invocation" - "status" - "createdAt" - "updatedAt"
            }
            val command = PluginCommand(listOf(dialog(64), dialog(69))) { answers ->
                val confirmed = (answers.getOrNull(0) as? JsonPrimitive)?.content
                val text = (answers.getOrNull(1) as? JsonPrimitive)?.content
                "spike-dialog confirmed=$confirmed text=$text"
            }
            return PluginScript(mapOf("spike-dialog" to command))
        }
    }
}
