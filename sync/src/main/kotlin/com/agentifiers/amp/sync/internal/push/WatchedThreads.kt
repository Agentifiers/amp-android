package com.agentifiers.amp.sync.internal.push

import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.store.WatchList
import com.agentifiers.amp.store.WatchRow
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The threads the user chose to Watch, stored in the account's `watch_list` table so they survive a restart. */
internal class WatchedThreads(private val table: WatchList, private val scope: CoroutineScope) {
    val threads: StateFlow<PersistentSet<ThreadId>> = table.observe()
        .map { rows -> rows.mapNotNull { ThreadId.parse(it.threadId) }.toPersistentSet() }
        .stateIn(scope, SharingStarted.Eagerly, persistentSetOf())

    fun set(thread: ThreadId, watching: Boolean) {
        scope.launch {
            if (watching) table.watch(WatchRow(thread.raw, System.currentTimeMillis())) else table.unwatch(thread.raw)
        }
    }
}

/** Threads a STARTED window shows, counted per thread because two panes may lease the same one. */
internal class LeaseLedger {
    private val counts = HashMap<ThreadId, Int>()
    private val mutable = MutableStateFlow<PersistentSet<ThreadId>>(persistentSetOf())
    val threads: StateFlow<PersistentSet<ThreadId>> get() = mutable

    @Synchronized
    fun acquire(thread: ThreadId) {
        counts[thread] = (counts[thread] ?: 0) + 1
        mutable.value = counts.keys.toPersistentSet()
    }

    @Synchronized
    fun release(thread: ThreadId) {
        val left = (counts[thread] ?: return) - 1
        if (left <= 0) counts.remove(thread) else counts[thread] = left
        mutable.value = counts.keys.toPersistentSet()
    }
}
