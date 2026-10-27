// :sync  package amp.sync.api   (explicitApi(); the ONLY public package of :sync)
// The engine's whole public surface. Behind it: internal RPC, thread actor socket, user actor
// socket + stateless actions, stateless /request/rpc (client_append_user_msg only), relay mux, tokens
// and push keys, cache, outbox with delivery classes, push upkeep, terminal sessions.
// No type from amp.sync.internal appears here; no wire DTO appears anywhere outside :sync.
package amp.sync.api

import amp.model.*
import amp.vt.ScreenSnapshot
import amp.vt.VtKey
import amp.vt.VtMods
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// ── Construction and capabilities ─────────────────────────────────────────────

/**
 * The three capabilities one engine construction returns. Each holder gets only the one it may use:
 *  - [engines]: bound in Hilt by :app's EngineModule. Opens no socket on its own (no ViewerToken).
 *  - [leases]: never bound in Hilt. :app's AmpApplication keeps it in an `internal` field, so no other
 *    module can name it, and only :app's VisibleThreadLeases issues ViewerTokens.
 *  - [background]: bound in Hilt; :notify reaches it only through NotifyEntryPoint.
 * Background code cannot wake an orb because nothing outside :app can obtain a ViewerToken, and every
 * BackgroundAccess socket refuses an Asleep orb.
 */
public class EngineParts internal constructor(
    public val engines: AmpEngines,
    public val leases: LeaseIssuer,
    public val background: BackgroundAccessProvider,
)

/**
 * Process singleton. One AccountScope per signed-in account.
 * [create] takes :store's Hilt-provided AccountDatabases (one Room instance per `amp-<accountId>.db`,
 * shared with :notify's ledger) and AppPreferences (one DataStore file per proto type).
 */
public interface AmpEngines {
    public val roster: StateFlow<AccountRoster>
    public fun scope(account: AccountId): AccountScope
    public val signIn: SignIn
    /**
     * Deletes the account's relay registration (DELETE /v1/registrations/{rid}, docs/design/push-relay.md),
     * revokes the token, then deletes what :sync owns: TokenEntry, PushKeys, PushRegistration, the Room
     * file, the image cache. :app's SignOutCoordinator calls it after :notify and :feature:web have cleaned up.
     */
    public suspend fun signOut(account: AccountId)

    public companion object {
        public fun create(context: android.content.Context, databases: amp.store.AccountDatabases, prefs: amp.store.AppPreferences): EngineParts = TODO()
    }
}

/** Device flow (spike S1: start verified; exchange/refresh NOT VERIFIED → see SignInStep.Failed). */
public interface SignIn {
    /** Starts discovery + device authorization. The UI opens `verificationUrl` in a Custom Tab. */
    public fun start(baseUrl: String): Flow<SignInStep>
}
public sealed interface SignInStep {
    public data class ShowCode(val userCode: String, val verificationUrl: String, val expiresInSeconds: Int) : SignInStep
    public data object Polling : SignInStep
    public data class SignedIn(val account: Account) : SignInStep
    public data class Failed(val reason: SignInFailure) : SignInStep
}
public enum class SignInFailure { Expired, Denied, Network, ServerRejected, TooManyAccounts }

/** Everything for one account. Each scope has its own HTTP client, DB file, sockets, push instance. */
public interface AccountScope {
    public val account: StateFlow<Account>
    public val fleet: Fleet
    public val catalog: Catalog
    public val puck: PuckDirectory
    /**
     * Coil loader on this account's OkHttp client. Bearer goes only to the account's origins, and a
     * 401 refreshes through the same Credentials as every other call. :core:design's AuthedImage takes it.
     */
    public val images: AuthenticatedImageLoader
    /**
     * Account settings reads. One typed key per cli-tier listing (AccountResource in :core:model).
     * Cached, refetched when the user actor reports a revision for the key, Loading until first fetch.
     */
    public fun <T> read(resource: AccountResource<T>): StateFlow<Loadable<T>>
    /** Account settings writes. Same routeOf as ThreadHandle.dispatch; Durable commands go through the outbox. */
    public fun dispatch(command: AccountCommand): IntentId
    /**
     * Cheap. Returns the same handle while the thread's engine is alive. An engine with no lease and
     * no collector is evicted 30 s after the last one goes, and the next call rebuilds it from the cache.
     */
    public fun thread(id: ThreadId): ThreadHandle
    /**
     * Interactive, so a direct call, not an outbox command: POST /api/thread-actors, then
     * client_spawn_executor on the new thread's socket (spike S4: runnerId must not be in the POST).
     * The POST has no client key, so it is never retried on its own: a lost ack returns Outcome.Unknown.
     * A failed spawn returns SpawnFailed with the thread that already exists.
     */
    public suspend fun startThread(spec: NewThreadSpec): Outcome<StartedThread>
    /**
     * Retries client_spawn_executor on the same thread, for orbs, runners and runner worktrees alike
     * (a worktree spawn carries runnerWorktree, read, not observed). Never creates a second thread and
     * never calls createRemoteExecutorThread.
     */
    public suspend fun respawn(thread: ThreadId, executor: ExecutorChoice): Outcome<Unit>
}
public sealed interface StartedThread {
    public val thread: ThreadId
    public data class Running(override val thread: ThreadId) : StartedThread
    /** The UI offers Retry (respawn), another executor (respawn), or Discard (ThreadIntent.Archive). */
    public data class SpawnFailed(override val thread: ThreadId, val reason: String) : StartedThread
}
public interface AuthenticatedImageLoader { public val coil: coil3.ImageLoader }

/** A view of one thread's engine. State is always present: cached first, then live. */
public interface ThreadHandle {
    public val id: ThreadId
    public val state: StateFlow<ThreadState>
    /**
     * Keeps the thread actor socket open while any lease is held, plus 30 s, and while any relay
     * channel on the socket is open. Feature code never calls this: :app's VisibleThreadLeases holds
     * one lease per thread shown in a STARTED window and releases it on ON_STOP. For a thread whose
     * executor is paused, a lease opens no socket and state.link is Link.OrbPaused.
     */
    public fun attach(token: ViewerToken): AutoCloseable
    /** Opens the socket for a leased thread whose executor is paused. The UI shows the cost notice first. */
    public fun connectLive()
    /**
     * Fire-and-track. A Durable command is written to the outbox first, then delivered by its route.
     * An Ephemeral command stays in memory and is sent once. The delivery class decides what happens
     * on a lost ack: Replayable resends, AtMostOnce and Ephemeral become OutboxState.Unknown, Guarded
     * re-checks its guard. Progress shows up in [state].
     */
    public fun dispatch(intent: ThreadIntent): IntentId
    public val machine: Machine
    /** Older history; idempotent, no-op while a page is in flight. */
    public fun loadEarlier()
}

/** Proof that a STARTED window is asking. Only LeaseIssuer can build one. */
public class ViewerToken internal constructor(public val windowId: String)
public interface LeaseIssuer {
    public fun issue(windowId: String): ViewerToken
}

/** Machine panes. Each is lazily connected and only while a pane is visible (it rides the lease). */
public interface Machine {
    public val changes: Changes
    public val files: Files
    /** One session per (thread, key), shared by every window. null is terminal tab 1, which has no key. */
    public fun terminal(session: TerminalSessionKey?): TerminalSession
    public fun desktop(target: DesktopTarget): RelayChannelFactory
    public val portals: Portals
}

public interface Changes {
    public val view: StateFlow<ChangesView>
    public fun diff(fileId: String): StateFlow<FileDiffView>
    public fun refresh()
    public fun setOrder(intelligent: Boolean)
    /** Interactive: needs a live socket and is never queued. Refused when the thread has none. */
    public suspend fun stage(path: String, staged: Boolean): Outcome<Unit>
    public suspend fun explain(selection: DiffSelection): Outcome<String>   // client_explain_diff_selection
}

public interface Files {
    public fun tree(path: String): StateFlow<FileNode>
    /**
     * Whole-file read (`range` reads are rejected, S19). One read at a time per thread, because the
     * base64 body shares the thread socket with the transcript. A decoded result over 4 MiB is refused.
     */
    public suspend fun read(path: String): Outcome<FileContent>
    /**
     * Interactive: needs a live socket and is never queued. Up to [maxDirectWriteBytes], one
     * client_filesystem_write_file_v3 (S19: 15 bytes measured). Larger files go as an attachment upload
     * then amp-exec-v1 staging, as TerminalSession.stageFile does. write_file_v3 is "reject" on resend,
     * so a lost ack returns Outcome.Unknown.
     */
    public suspend fun upload(destDir: String, name: String, bytes: Flow<ByteArray>, size: Long, overwrite: Boolean): Outcome<String>
    /** The largest write a test has made: 15 bytes today, 1 MiB once the M8 gate writes 1 MiB. */
    public val maxDirectWriteBytes: Long
}

/**
 * One terminal over amp-terminal-v1 on the actor relay (spike S8), with its VT engine, so a second
 * window joins with the parsed screen instead of a byte stream mid-escape (terminal.md). The relay
 * channel opens with the first viewer and closes 60 s after the last viewer leaves. While a viewer is
 * present, reconnect never stops; after a reconnect the session reopens the channel with the same key.
 */
public interface TerminalSession {
    public val key: TerminalSessionKey?
    public val screen: StateFlow<ScreenSnapshot>
    public val status: StateFlow<TerminalStatus>
    /** A viewer is a Terminal pane or a live terminal card in a STARTED window. The most recently focused viewer's size wins. */
    public fun attachViewer(windowId: String, cols: Int, rows: Int): TerminalViewer
    public fun key(key: VtKey, mods: VtMods)
    public fun text(s: String)
    public fun paste(s: String)
    /** Attachment upload, then amp-exec-v1 saves it on the executor; types the path, or the attachment URL on failure. */
    public suspend fun stageFile(name: String, mediaType: String, bytes: Flow<ByteArray>, size: Long): Outcome<String>
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
}

/** Opaque byte channels for :waymote. Service names and framing are hidden here. */
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
    public val list: StateFlow<PersistentList<Portal>>
    /** resolveThreadPortalURL✓ + mintPortalLoginURL✓; one-time URL for :feature:web to load. */
    public suspend fun loginUrl(port: Int): Outcome<String>
}

// ── Fleet ─────────────────────────────────────────────────────────────────────

public interface Fleet {
    public val sidebar: StateFlow<Sidebar>
    public val running: StateFlow<PersistentList<ThreadSummary>>
    public fun activity(query: ThreadQuery): StateFlow<ActivityPage>
    public fun search(query: ThreadQuery): Flow<PersistentList<ThreadSummary>>
    public fun labels(): StateFlow<PersistentList<LabelName>>
    /** Inactive-72h header and multi-select: one ThreadIntent.Archive per id through the outbox. */
    public fun archiveAll(ids: PersistentList<ThreadId>): PersistentList<IntentId>
    public fun children(parent: ThreadId): StateFlow<PersistentList<ThreadSummary>>
    /** Threads the user chose to Watch from the thread menu (notifications.md). Stored locally per account. */
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
}

public interface PuckDirectory {
    public val conversations: StateFlow<PersistentList<ThreadSummary>>
    /** openPuckThread✓ (archiveThreadID? to start a fresh conversation). Interactive, so a direct call. */
    public suspend fun open(startFresh: Boolean): Outcome<PuckConversationId>
    /** Puck voice over client_puck_voice_* on the Puck thread socket. */
    public fun voice(conversation: PuckConversationId): PuckVoiceChannel
}
public interface PuckVoiceChannel {
    public val state: StateFlow<PuckVoiceState>
    public val downlink: Flow<ByteArray>                 // opus_24k frames
    public val navigation: Flow<PaneDestination>         // puck_platform_navigation, already mapped
    public suspend fun start()
    public fun uplink(opusFrame: ByteArray)
    public fun stop()
}
public sealed interface PuckVoiceState {
    public data object Idle : PuckVoiceState
    public data object Connecting : PuckVoiceState
    public data class Live(val userSpeaking: Boolean, val puckSpeaking: Boolean, val transcriptTail: String) : PuckVoiceState
    public data class Ended(val error: String?) : PuckVoiceState
}

public sealed interface Loadable<out T> {
    public data object Loading : Loadable<Nothing>
    public data class Ready<T>(val value: T, val asOf: kotlin.time.Instant) : Loadable<T>
    public data class Failed(val error: EngineError) : Loadable<Nothing>
}

// ── Background ────────────────────────────────────────────────────────────────

public interface BackgroundAccessProvider {
    public fun forAccount(account: AccountId): BackgroundAccess
    /**
     * FirebaseMessagingService.onNewToken: PUT /v1/registrations/{rid} for every account's relay
     * registration on this device (docs/design/push-relay.md). Idempotent.
     */
    public suspend fun onFcmToken(token: String): Outcome<Unit>
    /**
     * FirebaseMessagingService.onMessageReceived: matches data["rid"] to an account. [RelayDelivery.body]
     * is the aes128gcm ciphertext to pass to [BackgroundAccess.decodePush]; null means a wake message,
     * which the caller answers by running that account's background sync. Null when rid is unknown.
     */
    public fun routeRelayMessage(data: Map<String, String>): RelayDelivery?
}

public class RelayDelivery(public val account: AccountId, public val body: ByteArray?)

/** How this device receives Web Push. :notify picks one from device capabilities (notifications.md). */
public sealed interface PushChannel {
    /** Google Play services present: register with our relay, which forwards to this FCM token. */
    public class Relay(public val fcmToken: String) : PushChannel
    /** No Google Play services, UnifiedPush distributor installed: Amp posts to this endpoint directly. */
    public class UnifiedPush(public val endpointUrl: String) : PushChannel
}

/**
 * What :notify may do. One-shot reads and short-lived writes only. None of these holds a lasting
 * thread socket, and every call that needs a short socket refuses (Outcome.Refused) for orb threads
 * whose last status is Asleep, so background work never wakes an orb. A short socket resumes from
 * the cached cursor, or from WsGrant.threadVersion (POST /api/thread-actors) when the thread has no
 * cache, so a thread this device never opened replays nothing. Such a socket folds in memory and
 * writes no cache rows.
 */
public interface BackgroundAccess {
    /** Stateless user-actor action getRecentThreads (S2): no socket, no orb wake. */
    public suspend fun recentStatuses(sinceSeen: kotlin.time.Instant): Outcome<PersistentList<ThreadSummary>>
    /** One-shot socket: connect, resume, read approvals and plugin.ui.snapshot from the burst, close. */
    public suspend fun peekGates(thread: ThreadId): Outcome<PersistentList<HumanGate>>
    /**
     * One-shot socket: connect, resume, check Guard.GatePending against the caught-up fold, send
     * client_tool_approval_response or plugin.ui.respond, await the correlated result, close. Never
     * /request/rpc: it returns -32601 for client_tool_approval_response (S18,
     * s18b-rpc-allowlist.jsonl:8). The plugin reply is measured (S15b); the approval payload is not (S15).
     */
    public suspend fun answer(thread: ThreadId, response: GateResponse): Outcome<Unit>
    /**
     * Stateless /request/rpc client_append_user_msg (S6 VERIFIED), the one method that path accepts (S18).
     * Goes through the outbox with a minted MessageId; a resend is deduped by the server (S16).
     */
    public suspend fun quickReply(thread: ThreadId, text: String): Outcome<MessageId>
    /** One-shot socket, same rule as [answer]. */
    public suspend fun markRead(thread: ThreadId, upTo: MessageId): Outcome<Unit>
    /**
     * Makes this account's push registration match [channel] and returns the endpoint URL Amp will post
     * to. Relay: POST /v1/registrations (or reuses the stored rid, secret and push URL in the vault).
     * UnifiedPush: stores the distributor's endpoint. Generates the push keys if missing (P-256 pair in
     * Keystore alias `amp_push_<accountId>` plus an auth secret, stored by :sync only). Idempotent.
     */
    public suspend fun ensurePushRegistration(channel: PushChannel): Outcome<String>
    /**
     * Keeps Web Push subscriptions current for threads worth notifying about, using the endpoint from
     * [ensurePushRegistration]: idempotent client_upsert_notification_subscription over a one-shot socket
     * (S11 verified it on the socket; /request/rpc rejects it, S18). Returns the number upserted.
     */
    public suspend fun reconcilePushSubscriptions(): Outcome<Int>
    /** RFC 8291 aes128gcm decrypt with this account's push keys, then parse the payload. Null when either fails. */
    public fun decodePush(body: ByteArray): PushEvent?
    /** User-actor socket for WatchService; one socket for all threads, no orb wake. */
    public fun watchFleet(): Flow<FleetSignal>
    /** The Watch set from Fleet.watched, read by KeepAlivePolicy. */
    public val watched: StateFlow<PersistentSet<ThreadId>>
    /** Threads with a viewer lease in a STARTED window. A thread in this set never posts a notification. */
    public val leasedThreads: StateFlow<PersistentSet<ThreadId>>
    /**
     * Plugin dialogs that left the fold: plugin.ui.request_settled and plugin.ui.request_removed, seen
     * by any socket of this account. :notify resolves their notifications.
     */
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

/** Versioned compact encoding of a GateResponse for a notification PendingIntent. decode returns null on a version mismatch. */
public object GateTokens {
    public fun encode(response: GateResponse): String = TODO()
    public fun decode(token: String): GateResponse? = TODO()
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
    /** Request may or may not have applied (timeout after send). The command's delivery class decides what follows. */
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
