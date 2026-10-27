// :notify  package amp.notify
// What stays alive when no window shows a thread. One pure function decides; the services only obey.
package amp.notify

import amp.model.ThreadId
import kotlin.time.Duration

enum class KeepAliveMode {
    /** A window is visible. Thread sockets ride viewer leases; nothing extra runs. */
    ForegroundUi,
    /** WatchService holds the user-actor socket for the watched threads. */
    WatchService,
    /** No socket. Web Push wakes the app: FCM from our relay, or a UnifiedPush distributor. */
    PushDriven,
    /** No socket, no push registration. PollWorker alone, at the 15-minute floor. */
    Polling,
}

data class KeepAliveInputs(
    /** STARTED windows, from ProcessLifecycleOwner. */
    val visibleWindows: Int,
    /** Watched threads that are still working. Watching is opt-in per thread; nothing is watched automatically. */
    val watchedWorking: Set<ThreadId>,
    val pushRegistered: Boolean,
    /** Time left of the user's watch limit (30 min default, notifications.md). The only bound on WatchService. */
    val watchTimeLeft: Duration,
    val networkUp: Boolean,
)

/**
 * Pure and table-tested. PollWorker runs in every mode as the subscription backstop, so this only
 * picks what runs on top of it.
 *   visibleWindows > 0                                  → ForegroundUi
 *   watchedWorking ≠ ∅ ∧ networkUp ∧ watchTimeLeft > 0  → WatchService
 *   pushRegistered                                      → PushDriven
 *   else                                                → Polling
 */
fun keepAlivePolicy(inputs: KeepAliveInputs): KeepAliveMode = TODO()

/**
 * Foreground service, foregroundServiceType="specialUse" with
 * PROPERTY_SPECIAL_USE_FGS_SUBTYPE "live agent monitoring", declared in Play Console. Play asks for a
 * declaration for every foreground service type when targeting Android 14 or later, so no type avoids
 * one (Play Console Help 13392821, read, not observed). specialUse has no daily runtime cap; the
 * user's watch limit stops it and keepAlivePolicy then picks PushDriven or Polling.
 * Started only from a foreground transition (the user tapping [Watch], or ON_STOP of the last visible
 * window), as Android requires. Holds the user-actor socket via BackgroundAccess.watchFleet() and
 * nothing else, so it cannot wake an orb.
 * Notification: "Watching N agents" with [Stop], plus one Live Update per watched thread.
 */
abstract class WatchService /* : LifecycleService() */

/** WorkManager periodic, 15 min, network-constrained: getRecentThreads diff + PushUpkeep.reconcile. */
interface PollWorker
