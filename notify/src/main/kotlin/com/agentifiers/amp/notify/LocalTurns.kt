package com.agentifiers.amp.notify

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.sync.api.BackgroundAccess
import com.agentifiers.amp.sync.api.BackgroundAccessProvider
import com.agentifiers.amp.sync.api.FleetSignal
import dagger.hilt.android.EntryPointAccessors
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.time.Instant

/**
 * Settings > Notifications > "Notify even when a thread is open elsewhere", on by default. Off leaves only Amp's push,
 * which Amp withholds while any client has the thread open (S23).
 */
object NotifyWhenOpenElsewhere {
    private val store by lazy { EntryPointAccessors.fromApplication(NotifyRuntime.app, NotifyEntryPoint::class.java).appSettings() }

    internal val changes: Flow<Boolean> get() = store.data.map { it.notifyWhenOpenElsewhere }.distinctUntilChanged()

    val on: StateFlow<Boolean> by lazy { changes.stateIn(NotifyRuntime.scope, SharingStarted.Eagerly, true) }

    internal suspend fun current(): Boolean = changes.first()

    fun set(on: Boolean) {
        NotifyRuntime.scope.launch {
            store.updateData { it.copy(notifyWhenOpenElsewhere = on) }
            if (on) LocalTurns.restart() else WorkManager.getInstance(NotifyRuntime.app).cancelAllWorkByTag(PollWorker.TURN_CHECKS)
        }
    }
}

/** Posts what a summary from the user actor shows, through the same [PushInbox.route] a push takes. */
internal object LocalTurns {
    private val marks by lazy { NotifyRuntime.app.getSharedPreferences("notify-turns", Context.MODE_PRIVATE) }

    /**
     * Turns that ended before this never post, so the first check after sign-in, or after the setting comes back on,
     * posts no backlog. Set by the first check that asks.
     */
    @Synchronized
    fun since(account: AccountId): Instant {
        val stored = marks.getLong(account.raw, 0L)
        if (stored > 0L) return Instant.fromEpochMilliseconds(stored)
        val now = System.currentTimeMillis()
        marks.edit().putLong(account.raw, now).apply()
        return Instant.fromEpochMilliseconds(now)
    }

    @Synchronized
    fun restart() = marks.edit().clear().apply()

    @Synchronized
    fun forget(account: AccountId) = marks.edit().remove(account.raw).apply()

    suspend fun finished(account: AccountId, summary: ThreadSummary, via: String) {
        val event = TurnEnds.finished(summary, since(account)) ?: return
        Log.i(PushInbox.TAG, "$via thread_idle ${summary.id.raw.take(10)}: ${PushInbox.route(account, event, local = true)}")
    }

    suspend fun needsYou(context: Context, account: AccountId, summary: ThreadSummary, leased: Set<ThreadId>) {
        val tag = TurnEnds.needsYouTag(summary, LocalDate.now()) ?: return
        if (summary.id in leased) return
        val key = NoticeKey(account, summary.id, tag, summary.title ?: "Amp")
        if (NotifyRuntime.notifier.postNeedsYou(key, "Waiting for you")) GateFetchWorker.enqueue(context, key)
    }
}

/**
 * While the app is in front with the setting on, one user actor socket per account posts a finished turn or a waiting
 * dialog as soon as Amp reports it, for threads no started window shows. The socket closes when the app leaves the
 * front, and the background check takes over.
 */
internal object LiveTurns {
    private val foreground = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(provider: BackgroundAccessProvider) {
        NotifyRuntime.scope.launch {
            combine(foreground, provider.accounts, NotifyWhenOpenElsewhere.changes) { front, accounts, on -> if (front && on) accounts else persistentSetOf() }
                .distinctUntilChanged()
                .collectLatest { accounts -> coroutineScope { accounts.forEach { launch { watch(it, provider.forAccount(it)) } } } }
        }
    }

    fun onStart() {
        foreground.value = true
    }

    /** A turn may still be running when the app leaves the front, so the background check starts its chain. */
    fun onStop(context: Context, provider: BackgroundAccessProvider) {
        foreground.value = false
        NotifyRuntime.scope.launch {
            if (NotifyWhenOpenElsewhere.current()) provider.accounts.value.forEach { PollWorker.checkTurnsLater(context, it, chainStartedMs = null) }
        }
    }

    private suspend fun watch(account: AccountId, background: BackgroundAccess) = coroutineScope {
        val settling = HashMap<ThreadId, Job>()
        while (isActive) {
            background.watchFleet().collect { signal ->
                val summary = (signal as? FleetSignal.StatusChanged)?.summary ?: return@collect
                settling.remove(summary.id)?.cancel()
                settling[summary.id] = launch {
                    delay(SETTLE_MS)
                    LocalTurns.needsYou(NotifyRuntime.app, account, summary, background.leasedThreads.value)
                    LocalTurns.finished(account, summary, "live")
                }
            }
            delay(RECONNECT_MS)
        }
    }

    /** Longer than the gap between the idle that ends a turn and the update with its reply, a few ms in S14. */
    private const val SETTLE_MS = 1_000L
    private const val RECONNECT_MS = 10_000L
}
