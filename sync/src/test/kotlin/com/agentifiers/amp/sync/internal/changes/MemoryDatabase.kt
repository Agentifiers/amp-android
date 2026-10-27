package com.agentifiers.amp.sync.internal.changes

import com.agentifiers.amp.store.AccountDatabase
import com.agentifiers.amp.store.BlobRow
import com.agentifiers.amp.store.ChangeSnapshotRow
import com.agentifiers.amp.store.ChangesCache
import com.agentifiers.amp.store.CommentDraftRow
import com.agentifiers.amp.store.CommentDrafts
import com.agentifiers.amp.store.FileOrderRow
import com.agentifiers.amp.store.Outbox
import com.agentifiers.amp.sync.internal.NoOutbox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** The tables engines under test use, in memory. The others are not reached by these tests. */
internal class MemoryDatabase(override val outbox: Outbox = NoOutbox) : AccountDatabase {
    override val changes: ChangesCache = object : ChangesCache {
        private val snapshots = HashMap<Pair<String, String>, ChangeSnapshotRow>()
        private val blobs = HashMap<String, ByteArray>()
        private val orders = HashMap<Triple<String, String, String>, FileOrderRow>()

        override suspend fun latestSnapshot(threadId: String, scope: String) = snapshots[threadId to scope]
        override suspend fun saveSnapshot(row: ChangeSnapshotRow) { snapshots[row.threadId to row.scope] = row }
        override suspend fun blob(sha: String) = blobs[sha]
        override suspend fun saveBlob(row: BlobRow) { blobs[row.sha] = row.bytes }
        override suspend fun fileOrder(threadId: String, sectionId: String, diffHash: String) = orders[Triple(threadId, sectionId, diffHash)]
        override suspend fun saveFileOrder(row: FileOrderRow) { orders[Triple(row.threadId, row.sectionId, row.diffHash)] = row }
    }

    override val commentDrafts: CommentDrafts = object : CommentDrafts {
        private val rows = MutableStateFlow<Map<Pair<String, String>, CommentDraftRow>>(emptyMap())

        override fun observe(threadId: String): Flow<List<CommentDraftRow>> = rows.map { all -> all.values.filter { it.threadId == threadId } }
        override suspend fun save(row: CommentDraftRow) { rows.value = rows.value + ((row.threadId to row.anchor) to row) }
        override suspend fun delete(threadId: String, anchor: String) { rows.value = rows.value - (threadId to anchor) }
    }

    override val threadCache get() = unused()
    override val summaries get() = unused()
    override val catalog get() = unused()
    override val drafts get() = unused()
    override val watchList get() = unused()
    override val snoozes get() = unused()
    override val colors get() = unused()
    override val pushSubscriptions get() = unused()
    override val ledger get() = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by the Changes engine")
}
