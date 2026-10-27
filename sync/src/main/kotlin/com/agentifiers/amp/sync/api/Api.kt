package com.agentifiers.amp.sync.api

import android.content.Context
import com.agentifiers.amp.model.Account
import com.agentifiers.amp.model.AccountCommand
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AccountResource
import com.agentifiers.amp.model.AccountRoster
import com.agentifiers.amp.model.ActivityItem
import com.agentifiers.amp.model.AgentMode
import com.agentifiers.amp.model.ChangeScopeId
import com.agentifiers.amp.model.ChangesView
import com.agentifiers.amp.model.DiffComment
import com.agentifiers.amp.model.DesktopTarget
import com.agentifiers.amp.model.DiffSelection
import com.agentifiers.amp.model.ExecutorChoice
import com.agentifiers.amp.model.FileContent
import com.agentifiers.amp.model.FileDiffView
import com.agentifiers.amp.model.FileNode
import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.FleetView
import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.GateResponse
import com.agentifiers.amp.model.HumanGate
import com.agentifiers.amp.model.IntentId
import com.agentifiers.amp.model.LabelName
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.NewThreadSpec
import com.agentifiers.amp.model.OrbSize
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.Portal
import com.agentifiers.amp.model.ProjectInfo
import com.agentifiers.amp.model.PuckConversationId
import com.agentifiers.amp.model.RunnerInfo
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.model.Sidebar
import com.agentifiers.amp.model.TerminalSessionKey
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadQuery
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.model.UnavailableReason
import com.agentifiers.amp.model.UserId
import com.agentifiers.amp.store.AccountDatabases
import com.agentifiers.amp.store.AppPreferences
import com.agentifiers.amp.sync.internal.DefaultEngines
import com.agentifiers.amp.sync.internal.push.GateTokenCodec
import com.agentifiers.amp.vt.ScreenSnapshot
import com.agentifiers.amp.vt.VtKey
import com.agentifiers.amp.vt.VtMods
import com.agentifiers.amp.vt.VtMouse
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

// Construction and capabilities

public class EngineConfig(
    /** Where a sign-in goes unless the caller names another server. Release builds bind ampcode.com. */
    public val defaultServer: ServerEnvironment,
    /** Embedded in the Amp CLI; read at build time from `local.properties`. Blank means no socket can open. */
    public val rivetPublishableToken: String,
    public val clientVersion: String,
    /** Debug builds log `AgentNet` and `AmpWs` lines. Never a token. */
    public val networkLog: Boolean,
    /**
     * The push relay the app registers with (`push-relay.md`), from `amp.pushRelayUrl` in `local.properties`. Null
     * when the build names none: the app then never registers with any relay and uses UnifiedPush or the poll worker.
     */
    public val pushRelayUrl: String?,
)

/**
 * The three capabilities one engine construction returns. Each holder gets only the one it may use:
 * [engines] and [background] go into Hilt through :app's EngineModule; [leases] stays in an `internal`
 * field of :app's AmpApplication, so only :app's VisibleThreadLeases can issue a [ViewerToken].
 */
public class EngineParts internal constructor(
    public val engines: AmpEngines,
    public val leases: LeaseIssuer,
    public val background: BackgroundAccessProvider,
)

/** Process singleton. One [AccountScope] per signed-in account. */
public interface AmpEngines {
    /** Loading until the token vault has been read. */
    public val roster: StateFlow<Loadable<AccountRoster>>
    public fun scope(account: AccountId): AccountScope
    public val signIn: SignIn

    /** Ends the account's session and deletes what :sync stores for it. */
    public suspend fun signOut(account: AccountId)

    public companion object {
        public fun create(context: Context, databases: AccountDatabases, prefs: AppPreferences, config: EngineConfig): EngineParts =
            DefaultEngines(context.applicationContext, databases, prefs, config).parts
    }
}

/**
 * Each method is one attempt as a flow of steps. Every method refuses with [SignInFailure.TooManyAccounts]
 * before any request when five accounts are signed in and none on that server needs sign-in, and every method
 * ends at [SignInStep.Confirm]: the
 * flow suspends there until the UI calls [AccountCandidate.add] or [AccountCandidate.cancel], then emits
 * [SignInStep.SignedIn] or [SignInStep.Failed], or completes with nothing more after a cancel.
 */
public interface SignIn {
    /** Device flow: discovery, device authorization, [SignInStep.ShowCode], polling. The UI opens `verificationUrl` in a Custom Tab. */
    public fun start(server: ServerEnvironment): Flow<SignInStep>

    /** Loopback PKCE: [SignInStep.OpenBrowser] with the authorize URL, then the 127.0.0.1 callback and the code exchange. */
    public fun browser(server: ServerEnvironment): Flow<SignInStep>

    /** A pasted personal access token, checked against [server] before the confirmation. */
    public fun paste(server: ServerEnvironment, accessToken: String): Flow<SignInStep>

    /** Validates [accessToken] with `getUserInfo` on [server] and stores it only when Amp accepts it. */
    public suspend fun withToken(server: ServerEnvironment, accessToken: String): SignInResult
}

public sealed interface SignInResult {
    public data class Accepted(val account: AccountId) : SignInResult
    public data class Rejected(val reason: String) : SignInResult
}

public sealed interface SignInStep {
    public data class ShowCode(val userCode: String, val verificationUrl: String, val expiresInSeconds: Int) : SignInStep
    public data object Polling : SignInStep
    public data class OpenBrowser(val url: String) : SignInStep
    public data class Confirm(val candidate: AccountCandidate) : SignInStep
    public data class SignedIn(val account: Account) : SignInStep
    public data class Failed(val reason: SignInFailure) : SignInStep
}

public enum class SignInFailure {
    Expired, Denied, Network, ServerRejected, TooManyAccounts,

    /** `oauth-config` lacked a `clientID` or an https `apiURL`. */
    NotAmpServer,

    /** The `(server, userId)` pair is already in the roster and signed in. */
    AlreadySignedIn,

    /** A pasted token that Amp answered with 401. */
    TokenRejected,
}

/** Everything for one account: its own HTTP client, sockets, and (later) database file and push instance. */
public interface AccountScope {
    public val account: StateFlow<Account>
    public val fleet: Fleet
    public val catalog: Catalog
    public val puck: PuckDirectory

    /** Coil loader on this account's OkHttp client; the bearer goes only to the account's origins. */
    public val images: AuthenticatedImageLoader

    /** The S12 resumable upload for composer, Files and Terminal attachments. */
    public val attachments: AttachmentUploads

    /** The host side of MCP App pages shown in :feature:web. */
    public val mcpApps: McpApps

    /** Account settings reads, one typed key per listing. Loading until the first fetch. */
    public fun <T> read(resource: AccountResource<T>): StateFlow<Loadable<T>>

    /** Account settings writes, through the same routing as ThreadHandle.dispatch. Each settles once in [commandResults]. */
    public fun dispatch(command: AccountCommand): IntentId

    /** One result per [dispatch], after the write settles and the affected [read] key refetched. */
    public val commandResults: SharedFlow<AccountCommandResult>
    /** The same handle for the same thread, so two panes share one socket and one fold. */
    public fun thread(id: ThreadId): ThreadHandle

    /**
     * POST /api/thread-actors, then client_spawn_executor on the new thread's socket; internal createRemoteExecutorThread
     * for an orb on a self-hosted provider, which only that call can name. Never retried on its own.
     */
    public suspend fun startThread(spec: NewThreadSpec): Outcome<StartedThread>

    /** Retries client_spawn_executor on the same thread; never creates a second thread, so it refuses a self-hosted orb. */
    public suspend fun respawn(thread: ThreadId, executor: ExecutorChoice): Outcome<Unit>
}

public sealed interface StartedThread {
    public val thread: ThreadId

    public data class Running(override val thread: ThreadId) : StartedThread
    /** The UI offers Retry (respawn), another executor (respawn), or Discard (ThreadIntent.Archive). */
    public data class SpawnFailed(override val thread: ThreadId, val reason: String) : StartedThread
}

public interface AuthenticatedImageLoader {
    public val coil: coil3.ImageLoader
}

/** A view of one thread's engine. State is always present: cached first, then live. */
public interface ThreadHandle {
    public val id: ThreadId
    public val state: StateFlow<ThreadState>

    /**
     * Keeps the thread actor socket open while any lease is held, plus 30 s. Feature code never calls this:
     * :app's VisibleThreadLeases holds one lease per thread shown in a STARTED window. A leased thread whose orb
     * went to sleep wakes it once, and [ThreadState.executor] shows the wake.
     */
    public fun attach(token: ViewerToken): AutoCloseable

    /** Connects a leased thread now: opens its socket, ends a reconnect backoff early, and retries a failed orb wake. */
    public fun connectLive()

    /**
     * Fire-and-track. Progress shows up in [state]: a send as a pending row, any other intent as an outbox
     * entry until it settles.
     */
    public fun dispatch(intent: ThreadIntent): IntentId

    /** Sends a rejected, unknown or dropped outbox entry again. Its message id or edit id is kept, so the server can dedupe. */
    public fun resend(intent: IntentId)

    /** Removes a rejected, unknown or dropped outbox entry, or an unsent message, without sending it. */
    public fun discard(intent: IntentId)

    public val machine: Machine

    /** Older history; idempotent, no-op while a page is in flight. */
    public fun loadEarlier()
}

/** Proof that a STARTED window is asking. Only [LeaseIssuer] can build one. */
public class ViewerToken internal constructor(public val windowId: String)

public interface LeaseIssuer {
    public fun issue(windowId: String): ViewerToken
}

/** Machine panes. Each connects lazily and only while a pane is visible (it rides the lease). */
public interface Machine {
    public val changes: Changes
    public val files: Files

    /** One session per (thread, key), shared by every window. null is terminal tab 1, which has no key. */
    public fun terminal(session: TerminalSessionKey?): TerminalSession
    public fun desktop(target: DesktopTarget): RelayChannelFactory
    public val portals: Portals
}

public interface Changes {
    /** Collecting it is what makes the pane load: cache first, then live git while the executor is connected. */
    public val view: StateFlow<ChangesView>
    public fun diff(fileId: String): StateFlow<FileDiffView>
    public fun refresh()
    public fun setOrder(intelligent: Boolean)
    public fun selectScope(scope: ChangeScopeId)

    /** Pending review comments for this thread, persisted, sent with the next message. */
    public val comments: StateFlow<PersistentList<DiffComment>>

    /** Refused with the web's text past 20 comments or 10,240 bytes of text. */
    public suspend fun addComment(selection: DiffSelection, text: String): Outcome<DiffComment>
    public fun removeComments(ids: Collection<String>)

    /** Interactive: needs a live socket and is never queued. */
    public suspend fun stage(path: String, staged: Boolean): Outcome<Unit>
    public suspend fun explain(selection: DiffSelection): Outcome<String>
}

public interface Files {
    public fun tree(path: String): StateFlow<FileNode>

    /** Whole-file read; range reads are rejected (S19). A decoded result over 4 MiB is refused. */
    public suspend fun read(path: String): Outcome<FileContent>

    /** Interactive. Up to [maxDirectWriteBytes] in one client_filesystem_write_file_v3; a lost ack returns Outcome.Unknown. */
    public suspend fun upload(destDir: String, name: String, bytes: Flow<ByteArray>, size: Long, overwrite: Boolean): Outcome<String>
    public val maxDirectWriteBytes: Long
}

/** One terminal over amp-terminal-v1 on the actor relay, with its VT engine, shared by every window. */
public interface TerminalSession {
    public val key: TerminalSessionKey?
    public val screen: StateFlow<ScreenSnapshot>
    public val status: StateFlow<TerminalStatus>

    /** The most recently focused viewer's size wins. */
    public fun attachViewer(windowId: String, cols: Int, rows: Int): TerminalViewer
    public fun key(key: VtKey, mods: VtMods)
    public fun text(s: String)
    public fun paste(s: String)

    /** Attachment upload, then amp-exec-v1 saves it on the executor; types the path, or the attachment URL on failure. */
    public suspend fun stageFile(name: String, mediaType: String, bytes: Flow<ByteArray>, size: Long): Outcome<String>

    /** Bell, OSC 52 writes, and dropped pre-ready input, as they happen. */
    public val events: Flow<TerminalEvent>

    /** The file [stageFile] is working on, or null. */
    public val staging: StateFlow<FileStaging?>

    /** From [TerminalStatus.Failed] or [TerminalStatus.Exited]: opens a fresh channel now. */
    public fun retry()

    /** Closes the tab: the channel closes and the next [Machine.terminal] call for this key starts fresh. */
    public fun close()

    /** Sent only when the application enabled mouse reporting for this kind of event. */
    public fun mouse(event: VtMouse)

    /** Sent only when the application enabled focus events (DECSET 1004). */
    public fun focus(focused: Boolean)
}

public interface TerminalViewer : AutoCloseable {
    public fun resize(cols: Int, rows: Int)
    public fun focused()
}

public sealed interface TerminalStatus {
    public data object Opening : TerminalStatus
    public data object Ready : TerminalStatus
    public data object Reconnecting : TerminalStatus
    public data class Exited(val error: String?) : TerminalStatus
    public data class Unavailable(val reason: UnavailableReason) : TerminalStatus
    public data class Failed(val failure: TerminalFailure) : TerminalStatus
}

/** Opaque byte channels for :waymote. Service names and framing stay inside :sync. */
public interface RelayChannelFactory {
    public suspend fun open(stream: DesktopStream): RelayChannel
}

public enum class DesktopStream { Video, Audio, Control }

public interface RelayChannel : AutoCloseable {
    public val incoming: Flow<RelayPayload>
    public fun send(payload: RelayPayload)
}

public sealed interface RelayPayload {
    public class Binary(public val bytes: ByteArray) : RelayPayload
    public class Text(public val json: String) : RelayPayload
}

public interface Portals {
    /** Links from the executor's JSON manifests in `.amp/portals/`, read while collected. Empty until read, or when none exist. */
    public val list: StateFlow<PersistentList<Portal>>

    /** resolveThreadPortalURL + mintPortalLoginURL; a one-time URL for :feature:web to load. */
    public suspend fun loginUrl(port: Int): Outcome<String>

    /** mintPortalLoginURL for a portal URL already known, such as a manifest link or the `return_to` of a lapsed cookie. */
    public suspend fun loginUrlFor(portalUrl: String): Outcome<String>
}

// Fleet

public interface Fleet {
    /** Slice 1's flat thread list from listThreads, refreshed by [refresh]. */
    public val threads: StateFlow<FleetView>
    public fun refresh()

    public val sidebar: StateFlow<Sidebar>
    public val running: StateFlow<PersistentList<ThreadSummary>>
    public fun activity(query: ThreadQuery): StateFlow<ActivityPage>
    public fun search(query: ThreadQuery): Flow<PersistentList<ThreadSummary>>
    public fun labels(): StateFlow<PersistentList<LabelName>>

    /** One ThreadIntent.Archive per id through the outbox. */
    public fun archiveAll(ids: PersistentList<ThreadId>): PersistentList<IntentId>

    /** Archive confirmations (for Undo) and organize writes the server refused. */
    public val notices: Flow<FleetNotice>
    public fun children(parent: ThreadId): StateFlow<PersistentList<ThreadSummary>>

    /** Threads the user chose to Watch from the thread menu. Stored locally per account. */
    public val watched: StateFlow<PersistentSet<ThreadId>>
    public fun setWatched(thread: ThreadId, watching: Boolean)
}

public sealed interface ActivityPage {
    public data object Loading : ActivityPage
    public data class Ready(val items: PersistentList<ActivityItem>, val more: Boolean, val serverFiltered: Boolean) : ActivityPage
}

public interface Catalog {
    public val runners: StateFlow<PersistentList<RunnerInfo>>
    public val projects: StateFlow<PersistentList<ProjectInfo>>
    public val modes: StateFlow<PersistentList<AgentMode>>
    public val orbSizes: StateFlow<PersistentList<OrbSize>>
    public val credits: StateFlow<CreditsGlance>
}

public sealed interface CreditsGlance {
    public data object Loading : CreditsGlance
    public data class Known(val remainingUsd: Double, val workspaceQuota: String?) : CreditsGlance

    /** Amp did not answer with a balance this build can read. */
    public data object Unavailable : CreditsGlance
}

public interface PuckDirectory {
    public val conversations: StateFlow<PersistentList<ThreadSummary>>

    /** openPuckThread; startFresh archives the current conversation first. */
    public suspend fun open(startFresh: Boolean): Outcome<PuckConversationId>

    /** Puck voice over client_puck_voice_* on the Puck thread socket. */
    public fun voice(conversation: PuckConversationId): PuckVoiceChannel
}

public interface PuckVoiceChannel {
    public val state: StateFlow<PuckVoiceState>

    /** PCM16 little-endian, 24 kHz, mono: the web's PCM path (`client_puck_voice_start{version:"v3"}`). */
    public val downlink: Flow<ByteArray>

    /** puck_platform_navigation, already mapped. */
    public val navigation: Flow<PaneDestination>

    /** Opens the call on the conversation's socket. Starting again after [PuckVoiceState.Ended] places a new call. */
    public suspend fun start()

    /** Microphone PCM16 little-endian, 24 kHz, mono, any length; muted audio is zeros of the same length. */
    public fun uplink(opusFrame: ByteArray)
    public fun stop()
}

public sealed interface PuckVoiceState {
    public data object Idle : PuckVoiceState
    public data object Connecting : PuckVoiceState
    public data class Live(val userSpeaking: Boolean, val puckSpeaking: Boolean, val transcriptTail: String) : PuckVoiceState

    /** `puck_voice_state.status` "thinking": Puck is preparing an answer. */
    public data class Thinking(val transcriptTail: String) : PuckVoiceState
    public data class Ended(val error: String?) : PuckVoiceState
}

public sealed interface Loadable<out T> {
    public data object Loading : Loadable<Nothing>
    public data class Ready<T>(val value: T, val asOf: Instant) : Loadable<T>
    public data class Failed(val error: EngineError) : Loadable<Nothing>
}

// Background

public interface BackgroundAccessProvider {
    public fun forAccount(account: AccountId): BackgroundAccess

    /** Signed-in accounts, so :notify registers push for each one and stops when one signs out. */
    public val accounts: StateFlow<PersistentSet<AccountId>>

    /** False when the build names no push relay, so :notify never picks the FCM relay channel. */
    public val hasPushRelay: Boolean

    /** FirebaseMessagingService.onNewToken: re-registers every account's relay registration. Idempotent. */
    public suspend fun onFcmToken(token: String): Outcome<Unit>

    /** FirebaseMessagingService.onMessageReceived: matches data["rid"] to an account, or says why the message is dropped. */
    public fun routeRelayMessage(data: Map<String, String>): RelayRoute
}

/** Where one relay message goes. */
public sealed interface RelayRoute {
    /** [body] is the aes128gcm ciphertext for [BackgroundAccess.decodePush]; null is a wake message. */
    public class Deliver(public val account: AccountId, public val body: ByteArray?) : RelayRoute

    /** [why] names the reason and holds no secret: a relay id appears as its first 6 characters. */
    public data class Dropped(val why: String) : RelayRoute
}

/** How this device receives Web Push. :notify picks one from device capabilities. */
public sealed interface PushChannel {
    public class Relay(public val fcmToken: String) : PushChannel
    public class UnifiedPush(public val endpointUrl: String) : PushChannel

    /** Neither. Only an account whose server takes its push itself (`:fake-amp` in a debug build) can register. */
    public data object None : PushChannel
}

/**
 * What :notify may do: one-shot reads and short-lived writes. None holds a lasting thread socket, and every
 * call that needs a short socket refuses for orb threads whose last status is Asleep.
 */
public interface BackgroundAccess {
    public suspend fun recentStatuses(sinceSeen: Instant): Outcome<PersistentList<ThreadSummary>>
    public suspend fun peekGates(thread: ThreadId): Outcome<PersistentList<HumanGate>>
    public suspend fun answer(thread: ThreadId, response: GateResponse): Outcome<Unit>
    public suspend fun quickReply(thread: ThreadId, text: String): Outcome<MessageId>
    public suspend fun markRead(thread: ThreadId, upTo: MessageId): Outcome<Unit>
    public suspend fun ensurePushRegistration(channel: PushChannel): Outcome<String>
    public suspend fun reconcilePushSubscriptions(): Outcome<Int>

    /** RFC 8291 aes128gcm decrypt with this account's push keys, then parse. Null when either fails. */
    public fun decodePush(body: ByteArray): PushEvent?
    public fun watchFleet(): Flow<FleetSignal>
    public val watched: StateFlow<PersistentSet<ThreadId>>

    /** The Watch notification's Stop: the same write as Fleet.setWatched, for a caller with no AccountScope. */
    public fun setWatched(thread: ThreadId, watching: Boolean)

    /** Threads with a viewer lease in a STARTED window. A thread in this set never posts a notification. */
    public val leasedThreads: StateFlow<PersistentSet<ThreadId>>
    public val settledGates: Flow<SettledGate>
}

public data class SettledGate(val thread: ThreadId, val gate: GateId)

/** Decrypted Web Push payload {kind, threadId, threadURL, title, body, tag} (S11). Unknown kinds become Other. */
public sealed interface PushEvent {
    public val thread: ThreadId
    public val tag: String

    public data class ThreadIdle(override val thread: ThreadId, override val tag: String, val title: String, val body: String) : PushEvent
    public data class ApprovalRequired(override val thread: ThreadId, override val tag: String, val title: String, val body: String) : PushEvent
    public data class Other(override val thread: ThreadId, override val tag: String, val kind: String, val title: String, val body: String) : PushEvent
}

/** Versioned compact encoding of a GateResponse for a notification PendingIntent. */
public object GateTokens {
    public fun encode(response: GateResponse): String = GateTokenCodec.encode(response)

    /** Null on a version mismatch or a token this version cannot read. */
    public fun decode(token: String): GateResponse? = GateTokenCodec.decode(token)
}

public sealed interface FleetSignal {
    public data class StatusChanged(val summary: ThreadSummary, val previous: FleetStatus) : FleetSignal
    public data class TurnCompleted(val thread: ThreadId, val latestMessage: MessageId, val unread: Int) : FleetSignal
    public data class Mentioned(val thread: ThreadId, val by: UserId) : FleetSignal
    public data class SpaceInvite(val thread: ThreadId, val from: UserId) : FleetSignal
}

/** Result of any engine call. No exceptions cross the module boundary. */
public sealed interface Outcome<out T> {
    public data class Ok<T>(val value: T) : Outcome<T>
    public data class Failed(val error: EngineError) : Outcome<Nothing>

    /** The request may or may not have applied. The command's delivery class decides what follows. */
    public data object Unknown : Outcome<Nothing>
    public data class Refused(val why: String) : Outcome<Nothing>
}

public sealed interface EngineError {
    public data object Offline : EngineError
    public data object Unauthorized : EngineError
    public data object StepUpRequired : EngineError
    public data object PaymentRequired : EngineError
    public data class NotFound(val what: String) : EngineError
    public data class Server(val code: String, val message: String) : EngineError

    /** The server answered with a shape we don't understand; logged with the raw body in debug. */
    public data class Drift(val where: String) : EngineError
}
