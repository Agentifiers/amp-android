// :notify  package amp.notify
// Notifications inside Android background limits (docs/design/notifications.md).
// Three delivery paths feed one Notifier through one NotificationLedger, so an event posts once
// whichever path sees it first. KeepAlivePolicy (KeepAlive.kt) decides which paths run.
//
//  PushDriven   Web Push (spike S11 VERIFIED with ntfy): the server pushes thread_idle and
//               approval_required (payload {kind, threadId, threadURL, title, body, tag}) to the account's
//               endpoint. With Google Play services the endpoint is our push relay, which forwards the
//               still-encrypted body over FCM to RelayMessagingService (docs/design/push-relay.md).
//               Without them it is a UnifiedPush distributor's endpoint, delivered to UnifiedPushReceiver.
//               Latency ≈ 1 s through ntfy (measured); through the relay it is unmeasured until spike S11b.
//               A plugin dialog left pending with no observer produced no push within 45 s (S15c, NOT
//               VERIFIED either way), and S15 queued no approval, so approval_required pushes remain unrecorded.
//  WatchService The user taps [Watch] on a thread. A `specialUse` foreground service (Play declaration:
//               live agent monitoring) holds ONE user-actor socket for all watched threads
//               (threadStatusUpdated, S14) and shows each as an Android 16 Live Update. Stops when every
//               watched thread is idle or the user's limit (30 min default) runs out. Never opens thread sockets.
//  PollWorker   WorkManager periodic (15 min floor), network-constrained, stateless user-actor
//               getRecentThreads (S2) diffed against last-seen, plus push-subscription upkeep. Runs in
//               every mode as the backstop.
//
// Needs-you without a push: on watch and poll paths, FleetStatus.NeedsHuman (threadStatusUpdated
// indicator kind "action-required", measured for plugin dialogs in S15b/S15c) enqueues GateFetchWorker
// exactly as approval_required does. Whether getRecentThreads also carries the indicator is [INFERENCE].
//
// Channels (notifications.md): needs_you (High, heads-up: approval_required and any gate), finished
// (Default: thread_idle), watching (Low, ongoing: Live Updates), other (Default: unknown push kinds,
// mentions and Space invites from the watch socket). Notifications group by project. Snoozed or muted
// threads post nothing except needs_you (core.md:26). A thread with a viewer lease never posts
// (BackgroundAccess.leasedThreads, plus the reportVisibility user-actor action).
//
// :notify parses no wire format. Push decryption and payload parsing (BackgroundAccess.decodePush) and
// gate tokens (GateTokens) live in :sync.
package amp.notify

import amp.model.*
import amp.sync.api.*

/**
 * @EntryPoint @InstallIn(SingletonComponent). The only way Android-created components in :notify
 * (receivers, WatchService, @HiltWorkers) reach the engine. BackgroundAccess is all it yields, so
 * nothing here can obtain a ViewerToken.
 */
interface NotifyEntryPoint {
    fun background(): BackgroundAccessProvider
    fun databases(): amp.store.AccountDatabases
}

/**
 * Picks the device's PushChannel: Relay when GoogleApiAvailability reports Play services, else
 * UnifiedPush when a distributor is installed, else none (onboarding offers ntfy). Then calls
 * BackgroundAccess.ensurePushRegistration and reconcilePushSubscriptions for every account.
 */
interface PushChannelSelector { suspend fun current(): PushChannel? }

/**
 * Default channel. onMessageReceived → BackgroundAccessProvider.routeRelayMessage(data): a body goes to
 * BackgroundAccess.decodePush → enqueue GateFetchWorker or post ready; a wake runs the account's
 * PollWorker once, expedited. onNewToken → BackgroundAccessProvider.onFcmToken. Work stays < 10 s.
 * Debug builds inject a plaintext payload only through the inject-push app event (verification.md).
 */
abstract class RelayMessagingService /* : com.google.firebase.messaging.FirebaseMessagingService() */ {
    abstract fun onMessageReceived(data: Map<String, String>)
    abstract fun onNewToken(token: String)
}

/**
 * Fallback on devices without Google Play services. instance = accountId, so N accounts get N endpoints
 * from one distributor. onNewEndpoint → ensurePushRegistration(PushChannel.UnifiedPush(url)) then
 * reconcilePushSubscriptions. onMessage → BackgroundAccess.decodePush, as above.
 */
abstract class UnifiedPushReceiver /* : org.unifiedpush.android.connector.MessagingReceiver() */ {
    abstract fun onMessage(content: ByteArray, instance: String)
    abstract fun onNewEndpoint(url: String, instance: String)
    abstract fun onUnregistered(instance: String)
}

/**
 * Expedited work after approval_required or a NeedsHuman status: peekGates (one-shot socket: connect,
 * resume from the cached cursor or from threadVersion when the thread has no cache, read approvals and
 * plugin.ui.snapshot, close; the orb is awake because it is asking) → post the gate with buttons.
 * Plugin dialogs decode natively (wire measured in S15b). The approval payload is still unrecorded
 * (S15 INCONCLUSIVE), so an approval that fails to decode arrives as UnsupportedGate and posts
 * [Answer on web] + [Open].
 */
interface GateFetchWorker {
    suspend fun run(account: AccountId, trigger: GateFetchTrigger)
    companion object { fun enqueueExpedited(context: android.content.Context, account: AccountId, trigger: GateFetchTrigger): Unit = TODO("OneTimeWorkRequest.setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)") }
}
sealed interface GateFetchTrigger {
    val thread: ThreadId
    data class Push(val event: PushEvent.ApprovalRequired) : GateFetchTrigger { override val thread get() = event.thread }
    data class Status(override val thread: ThreadId, val status: FleetStatus.NeedsHuman) : GateFetchTrigger
}

/** The one place notifications are built. Claims the ledger tag first; posts nothing when claim fails. */
interface Notifier {
    /**
     * Tool approval: [Allow] [Deny] [Open] only when the whole input fits in the notification's visible
     * text; otherwise [Review] [Deny] [Open], and Review opens the thread on the gate. ChoiceGate with
     * ≤ 3 single-choice options: one action per option. UnsupportedGate: [Answer on web] [Open].
     * PluginGate.SecretInput and anything else: [Open]. When the account requires passkey step-up for
     * interaction, every gate posts [Open] only (notifications.md). Each action's PendingIntent carries
     * GateTokens.encode(response).
     */
    suspend fun postGate(account: AccountId, thread: ThreadId, title: String, tag: String, gate: HumanGate)
    /** Tap opens the thread focused on its gate; used when no answerable gate could be read. */
    suspend fun postOpenOnly(account: AccountId, thread: ThreadId)
    /** [Reply] (RemoteInput → quickReply via outbox), [Mark read], tap opens the thread. */
    suspend fun postReady(account: AccountId, event: PushEvent.ThreadIdle)
    suspend fun postOther(account: AccountId, event: PushEvent.Other)
    suspend fun postSignal(account: AccountId, signal: FleetSignal)
    /** Live Update for one watched thread on the watching channel. */
    suspend fun postWatching(account: AccountId, summary: ThreadSummary)
    /** Also called for every BackgroundAccess.settledGates entry, so a dialog settled or removed elsewhere loses its notification. */
    suspend fun resolve(tag: String, outcome: ResolvedAs)
}
enum class ResolvedAs { Approved, Denied, Replied, AnsweredElsewhere }

/**
 * Notification actions → broadcast with (account, thread, tag, GateTokens token | reply text).
 * goAsync(), then BackgroundAccess.answer() over a short socket. The guard check inside answer makes a
 * second tap, or an answer from another client, settle as AnsweredElsewhere.
 */
interface GateActionReceiver
data class GateAction(val account: AccountId, val thread: ThreadId, val tag: String, val response: GateResponse) {
    companion object { fun fromIntent(intent: android.content.Intent): GateAction? = TODO("GateTokens.decode(extra)") }
}
interface QuickReplyReceiver

/** Deep link from a notification: amp://open?account=&thread=&focus=gate|latest → PaneDestination. */
data class OpenTarget(val account: AccountId, val dest: PaneDestination)

/**
 * Launcher surfaces: dynamic shortcuts for the 4 most recent threads, static shortcuts "New thread"
 * and "Dictate to Puck" (product.md first release); the Glance widget "Running agents" is later.
 * Fed by the same ThreadSummary flow, never by opening thread sockets.
 */
interface LauncherSurfaces { suspend fun refresh(account: AccountId, recent: List<ThreadSummary>) }

/**
 * Sign-out step owned by :notify, called by :app's SignOutCoordinator: cancel the account's
 * notifications and its WorkManager tag `account:<accountId>`, unregister its UnifiedPush instance when
 * the device uses one, and remove its launcher shortcuts. The relay registration is deleted by :sync.
 * Idempotent.
 */
interface AccountTeardown { suspend fun forget(account: AccountId) }
