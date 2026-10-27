package com.agentifiers.amp.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A thread color this device set. `listThreads` carries no color, so the device remembers what it wrote (S10). */
@Entity(tableName = "thread_color")
data class ColorRow(@PrimaryKey val threadId: String, val color: String, val setAtMs: Long)

interface ColorLedger {
    fun observeAll(): Flow<List<ColorRow>>
    suspend fun record(row: ColorRow)
    suspend fun clear(threadId: String)
}

@Dao
internal abstract class ColorDao : ColorLedger {

    @Query("SELECT * FROM thread_color ORDER BY threadId")
    abstract override fun observeAll(): Flow<List<ColorRow>>

    @Upsert abstract override suspend fun record(row: ColorRow)

    @Query("DELETE FROM thread_color WHERE threadId = :threadId")
    abstract override suspend fun clear(threadId: String)
}
