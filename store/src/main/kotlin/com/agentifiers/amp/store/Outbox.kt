package com.agentifiers.amp.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class OutboxRowState {
    Queued,
    Sending,
    WaitingNetwork,

    /** The user must edit or discard it. */
    Rejected,

    /** An AtMostOnce command lost its ack; the user retries or discards. */
    Unknown,

    /** A Guarded command whose guard stopped holding. */
    Dropped,
}

/**
 * One dispatched Command.Durable (a thread intent, or an account command with no [threadId]) until
 * it settles. [commandJson] is written and read only by :sync's versioned OutboxCodec; the delivery
 * class is part of the decoded type, so no column repeats it. [messageId] is the client-minted id
 * whose first echo acknowledges a send; commands without an echo have none. [reason] is what the
 * user reads for a [OutboxRowState.Rejected] row.
 */
@Entity(tableName = "outbox", indices = [Index("threadId"), Index("messageId")])
data class OutboxRow(
    @PrimaryKey val intentId: String,
    val threadId: String?,
    val messageId: String?,
    val commandJson: String,
    val state: OutboxRowState,
    val attempts: Int,
    val createdAtMs: Long,
    val reason: String? = null,
)

interface Outbox {
    fun observe(threadId: String): Flow<List<OutboxRow>>

    /** The thread's rows now, oldest first. */
    suspend fun rows(threadId: String): List<OutboxRow>

    /** Enqueueing an intent id that is already queued keeps the existing row. A command over [MAX_CACHED_ROW_BYTES] is not stored. */
    suspend fun enqueue(row: OutboxRow)

    /** Moving to [OutboxRowState.Sending] counts one more attempt. [reason] replaces the stored one. */
    suspend fun mark(intentId: String, state: OutboxRowState, reason: String?)

    /** Settles the sends whose client-minted message ids were echoed by message_added or queued_message_added. */
    suspend fun acknowledge(messageIds: List<String>)

    /** The user discarded a rejected, unknown or dropped command. */
    suspend fun discard(intentId: String)

    /** Rows the drainer may still deliver, oldest first, across every thread and account command. */
    suspend fun pendingAcrossThreads(): List<OutboxRow>
}

private val DRAINABLE = listOf(OutboxRowState.Queued, OutboxRowState.Sending, OutboxRowState.WaitingNetwork)

// Only a build from before the row cap could have stored a larger command, and a row over the 2 MB CursorWindow cannot
// be read at all. Reads blank such a command, so the codec reads it as a command from an older version.
private const val READABLE_ROW =
    "intentId, threadId, messageId, state, attempts, createdAtMs, reason, " +
        "CASE WHEN length(CAST(commandJson AS BLOB)) > $MAX_CACHED_ROW_BYTES THEN '' ELSE commandJson END AS commandJson"

@Dao
internal abstract class OutboxDao : Outbox {

    @Query("SELECT $READABLE_ROW FROM outbox WHERE threadId = :threadId ORDER BY createdAtMs, intentId")
    abstract override fun observe(threadId: String): Flow<List<OutboxRow>>

    override suspend fun enqueue(row: OutboxRow) {
        if (utf8Size(row.commandJson) <= MAX_CACHED_ROW_BYTES) insert(row)
    }

    @Query("SELECT $READABLE_ROW FROM outbox WHERE threadId = :threadId ORDER BY createdAtMs, intentId")
    abstract override suspend fun rows(threadId: String): List<OutboxRow>

    override suspend fun mark(intentId: String, state: OutboxRowState, reason: String?) =
        update(intentId, state, reason, attemptIncrement = if (state == OutboxRowState.Sending) 1 else 0)

    @Query("DELETE FROM outbox WHERE messageId IN (:messageIds)")
    abstract override suspend fun acknowledge(messageIds: List<String>)

    @Query("DELETE FROM outbox WHERE intentId = :intentId")
    abstract override suspend fun discard(intentId: String)

    override suspend fun pendingAcrossThreads(): List<OutboxRow> = withStates(DRAINABLE)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(row: OutboxRow)

    @Query("UPDATE outbox SET state = :state, reason = :reason, attempts = attempts + :attemptIncrement WHERE intentId = :intentId")
    abstract suspend fun update(intentId: String, state: OutboxRowState, reason: String?, attemptIncrement: Int)

    @Query("SELECT $READABLE_ROW FROM outbox WHERE state IN (:states) ORDER BY createdAtMs, intentId")
    abstract suspend fun withStates(states: List<OutboxRowState>): List<OutboxRow>
}
