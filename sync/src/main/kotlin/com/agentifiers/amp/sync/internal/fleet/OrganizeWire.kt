package com.agentifiers.amp.sync.internal.fleet

import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.SnoozeUntil
import com.agentifiers.amp.model.ThreadColor
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.Visibility
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Where one organize write goes and what it carries. */
internal sealed interface WireRequest {
    /** `POST /api/internal?<method>`. */
    data class Internal(val method: String, val params: JsonObject) : WireRequest

    /** A thread actor socket request; `/request/rpc` rejects every one of these (S18). */
    data class Socket(val method: String, val params: JsonObject) : WireRequest

    /** `openCustomBugReport`, then `attachBugReportDiagnostics` with the id it returns. */
    data class BugReport(val text: String, val includeDiagnostics: Boolean) : WireRequest
}

/** The organize and sharing intents U11 owns, as one table. Null for every other intent. */
internal fun organizeRequest(intent: ThreadIntent, thread: ThreadId): WireRequest? {
    fun update(field: String, value: JsonPrimitive) = WireRequest.Internal(
        "updateThread",
        buildJsonObject {
            put("thread", thread.raw)
            put(field, value)
        },
    )
    return when (intent) {
        is ThreadIntent.Rename -> WireRequest.Socket("client_set_thread_title", buildJsonObject { put("title", intent.title) })
        is ThreadIntent.Pin -> update("pinned", JsonPrimitive(intent.pinned))
        is ThreadIntent.Color -> update("color", intent.color.wire())
        is ThreadIntent.SetLabels -> WireRequest.Internal(
            "setThreadLabels",
            buildJsonObject {
                put("thread", thread.raw)
                putJsonArray("labels") { intent.labels.forEach { add(JsonPrimitive(it.raw)) } }
            },
        )
        is ThreadIntent.Snooze -> update("snooze", JsonPrimitive(intent.until.wire()))
        is ThreadIntent.Archive -> WireRequest.Internal(
            "archiveThread",
            buildJsonObject {
                put("thread", thread.raw)
                put("archived", intent.archived)
            },
        )
        ThreadIntent.Delete -> WireRequest.Internal("deleteThread", buildJsonObject { put("thread", thread.raw) })
        is ThreadIntent.MarkRead -> WireRequest.Socket("client_mark_message_read", buildJsonObject { put("messageId", intent.upTo.raw) })
        ThreadIntent.MarkUnread -> WireRequest.Socket("client_mark_thread_unread", JsonObject(emptyMap()))
        ThreadIntent.DismissRecap -> WireRequest.Socket("client_dismiss_recap", JsonObject(emptyMap()))
        is ThreadIntent.SetFeature -> WireRequest.Socket(
            "client_update_thread_feature",
            buildJsonObject {
                put("feature", intent.feature)
                put("enabled", intent.enabled)
            },
        )
        is ThreadIntent.SetVisibility -> update("visibility", JsonPrimitive(intent.visibility.wire()))
        is ThreadIntent.OpenToCollaborators -> WireRequest.Socket(
            "client_set_thread_open",
            buildJsonObject { put("ttlSeconds", intent.ttlSeconds?.let(::JsonPrimitive) ?: JsonNull) },
        )
        is ThreadIntent.Comment -> WireRequest.Socket(
            "client_add_thread_comment",
            buildJsonObject {
                put("commentId", MessageId.mint().raw)
                put("anchorMessageId", intent.anchor.raw)
                put("content", intent.text)
                putJsonObject("selection") {
                    put("textIndex", intent.selection.textIndex)
                    put("start", intent.selection.start)
                    put("end", intent.selection.end)
                    put("quoted", intent.selection.quoted)
                }
            },
        )
        is ThreadIntent.ReportBug -> WireRequest.BugReport(intent.text, intent.includeDiagnostics)
        else -> null
    }
}

/** The CLI accepts named colors (cli-sdk.md:328); null clears. */
private fun ThreadColor.wire(): JsonPrimitive = if (this == ThreadColor.None) JsonNull else JsonPrimitive(name.lowercase())

/** `one_hour` and `off` are observed (S10); the other values are read from the web client and unobserved. */
internal fun SnoozeUntil?.wire(): String = when (this) {
    null -> "off"
    SnoozeUntil.OneHour -> "one_hour"
    SnoozeUntil.NextReply -> "until_next_reply"
    SnoozeUntil.Indefinitely -> "indefinitely"
    is SnoozeUntil.At -> instant.toString()
}

/** The wire enum of core.md:282, which `listThreads.meta.visibility` also uses (s10-thread-ops.jsonl:10). */
internal fun Visibility.wire(): String = when (this) {
    Visibility.Private -> "private"
    Visibility.Workspace -> "thread_workspace_shared"
    is Visibility.Groups -> "thread_group_shared"
    Visibility.Unlisted -> "public_unlisted"
}

internal fun visibilityOf(wire: String?): Visibility? = when (wire) {
    "private" -> Visibility.Private
    "thread_workspace_shared", "workspace" -> Visibility.Workspace
    "thread_group_shared", "group" -> Visibility.Groups(kotlinx.collections.immutable.persistentListOf())
    "public_unlisted", "unlisted" -> Visibility.Unlisted
    else -> null
}
