package com.agentifiers.amp.sync.internal.outbox

import com.agentifiers.amp.model.AccountCommand
import com.agentifiers.amp.model.Command
import com.agentifiers.amp.model.Draft
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.SendMode
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.UploadState
import com.agentifiers.amp.sync.internal.changes.changesWorkflowOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.TimeZone
import java.util.UUID

/** Where one command goes on the wire, with its method and params. */
internal sealed interface Route {
    /** The thread actor socket; the engine's one socket for the thread. */
    data class ThreadSocket(val method: String, val params: JsonObject) : Route

    /** `POST /api/internal?<method>`; needs no socket, so it drains without a lease. */
    data class Internal(val method: String, val params: JsonObject) : Route
}

/**
 * The one exhaustive table from command to wire, so a new command without a decision fails to compile.
 * Null means this build has no wire for the command yet; the drainer rejects such a row instead of guessing.
 * `/request/rpc` accepts only `client_append_user_msg` (S18), and the engine drains only over a live socket,
 * so no command routes there; notification quick replies use it from :notify's background path.
 */
internal fun routeOf(command: Command, thread: ThreadId, agentMode: String): Route? = when (command) {
    is ThreadIntent.Send -> socket("client_append_user_msg", appendParams(command.draft, command.messageId, command.mode == SendMode.Steer, agentMode))
    is ThreadIntent.RunChangesAction -> socket(
        "client_append_user_msg",
        appendParams(Draft.text(command.prompt), command.messageId, steer = false, agentMode, changesWorkflowOf(command.action)),
    )
    is ThreadIntent.SetNetRoute -> Route.Internal("setThreadNetRoute", buildJsonObject {
        put("threadID", thread.raw)
        put("exitNode", command.exitNode?.raw)
    })
    is ThreadIntent.SetPortalHostname -> Route.Internal("setThreadPortalHostname", buildJsonObject {
        put("threadID", thread.raw)
        put("port", command.port)
        put("hostname", command.hostname)
    })
    is ThreadIntent.EditMessage -> socket("client_edit_message") {
        put("messageId", command.target.raw)
        put("editId", command.editId.raw)
        putJsonArray("content") { addJsonObject { put("type", "text"); put("text", command.text) } }
        put("agentMode", agentMode)
    }
    is ThreadIntent.SteerQueued -> socket("client_steer_queued_msg") { put("queuedMessageId", command.id.raw) }
    is ThreadIntent.RemoveQueued -> socket("client_remove_queued_msg") { put("queuedMessageId", command.id.raw) }
    is ThreadIntent.ReorderQueue -> socket("client_reorder_queued_msgs") {
        putJsonArray("queuedMessageIds") { command.order.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.raw)) } }
    }
    ThreadIntent.Interrupt -> socket("client_cancel") {}
    ThreadIntent.Retry -> socket("client_retry") {}
    ThreadIntent.DismissError -> socket("client_dismiss_active_error") {}
    is ThreadIntent.React -> socket("client_set_message_reaction") {
        put("messageId", command.message.raw)
        put("reaction", command.reaction)
        put("active", command.on)
    }
    ThreadIntent.RetryFailedSetup -> socket("client_spawn_executor") {
        put("requestId", UUID.randomUUID().toString())
        put("retryFailedSetup", true)
    }
    ThreadIntent.RestartOrbProcesses -> Route.Internal("restartOrbProcesses", buildJsonObject { put("threadID", thread.raw) })
    is ThreadIntent.Answer, is ThreadIntent.AnswerSecret, is ThreadIntent.RunPluginCommand, is ThreadIntent.Archive, is ThreadIntent.Rename, is ThreadIntent.Pin, is ThreadIntent.Color,
    is ThreadIntent.SetLabels, is ThreadIntent.Snooze, ThreadIntent.Delete, is ThreadIntent.MarkRead, ThreadIntent.MarkUnread,
    ThreadIntent.DismissRecap, is ThreadIntent.SetFeature, is ThreadIntent.SetVisibility, is ThreadIntent.OpenToCollaborators,
    is ThreadIntent.Comment, is ThreadIntent.ReportBug,
    is AccountCommand.ShareRunner, is AccountCommand.SetSecret, is AccountCommand.SetAutomationEnabled, is AccountCommand.DeleteAutomation,
    is AccountCommand.SetShipBehavior, is AccountCommand.EditVocabulary, is AccountCommand.CreateOAuthClient, is AccountCommand.ClearOrbSnapshots,
    -> null
}

/** Sends and Changes prompts are acknowledged by the first echo of their messageId, not by the RPC answer (S5, S16). */
internal fun echoOf(command: Command): MessageId? = when (command) {
    is ThreadIntent.Send -> command.messageId
    is ThreadIntent.RunChangesAction -> command.messageId
    else -> null
}

/**
 * `client_append_user_msg` (S4, `captures/s04-live-thread.jsonl:39`). Images go in as `image` blocks (S12);
 * other attachments go in as their URLs on their own lines, as the CLI's `--attach` does.
 */
internal fun appendParams(draft: Draft, messageId: MessageId, steer: Boolean, agentMode: String, changesWorkflow: String? = null): JsonObject = buildJsonObject {
    val ready = draft.attachments.mapNotNull { a -> (a.upload as? UploadState.Ready)?.let { a to it.url } }
    val (pictures, others) = ready.partition { (a, _) -> a.mediaType.startsWith("image/") }
    val text = (listOf(draft.text) + others.map { it.second }).filter { it.isNotEmpty() }.joinToString("\n")
    putJsonArray("content") {
        if (text.isNotEmpty() || pictures.isEmpty()) {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
        }
        pictures.forEach { (a, url) ->
            addJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "url")
                    put("url", url)
                }
                put("sourcePath", lastSegment(a.localUri))
            }
        }
    }
    put("messageId", messageId.raw)
    put("agentMode", agentMode)
    putJsonObject("userState") {
        putJsonArray("currentlyVisibleFiles") {}
        put("timeZone", TimeZone.getDefault().id)
    }
    if (steer) put("steer", true)
    changesWorkflow?.let { putJsonObject("meta") { put("changesWorkflow", it) } }
}

/** The picked file's name, which the S12 image block carries as `sourcePath` (`captures/s12-attachment.jsonl:68`). */
private fun lastSegment(uri: String): String {
    val path = runCatching { java.net.URI(uri).path }.getOrNull() ?: uri
    return path.trimEnd('/').substringAfterLast('/')
}

private fun socket(method: String, params: JsonObject) = Route.ThreadSocket(method, params)

private inline fun socket(method: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
    Route.ThreadSocket(method, buildJsonObject(build))
