package com.agentifiers.amp.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A thread the user chose to Watch, since [sinceMs]. */
@Entity(tableName = "watch_list")
data class WatchRow(@PrimaryKey val threadId: String, val sinceMs: Long)

interface WatchList {
    fun observe(): Flow<List<WatchRow>>
    suspend fun watch(row: WatchRow)
    suspend fun unwatch(threadId: String)
}

/** A snooze this device set, or a reconciled server result. [untilMs] is absent for snoozes that end on an event rather than a time. */
@Entity(tableName = "snooze_ledger")
data class SnoozeRow(@PrimaryKey val threadId: String, val untilKind: String, val untilMs: Long?, val source: String, val setAtMs: Long)

interface SnoozeLedger {
    fun observeAll(): Flow<List<SnoozeRow>>
    suspend fun find(threadId: String): SnoozeRow?
    suspend fun record(row: SnoozeRow)
    suspend fun clear(threadId: String)
}

/** A live Web Push upsert of [threadId] for the push endpoint whose hash is [endpointHash]. */
@Entity(tableName = "push_subscription", primaryKeys = ["threadId", "endpointHash"], indices = [Index("endpointHash")])
data class PushSubRow(val threadId: String, val endpointHash: String, val upsertedAtMs: Long)

interface PushSubscriptions {
    suspend fun isSubscribed(threadId: String, endpointHash: String): Boolean
    suspend fun record(row: PushSubRow)

    /** The endpoint was replaced, so none of its upserts deliver any more. */
    suspend fun forgetEndpoint(endpointHash: String)
}

enum class LedgerState { Posted, Answered, Dismissed }

/**
 * One notification event. [tag] is the server's push tag (`amp-thread-<id>-idle-<messageId>`) or
 * the same shape synthesized by the watch and poll paths, so any two delivery paths post an event
 * once. Approvals claim `amp-thread-<id>-approval-<toolCallId>` and plugin dialogs
 * `amp-thread-<id>-plugin-<requestId>` until a capture shows the server's shape.
 */
@Entity(tableName = "notification_ledger", indices = [Index("threadId")])
data class LedgerRow(@PrimaryKey val tag: String, val threadId: String, val kind: String, val postedAtMs: Long, val state: LedgerState)

interface NotificationLedger {
    /** True exactly once per tag: the first delivery path to claim an event posts it. */
    suspend fun claim(row: LedgerRow): Boolean
    suspend fun resolve(tag: String, state: LedgerState)
}

@Dao
internal abstract class WatchDao : WatchList {

    @Query("SELECT * FROM watch_list ORDER BY sinceMs, threadId")
    abstract override fun observe(): Flow<List<WatchRow>>

    @Upsert abstract override suspend fun watch(row: WatchRow)

    @Query("DELETE FROM watch_list WHERE threadId = :threadId")
    abstract override suspend fun unwatch(threadId: String)
}

@Dao
internal abstract class SnoozeDao : SnoozeLedger {

    @Query("SELECT * FROM snooze_ledger ORDER BY threadId")
    abstract override fun observeAll(): Flow<List<SnoozeRow>>

    @Query("SELECT * FROM snooze_ledger WHERE threadId = :threadId")
    abstract override suspend fun find(threadId: String): SnoozeRow?

    @Upsert abstract override suspend fun record(row: SnoozeRow)

    @Query("DELETE FROM snooze_ledger WHERE threadId = :threadId")
    abstract override suspend fun clear(threadId: String)
}

@Dao
internal abstract class PushSubscriptionDao : PushSubscriptions {

    @Query("SELECT EXISTS(SELECT 1 FROM push_subscription WHERE threadId = :threadId AND endpointHash = :endpointHash)")
    abstract override suspend fun isSubscribed(threadId: String, endpointHash: String): Boolean

    @Upsert abstract override suspend fun record(row: PushSubRow)

    @Query("DELETE FROM push_subscription WHERE endpointHash = :endpointHash")
    abstract override suspend fun forgetEndpoint(endpointHash: String)
}

@Dao
internal abstract class LedgerDao : NotificationLedger {

    override suspend fun claim(row: LedgerRow): Boolean = insertIfAbsent(row) != -1L

    @Query("UPDATE notification_ledger SET state = :state WHERE tag = :tag")
    abstract override suspend fun resolve(tag: String, state: LedgerState)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(row: LedgerRow): Long
}
