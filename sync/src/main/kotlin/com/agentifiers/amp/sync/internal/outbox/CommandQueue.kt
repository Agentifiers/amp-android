package com.agentifiers.amp.sync.internal.outbox

import com.agentifiers.amp.model.AccountCommand
import com.agentifiers.amp.model.Command
import com.agentifiers.amp.model.IntentId
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.OutboxEntry
import com.agentifiers.amp.model.OutboxState
import com.agentifiers.amp.model.PendingSend
import com.agentifiers.amp.model.SendMode
import com.agentifiers.amp.model.SendState
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.store.MAX_CACHED_ROW_BYTES
import com.agentifiers.amp.store.Outbox
import com.agentifiers.amp.store.OutboxRow
import com.agentifiers.amp.store.OutboxRowState
import com.agentifiers.amp.store.utf8Size
import com.agentifiers.amp.sync.internal.readOrAbsent
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.time.Clock
import kotlin.time.Instant

/** A command that reached a final outcome. [result] null means delivered; otherwise Rejected, Unknown or Dropped. */
internal data class Settlement(val thread: ThreadId?, val intent: IntentId, val command: Command, val result: OutboxState?)

/** One dispatched command until it settles. [command] is null for a stored row this build cannot decode. */
internal data class QueuedCommand(val id: IntentId, val command: Command?, val state: OutboxState, val createdAtMs: Long)

/**
 * One thread's unsent commands, in dispatch order. Durable commands are mirrored to the `outbox` table through
 * [OutboxCodec]; Ephemeral ones live only here, so a secret never reaches Room. Not thread-safe: the owning
 * engine calls it from its one confined dispatcher.
 */
internal class CommandQueue(private val thread: ThreadId?, private val store: Outbox, private val clock: Clock = Clock.System) {
    var items: PersistentList<QueuedCommand> = persistentListOf()
        private set

    /**
     * Reads the stored rows after a restart. A row that was in flight when the process died lost its answer, so
     * it follows its delivery class: Replayable and Guarded go back to the queue, AtMostOnce becomes Unknown.
     */
    suspend fun load() {
        val threadId = thread ?: return
        items = readOrAbsent("Outbox") { store.rows(threadId.raw) }.orEmpty().map { row ->
            val command = OutboxCodec.decode(row.commandJson)
            val state = if (command == null) OutboxState.Rejected(OLDER_VERSION) else stateOf(row)
            QueuedCommand(IntentId(row.intentId), command, state, row.createdAtMs)
        }.toPersistentList()
        recoverInFlight()
    }

    /** Applies the lost-answer rule to every command still marked Sending. */
    suspend fun recoverInFlight() {
        items.filter { it.state is OutboxState.Sending }.forEach { item -> set(item.id, afterLostAnswer(item.command)) }
    }

    /** A Durable command over [MAX_CACHED_ROW_BYTES] encoded is refused with [TOO_LARGE] rather than stored. */
    suspend fun submit(id: IntentId, command: Command, state: OutboxState = OutboxState.Queued) {
        val item = QueuedCommand(id, command, state, clock.now().toEpochMilliseconds())
        if (command !is Command.Durable) {
            items = items.add(item)
            return
        }
        val row = rowOf(item, command)
        if (utf8Size(row.commandJson) > MAX_CACHED_ROW_BYTES) return refuse(id, command, TOO_LARGE)
        items = items.add(item)
        store.enqueue(row)
    }

    /** Shown at once and never stored, for a command that cannot even be queued, such as an Interrupt while offline. */
    fun refuse(id: IntentId, command: Command, reason: String) {
        items = items.add(QueuedCommand(id, command, OutboxState.Rejected(reason), clock.now().toEpochMilliseconds()))
    }

    suspend fun set(id: IntentId, state: OutboxState) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        val item = items[index]
        items = items.set(index, item.copy(state = state))
        if (item.command is Command.Durable) store.mark(id.raw, rowState(state), (state as? OutboxState.Rejected)?.reason)
    }

    suspend fun remove(id: IntentId) {
        items = items.removeAll { it.id == id }
        store.discard(id.raw)
    }

    /** Removes the sends whose client-minted id an echo carried. Returns what it removed. */
    suspend fun acknowledge(echo: MessageId): List<QueuedCommand> {
        val acked = items.filter { it.command?.let(::echoOf) == echo }
        if (acked.isEmpty()) return acked
        items = items.removeAll { it in acked }
        store.acknowledge(listOf(echo.raw))
        return acked
    }

    fun find(id: IntentId): QueuedCommand? = items.firstOrNull { it.id == id }

    fun findByEcho(echo: MessageId): QueuedCommand? = items.firstOrNull { it.command?.let(::echoOf) == echo }

    /** Sends not yet echoed, for the transcript's pending bubbles. */
    fun pending(): PersistentList<PendingSend> = items.mapNotNull { item ->
        val (id, text, steer) = when (val command = item.command) {
            is ThreadIntent.Send -> Triple(command.messageId, command.draft.text, command.mode == SendMode.Steer)
            is ThreadIntent.RunChangesAction -> Triple(command.messageId, command.prompt, false)
            else -> return@mapNotNull null
        }
        val state = when (val s = item.state) {
            OutboxState.Queued, is OutboxState.WaitingForNetwork -> SendState.Waiting
            is OutboxState.Sending -> SendState.Sending
            is OutboxState.Rejected -> SendState.Failed(s.reason)
            OutboxState.Unknown -> SendState.Failed("No answer from Amp.")
            is OutboxState.Dropped -> SendState.Failed("Dropped.")
        }
        PendingSend(id, text, steer, state)
    }.toPersistentList()

    /** Everything other than sends and Changes prompts, for the composer's notice. */
    fun entries(): PersistentList<OutboxEntry> =
        items.filter { it.command?.let(::echoOf) == null }.map { OutboxEntry(it.id, previewOf(it.command), it.state) }.toPersistentList()

    private fun stateOf(row: OutboxRow): OutboxState = when (row.state) {
        OutboxRowState.Queued -> OutboxState.Queued
        OutboxRowState.Sending -> OutboxState.Sending(row.attempts)
        OutboxRowState.WaitingNetwork -> OutboxState.WaitingForNetwork(Instant.fromEpochMilliseconds(row.createdAtMs))
        OutboxRowState.Rejected -> OutboxState.Rejected(row.reason ?: "Not sent.")
        OutboxRowState.Unknown -> OutboxState.Unknown
        OutboxRowState.Dropped -> (OutboxCodec.decode(row.commandJson) as? Command.Guarded)?.let { OutboxState.Dropped(it.guard) }
            ?: OutboxState.Rejected("Not sent.")
    }

    private fun rowOf(item: QueuedCommand, command: Command.Durable) = OutboxRow(
        intentId = item.id.raw,
        threadId = thread?.raw,
        messageId = echoOf(command)?.raw,
        commandJson = OutboxCodec.encode(command),
        state = rowState(item.state),
        attempts = (item.state as? OutboxState.Sending)?.attempt ?: 0,
        createdAtMs = item.createdAtMs,
        reason = (item.state as? OutboxState.Rejected)?.reason,
    )

    companion object {
        const val OLDER_VERSION = "Sent by an older version of the app."
        const val TOO_LARGE = "Too large to send. Keep it under 1 MB."

        /** What a command becomes when its answer never arrived. */
        fun afterLostAnswer(command: Command?): OutboxState = when (command) {
            is Command.Replayable, is Command.Guarded -> OutboxState.Queued
            is Command.AtMostOnce, is Command.Ephemeral, null -> OutboxState.Unknown
        }

        fun rowState(state: OutboxState): OutboxRowState = when (state) {
            OutboxState.Queued -> OutboxRowState.Queued
            is OutboxState.Sending -> OutboxRowState.Sending
            is OutboxState.WaitingForNetwork -> OutboxRowState.WaitingNetwork
            is OutboxState.Rejected -> OutboxRowState.Rejected
            OutboxState.Unknown -> OutboxRowState.Unknown
            is OutboxState.Dropped -> OutboxRowState.Dropped
        }

        fun previewOf(command: Command?): String = when (command) {
            null -> "A change"
            is ThreadIntent.Send -> "Message"
            is ThreadIntent.EditMessage -> "Edit"
            is ThreadIntent.SteerQueued -> "Steer now"
            is ThreadIntent.RemoveQueued -> "Remove queued message"
            is ThreadIntent.ReorderQueue -> "Reorder queue"
            ThreadIntent.Interrupt -> "Interrupt"
            ThreadIntent.Retry -> "Retry"
            ThreadIntent.DismissError -> "Dismiss error"
            is ThreadIntent.React -> "Reaction"
            ThreadIntent.RetryFailedSetup -> "Retry setup"
            ThreadIntent.RestartOrbProcesses -> "Restart orb processes"
            is ThreadIntent.Archive -> if (command.archived) "Archive" else "Unarchive"
            else -> command::class.simpleName ?: "A change"
        }
    }
}
