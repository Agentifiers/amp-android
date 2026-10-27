package com.agentifiers.amp.sync.internal.wire

import com.agentifiers.amp.model.AgentActivity
import com.agentifiers.amp.model.AgentError
import com.agentifiers.amp.model.DesktopTarget
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.QueuedMessage
import com.agentifiers.amp.model.QueuedMessageId
import com.agentifiers.amp.model.Recap
import com.agentifiers.amp.model.Seq
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import com.agentifiers.amp.sync.internal.gates.GateDecoders
import com.agentifiers.amp.sync.internal.gates.GateFact
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.agentifiers.amp.model.UserId
import com.agentifiers.amp.model.Viewer
import kotlin.time.Instant

/** Every thread actor event decodes to exactly one fact. */
internal sealed interface ThreadFact {
    /** Carries `seq`. Upserted by key whatever the cursor, and the cursor becomes the highest seq seen. */
    sealed interface Sequenced : ThreadFact { val seq: Seq }

    data class MessageAdded(override val seq: Seq, val message: DecodedMessage) : Sequenced
    data class QueuedAdded(override val seq: Seq, val item: QueuedMessage) : Sequenced
    data class QueuedDequeued(override val seq: Seq, val id: QueuedMessageId) : Sequenced
    data class QueuedRemoved(override val seq: Seq, val id: QueuedMessageId) : Sequenced
    data class Cancelled(override val seq: Seq) : Sequenced
    data class ErrorSet(override val seq: Seq, val error: AgentError) : Sequenced
    data class ErrorCleared(override val seq: Seq) : Sequenced

    data class QueueSnapshot(val items: List<QueuedMessage>) : ThreadFact
    data class Title(val title: String?) : ThreadFact
    data class Observers(val hasExecutor: Boolean, val executorType: String?, val desktops: PersistentSet<DesktopTarget>, val viewers: List<Viewer>) : ThreadFact

    /** `prompt_drafts`: what each connected person has typed but not sent. Volatile, like [AgentState]. */
    data class Drafts(val drafts: List<Pair<UserId, String>>) : ThreadFact

    /** `thread_open_state{openExpiresAt}`; null expiry means multiplayer is off. */
    data class OpenState(val until: Instant?) : ThreadFact

    /**
     * `executor_status`. [provider] and [operation] are `details.executionEnvironment`'s (`orb-provider`, `runner`;
     * `idle`, `creating`, `recovering`), [reasonCode] is `details.reasonCode` (`captures/s21-orb-wake.jsonl:29-48`).
     */
    data class ExecutorStatus(
        val status: String?,
        val message: String?,
        val spawnId: String? = null,
        val reasonCode: String? = null,
        val provider: String? = null,
        val operation: String? = null,
    ) : ThreadFact

    /** Volatile: lives only as long as the socket that delivered it. */
    data class AgentState(val activity: AgentActivity) : ThreadFact
    data class Delta(val message: MessageId, val blockIndex: Int?, val blocks: List<JsonObject>, val snapshot: Boolean) : ThreadFact

    data object CompactionStarted : ThreadFact
    data class CompactionComplete(val cut: MessageId?) : ThreadFact

    /** `thread_recap`; null when the recap was dismissed or cleared. */
    data class RecapSet(val recap: Recap?) : ThreadFact

    /** `tool_approval_queue` (Snapshot) and `plugin_message` (dialogs Keyed by requestId, notices Volatile). */
    data class Gate(val fact: GateFact) : ThreadFact

    /** A captured event type this slice decodes but does not render. */
    data class Ignored(val type: String) : ThreadFact
    data class Unrecognized(val type: String) : ThreadFact
    data class Malformed(val type: String) : ThreadFact
}

private typealias Decoder = (seq: Seq?, body: JsonObject) -> ThreadFact?

/**
 * Every event type the app knows, in one list. A type with no entry decodes to [ThreadFact.Unrecognized];
 * a decoder that returns null, including a Sequenced type without `seq`, yields [ThreadFact.Malformed].
 */
internal object WireDecoders {
    private val ignored = listOf(
        "thread_features", "thread_settings",
        "background_work", "background_subagents", "compacting_subagents", "environment_update", "executor_health",
        "executor_workspace_maybe_changed", "inference_tools", "tool_progress", "retry_cancelled",
        "comment_typing", "discussion_typing", "client_executor_relay_exit",
    )

    val entries: List<Pair<String, Decoder>> = listOf<Pair<String, Decoder>>(
        "message_added" to { seq, body ->
            val message = body.obj("message")?.let { decodeMessage(it, MessageKey.Event) }
            if (seq == null || message == null) null else ThreadFact.MessageAdded(seq, message)
        },
        "queued_message_added" to { seq, body ->
            val item = body.obj("message")?.let(::decodeQueued)
            if (seq == null || item == null) null else ThreadFact.QueuedAdded(seq, item)
        },
        "queued_message_dequeued" to { seq, body ->
            val id = body.string("queuedMessageId")
            if (seq == null || id == null) null else ThreadFact.QueuedDequeued(seq, QueuedMessageId(id))
        },
        "queued_message_removed" to { seq, body ->
            val id = body.string("queuedMessageId")
            if (seq == null || id == null) null else ThreadFact.QueuedRemoved(seq, QueuedMessageId(id))
        },
        "cancelled" to { seq, _ -> seq?.let(ThreadFact::Cancelled) },
        "error_set" to { seq, body ->
            val error = body.obj("error")
            val message = error?.string("message")
            if (seq == null || error == null || message == null) null else ThreadFact.ErrorSet(seq, AgentError(message, error.string("code")))
        },
        "error_cleared" to { seq, _ -> seq?.let(ThreadFact::ErrorCleared) },
        "queued_messages" to { _, body ->
            val items = body.array("messages")?.map { (it as? JsonObject)?.let(::decodeQueued) }
            if (items == null || null in items) null else ThreadFact.QueueSnapshot(items.filterNotNull())
        },
        "thread_title" to { _, body -> ThreadFact.Title(body.string("title")) },
        "observers" to { _, body ->
            val viewers = body.array("observers")?.mapNotNull { element ->
                val observer = element as? JsonObject ?: return@mapNotNull null
                val user = observer.string("userId") ?: return@mapNotNull null
                Viewer(UserId(user), observer.string("name") ?: user)
            }.orEmpty()
            body.bool("hasExecutor")?.let { ThreadFact.Observers(it, body.string("executorType"), desktopsOf(body), viewers) }
        },
        "prompt_drafts" to { _, body ->
            body.array("drafts")?.mapNotNull { element ->
                val draft = element as? JsonObject ?: return@mapNotNull null
                val user = draft.string("userId") ?: return@mapNotNull null
                UserId(user) to draft.string("text").orEmpty()
            }?.let(ThreadFact::Drafts)
        },
        "thread_open_state" to { _, body ->
            when (val expiry = body["openExpiresAt"]) {
                null, is JsonNull -> ThreadFact.OpenState(null)
                else -> (expiry as? JsonPrimitive)?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let { ThreadFact.OpenState(it) }
            }
        },
        "executor_status" to { _, body ->
            val details = body.obj("details")
            val environment = details?.obj("executionEnvironment")
            ThreadFact.ExecutorStatus(
                body.string("status"), body.string("message"), body.string("spawnId"),
                details?.string("reasonCode"), environment?.string("provider"), environment?.string("operation"),
            )
        },
        "agent_state" to { _, body -> body.string("state")?.let { ThreadFact.AgentState(activityOf(it)) } },
        "delta" to { _, body ->
            body.string("messageId")?.let { id ->
                ThreadFact.Delta(
                    MessageId(id), body.long("blockIndex")?.toInt(), body.array("blocks")?.mapNotNull { it as? JsonObject }.orEmpty(),
                    snapshot = body.string("contentMode") == "snapshot",
                )
            }
        },
        "compaction_started" to { _, _ -> ThreadFact.CompactionStarted },
        "compaction_complete" to { _, body -> ThreadFact.CompactionComplete(body.string("cutMessageId")?.let(::MessageId)) },
        "thread_recap" to { _, body ->
            val recap = body.obj("recap")
            val text = recap?.string("text")
            ThreadFact.RecapSet(text?.let { Recap(it, recap.string("messageID")?.let(::MessageId), body.long("recapSeq")) })
        },
        "tool_approval_queue" to { _, body -> GateDecoders.approvalQueue(body)?.let(ThreadFact::Gate) },
        "plugin_message" to { _, body -> GateDecoders.pluginMessage(body)?.let(ThreadFact::Gate) ?: ThreadFact.Ignored("plugin_message") },
    ) + ignored.map { type -> type to { _: Seq?, _: JsonObject -> ThreadFact.Ignored(type) } }

    private val byType: Map<String, Decoder> = entries.toMap()

    fun decode(event: InboundFrame.Event): ThreadFact {
        val decoder = byType[event.type] ?: return ThreadFact.Unrecognized(event.type)
        return decoder(event.seq, event.body) ?: ThreadFact.Malformed(event.type)
    }

    /** The web picks the relay desktop transport from these flags (spike-desktop.md); the Mac sidecar also needs the relay. */
    private fun desktopsOf(body: JsonObject): PersistentSet<DesktopTarget> {
        if (body.bool("relayDesktopAvailable") != true) return persistentSetOf()
        return if (body.bool("macDesktopAvailable") == true) persistentSetOf(DesktopTarget.Orb, DesktopTarget.Mac) else persistentSetOf(DesktopTarget.Orb)
    }

    private fun activityOf(state: String): AgentActivity = when (state) {
        "idle" -> AgentActivity.Idle
        "working" -> AgentActivity.Working
        "streaming" -> AgentActivity.Streaming
        "tool_use" -> AgentActivity.ComposingToolCall
        "running_tools" -> AgentActivity.RunningTools
        "compacting" -> AgentActivity.Compacting
        "error" -> AgentActivity.Failed
        else -> AgentActivity.Reported(state)
    }
}
