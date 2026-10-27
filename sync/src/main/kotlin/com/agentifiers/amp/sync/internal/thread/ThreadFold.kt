package com.agentifiers.amp.sync.internal.thread

import com.agentifiers.amp.model.AgentActivity
import com.agentifiers.amp.model.AgentError
import com.agentifiers.amp.model.DesktopTarget
import com.agentifiers.amp.model.Compaction
import com.agentifiers.amp.model.Drift
import com.agentifiers.amp.model.EarlierHistory
import com.agentifiers.amp.model.ExecutorStatus
import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.Multiplayer
import com.agentifiers.amp.model.Presence
import com.agentifiers.amp.model.UserId
import com.agentifiers.amp.model.Viewer
import com.agentifiers.amp.model.OutboxEntry
import com.agentifiers.amp.model.PendingSend
import com.agentifiers.amp.model.QueuedMessage
import com.agentifiers.amp.model.QueuedMessageId
import com.agentifiers.amp.model.Recap
import com.agentifiers.amp.model.Seq
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.TranscriptView
import com.agentifiers.amp.sync.internal.gates.GateBook
import com.agentifiers.amp.sync.internal.wire.DecodedMessage
import com.agentifiers.amp.sync.internal.wire.MessageKey
import com.agentifiers.amp.sync.internal.wire.Part
import com.agentifiers.amp.sync.internal.wire.ThreadFact
import com.agentifiers.amp.sync.internal.wire.array
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.decodeMessage
import com.agentifiers.amp.sync.internal.wire.long
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.part
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.json.JsonObject

/**
 * The fold of one thread's actor events. Durable fields survive a reconnect; [live] is volatile and is
 * replaced by [LiveOverlay.Empty] whenever a socket ends. Maps keep insertion order, so an upsert of a
 * known key keeps its position and [messages] stays in transcript order.
 */
internal data class ThreadFold(
    val cursor: Seq,
    val title: String?,
    val agentMode: String?,
    val messages: PersistentMap<MessageId, DecodedMessage>,
    val window: HistoryWindow,
    val queue: PersistentMap<QueuedMessageId, QueuedMessage>,
    val error: AgentError?,
    val executor: ExecutorFacts,
    val compaction: Compaction,
    val recap: Recap?,
    val live: LiveOverlay,
    val drift: Drift,
    val gates: GateBook = GateBook.Empty,
) {
    fun endLive(): ThreadFold = copy(live = LiveOverlay.Empty)

    /** Puts an older `getThreadTranscriptPage` page in front of the loaded messages; null when the page is unreadable. */
    fun prepend(page: JsonObject): ThreadFold? {
        val older = pageMessages(page) ?: return null
        val window = HistoryWindow.of(page) ?: return null
        val merged = older.filter { it.id !in messages }.associateBy { it.id }.toPersistentMap().putAll(messages)
        return copy(messages = merged, window = window)
    }

    /** A stale page answer means the loaded window no longer lines up with the server: start over from the tail. */
    fun reloadedFrom(initial: ThreadFold): ThreadFold =
        initial.copy(
            cursor = maxOf(cursor, initial.cursor), queue = queue, error = error, executor = executor, compaction = compaction, recap = recap,
            live = live, drift = drift, gates = gates,
        )

    companion object {
        /** `getThreadInitialTranscriptPage`: rows keyed by `protocolMessageID`, cursor from `thread.data.v` (S17). */
        fun fromHistoryPage(result: JsonObject): ThreadFold? {
            val thread = result.obj("thread") ?: return null
            val data = thread.obj("data") ?: return null
            val version = data.long("v") ?: return null
            val messages = pageMessages(result) ?: return null
            return empty().copy(
                cursor = Seq(version),
                title = thread.string("title") ?: data.string("title"),
                agentMode = data.string("agentMode"),
                messages = messages.associateBy { it.id }.toPersistentMap(),
                window = HistoryWindow.of(result) ?: HistoryWindow.Complete,
            )
        }

        fun empty(): ThreadFold = ThreadFold(
            Seq.Zero, null, null, persistentMapOf(), HistoryWindow.Complete, persistentMapOf(), null, ExecutorFacts.Unknown,
            Compaction.None, null, LiveOverlay.Empty, Drift.None,
        )

        private fun pageMessages(page: JsonObject): List<DecodedMessage>? =
            page.array("messages")?.mapNotNull { (it as? JsonObject)?.let { m -> decodeMessage(m, MessageKey.Page) } }
    }
}

/**
 * Where the loaded messages start in the thread. `getThreadTranscriptPage{before, anchorID}` asks for the messages
 * before [firstIndex], naming the message there so the server can answer `stale` when the thread changed (S9).
 */
internal data class HistoryWindow(val firstIndex: Int, val firstId: MessageId?, val hasMoreBefore: Boolean) {
    companion object {
        val Complete = HistoryWindow(0, null, false)

        fun of(page: JsonObject): HistoryWindow? {
            val index = page.long("firstMessageIndex")?.toInt() ?: return if (page.bool("hasMoreBefore") == true) null else Complete
            return HistoryWindow(index, page.string("firstMessageID")?.let(::MessageId), page.bool("hasMoreBefore") == true)
        }
    }
}

/** What `observers` and the latest `executor_status` say about the thread's executor. */
internal data class ExecutorFacts(
    val hasExecutor: Boolean?,
    val kind: String?,
    val status: String?,
    val message: String?,
    val desktops: PersistentSet<DesktopTarget> = persistentSetOf(),
    val spawnId: String? = null,
    val reasonCode: String? = null,
    val provider: String? = null,
    val operation: String? = null,
) {
    /** The web's test for a running executor (`532df6c0.js` `xM`: `hasExecutor===!0||status==="running"`). */
    val connected: Boolean get() = hasExecutor == true || status == "running"

    private val orbOffline: Boolean get() = !connected && provider != null && provider != RUNNER_PROVIDER

    /** The orb went to sleep: `executor_status{status:"failed", reasonCode:"executor_disconnected"}` from `orb-provider` (`captures/s21-orb-sleep.jsonl:29`). */
    val orbAsleep: Boolean get() = orbOffline && reasonCode == DISCONNECTED

    /** Amp is bringing the orb back, whoever asked: `operation:"recovering"` (`captures/s21-orb-wake.jsonl:38-43`). */
    val orbRecovering: Boolean get() = orbOffline && operation == RECOVERING

    companion object {
        val Unknown = ExecutorFacts(null, null, null, null)
        private const val RUNNER_PROVIDER = "runner"
        private const val DISCONNECTED = "executor_disconnected"
        private const val RECOVERING = "recovering"
    }
}

/** This app's wake of the thread's sleeping orb. The engine owns it; [project] lays it over the fold's facts. */
internal sealed interface OrbWake {
    data object Idle : OrbWake
    data class Sent(val requestId: String) : OrbWake
    data class Failed(val reason: String) : OrbWake
}

internal data class LiveOverlay(
    val activity: AgentActivity,
    val streaming: PersistentMap<MessageId, StreamingMessage>,
    val viewers: List<Viewer> = emptyList(),
    val drafts: List<Pair<UserId, String>> = emptyList(),
    val multiplayer: Multiplayer = Multiplayer.Unknown,
) {
    companion object {
        val Empty = LiveOverlay(AgentActivity.Unknown, persistentMapOf())
    }
}

/** An assistant message still arriving through `delta` frames, by block index. */
internal data class StreamingMessage(val parts: PersistentMap<Int, Part>)

/** Pure. Applying a Sequenced fact twice equals applying it once, so a replay overlap is harmless. */
internal object ThreadReducer {
    fun reduce(fold: ThreadFold, fact: ThreadFact): ThreadFold {
        val advanced = if (fact is ThreadFact.Sequenced) fold.copy(cursor = maxOf(fold.cursor, fact.seq)) else fold
        return when (fact) {
            is ThreadFact.MessageAdded -> advanced.copy(
                messages = advanced.messages.put(fact.message.id, fact.message),
                live = advanced.live.copy(streaming = advanced.live.streaming.remove(fact.message.id)),
                recap = advanced.recap.takeUnless { fact.message is DecodedMessage.User && fact.message.blocks.isNotEmpty() },
            )
            is ThreadFact.QueuedAdded -> advanced.copy(queue = advanced.queue.put(fact.item.id, fact.item))
            is ThreadFact.QueuedDequeued -> advanced.copy(queue = advanced.queue.remove(fact.id))
            is ThreadFact.QueuedRemoved -> advanced.copy(queue = advanced.queue.remove(fact.id))
            is ThreadFact.Cancelled -> advanced
            is ThreadFact.ErrorSet -> advanced.copy(error = fact.error)
            is ThreadFact.ErrorCleared -> advanced.copy(error = null)
            is ThreadFact.QueueSnapshot -> advanced.copy(queue = fact.items.associateBy { it.id }.toPersistentMap())
            is ThreadFact.Title -> advanced.copy(title = fact.title)
            is ThreadFact.Observers -> advanced.copy(
                executor = advanced.executor.copy(hasExecutor = fact.hasExecutor, kind = fact.executorType, desktops = fact.desktops),
                live = advanced.live.copy(viewers = fact.viewers),
            )
            is ThreadFact.Drafts -> advanced.copy(live = advanced.live.copy(drafts = fact.drafts))
            is ThreadFact.OpenState -> advanced.copy(live = advanced.live.copy(multiplayer = fact.until?.let(Multiplayer::Open) ?: Multiplayer.Off))
            is ThreadFact.ExecutorStatus -> advanced.copy(
                executor = advanced.executor.copy(
                    status = fact.status, message = fact.message, spawnId = fact.spawnId, reasonCode = fact.reasonCode,
                    provider = fact.provider, operation = fact.operation,
                ),
            )
            is ThreadFact.AgentState -> advanced.copy(
                live = if (fact.activity is AgentActivity.Busy) {
                    advanced.live.copy(activity = fact.activity)
                } else {
                    advanced.live.copy(activity = fact.activity, streaming = persistentMapOf())
                },
            )
            is ThreadFact.Delta -> applyDelta(advanced, fact)
            ThreadFact.CompactionStarted -> advanced.copy(compaction = Compaction.Running)
            is ThreadFact.CompactionComplete -> advanced.copy(compaction = Compaction.Done(fact.cut))
            is ThreadFact.RecapSet -> advanced.copy(recap = fact.recap)
            is ThreadFact.Gate -> advanced.copy(gates = advanced.gates.apply(fact.fact))
            is ThreadFact.Ignored -> advanced
            is ThreadFact.Unrecognized -> advanced.copy(drift = advanced.drift + fact.type)
            is ThreadFact.Malformed -> advanced.copy(drift = advanced.drift + fact.type)
        }
    }

    /** The client-minted id a fact echoes. Its first echo acknowledges the matching send. */
    fun acknowledged(fact: ThreadFact): MessageId? = when (fact) {
        is ThreadFact.MessageAdded -> fact.message.id
        is ThreadFact.QueuedAdded -> MessageId(fact.item.id.raw)
        else -> null
    }

    private fun applyDelta(fold: ThreadFold, delta: ThreadFact.Delta): ThreadFold {
        if (delta.message in fold.messages) return fold
        val current = fold.live.streaming[delta.message] ?: StreamingMessage(persistentMapOf())
        val index = delta.blockIndex
        val next = if (index == null) current else delta.blocks.fold(current) { message, block -> message.merge(index, block, delta.snapshot) }
        return fold.copy(live = fold.live.copy(streaming = fold.live.streaming.put(delta.message, next)))
    }

    /** Text and thinking deltas append unless the frame is a snapshot; a tool_use delta carries its whole input so far. */
    private fun StreamingMessage.merge(index: Int, block: JsonObject, snapshot: Boolean): StreamingMessage {
        val existing = parts[index]
        val incoming = part(block) ?: return this
        val merged = when {
            snapshot -> incoming
            incoming is Part.Text && existing is Part.Text -> Part.Text(existing.text + incoming.text)
            incoming is Part.Thinking && existing is Part.Thinking && !incoming.redacted -> Part.Thinking(existing.text + incoming.text, redacted = false)
            else -> incoming
        }
        return copy(parts = parts.put(index, merged))
    }

    private operator fun Drift.plus(type: String): Drift = when (this) {
        Drift.None -> Drift.Seen(setOf(type), 1)
        is Drift.Seen -> Drift.Seen(types + type, count + 1)
    }
}

/** [earlier] is the engine's paging state while a page is in flight or failed; null defers to the fold's window. */
internal fun project(
    id: ThreadId,
    fold: ThreadFold,
    link: Link,
    pending: PersistentList<PendingSend>,
    outbox: PersistentList<OutboxEntry>,
    earlier: EarlierHistory? = null,
    self: UserId? = null,
    wake: OrbWake = OrbWake.Idle,
): ThreadState = ThreadState(
    id = id,
    title = fold.title,
    mode = fold.agentMode,
    activity = fold.live.activity,
    executor = fold.executor.toStatus(wake),
    transcript = TranscriptView.Ready(
        turns = buildTurns(fold.messages.values, fold.live.streaming),
        earlier = earlier ?: if (fold.window.hasMoreBefore) EarlierHistory.Available else EarlierHistory.None,
        compaction = fold.compaction,
        recap = fold.recap,
    ),
    queue = fold.queue.values.toPersistentList(),
    pending = pending.removeAll { it.id in fold.messages || QueuedMessageId(it.id.raw) in fold.queue },
    outbox = outbox,
    error = fold.error,
    link = link,
    drift = fold.drift,
    presence = fold.live.presence(self),
    multiplayer = fold.live.multiplayer,
)

/** Others only: the signed-in user's own viewers and drafts are left out. */
private fun LiveOverlay.presence(self: UserId?): Presence {
    val others = viewers.filter { it.user != self }.distinctBy { it.user }
    val names = viewers.associate { it.user to it.name }
    val typing = drafts.filter { (user, text) -> user != self && text.isNotBlank() }.map { (user, _) -> Viewer(user, names[user] ?: user.raw) }.distinctBy { it.user }
    return Presence(others.toPersistentList(), typing.toPersistentList())
}

/** The web's orb states (`532df6c0.js` `xM`): connected, waking (`operation` `recovering`), asleep; then the plain cases. */
private fun ExecutorFacts.toStatus(wake: OrbWake): ExecutorStatus = when {
    connected -> ExecutorStatus.Attached(kind, status, message, desktops)
    wake is OrbWake.Failed -> ExecutorStatus.OrbWakeFailed(wake.reason)
    wake is OrbWake.Sent || orbRecovering -> ExecutorStatus.OrbWaking
    orbAsleep -> ExecutorStatus.OrbAsleep
    hasExecutor == false -> ExecutorStatus.None
    status == null -> ExecutorStatus.Unknown
    else -> ExecutorStatus.Attached(kind, status, message, desktops)
}
