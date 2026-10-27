package com.agentifiers.amp.sync.internal.fleet

import com.agentifiers.amp.model.SnoozeSource
import com.agentifiers.amp.model.SnoozeState
import com.agentifiers.amp.model.SnoozeUntil
import com.agentifiers.amp.model.ThreadColor
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.store.AccountDatabase
import com.agentifiers.amp.store.ColorRow
import com.agentifiers.amp.store.SnoozeRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** What this device wrote that no read returns: snoozes and colors (S10). */
internal interface OrganizeLedger {
    val snoozes: Flow<Map<ThreadId, DeviceSnooze>>
    val colors: Flow<Map<ThreadId, ThreadColor>>
    suspend fun snooze(thread: ThreadId, until: SnoozeUntil?, now: Instant)
    suspend fun color(thread: ThreadId, color: ThreadColor)
}

/** A snooze this device set, and when it ends on its own. */
internal data class DeviceSnooze(val until: SnoozeUntil, val endsAt: Instant?, val setAt: Instant) {
    /** Next Reply ends when a reply makes the thread unread; timed snoozes end at [endsAt]. */
    fun state(now: Instant, unread: Boolean): SnoozeState = when {
        endsAt != null && now >= endsAt -> SnoozeState.NotSnoozed
        until == SnoozeUntil.NextReply && unread -> SnoozeState.NotSnoozed
        else -> SnoozeState.Snoozed(until, SnoozeSource.ThisDevice)
    }

    companion object {
        fun of(until: SnoozeUntil, now: Instant) = DeviceSnooze(
            until = until,
            endsAt = when (until) {
                SnoozeUntil.OneHour -> now + 1.hours
                is SnoozeUntil.At -> until.instant
                SnoozeUntil.NextReply, SnoozeUntil.Indefinitely -> null
            },
            setAt = now,
        )
    }
}

internal class StoreOrganizeLedger(private val database: AccountDatabase) : OrganizeLedger {
    override val snoozes: Flow<Map<ThreadId, DeviceSnooze>> = database.snoozes.observeAll().map { rows ->
        rows.mapNotNull { row -> ThreadId.parse(row.threadId)?.let { id -> row.snooze()?.let { id to it } } }.toMap()
    }

    override val colors: Flow<Map<ThreadId, ThreadColor>> = database.colors.observeAll().map { rows ->
        rows.mapNotNull { row ->
            val id = ThreadId.parse(row.threadId) ?: return@mapNotNull null
            val color = ThreadColor.entries.firstOrNull { it.name == row.color } ?: return@mapNotNull null
            id to color
        }.toMap()
    }

    override suspend fun snooze(thread: ThreadId, until: SnoozeUntil?, now: Instant) {
        if (until == null) return database.snoozes.clear(thread.raw)
        val snooze = DeviceSnooze.of(until, now)
        val kind = when (until) {
            SnoozeUntil.OneHour -> ONE_HOUR
            SnoozeUntil.NextReply -> NEXT_REPLY
            SnoozeUntil.Indefinitely -> INDEFINITELY
            is SnoozeUntil.At -> AT
        }
        database.snoozes.record(SnoozeRow(thread.raw, kind, snooze.endsAt?.toEpochMilliseconds(), SnoozeSource.ThisDevice.name, now.toEpochMilliseconds()))
    }

    override suspend fun color(thread: ThreadId, color: ThreadColor) {
        if (color == ThreadColor.None) database.colors.clear(thread.raw)
        else database.colors.record(ColorRow(thread.raw, color.name, System.currentTimeMillis()))
    }

    private fun SnoozeRow.snooze(): DeviceSnooze? {
        val endsAt = untilMs?.let(Instant::fromEpochMilliseconds)
        val until = when (untilKind) {
            ONE_HOUR -> SnoozeUntil.OneHour
            NEXT_REPLY -> SnoozeUntil.NextReply
            INDEFINITELY -> SnoozeUntil.Indefinitely
            AT -> SnoozeUntil.At(endsAt ?: return null)
            else -> return null
        }
        return DeviceSnooze(until, endsAt, Instant.fromEpochMilliseconds(setAtMs))
    }

    private companion object {
        const val ONE_HOUR = "one_hour"
        const val NEXT_REPLY = "next_reply"
        const val INDEFINITELY = "indefinitely"
        const val AT = "at"
    }
}
