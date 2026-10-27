package com.agentifiers.amp.sync.internal.fleet

import com.agentifiers.amp.store.AccountDatabase
import com.agentifiers.amp.model.SnoozeUntil
import com.agentifiers.amp.model.ThreadColor
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.store.WatchList
import com.agentifiers.amp.sync.internal.attachments.AttachmentUploader
import com.agentifiers.amp.sync.internal.changes.MemoryDatabase
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.NetLog
import com.agentifiers.amp.sync.internal.push.WatchedThreads
import com.agentifiers.amp.sync.internal.thread.ThreadDeps
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import com.agentifiers.amp.store.WatchRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

/** The device ledger without Room, for tests that drive the engines against :fake-amp. */
internal class MemoryLedger : OrganizeLedger {
    override val snoozes = MutableStateFlow<Map<ThreadId, DeviceSnooze>>(emptyMap())
    override val colors = MutableStateFlow<Map<ThreadId, ThreadColor>>(emptyMap())

    override suspend fun snooze(thread: ThreadId, until: SnoozeUntil?, now: Instant) {
        snoozes.value = if (until == null) snoozes.value - thread else snoozes.value + (thread to DeviceSnooze.of(until, now))
    }

    override suspend fun color(thread: ThreadId, color: ThreadColor) {
        colors.value = if (color == ThreadColor.None) colors.value - thread else colors.value + (thread to color)
    }
}

internal class MemoryWatchList : WatchList {
    private val state = MutableStateFlow<List<WatchRow>>(emptyList())
    override fun observe(): Flow<List<WatchRow>> = state
    override suspend fun watch(row: WatchRow) {
        state.value = state.value.filter { it.threadId != row.threadId } + row
    }
    override suspend fun unwatch(threadId: String) {
        state.value = state.value.filter { it.threadId != threadId }
    }
}

/**
 * The thread dependencies DefaultEngines.Session builds, for tests that never dispatch an organize write: the
 * fleet behind the organizer is real but unused.
 */
internal fun testThreadDeps(
    client: OkHttpClient,
    endpoint: Endpoint,
    http: AmpHttp,
    attachments: AttachmentUploader,
    scope: CoroutineScope,
    database: AccountDatabase = MemoryDatabase(),
    onUnauthorized: () -> Unit = {},
): ThreadDeps {
    val fleet = FleetEngine(http, scope, onUnauthorized, WatchedThreads(MemoryWatchList(), scope), MemoryLedger(), MutableStateFlow(emptySet()), dispatch = { _, _ -> error("this test dispatches no organize write") }, threadState = { error("this test views no thread") })
    return ThreadDeps(client, endpoint, http, "test", NetLog(false), onUnauthorized, database, attachments, Organizer(http, fleet, scope, onUnauthorized), fleet::self)
}
