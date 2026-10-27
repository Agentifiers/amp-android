// :sync  package amp.sync.internal.thread   (internal)
// Fact classes. Every thread actor event decodes to exactly one ThreadFact, and its class decides how
// the fold and the cache treat it. The classes come from the captures: across every recv frame in
// spikes/protocol/captures/*.jsonl (spikes 1 and 2, 895 typed frames), only these types carry `seq`
// (measured, frame counts): message_added 210, error_set 39, error_cleared 7, queued_message_added 8,
// queued_message_dequeued 8, cancelled 8.
// Every other captured type carries none: observers, thread_title, thread_open_state, thread_settings,
// thread_features, queued_messages, tool_approval_queue, plugin_message (command.list,
// link-pattern.list, plugin.ui.snapshot, plugin.ui.request_added, plugin.ui.request_settled, ui.notify),
// executor_status, executor_health, environment_update, background_*, agent_state, delta,
// tool_progress, prompt_drafts, comment_typing, discussion_typing, inference_tools, retry_cancelled,
// executor_workspace_maybe_changed.
// Types not seen in any capture are classed by family and marked [inferred] below.
//
// Invariants. ThreadReducerReplayTest (sync/test) is their executable form.
//   I1  A Sequenced fact upserts its row by key (messageId, or seq for non-message types), and the cursor
//       becomes max(cursor, seq). Applying the same fact twice equals applying it once. Facts at or
//       below the cursor are still applied, because the connect burst re-sends error_set{seq:9} when
//       resuming at version 9 (s17-resume-cursor.jsonl:157, measured; docs/design/transport.md).
//   I2  Rows and cursor advance in one cache transaction; neither moves alone.
//   I3  A Snapshot replaces its whole aspect, and prunes that aspect's Sequenced rows. Twice = once.
//       A Keyed fact upserts, settles, or removes one entry of an aspect by the entry's own id. Twice = once.
//       The PluginUi aspect (snapshot and keyed changes) is never cached (Aspect.cached = false): the
//       server re-lists pending dialogs in plugin.ui.snapshot on every reconnect (S15c :101, measured),
//       and answering needs a socket anyway, so a cached dialog could only be stale.
//   I4  Volatile facts never reach the cache. They belong to one Epoch and drop when it ends.
//   I5  For one messageId, the durable message shadows its streaming overlay.
//   I6  Message identity is the protocol MessageId. Paged rows and replayed rows upsert the same key.
//   I7  The first echo of a client-minted MessageId acknowledges its outbox entry: queued_message_added
//       (a steer or queue while busy; queuedMessage.messageId is the client id, s05-steer-interrupt.jsonl:56,
//       measured) or message_added. A resend after the ack is harmless: the server dedupes by messageId
//       and re-emits the original seq (S16), and I1 upserts the same row again.
//   I8  A frame of a Sequenced type that arrives without seq is Malformed. It is never applied.
package amp.sync.internal.thread

import amp.model.*
import kotlinx.serialization.json.JsonObject

/** The verbatim frame JSON. Cached as-is so a decoder fix applies to cached threads without a migration. */
@JvmInline internal value class RawFrame(val json: String)

/** The part of DurableThread a fact touches. Snapshots replace one aspect; Sequenced rows and Keyed facts name theirs. */
internal enum class Aspect(val cached: Boolean = true) { Queue, Error, Transcript, Approvals, PluginUi(cached = false), PluginCommands, Title, Settings, Features, OpenState, Recap, Executor, Health, Environment, Observers, BackgroundWork }

internal sealed interface ThreadFact {
    /** Carries seq; upserted by key whatever the cursor (I1). Cached as a row keyed by its own seq or messageId. */
    sealed interface Sequenced : ThreadFact { val seq: Seq; val raw: RawFrame }
    /** Whole-value replace of one aspect (I3). Cached as the aspect's latest frame when aspect.cached. */
    sealed interface Snapshot : ThreadFact { val aspect: Aspect; val raw: RawFrame }
    /** Memory only, scoped to one Epoch (I4). */
    sealed interface Volatile : ThreadFact
    /** No seq; upserts or settles one entry of [aspect] by its id (I3). Not cached. */
    sealed interface Keyed : ThreadFact { val aspect: Aspect; val key: String }

    // ── Sequenced ──
    data class MessageAdded(override val seq: Seq, override val raw: RawFrame, val message: DecodedMessage) : Sequenced
    data class MessageUpdated(override val seq: Seq, override val raw: RawFrame, val message: DecodedMessage) : Sequenced      // [inferred]
    /** queuedMessage.messageId is the client's MessageId for its own sends (S5 :56), so [echo] acknowledges the outbox (I7). */
    data class QueuedAdded(override val seq: Seq, override val raw: RawFrame, val item: QueuedMessage, val echo: MessageId) : Sequenced
    data class QueuedDequeued(override val seq: Seq, override val raw: RawFrame, val id: QueuedMessageId) : Sequenced
    data class QueuedRemoved(override val seq: Seq, override val raw: RawFrame, val id: QueuedMessageId) : Sequenced         // [inferred]
    data class Cancelled(override val seq: Seq, override val raw: RawFrame, val message: MessageId?) : Sequenced
    data class Truncated(override val seq: Seq, override val raw: RawFrame, val from: MessageId) : Sequenced                 // [inferred]
    data class ErrorSet(override val seq: Seq, override val raw: RawFrame, val error: AgentError) : Sequenced
    data class ErrorCleared(override val seq: Seq, override val raw: RawFrame) : Sequenced

    // ── Snapshot ──
    data class QueueSnapshot(override val raw: RawFrame, val items: List<QueuedMessage>) : Snapshot { override val aspect get() = Aspect.Queue }
    /** tool_approval_queue. Entries that fail to decode arrive here already as UnsupportedGate. */
    data class Approvals(override val raw: RawFrame, val gates: List<HumanGate>) : Snapshot { override val aspect get() = Aspect.Approvals }
    /** plugin_message plugin.ui.snapshot: every pending dialog. Unknown request methods arrive as UnsupportedGate. */
    data class PluginUi(override val raw: RawFrame, val gates: List<HumanGate>) : Snapshot { override val aspect get() = Aspect.PluginUi }
    /** plugin_message command.list (S15b :50). */
    data class PluginCommands(override val raw: RawFrame, val commands: List<PluginCommand>) : Snapshot { override val aspect get() = Aspect.PluginCommands }
    data class Title(override val raw: RawFrame, val title: ThreadTitle) : Snapshot { override val aspect get() = Aspect.Title }
    data class Settings(override val raw: RawFrame, val mode: AgentMode) : Snapshot { override val aspect get() = Aspect.Settings }
    data class Features(override val raw: RawFrame, val features: Set<String>) : Snapshot { override val aspect get() = Aspect.Features }
    /** thread_open_state carries no seq in any capture, so it is a Snapshot, not gated on the cursor. */
    data class OpenState(override val raw: RawFrame, val collaboration: Collaboration) : Snapshot { override val aspect get() = Aspect.OpenState }
    data class RecapState(override val raw: RawFrame, val recap: Recap) : Snapshot { override val aspect get() = Aspect.Recap }         // [inferred]
    data class ExecutorStatus(override val raw: RawFrame, val executor: ExecutorState) : Snapshot { override val aspect get() = Aspect.Executor }
    data class Health(override val raw: RawFrame, val health: ExecutorHealth) : Snapshot { override val aspect get() = Aspect.Health }
    data class Environment(override val raw: RawFrame, val body: JsonValue) : Snapshot { override val aspect get() = Aspect.Environment }
    data class Observers(override val raw: RawFrame, val viewers: List<Participant>, val caps: MachineCaps) : Snapshot { override val aspect get() = Aspect.Observers }
    data class BackgroundWork(override val raw: RawFrame, val calls: List<ToolCallId>) : Snapshot { override val aspect get() = Aspect.BackgroundWork }

    // ── Volatile ──
    data class AgentStateFact(val activity: AgentActivity) : Volatile
    data class Delta(val message: MessageId, val blockIndex: Int, val append: Boolean, val body: JsonObject) : Volatile
    data class Progress(val call: ToolCallId, val progress: JsonValue) : Volatile
    data class InferenceTools(val calls: List<ToolCallId>) : Volatile
    data class RetryCancelled(val at: kotlin.time.Instant) : Volatile
    data class Typing(val drafts: List<TypingDraft>) : Volatile
    /** executor_workspace_maybe_changed: no state of its own, only Effect.RefreshChanges. */
    data object WorkspaceMaybeChanged : Volatile
    /** plugin_message ui.notify (S15b :73): a snackbar, no state. */
    data class PluginNotify(val message: String) : Volatile

    // ── Keyed ──
    /** plugin.ui.request_added (S15b :64). */
    data class PluginRequestAdded(val gate: HumanGate) : Keyed { override val aspect get() = Aspect.PluginUi; override val key get() = gate.id.raw }
    /** plugin.ui.request_settled (S15b :67): removes the pending dialog, whoever answered it. */
    data class PluginRequestSettled(val requestId: String) : Keyed { override val aspect get() = Aspect.PluginUi; override val key get() = requestId }
    /** plugin.ui.request_removed (customize-enterprise.md:24, read, not observed): removes the dialog, e.g. a skipIfUnwatched one. */
    data class PluginRequestRemoved(val requestId: String) : Keyed { override val aspect get() = Aspect.PluginUi; override val key get() = requestId }

    // ── Drift ──
    data class Unrecognized(val type: String) : ThreadFact
    data class Malformed(val type: String, val reason: String) : ThreadFact
}

// ── Decoding: one map, one file per family ───────────────────────────────────

/**
 * Adding a protocol message is one decoder in its family file plus its type names in [WireDecoders].
 * A type with no decoder becomes Unrecognized; a decoder returning null becomes Malformed. Both raise
 * ThreadState.drift, never throw.
 */
internal interface EventDecoder {
    /** Envelope already parsed by Transport; seq is the frame's seq or null. A Sequenced type with null seq returns null (I8). */
    fun decode(type: String, seq: Seq?, raw: RawFrame, body: JsonObject): ThreadFact?
}

/**
 * Every event type the app decodes, in one list. WireDecodersTest fails when a type name appears
 * twice, because `toMap()` would silently keep the last entry.
 */
internal object WireDecoders {
    val entries: List<Pair<String, EventDecoder>> =
        QueueEvents.types.map { it to QueueEvents } /* + TranscriptEvents, StreamEvents, GateEvents, ExecutorEvents,
        MetaEvents, PresenceEvents, CommentEvents, PuckEvents, each the same way */
    val byType: Map<String, EventDecoder> = entries.toMap()
}

// Example family file: events/QueueEvents.kt
internal object QueueEvents : EventDecoder {
    val types = listOf("queued_messages", "queued_message_added", "queued_message_removed", "queued_message_dequeued")
    override fun decode(type: String, seq: Seq?, raw: RawFrame, body: JsonObject): ThreadFact? = TODO(
        "queued_messages → QueueSnapshot; _added → QueuedAdded (needs seq); _removed/_dequeued → by queuedMessage.messageId (need seq). " +
        "steer:true → QueuedDelivery.SteerAtNextToolBoundary (spike S5 :56, :63)"
    )
}

// Families (one file each): TranscriptEvents (message_added/updated, thread_truncated, cancelled,
// compaction_*), StreamEvents (delta, agent_state, inference_tools, tool_progress, retry_*), GateEvents
// (tool_approval_queue, plugin_message → plugin.ui.snapshot, request_added, request_settled,
// request_removed), ExecutorEvents (executor_status/health, environment_update,
// executor_workspace_maybe_changed, background_*; tunnel_credentials ignored), MetaEvents
// (thread_settings, thread_features, thread_title, thread_recap, thread_open_state, error_set/cleared),
// PresenceEvents (observers, prompt_drafts, comment_typing, discussion_typing), CommentEvents
// (thread_comments, discussion_comments), PuckEvents (puck_voice_*, puck_platform_navigation,
// puck_media_presentation). client_*_result frames never become facts: Transport routes them to the
// RequestCorrelator.

// ── The fold ─────────────────────────────────────────────────────────────────

/** Pure. No I/O and no clock (time arrives inside facts), so a test can replay any capture through it. */
internal object ThreadReducer {
    fun plan(epoch: Epoch, durable: DurableThread, volatile: VolatileThread, facts: List<ThreadFact>): ApplyPlan = TODO(
        """
        var d = durable; var v = if (volatile.epoch == epoch) volatile else VolatileThread.empty(epoch)    // I4
        val write = CacheWriteBuilder(); val echoes = mutableListOf<MessageId>(); val effects = mutableListOf<Effect>()
        for (f in facts) when (f) {
          is ThreadFact.Sequenced -> {                                                                  // I1: upsert by key
              d = foldSequenced(d, f); write.sequenced(f); d = d.copy(cursor = maxOf(d.cursor, f.seq))
              if (f is ThreadFact.MessageAdded) echoes += f.message.id                                  // I7
              if (f is ThreadFact.QueuedAdded) echoes += f.echo                                         // I7
          }
          is ThreadFact.Snapshot -> { d = foldSnapshot(d, f); if (f.aspect.cached) write.snapshot(f) }  // I3
          is ThreadFact.Volatile -> { v = foldVolatile(v, f); effects += effectOf(f) }                   // I4
          is ThreadFact.Keyed -> {                                                                     // I3, not cached
              d = foldKeyed(d, f)
              if (f is ThreadFact.PluginRequestSettled || f is ThreadFact.PluginRequestRemoved) effects += Effect.GateSettled(GateId(f.key))
          }
          is ThreadFact.Unrecognized, is ThreadFact.Malformed -> d = d.copy(drift = d.drift + f)
        }
        return ApplyPlan(d, v, write.build(cursor = d.cursor), echoes, effects)                          // I2
        """,
    )
    /** Cold open: rebuild DurableThread from cache rows through the same decoders and fold functions. */
    fun rebuild(cached: amp.store.CachedThread): DurableThread = TODO()
}

internal data class ApplyPlan(
    val durable: DurableThread,
    val volatile: VolatileThread,
    val cache: amp.store.CacheWrite,
    val echoes: List<MessageId>,
    val effects: List<Effect>,
)

/** Side effects the engine performs after the commit. The reducer only names them. */
internal sealed interface Effect {
    data object RefreshChanges : Effect                // executor_workspace_maybe_changed
    data object UpsertPushSubscription : Effect        // on every new Epoch while push is registered
    data object DrainOutbox : Effect                   // after the replay and snapshot burst, so Guarded commands check the caught-up fold
    /** Emitted on BackgroundAccess.settledGates, so :notify cancels the gate's notification. */
    data class GateSettled(val gate: GateId) : Effect
}
