package com.agentifiers.amp.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.time.Instant

/** Everything a thread screen reads. Built by :sync from the fold, the link, and this device's unsent work. */
data class ThreadState(
    val id: ThreadId,
    val title: String?,
    /** The thread's agent mode key, such as `low` or a plugin mode's key. Fixed once the first message is sent. */
    val mode: String? = null,
    val activity: AgentActivity,
    val executor: ExecutorStatus,
    val transcript: TranscriptView,
    val queue: PersistentList<QueuedMessage>,
    val pending: PersistentList<PendingSend>,
    /** Dispatched intents other than sends, until they settle; failed and unknown ones stay for the composer to show. */
    val outbox: PersistentList<OutboxEntry>,
    val error: AgentError?,
    val link: Link,
    val drift: Drift,
    /** Human gates still waiting, in the order they were raised (Gates.kt). */
    val gates: PersistentList<HumanGate> = persistentListOf(),
    /** Gates that settled while this engine watched, oldest first. */
    val gateRecords: PersistentList<GateRecord> = persistentListOf(),
    /** Plugin `ui.notify` messages from the live socket, oldest first. */
    val pluginNotices: PersistentList<PluginNotice> = persistentListOf(),
    /** The executor's plugin commands from `command.list`, for the command palette. */
    val pluginCommands: PersistentList<PluginCommand> = persistentListOf(),
    val presence: Presence = Presence.Alone,
    val multiplayer: Multiplayer = Multiplayer.Unknown,
) {
    companion object {
        fun initial(id: ThreadId) = ThreadState(
            id = id,
            title = null,
            activity = AgentActivity.Unknown,
            executor = ExecutorStatus.Unknown,
            transcript = TranscriptView.Loading,
            queue = persistentListOf(),
            pending = persistentListOf(),
            outbox = persistentListOf(),
            error = null,
            link = Link.Closed,
            drift = Drift.None,
        )
    }
}

/** From `agent_state{state}`. Volatile: it resets to [Unknown] whenever the socket ends. */
sealed interface AgentActivity {
    val word: String

    data object Unknown : AgentActivity { override val word = "unknown" }
    data object Idle : AgentActivity { override val word = "idle" }
    data object Failed : AgentActivity { override val word = "error" }
    data class Reported(val state: String) : AgentActivity { override val word = state }

    sealed interface Busy : AgentActivity
    data object Working : Busy { override val word = "working" }
    data object Streaming : Busy { override val word = "streaming" }
    data object ComposingToolCall : Busy { override val word = "tool use" }
    data object RunningTools : Busy { override val word = "running tools" }
    data object Compacting : Busy { override val word = "compacting" }
}

data class AgentError(val message: String, val code: String?)

/** What the machine pane shows about the thread's executor, from `observers` and `executor_status`. */
sealed interface ExecutorStatus {
    data object Unknown : ExecutorStatus
    data object None : ExecutorStatus
    /** [desktops] are the desktops this executor offers over the actor relay (`observers.relayDesktopAvailable`, `macDesktopAvailable`). */
    data class Attached(
        val kind: String?,
        val status: String?,
        val message: String?,
        val desktops: PersistentSet<DesktopTarget> = persistentSetOf(),
    ) : ExecutorStatus

    /** The thread's orb is not connected. Machine panes wait for it instead of saying the thread has no executor. */
    sealed interface OrbOffline : ExecutorStatus

    /** The orb went to sleep after being idle. A thread shown in a started window wakes it. */
    data object OrbAsleep : OrbOffline

    /** Amp is waking the orb, at this app's request or another client's. */
    data object OrbWaking : OrbOffline

    /** The wake this app sent was refused, or the orb did not connect within the web's 2 minutes. */
    data class OrbWakeFailed(val reason: String) : OrbOffline
}

data class QueuedMessage(val id: QueuedMessageId, val text: String, val steer: Boolean)

/** A send this device made that no `message_added` or `queued_message_added` has echoed yet. */
data class PendingSend(val id: MessageId, val text: String, val steer: Boolean, val state: SendState)

sealed interface SendState {
    data object Waiting : SendState
    data object Sending : SendState
    data class Failed(val reason: String) : SendState
}

/** The thread socket's lifecycle. Owned by the engine, never by the fold. */
sealed interface Link {
    data object Closed : Link
    data object Connecting : Link
    data object Resuming : Link
    data object Live : Link
    data class Reconnecting(val attempt: Int, val cause: String) : Link
    data class Stopped(val reason: StopReason) : Link
}

enum class StopReason { Archived, MissingRivetToken }

/** Frames this build could not decode, counted and never thrown. */
sealed interface Drift {
    data object None : Drift
    data class Seen(val types: Set<String>, val count: Int) : Drift
}

/** One dispatched command until it settles. */
data class OutboxEntry(val id: IntentId, val preview: String, val state: OutboxState)

sealed interface OutboxState {
    data object Queued : OutboxState
    data class Sending(val attempt: Int) : OutboxState
    data class WaitingForNetwork(val since: Instant) : OutboxState
    /** The user must edit or discard. */
    data class Rejected(val reason: String) : OutboxState
    /** An AtMostOnce command lost its ack. The user picks Retry or Discard; nothing resends on its own. */
    data object Unknown : OutboxState
    /** A Guarded command whose guard stopped holding, e.g. the gate was answered on another client. */
    data class Dropped(val why: Guard) : OutboxState
}

data class Draft(val text: String, val attachments: PersistentList<AttachmentDraft>) {
    companion object {
        fun text(text: String) = Draft(text, persistentListOf())
    }
}

data class AttachmentDraft(val localUri: String, val mediaType: String, val upload: UploadState)

sealed interface UploadState {
    data object Pending : UploadState
    data class Uploading(val fraction: Float) : UploadState
    data class Ready(val url: String) : UploadState
    data class Failed(val reason: String) : UploadState
}

enum class ComposerActionKind { Send, Steer, Queue, InterruptAndSend, Interrupt }

/** The mode dial plus plugin and custom modes. Fixed after the first message. [model] is the agent model the mode runs. */
data class AgentMode(
    val key: String,
    val display: String,
    val locked: Boolean,
    val description: String? = null,
    val model: String? = null,
    val source: ModeSource = ModeSource.Dial,
)

/** Where a mode comes from, which also orders the mode picker: the Dial first, then built-in modes, then plugin modes. Raw models sit on their own tab. */
enum class ModeSource { Dial, BuiltIn, Plugin, RawModel }

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

/** Snooze is write-only on the cli tier (S10). Known is what this device set or reconciled; Unknown is the honest default. */
sealed interface SnoozeState {
    data object NotSnoozed : SnoozeState
    data class Snoozed(val until: SnoozeUntil, val source: SnoozeSource) : SnoozeState
    data object Unknown : SnoozeState
}

/** S10 observed only one_hour and off on the wire; the other cases are read from the web client. */
sealed interface SnoozeUntil {
    data object OneHour : SnoozeUntil
    data object NextReply : SnoozeUntil
    data class At(val instant: Instant) : SnoozeUntil
    data object Indefinitely : SnoozeUntil
}

enum class SnoozeSource { ThisDevice, Reconciled }

/** e.g. `a1.medium`. */
@JvmInline
value class OrbSize(val key: String)

sealed interface OrbProvider {
    data object Amp : OrbProvider

    /** A workspace's or the user's own provider. The two cases are the two ways Amp's `orbProvider` schema names one. */
    sealed interface SelfHosted : OrbProvider {
        val name: String

        /** Listed by the account's web session; Amp gets `{configID}`, as the web composer sends. */
        data class Listed(val configId: OrbProviderConfigId, override val name: String) : SelfHosted

        /** Typed by the user; Amp gets `{name}`, as `amp --orb-provider <name>` sends. */
        data class Named internal constructor(override val name: String) : SelfHosted

        companion object {
            /** Null unless the trimmed name fits Amp's `orbProvider{name}` schema of 1 to 64 characters on one line. */
            fun named(raw: String): Named? = raw.trim().takeIf { it.length in 1..64 && it.none(Char::isISOControl) }?.let(::Named)
        }
    }
}

@JvmInline
value class OrbProviderConfigId(val raw: String)

enum class OrbProviderOwner { Workspace, Personal }

/** One row of the web composer's `orbProviderOptions`. */
data class OrbProviderOption(val provider: OrbProvider.SelfHosted.Listed, val isDefault: Boolean, val owner: OrbProviderOwner)

/** One read of the account's self-hosted providers through its ampcode.com web session. */
sealed interface OrbProviderListing {
    data class Listed(val options: List<OrbProviderOption>) : OrbProviderListing

    /** The account's WebView profile has no ampcode.com session, or it expired. */
    data object SignedOut : OrbProviderListing

    data class Failed(val reason: String) : OrbProviderListing
}

/** Lists an account's self-hosted providers. :feature:web reads them inside the account's WebView profile. */
fun interface OrbProviderSource {
    suspend fun list(account: Account): OrbProviderListing
}

/** Why a machine pane or capability is missing; the pane shows the reason instead of hiding. */
enum class UnavailableReason { NoExecutor, RunnerFlagMissing, NotOrb, PolicyDisabled, ReadOnlyViewer, Unknown }
