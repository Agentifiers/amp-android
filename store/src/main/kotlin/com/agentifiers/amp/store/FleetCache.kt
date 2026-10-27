package com.agentifiers.amp.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Where a thread summary came from: a thread listing or the user actor's live updates. :sync merges one row per source. */
enum class SummarySource { Listing, Live }

@Entity(tableName = "thread_summary", primaryKeys = ["threadId", "source"])
data class SummaryRow(val threadId: String, val source: SummarySource, val json: String, val updatedAtMs: Long)

interface SummaryCache {
    fun observe(): Flow<List<SummaryRow>>

    /** A fresh listing replaces every row of its [source], so threads gone from the server leave the cache. */
    suspend fun replace(source: SummarySource, rows: List<SummaryRow>)

    suspend fun upsert(row: SummaryRow)
}

/** One snapshot per picker or settings resource, shown instantly while a fresh copy loads. */
enum class CatalogKind { Labels, Projects, Runners, AccountResource }

@Entity(tableName = "catalog")
data class CatalogRow(@PrimaryKey val kind: CatalogKind, val json: String, val fetchedAtMs: Long)

interface CatalogCache {
    fun observe(kind: CatalogKind): Flow<CatalogRow?>
    suspend fun save(row: CatalogRow)
}

@Dao
internal abstract class SummaryDao : SummaryCache {

    @Query("SELECT * FROM thread_summary ORDER BY threadId, source")
    abstract override fun observe(): Flow<List<SummaryRow>>

    @Transaction
    override suspend fun replace(source: SummarySource, rows: List<SummaryRow>) {
        deleteSource(source)
        upsertAll(rows)
    }

    @Upsert abstract override suspend fun upsert(row: SummaryRow)

    @Upsert abstract suspend fun upsertAll(rows: List<SummaryRow>)

    @Query("DELETE FROM thread_summary WHERE source = :source")
    abstract suspend fun deleteSource(source: SummarySource)
}

@Dao
internal abstract class CatalogDao : CatalogCache {

    @Query("SELECT * FROM catalog WHERE kind = :kind")
    abstract override fun observe(kind: CatalogKind): Flow<CatalogRow?>

    @Upsert abstract override suspend fun save(row: CatalogRow)
}
