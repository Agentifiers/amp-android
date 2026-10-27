// :sync  package amp.sync.internal.thread   (internal)
// One ThreadEngine per (account, thread). ThreadState is built at read time from four sources, and
// each source has exactly one writer (separate-before-serializing-shared-state):
//
//   durable     DurableThread    written only by apply(), from Sequenced + Snapshot facts; cached
//   volatile    VolatileThread   written only by apply(), from Volatile facts; dropped on a new Epoch
//   connection  Connection       written only by LinkSupervisor: Link + Viewer, never by the fold
//   local       LocalThread      written only by the outbox, the ephemeral sender and the draft store: this device's unsent work
//
// A replayed frame can move the fold, never the link; a reconnect can move the link, never the fold.
package amp.sync.internal.thread

import amp.model.*
import amp.sync.api.*
import amp.sync.internal.outbox.EphemeralSender
import amp.sync.internal.outbox.OutboxDrainer
import amp.sync.internal.transport.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

internal class ThreadEngine(
    override val id: ThreadId,
    private val scope: CoroutineScope,
    private val links: LinkSupervisor,
    private val outbox: OutboxDrainer,
    private val ephemeral: EphemeralSender,
    private val cache: amp.store.ThreadCache,
    private val drafts: amp.store.Drafts,
    private val reported: StateFlow<ThreadSummary?>,
) : ThreadHandle {

    private val durable: StateFlow<DurableThread> = TODO("seeded from ThreadCache.load, then apply()")
    private val volatile: StateFlow<VolatileThread> = TODO("VolatileThread.empty(epoch) on every new Epoch (I4)")
    private val connection: StateFlow<Connection> = TODO("links.connection(id)")
    private val local: StateFlow<LocalThread> = TODO("outbox.observe(id) + ephemeral.observe(id) + drafts.observe(id) + dictation")

    override val state: StateFlow<ThreadState> = TODO("combine(durable, volatile, connection, local, reported, ::project)")

    override fun attach(token: ViewerToken): AutoCloseable = TODO("links.lease(id, token): refcount; no socket while the executor is paused; last release closes after 30 s unless a relay channel is open")
    override fun connectLive(): Unit = TODO("links.connectLive(id)")
    override fun dispatch(intent: ThreadIntent): IntentId = TODO(
        "when (intent) { is Command.Durable -> outbox.enqueue(id, intent); is Command.Ephemeral -> ephemeral.send(id, intent) }"
    )
    override val machine: Machine get() = TODO()
    override fun loadEarlier() = TODO("getThreadTranscriptPage(before, anchorID) → MessageRows (I6); Stale → EarlierHistory.Stale")

    /**
     * Called by LinkSupervisor once per inbound frame, in order, on one coroutine per thread.
     * 1. plan = ThreadReducer.plan(cursor, epoch, durable, volatile, facts)       pure
     * 2. cache.commit(id, plan.cache)                                             rows + cursor, one transaction (I2)
     * 3. publish plan.durable and plan.volatile
     * 4. outbox.acknowledge(plan.echoes)                                          I7, after commit
     * 5. perform plan.effects                                                      never inside the reducer
     */
    suspend fun apply(epoch: Epoch, facts: List<ThreadFact>): Unit = TODO()
}

/**
 * Cold open and reconnect, driven by LinkSupervisor:
 * 1. durable = rebuild(cache.load(id)) through the same decoders; Link.Cached.
 * 2. Cache empty: getThreadInitialTranscriptPage (S9) seeds MessageRows and the cursor in one
 *    commit. The cursor is the page's thread.data.v: client_resume{version: v} replays 0 messages,
 *    and version 0 replays all 28 message_added frames of that thread (replayMessageCount 40 also
 *    counts title, queue and cancelled frames), with v equal to replayThroughSeq (spike S17,
 *    s17-resume-cursor.jsonl:2,73,104).
 * 3. Executor paused: stop here with Link.OrbPaused until connectLive(), a send, or a machine pane.
 * 4. threadToken → connect → client_resume{version: cursor} → Link.Resuming → new Epoch.
 * 5. Replay, then the snapshot burst (observers, approvals, plugin.ui.snapshot …), fold through apply()
 *    in that order (S17: replay at :10-44, burst at :45-70); Link.Live.
 * 6. Effect.DrainOutbox runs after the burst. A resent Send is safe: the server dedupes by messageId (S16).
 */
internal interface LinkSupervisor {
    fun connection(thread: ThreadId): StateFlow<Connection>
    fun lease(thread: ThreadId, token: ViewerToken): AutoCloseable
    fun connectLive(thread: ThreadId)
    /** The thread's one socket if it is open, or null. Never opens one. */
    fun liveSocket(thread: ThreadId): ThreadSocket?
    /**
     * Runs [block] on the thread's one socket. If none is open, opens a short socket and registers it
     * as that socket, so a lease taken meanwhile joins it and the thread never has two sockets or two
     * folds. A short socket resumes from the cached cursor. When the thread has no cache, it resumes
     * from WsGrant.threadVersion, folds in memory, and writes no cache rows. [block] runs after the
     * replay and snapshot burst have folded, so guards see dialogs that are never cached. Refused for an
     * Asleep orb. This is the only way to answer a gate without a lease, because /request/rpc rejects
     * client_tool_approval_response (S18) and accepts nothing but append.
     */
    suspend fun <R> onSocket(thread: ThreadId, block: suspend (ThreadSocket, DurableThread) -> R): Outcome<R>
}

/** Connection lifecycle, outside the fold. Viewer comes from the WsGrant capability. */
internal data class Connection(val link: Link, val viewer: Viewer, val epoch: Epoch?)

/** Incremented each time a socket reaches Live. Volatile state belongs to exactly one Epoch. */
@JvmInline internal value class Epoch(val n: Int)

/** Server truth from Sequenced and Snapshot facts. Rebuildable from cache rows alone, except drift, which counts this process's frames. */
internal data class DurableThread(
    val cursor: Seq,
    val header: ThreadHeader,
    val executor: ExecutorState,
    val messages: PersistentMap<MessageId, DecodedMessage>,   // I6: one entry per protocol message id
    val notices: PersistentList<Step.Marker>,                 // cancelled, truncated
    val queue: PersistentList<QueuedMessage>,
    val gates: PersistentList<HumanGate>,                     // approvals + pending plugin dialogs (PluginUi aspect)
    val pluginCommands: PersistentList<PluginCommand>,        // plugin_message command.list (S15b :50)
    val error: AgentError?,
    val collaboration: Collaboration,
    val earlier: EarlierHistory,
    val drift: Degradation,
    val viewers: PersistentList<Participant>,                 // observers (Snapshot); its flags also feed ExecutorState caps
)
internal data class DecodedMessage(val id: MessageId, val ordinal: Long, val prompt: Prompt?, val steps: PersistentList<Step>)

/** Volatile facts for one Epoch. In memory only (I4). A durable message shadows its streaming overlay (I5). */
internal data class VolatileThread(
    val epoch: Epoch,
    val phase: AgentActivity?,                                 // agent_state, retry_*; null until the first agent_state
    val streaming: PersistentMap<StepKey, Step>,               // delta overlay, one Step per messageId+blockIndex
    val toolProgress: PersistentMap<ToolCallId, JsonValue>,
    val typing: PersistentList<TypingDraft>,                   // prompt_drafts, comment_typing, discussion_typing
) { companion object { fun empty(epoch: Epoch): VolatileThread = TODO() } }

internal data class LocalThread(val draft: Draft, val outbox: PersistentList<OutboxEntry>, val dictation: DictationState)

/**
 * Pure read-time merge. Composer actions derive here (Idle → Send; Busy → Steer, Queue, Interrupt).
 * activity = volatile.phase ?: AgentActivity.LastKnown(reported.status, reported.lastActivity).
 * transcript = durable.messages ⊕ volatile.streaming (I5) ⊕ local outbox Sends not yet echoed.
 */
internal fun project(durable: DurableThread, volatile: VolatileThread, connection: Connection, local: LocalThread, reported: ThreadSummary?): ThreadState = TODO()

// ── Delta handling (the hot path) ────────────────────────────────────────────

/**
 * delta{messageId, blocks, blockIndex, contentMode: append|snapshot, state} is Volatile and touches
 * exactly one Step in VolatileThread.streaming (StepKey = messageId+blockIndex). Replacing one entry
 * in a PersistentMap costs O(log n), and the LazyColumn recomposes one item. message_added for the
 * same messageId later lands in durable and shadows the overlay entry.
 */
internal object DeltaOverlay {
    fun apply(v: VolatileThread, f: ThreadFact.Delta): VolatileThread = TODO()
}
