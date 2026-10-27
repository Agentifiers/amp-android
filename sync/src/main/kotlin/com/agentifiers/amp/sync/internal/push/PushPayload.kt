package com.agentifiers.amp.sync.internal.push

import com.agentifiers.amp.model.AssistantText
import com.agentifiers.amp.model.MarkdownPart
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.api.PushEvent
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/**
 * The decrypted payload Amp's service worker reads, `{kind, threadId, threadURL, title, body, tag}` (S11). A
 * `thread_idle` body is the agent's last reply, so its `<suggested-replies>` block and `:::` fences are cut here like
 * the transcript's.
 */
internal object PushPayload {
    fun parse(plaintext: ByteArray): PushEvent? {
        val json = try {
            AmpJson.parseToJsonElement(plaintext.decodeToString()) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        val thread = json.string("threadId")?.let(ThreadId::parse) ?: return null
        val kind = json.string("kind") ?: return null
        val title = json.string("title") ?: "Amp"
        val body = MarkdownPart.withoutFences(AssistantText.parse(json.string("body").orEmpty()).prose)
        val tag = json.string("tag") ?: "amp-thread-${thread.raw}-$kind"
        return when (kind) {
            "thread_idle" -> PushEvent.ThreadIdle(thread, tag, title, body)
            "approval_required" -> PushEvent.ApprovalRequired(thread, tag, title, body)
            else -> PushEvent.Other(thread, tag, kind, title, body)
        }
    }
}
