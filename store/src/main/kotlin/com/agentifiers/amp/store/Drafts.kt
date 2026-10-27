package com.agentifiers.amp.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The composer's unsent text. :sync also mirrors it to the user actor so drafts follow the user across clients. */
@Entity(tableName = "draft")
data class DraftRow(@PrimaryKey val threadId: String, val text: String, val attachmentsJson: String, val updatedAtMs: Long)

interface Drafts {
    fun observe(threadId: String): Flow<DraftRow?>
    suspend fun save(row: DraftRow)
}

/** An unsent diff or message comment, so it survives process death. */
@Entity(tableName = "comment_draft", primaryKeys = ["threadId", "anchor"])
data class CommentDraftRow(val threadId: String, val anchor: String, val text: String, val updatedAtMs: Long)

interface CommentDrafts {
    fun observe(threadId: String): Flow<List<CommentDraftRow>>
    suspend fun save(row: CommentDraftRow)
    suspend fun delete(threadId: String, anchor: String)
}

@Dao
internal abstract class DraftDao : Drafts {

    @Query("SELECT * FROM draft WHERE threadId = :threadId")
    abstract override fun observe(threadId: String): Flow<DraftRow?>

    @Upsert abstract override suspend fun save(row: DraftRow)
}

@Dao
internal abstract class CommentDraftDao : CommentDrafts {

    @Query("SELECT * FROM comment_draft WHERE threadId = :threadId ORDER BY anchor")
    abstract override fun observe(threadId: String): Flow<List<CommentDraftRow>>

    @Upsert abstract override suspend fun save(row: CommentDraftRow)

    @Query("DELETE FROM comment_draft WHERE threadId = :threadId AND anchor = :anchor")
    abstract override suspend fun delete(threadId: String, anchor: String)
}
