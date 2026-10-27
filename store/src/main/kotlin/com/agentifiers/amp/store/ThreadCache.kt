package com.agentifiers.amp.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * Sequenced message facts (message_added, message_updated) and paged history rows upsert the same
 * key. [ordinal] is the paged messageIndex when known, else max + 1. [json] is the raw wire event,
 * parsed only by :sync, so parser fixes apply to cached threads without a migration.
 */
@Entity(
    tableName = "message_row",
    primaryKeys = ["threadId", "messageId"],
    indices = [Index("threadId", "ordinal")],
)
data class MessageRow(val threadId: String, val messageId: String, val ordinal: Long, val seq: Long?, val json: String)

/**
 * Non-message Sequenced facts keyed by the seq the frame carries. [aspect] names the slice of the
 * thread the fact belongs to: "Queue", "Error" or "Transcript". A snapshot of an aspect deletes
 * that aspect's rows, and only the newest "Error" row survives a commit.
 */
@Entity(
    tableName = "sequenced_row",
    primaryKeys = ["threadId", "seq"],
    indices = [Index("threadId", "aspect")],
)
data class SequencedRow(val threadId: String, val seq: Long, val aspect: String, val json: String)

/** The latest Snapshot frame per aspect; a write replaces it. */
@Entity(tableName = "snapshot_row", primaryKeys = ["threadId", "aspect"])
data class SnapshotRow(val threadId: String, val aspect: String, val json: String)

/**
 * Where a resumed socket starts ([seq]) and how far back history is loaded. Written only by
 * [ThreadCache.commit] and [ThreadCache.prepend], in the same transaction as the rows it covers.
 * [touchedAtMs] is when either last ran, which [ThreadCache.evictOlderThan] reads.
 */
@Entity(tableName = "resume_cursor")
data class CursorRow(
    @PrimaryKey val threadId: String,
    val seq: Long,
    val firstLoadedOrdinal: Long,
    val hasMoreBefore: Boolean,
    val touchedAtMs: Long,
)

/** Everything one reducer plan writes. [ThreadCache.commit] applies it in one transaction; replaying it is a no-op. */
data class CacheWrite(
    val messages: List<MessageRow>,
    val sequenced: List<SequencedRow>,
    val snapshots: List<SnapshotRow>,
    val cursorSeq: Long,
)

data class CachedThread(
    val messages: List<MessageRow>,
    val sequenced: List<SequencedRow>,
    val snapshots: List<SnapshotRow>,
    val cursor: CursorRow,
)

/** The transcript cache, one table per fact class. Volatile and Keyed facts are never cached. */
interface ThreadCache {
    /** The newest [lastN] messages in ordinal order, every sequenced and snapshot row, and the cursor; null when the thread was never committed. */
    suspend fun load(threadId: String, lastN: Int): CachedThread?

    /** Upserts rows, replaces snapshot aspects and prunes their sequenced rows, advances the cursor. One transaction. */
    suspend fun commit(threadId: String, write: CacheWrite)

    /** Older history pages. Touches message_row and the cursor's paging fields, never the cursor's seq. */
    suspend fun prepend(threadId: String, older: List<MessageRow>, firstLoadedOrdinal: Long, hasMoreBefore: Boolean)

    /** Drops the transcript of every thread untouched for [days], and change snapshots captured before then. */
    suspend fun evictOlderThan(days: Int)
}

/** The only sequenced aspect whose older rows are dead weight: a newer error_set or error_cleared supersedes them. */
private const val ERROR_ASPECT = "Error"

private const val DAY_MS = 24L * 60 * 60 * 1000

@Dao
internal abstract class ThreadCacheDao : ThreadCache {

    @Transaction
    override suspend fun load(threadId: String, lastN: Int): CachedThread? {
        val cursor = cursor(threadId) ?: return null
        return CachedThread(lastMessages(threadId, lastN), sequenced(threadId), snapshots(threadId), cursor)
    }

    @Transaction
    override suspend fun commit(threadId: String, write: CacheWrite) {
        upsertMessages(write.messages)
        val replacedAspects = write.snapshots.map { it.aspect }
        if (replacedAspects.isNotEmpty()) deleteSequenced(threadId, replacedAspects)
        upsertSnapshots(write.snapshots)
        upsertSequenced(write.sequenced)
        pruneOlderThanNewest(threadId, ERROR_ASPECT)
        val now = System.currentTimeMillis()
        val existing = cursor(threadId)
        val cursor = if (existing != null) {
            existing.copy(seq = maxOf(existing.seq, write.cursorSeq), touchedAtMs = now)
        } else {
            val first = minOrdinal(threadId) ?: 0L
            CursorRow(threadId, write.cursorSeq, first, hasMoreBefore = first > 0, touchedAtMs = now)
        }
        upsertCursor(cursor)
    }

    @Transaction
    override suspend fun prepend(threadId: String, older: List<MessageRow>, firstLoadedOrdinal: Long, hasMoreBefore: Boolean) {
        upsertMessages(older)
        val now = System.currentTimeMillis()
        // A thread paged before any commit resumes from 0, which replays history idempotently.
        upsertCursor(
            cursor(threadId)?.copy(firstLoadedOrdinal = firstLoadedOrdinal, hasMoreBefore = hasMoreBefore, touchedAtMs = now)
                ?: CursorRow(threadId, seq = 0, firstLoadedOrdinal, hasMoreBefore, touchedAtMs = now),
        )
    }

    @Transaction
    override suspend fun evictOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - days * DAY_MS
        deleteStaleMessages(cutoff)
        deleteStaleSequenced(cutoff)
        deleteStaleSnapshots(cutoff)
        deleteStaleCursors(cutoff)
        deleteChangeSnapshotsBefore(cutoff)
        deleteOrphanFileOrders()
    }

    @Query("SELECT * FROM resume_cursor WHERE threadId = :threadId")
    abstract suspend fun cursor(threadId: String): CursorRow?

    @Query(
        "SELECT * FROM (SELECT * FROM message_row WHERE threadId = :threadId ORDER BY ordinal DESC LIMIT :lastN) " +
            "ORDER BY ordinal ASC",
    )
    abstract suspend fun lastMessages(threadId: String, lastN: Int): List<MessageRow>

    @Query("SELECT * FROM sequenced_row WHERE threadId = :threadId ORDER BY seq")
    abstract suspend fun sequenced(threadId: String): List<SequencedRow>

    @Query("SELECT * FROM snapshot_row WHERE threadId = :threadId ORDER BY aspect")
    abstract suspend fun snapshots(threadId: String): List<SnapshotRow>

    @Query("SELECT MIN(ordinal) FROM message_row WHERE threadId = :threadId")
    abstract suspend fun minOrdinal(threadId: String): Long?

    @Upsert abstract suspend fun upsertMessages(rows: List<MessageRow>)

    @Upsert abstract suspend fun upsertSequenced(rows: List<SequencedRow>)

    @Upsert abstract suspend fun upsertSnapshots(rows: List<SnapshotRow>)

    @Upsert abstract suspend fun upsertCursor(row: CursorRow)

    @Query("DELETE FROM sequenced_row WHERE threadId = :threadId AND aspect IN (:aspects)")
    abstract suspend fun deleteSequenced(threadId: String, aspects: List<String>)

    @Query(
        "DELETE FROM sequenced_row WHERE threadId = :threadId AND aspect = :aspect AND seq < " +
            "(SELECT MAX(seq) FROM sequenced_row WHERE threadId = :threadId AND aspect = :aspect)",
    )
    abstract suspend fun pruneOlderThanNewest(threadId: String, aspect: String)

    @Query("DELETE FROM message_row WHERE threadId IN (SELECT threadId FROM resume_cursor WHERE touchedAtMs < :cutoff)")
    abstract suspend fun deleteStaleMessages(cutoff: Long)

    @Query("DELETE FROM sequenced_row WHERE threadId IN (SELECT threadId FROM resume_cursor WHERE touchedAtMs < :cutoff)")
    abstract suspend fun deleteStaleSequenced(cutoff: Long)

    @Query("DELETE FROM snapshot_row WHERE threadId IN (SELECT threadId FROM resume_cursor WHERE touchedAtMs < :cutoff)")
    abstract suspend fun deleteStaleSnapshots(cutoff: Long)

    @Query("DELETE FROM resume_cursor WHERE touchedAtMs < :cutoff")
    abstract suspend fun deleteStaleCursors(cutoff: Long)

    @Query("DELETE FROM change_snapshot WHERE capturedAtMs < :cutoff")
    abstract suspend fun deleteChangeSnapshotsBefore(cutoff: Long)

    @Query("DELETE FROM file_order WHERE threadId NOT IN (SELECT threadId FROM change_snapshot)")
    abstract suspend fun deleteOrphanFileOrders()
}
