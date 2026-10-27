package com.agentifiers.amp.sync.internal

import com.agentifiers.amp.store.Outbox
import com.agentifiers.amp.store.OutboxRow
import com.agentifiers.amp.store.OutboxRowState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** For engines under test whose behavior does not depend on what survives a restart. */
internal object NoOutbox : Outbox {
    override fun observe(threadId: String): Flow<List<OutboxRow>> = emptyFlow()
    override suspend fun rows(threadId: String): List<OutboxRow> = emptyList()
    override suspend fun enqueue(row: OutboxRow) = Unit
    override suspend fun mark(intentId: String, state: OutboxRowState, reason: String?) = Unit
    override suspend fun acknowledge(messageIds: List<String>) = Unit
    override suspend fun discard(intentId: String) = Unit
    override suspend fun pendingAcrossThreads(): List<OutboxRow> = emptyList()
}
