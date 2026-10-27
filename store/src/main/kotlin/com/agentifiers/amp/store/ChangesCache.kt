package com.agentifiers.amp.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** The last change set seen for one scope, with when it was captured. The first paint, never the final answer. */
@Entity(tableName = "change_snapshot", primaryKeys = ["threadId", "scope", "diffHash"])
data class ChangeSnapshotRow(val threadId: String, val scope: String, val diffHash: String, val json: String, val capturedAtMs: Long)

/** File contents for client-side diffs, keyed by git SHA. */
@Entity(tableName = "blob")
class BlobRow(@PrimaryKey val sha: String, val bytes: ByteArray, val lastUsedMs: Long) {
    override fun equals(other: Any?): Boolean =
        other is BlobRow && sha == other.sha && lastUsedMs == other.lastUsedMs && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * sha.hashCode() + bytes.contentHashCode()) + lastUsedMs.hashCode()
}

/** The intelligent order, summary and code tour for one section. */
@Entity(tableName = "file_order", primaryKeys = ["threadId", "sectionId", "diffHash"])
data class FileOrderRow(val threadId: String, val sectionId: String, val diffHash: String, val json: String)

interface ChangesCache {
    /** The scope's newest snapshot that fits [MAX_CACHED_ROW_BYTES]; a larger row already on disk is deleted, not read. */
    suspend fun latestSnapshot(threadId: String, scope: String): ChangeSnapshotRow?

    /** Replaces the scope's previous snapshot. A snapshot over [MAX_CACHED_ROW_BYTES] is not cached. */
    suspend fun saveSnapshot(row: ChangeSnapshotRow)

    /** The blob's bytes, marking it used now; null when it was never cached or has been evicted. */
    suspend fun blob(sha: String): ByteArray?

    /**
     * Caches a blob, then evicts least recently used blobs until the account holds at most
     * [BLOB_BUDGET_BYTES]. A blob over [MAX_CACHED_ROW_BYTES] is not cached.
     */
    suspend fun saveBlob(row: BlobRow)

    /** Null when none is cached or the cached one is over [MAX_CACHED_ROW_BYTES], which is then deleted. */
    suspend fun fileOrder(threadId: String, sectionId: String, diffHash: String): FileOrderRow?

    /** An order over [MAX_CACHED_ROW_BYTES] is not cached. */
    suspend fun saveFileOrder(row: FileOrderRow)
}

const val BLOB_BUDGET_BYTES: Long = 64L * 1024 * 1024

// Android reads a row through a 2 MB CursorWindow, so a larger row could be written and never read back: the read
// throws SQLiteBlobTooBigException.
const val MAX_CACHED_ROW_BYTES: Int = 1024 * 1024

/** The UTF-8 size SQLite stores for [text], without encoding it. */
fun utf8Size(text: String): Int {
    var size = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        size += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { i++; 4 }
            else -> 3
        }
        i++
    }
    return size
}

internal data class BlobUsage(val sha: String, val size: Long, val lastUsedMs: Long)

/** Least recently used first out: keep the newest blobs while they fit in [budgetBytes], evict the rest. */
internal fun blobsToEvict(usages: List<BlobUsage>, budgetBytes: Long): List<String> {
    val newestFirst = usages.sortedWith(compareByDescending<BlobUsage> { it.lastUsedMs }.thenBy { it.sha })
    val totals = newestFirst.runningFold(0L) { sum, usage -> sum + usage.size }.drop(1)
    return newestFirst.zip(totals).filter { (_, total) -> total > budgetBytes }.map { (usage, _) -> usage.sha }
}

@Dao
internal abstract class ChangesDao : ChangesCache {

    @Transaction
    override suspend fun latestSnapshot(threadId: String, scope: String): ChangeSnapshotRow? {
        deleteOversizeSnapshots(threadId, scope, MAX_CACHED_ROW_BYTES)
        return newestSnapshot(threadId, scope)
    }

    @Transaction
    override suspend fun saveSnapshot(row: ChangeSnapshotRow) {
        deleteSnapshots(row.threadId, row.scope)
        if (utf8Size(row.json) <= MAX_CACHED_ROW_BYTES) insertSnapshot(row)
    }

    @Transaction
    override suspend fun blob(sha: String): ByteArray? {
        val bytes = blobBytes(sha) ?: return null
        touchBlob(sha, System.currentTimeMillis())
        return bytes
    }

    @Transaction
    override suspend fun saveBlob(row: BlobRow) {
        if (row.bytes.size > MAX_CACHED_ROW_BYTES) return
        upsertBlob(row)
        val evicted = blobsToEvict(blobUsages(), BLOB_BUDGET_BYTES)
        if (evicted.isNotEmpty()) deleteBlobs(evicted)
    }

    @Transaction
    override suspend fun fileOrder(threadId: String, sectionId: String, diffHash: String): FileOrderRow? {
        deleteOversizeFileOrder(threadId, sectionId, diffHash, MAX_CACHED_ROW_BYTES)
        return storedFileOrder(threadId, sectionId, diffHash)
    }

    override suspend fun saveFileOrder(row: FileOrderRow) {
        if (utf8Size(row.json) <= MAX_CACHED_ROW_BYTES) upsertFileOrder(row)
    }

    @Query("SELECT * FROM change_snapshot WHERE threadId = :threadId AND scope = :scope ORDER BY capturedAtMs DESC LIMIT 1")
    abstract suspend fun newestSnapshot(threadId: String, scope: String): ChangeSnapshotRow?

    @Query("DELETE FROM change_snapshot WHERE threadId = :threadId AND scope = :scope AND length(CAST(json AS BLOB)) > :maxBytes")
    abstract suspend fun deleteOversizeSnapshots(threadId: String, scope: String, maxBytes: Int)

    @Query("SELECT * FROM file_order WHERE threadId = :threadId AND sectionId = :sectionId AND diffHash = :diffHash")
    abstract suspend fun storedFileOrder(threadId: String, sectionId: String, diffHash: String): FileOrderRow?

    @Query("DELETE FROM file_order WHERE threadId = :threadId AND sectionId = :sectionId AND diffHash = :diffHash AND length(CAST(json AS BLOB)) > :maxBytes")
    abstract suspend fun deleteOversizeFileOrder(threadId: String, sectionId: String, diffHash: String, maxBytes: Int)

    @Upsert abstract suspend fun upsertFileOrder(row: FileOrderRow)

    @Query("DELETE FROM change_snapshot WHERE threadId = :threadId AND scope = :scope")
    abstract suspend fun deleteSnapshots(threadId: String, scope: String)

    @Upsert abstract suspend fun insertSnapshot(row: ChangeSnapshotRow)

    @Query("SELECT bytes FROM blob WHERE sha = :sha")
    abstract suspend fun blobBytes(sha: String): ByteArray?

    @Query("UPDATE blob SET lastUsedMs = :usedAtMs WHERE sha = :sha")
    abstract suspend fun touchBlob(sha: String, usedAtMs: Long)

    @Upsert abstract suspend fun upsertBlob(row: BlobRow)

    @Query("SELECT sha, length(bytes) AS size, lastUsedMs FROM blob")
    abstract suspend fun blobUsages(): List<BlobUsage>

    @Query("DELETE FROM blob WHERE sha IN (:shas)")
    abstract suspend fun deleteBlobs(shas: List<String>)
}
