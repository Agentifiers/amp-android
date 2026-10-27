// :core:model  package amp.model
// ThreadState: the single value every thread screen reads. :sync builds it at read time from four
// sources that never write each other's fields (ThreadEngine.project):
//   durable fold   Sequenced + Snapshot facts, rebuildable from the cache
//   volatile tier  Volatile facts (deltas, agent_state, tool_progress), dropped on every reconnect
//   connection     Link + Viewer, owned by the link supervisor, never by the fold
//   local          draft + outbox, this device's own unsent work
// No field here is written by UI code.
package amp.model

import kotlinx.collections.immutable.PersistentList
import kotlin.time.Duration
import kotlin.time.Instant

data class ThreadState(
    val id: ThreadId,
    val header: ThreadHeader,
    val activity: AgentActivity,
    val executor: ExecutorState,
    val transcript: TranscriptView,
    val queue: PersistentList<QueuedMessage>,
    val gates: PersistentList<HumanGate>,
    val pluginCommands: PersistentList<PluginCommand>,        // command palette entries for this thread
    val composer: ComposerState,
    val presence: Presence,
    val collaboration: Collaboration,
    val link: Link,
    val viewer: Viewer,
    val drift: Degradation,
)

/** Title bar data. Every field is always known once the summary or first page loaded. */
data class ThreadHeader(
    val title: ThreadTitle,
    val mode: AgentMode,
    val project: ProjectRef,
    val visibility: Visibility,
    val labels: PersistentList<LabelName>,
    val pinned: Boolean,
    val color: ThreadColor,
    val snooze: SnoozeState,
    val recap: Recap,
    val cost: CostGlance,
)

sealed interface ThreadTitle {
    data object Untitled : ThreadTitle
    data class Generated(val text: String) : ThreadTitle
    data class Manual(val text: String) : ThreadTitle
}

/** The Dial plus plugin/custom modes. Fixed after the first message. */
data class AgentMode(val key: String, val display: String, val locked: Boolean)

sealed interface ProjectRef {
    data object None : ProjectRef
    data class Linked(val id: ProjectId, val name: String, val repos: PersistentList<RepoRef>, val shipBehavior: ShipBehavior) : ProjectRef
}
data class RepoRef(val url: String, val role: RepoRole)
enum class RepoRole { Primary, Additional }

sealed interface ShipBehavior {
    data object Ship : ShipBehavior
    data object PushBranch : ShipBehavior
    data class Custom(val prompt: String) : ShipBehavior
}

/** Wire enum private | thread_group_shared | thread_workspace_shared | public_unlisted (core.md:282). */
sealed interface Visibility {
    data object Private : Visibility
    data object Workspace : Visibility
    data class Groups(val groupIds: PersistentList<String>) : Visibility
    data object Unlisted : Visibility
}

enum class ThreadColor { None, Red, Orange, Yellow, Green, Blue, Purple, Gray }

/**
 * Snooze is write-only on cli tier (spike S10: listThreads has no snooze field). Known is what
 * this device set or reconciled via `find?q=snoozed:true`; Unknown is the honest default.
 */
sealed interface SnoozeState {
    data object NotSnoozed : SnoozeState
    data class Snoozed(val until: SnoozeUntil, val source: SnoozeSource) : SnoozeState
    data object Unknown : SnoozeState
}
/**
 * Wire values: S10 observed only one_hour and off (s10-thread-ops.jsonl:147-152, measured). The other
 * cases are read from the web client and not observed.
 */
sealed interface SnoozeUntil {
    data object OneHour : SnoozeUntil
    data object NextReply : SnoozeUntil
    data class At(val instant: Instant) : SnoozeUntil
    data object Indefinitely : SnoozeUntil
}
enum class SnoozeSource { ThisDevice, Reconciled }

sealed interface Recap {
    data object None : Recap
    data class Available(val text: String, val anchor: MessageId, val generatedAt: Instant) : Recap
}

sealed interface CostGlance {
    data object NotLoaded : CostGlance
    data class Known(val totalUsd: Double, val orbHours: Duration?) : CostGlance
}

// ── Agent loop ────────────────────────────────────────────────────────────────

/**
 * From agent_state{state} (private-protocol.md:151) plus retry_*/error_* events.
 * `Busy` subtypes are what the composer uses to offer Steer/Queue/Interrupt.
 */
sealed interface AgentActivity {
    data object Idle : AgentActivity
    /**
     * No socket epoch is live, so no agent_state has arrived (agent_state is Volatile). Shows the user
     * actor's last report from the fleet summary instead of claiming Idle.
     */
    data class LastKnown(val status: FleetStatus, val asOf: Instant) : AgentActivity
    sealed interface Busy : AgentActivity { val since: Instant }
    data class Working(override val since: Instant) : Busy                 // "working"
    data class Streaming(override val since: Instant) : Busy               // "streaming"
    data class ComposingToolCall(override val since: Instant) : Busy       // "tool_use"
    data class RunningTools(override val since: Instant, val background: Boolean) : Busy
    data class AwaitingHuman(override val since: Instant, val gateCount: Int) : Busy
    data class Compacting(override val since: Instant) : Busy
    data class Retrying(override val since: Instant, val at: Instant, val attempt: Int, val of: Int, val reason: String) : Busy
    data class Failed(val error: AgentError) : AgentActivity
}
data class AgentError(val message: String, val code: String?, val dismissible: Boolean, val retryable: Boolean)

// ── Executor ──────────────────────────────────────────────────────────────────

/** From executor_status, executor_connected, observers, environment_update, executor_health. */
sealed interface ExecutorState {
    data object None : ExecutorState                                       // plain thread, no machine panes
    data class Provisioning(val kind: ExecutorKind, val stage: String, val setupLog: SetupLogRef) : ExecutorState
    data class WaitingForCapacity(val kind: ExecutorKind, val estimate: Duration?) : ExecutorState
    data class Connected(val kind: ExecutorKind, val caps: MachineCaps, val health: ExecutorHealth) : ExecutorState
    data class Asleep(val kind: ExecutorKind.Orb) : ExecutorState          // opening wakes and bills
    data class RunnerOffline(val runner: RunnerId, val lastSeen: Instant?) : ExecutorState
    data class SetupFailed(val kind: ExecutorKind, val reason: String, val setupLog: SetupLogRef) : ExecutorState
}

sealed interface ExecutorKind {
    data class Orb(val size: OrbSize, val provider: OrbProvider) : ExecutorKind
    data class Runner(val id: RunnerId, val hostname: String, val directory: String) : ExecutorKind
    data object LocalCli : ExecutorKind
}
@JvmInline value class OrbSize(val key: String)                 // e.g. a1.medium
sealed interface OrbProvider { data object Amp : OrbProvider; data class SelfHosted(val name: String) : OrbProvider }
@JvmInline value class SetupLogRef(val thread: ThreadId)

/**
 * Machine capabilities from the `observers` event flags (private-protocol.md:156). Panes are
 * offered only when the capability is present; a missing capability renders its reason.
 */
data class MachineCaps(
    val terminal: Availability,      // relay always when connected; runner needs --remote-control-terminal
    val desktop: Availability,       // relayDesktopAvailable | macDesktopAvailable; the Desktop tab appears only when Yes
    val files: Availability,
    val gitLive: Availability,
    val diffCaptures: Availability,  // orb threads only (spike S7)
    val portals: Availability,
)
sealed interface Availability {
    data object Yes : Availability
    data class No(val reason: UnavailableReason) : Availability
}
enum class UnavailableReason { NoExecutor, RunnerFlagMissing, NotOrb, PolicyDisabled, ReadOnlyViewer, Unknown }

data class ExecutorHealth(val issues: PersistentList<HealthIssue>)
data class HealthIssue(val kind: String, val critical: Boolean, val detail: String)

// ── Queue ─────────────────────────────────────────────────────────────────────

/** From queued_messages / queued_message_added|removed|dequeued (spike S5). */
data class QueuedMessage(
    val id: QueuedMessageId,
    val preview: String,
    val delivery: QueuedDelivery,
    val author: Author,
)
enum class QueuedDelivery { SteerAtNextToolBoundary, AfterTurn }

sealed interface Author {
    data object Me : Author
    data class Member(val id: UserId, val name: String) : Author
    data object Puck : Author
    data object Automation : Author
    data class Plugin(val name: String) : Author
    data class OtherThread(val thread: ThreadId) : Author
}

// ── Composer (per-actor local state merged at read) ──────────────────────────

data class ComposerState(
    val draft: Draft,
    val actions: PersistentList<ComposerAction>,   // derived from activity + viewer; never empty for writers
    val mode: ModeChip,
    val outbox: PersistentList<OutboxEntry>,
    val dictation: DictationState,
    val blockedReason: ComposerBlock,
)
data class Draft(val text: String, val attachments: PersistentList<AttachmentDraft>)
data class AttachmentDraft(val localUri: String, val mediaType: String, val upload: UploadState)
sealed interface UploadState {
    data object Pending : UploadState
    data class Uploading(val fraction: Float) : UploadState
    data class Ready(val url: String) : UploadState
    data class Failed(val reason: String) : UploadState
}

/** A real button in the composer. `kind` drives the test tag; `intents` builds the dispatches, in order. */
data class ComposerAction(val kind: ComposerActionKind, val label: String, val primary: Boolean) {
    fun intents(draft: Draft): List<ThreadIntent> = TODO(
        "Send/Steer/Queue → [Send(draft, mode)]; Interrupt → [Interrupt]; InterruptAndSend → [Interrupt, Send(draft, SendMode.Auto)]"
    )
}
enum class ComposerActionKind { Send, Steer, Queue, InterruptAndSend, Interrupt }

sealed interface ModeChip {
    data class Choosable(val current: AgentMode, val options: PersistentList<AgentMode>) : ModeChip  // before first message
    data class Locked(val mode: AgentMode) : ModeChip
}

sealed interface ComposerBlock {
    data object None : ComposerBlock
    data object ReadOnlyViewer : ComposerBlock
    data object Archived : ComposerBlock
    data object OutOfCredits : ComposerBlock
}

/**
 * One row of the outbox, which holds every dispatched Command until it settles. Survives process
 * death. The composer shows the Send rows; the thread menu and row menus show the rest.
 */
data class OutboxEntry(val id: IntentId, val preview: String, val state: OutboxState)
sealed interface OutboxState {
    data object Queued : OutboxState
    data class Sending(val attempt: Int) : OutboxState
    data class WaitingForNetwork(val since: Instant) : OutboxState
    data class Rejected(val reason: String) : OutboxState            // user must edit or discard
    /** An AtMostOnce command lost its ack. The user picks [Retry] or [Discard]; nothing resends on its own. */
    data object Unknown : OutboxState
    /** A Guarded command whose guard stopped holding, e.g. the gate was answered on another client. */
    data class Dropped(val why: Guard) : OutboxState
}

sealed interface DictationState {
    data object Unavailable : DictationState
    data object Ready : DictationState
    data class Recording(val level: Float) : DictationState
    data object Transcribing : DictationState
    data class HeldElsewhere(val device: String) : DictationState    // claimDictation lock
}

// ── Presence and collaboration ───────────────────────────────────────────────

data class Presence(val viewers: PersistentList<Participant>, val typing: PersistentList<TypingDraft>)
data class Participant(val id: UserId, val name: String, val avatarUrl: String?)
data class TypingDraft(val who: Participant, val text: String)

sealed interface Collaboration {
    data object Solo : Collaboration
    data class Open(val until: Instant, val comments: Int) : Collaboration    // client_set_thread_open
}

// ── Connection lifecycle ─────────────────────────────────────────────────────

/**
 * One state machine for the thread socket, visible in the title bar. Owned by the link supervisor in
 * :sync, outside the fold: replaying events can never move it.
 */
sealed interface Link {
    /** Rendering cache only; no lease held. */
    data class Cached(val asOf: Instant) : Link
    /**
     * The executor is paused, so a lease opens no socket: the pane shows the cache plus
     * getThreadInitialTranscriptPage over internal RPC (S9). The socket opens on ThreadHandle.connectLive()
     * (Connect live, tag orb-connect-live), a send, or opening a machine pane, each after the cost notice.
     */
    data class OrbPaused(val asOf: Instant) : Link
    data object Connecting : Link
    data class Resuming(val from: Seq) : Link
    data object Live : Link
    data class Reconnecting(val attempt: Int, val nextAt: Instant, val cause: String) : Link
    data class Offline(val since: Instant) : Link
    data class Fatal(val reason: FatalReason) : Link
}
/** Protocol drift made visible: unknown event types and malformed frames seen by the fold, by type name, never thrown. */
sealed interface Degradation {
    data object None : Degradation
    /** Unknown types are new protocol, malformed ones are known types that failed to decode. Both can occur at once. */
    data class Seen(val unknownTypes: PersistentList<String>, val malformedTypes: PersistentList<String>, val count: Int) : Degradation
}
enum class FatalReason { Archived, Deleted, AccessDenied, ReauthRequired, PaymentRequired }

/**
 * From the wsToken cap claim (WsGrant), owned with Link by the link supervisor. Gates for contributors
 * are ReadOnly (customize-enterprise.md:32).
 */
sealed interface Viewer {
    data object Owner : Viewer                 // cap=write
    data object Contributor : Viewer           // cap=contribute, thread open
    data object Reader : Viewer                // cap=read
    /** No grant minted this session yet (cold open from cache). Commands queue; the grant decides. */
    data object NotYetGranted : Viewer
}
