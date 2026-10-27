package com.agentifiers.amp.notify

import com.agentifiers.amp.model.ThreadId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** What stays alive when no window shows a thread. [keepAlivePolicy] decides; the services only obey. */
enum class KeepAliveMode {
    /** A window is visible. Thread sockets ride viewer leases; nothing extra runs. */
    ForegroundUi,

    /** WatchService holds the user actor socket for the watched threads. */
    WatchService,

    /** No socket. Web Push wakes the app: FCM from the relay, or a UnifiedPush distributor. */
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
    /** Time left of the user's watch limit. The only bound on WatchService. */
    val watchTimeLeft: Duration,
    val networkUp: Boolean,
)

/** The user's watch limit (`notifications.md`, "Watching a running thread"). */
val WATCH_LIMIT: Duration = 30.minutes

/**
 * PollWorker runs in every mode as the subscription backstop, so this only picks what runs on top of it. A working
 * watched thread outranks a visible window: tapping Watch starts the Live Update at once, so it keeps showing when the
 * user switches apps or folds the device.
 */
fun keepAlivePolicy(inputs: KeepAliveInputs): KeepAliveMode = when {
    inputs.watchedWorking.isNotEmpty() && inputs.networkUp && inputs.watchTimeLeft.isPositive() -> KeepAliveMode.WatchService
    inputs.visibleWindows > 0 -> KeepAliveMode.ForegroundUi
    inputs.pushRegistered -> KeepAliveMode.PushDriven
    else -> KeepAliveMode.Polling
}
