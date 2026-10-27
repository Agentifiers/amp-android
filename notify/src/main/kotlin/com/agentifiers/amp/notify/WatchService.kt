package com.agentifiers.amp.notify

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.sync.api.BackgroundAccessProvider
import com.agentifiers.amp.sync.api.FleetSignal
import com.agentifiers.amp.sync.api.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A watched thread's live state: what the Live Update shows. */
internal data class WatchedThread(val account: AccountId, val summary: ThreadSummary)

/** Working, compacting and needs-you all mean the turn has not finished. */
internal fun FleetStatus.unfinished(): Boolean =
    this == FleetStatus.Working || this == FleetStatus.Compacting || word == "action-required"

/**
 * The opt-in Watch foreground service, type `specialUse` ("live agent monitoring"). It holds one user actor socket
 * per account through `BackgroundAccess.watchFleet`, shows each working watched thread as an Android 16 Live Update,
 * and stops when [keepAlivePolicy] no longer picks it: every watched thread finished, or the watch limit ran out.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private val threads = HashMap<ThreadId, WatchedThread>()
    private val accounts = HashMap<AccountId, Job>()
    private lateinit var startedAt: Instant

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWatching()
            return START_NOT_STICKY
        }
        startForeground(SUMMARY_ID, summary(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!::startedAt.isInitialized) {
            startedAt = Clock.System.now()
            scope.launch {
                delay(WATCH_LIMIT)
                Log.i(PushInbox.TAG, "watch: limit reached")
                finish()
            }
        }
        val provider = NotifyRuntime.provider
        for (account in provider.accounts.value) if (account !in accounts) accounts[account] = scope.launch { watch(provider, account) }
        return START_NOT_STICKY
    }

    private suspend fun watch(provider: BackgroundAccessProvider, account: AccountId) {
        val background = provider.forAccount(account)
        scope.launch {
            background.watched.drop(1).collect { watched -> update { threads.keys.removeAll { it !in watched && threads[it]?.account == account } } }
        }
        while (scope.isActive) {
            val watched = background.watched.value
            val recent = background.recentStatuses(Clock.System.now() - 24.hours)
            if (recent is Outcome.Ok) update { recent.value.filter { it.id in watched }.forEach { threads[it.id] = WatchedThread(account, it) } }
            background.watchFleet().collect { signal ->
                if (signal !is FleetSignal.StatusChanged || signal.summary.id !in background.watched.value) return@collect
                update { threads[signal.summary.id] = WatchedThread(account, signal.summary) }
            }
            delay(RECONNECT_MS)
        }
    }

    /** Applies [change], redraws every Live Update, and stops once the policy no longer picks Watch. */
    @Synchronized
    private fun update(change: () -> Unit) {
        change()
        val working = threads.values.filter { it.summary.status.unfinished() }
        for (thread in threads.values) {
            if (thread.summary.status.unfinished()) manager.notify(liveTag(thread.summary.id), LIVE_ID, liveUpdate(thread)) else manager.cancel(liveTag(thread.summary.id), LIVE_ID)
        }
        val mode = keepAlivePolicy(
            KeepAliveInputs(
                visibleWindows = if (NotifyRuntime.foreground()) 1 else 0,
                watchedWorking = working.map { it.summary.id }.toSet(),
                pushRegistered = false,
                watchTimeLeft = WATCH_LIMIT - (Clock.System.now() - startedAt),
                networkUp = true,
            ),
        )
        if (mode != KeepAliveMode.WatchService) {
            Log.i(PushInbox.TAG, "watch: stopping, policy picked $mode")
            finish()
        } else {
            manager.notify(SUMMARY_ID, summary())
        }
    }

    private fun stopWatching() {
        for ((account, _) in accounts) {
            val background = NotifyRuntime.background(account)
            background.watched.value.forEach { background.setWatched(it, false) }
        }
        finish()
    }

    private fun finish() {
        threads.keys.forEach { manager.cancel(liveTag(it), LIVE_ID) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        scope.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun summary(): Notification {
        val working = threads.values.count { it.summary.status.unfinished() }
        val stop = PendingIntent.getService(this, 0, Intent(this, WatchService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, AmpChannel.Watching.id)
            .setSmallIcon(R.drawable.ic_stat_amp).setColor(getColor(R.color.notification_accent))
            .setContentTitle(if (working == 1) "Watching 1 agent" else "Watching $working agents")
            .setOngoing(true)
            .setGroup(WATCH_GROUP)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun liveUpdate(thread: WatchedThread): Notification {
        val title = thread.summary.title ?: thread.summary.id.raw
        val state = if (thread.summary.status.word == "action-required") "Needs you" else thread.summary.status.word.replaceFirstChar(Char::titlecase)
        val builder = Notification.Builder(this, AmpChannel.Watching.id)
            .setSmallIcon(R.drawable.ic_stat_amp).setColor(getColor(R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(state)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(WATCH_GROUP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                .setShortCriticalText(state)
                .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        }
        return builder.build()
    }

    companion object {
        private const val ACTION_STOP = "com.agentifiers.amp.notify.WATCH_STOP"
        private const val SUMMARY_ID = 2
        private const val LIVE_ID = 3
        private const val RECONNECT_MS = 5_000L
        private const val WATCH_GROUP = "amp-watch"

        /** `Notification.EXTRA_REQUEST_PROMOTED_ONGOING`, public only from API 36.1; the same key NotificationCompat sets. */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

        fun liveTag(thread: ThreadId) = "amp-watch-${thread.raw}"

        /** Android allows this only while the app is in front or just left it; elsewhere polling covers the thread. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, WatchService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(PushInbox.TAG, "watch: Android refused the foreground start; polling covers watched threads")
            }
        }
    }
}

/** Starts [WatchService] from the two foreground transitions Android permits: tapping Watch, and leaving the app. */
internal object WatchStarter {
    fun start(context: Context, provider: BackgroundAccessProvider, scope: CoroutineScope) {
        scope.launch {
            val followed = HashSet<AccountId>()
            provider.accounts.collect { accounts ->
                for (account in accounts - followed) {
                    followed += account
                    launch {
                        var seen = emptySet<ThreadId>()
                        provider.forAccount(account).watched.collect { watched ->
                            val added = watched - seen
                            seen = watched
                            if (added.isNotEmpty() && NotifyRuntime.foreground()) WatchService.start(context)
                        }
                    }
                }
            }
        }
    }

    fun onAppStopped(context: Context, provider: BackgroundAccessProvider) {
        if (provider.accounts.value.any { provider.forAccount(it).watched.value.isNotEmpty() }) WatchService.start(context)
    }
}
