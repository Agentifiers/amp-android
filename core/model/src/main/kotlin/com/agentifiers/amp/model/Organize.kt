package com.agentifiers.amp.model

import kotlinx.collections.immutable.toPersistentList
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Threads untouched this long leave their project section for **Inactive Last 72h**. */
val INACTIVE_AFTER: Duration = 72.hours

/**
 * Places every thread in exactly one section, first match wins: Archived, Snoozed (unless a gate waits, because
 * Amp ends a snooze when a thread needs approval), Needs you, Pinned, its project while active, else Inactive.
 * Puck conversations live in the Puck pane, and a child whose parent is listed shows under the parent's row.
 */
fun arrangeSidebar(summaries: List<ThreadSummary>, now: Instant, freshness: Freshness): Sidebar {
    val listed = summaries.mapTo(HashSet()) { it.id }
    val rows = summaries.filter { !it.isPuck && (it.parent == null || it.parent !in listed) }.sortedByDescending { it.lastActivity }

    val archived = ArrayList<ThreadSummary>()
    val snoozed = ArrayList<ThreadSummary>()
    val waiting = ArrayList<ThreadSummary>()
    val stopped = ArrayList<ThreadSummary>()
    val finished = ArrayList<ThreadSummary>()
    val pinned = ArrayList<ThreadSummary>()
    val active = LinkedHashMap<ThreadProject, MutableList<ThreadSummary>>()
    val inactive = ArrayList<ThreadSummary>()
    for (row in rows) {
        val target = when {
            row.archived -> archived
            row.snooze is SnoozeState.Snoozed && row.status !is FleetStatus.Waiting -> snoozed
            row.status is FleetStatus.Waiting -> waiting
            row.status == FleetStatus.Error -> stopped
            row.status == FleetStatus.Done -> finished
            row.pinned -> pinned
            row.isActive(now) -> active.getOrPut(row.project) { ArrayList() }
            else -> inactive
        }
        target += row
    }
    return Sidebar(
        needsYou = (waiting + stopped + finished).toPersistentList(),
        pinned = pinned.toPersistentList(),
        active = active.map { (project, threads) -> ProjectGroup(project, threads.toPersistentList()) }.toPersistentList(),
        inactive72h = inactive.toPersistentList(),
        snoozed = snoozed.toPersistentList(),
        archived = archived.toPersistentList(),
        freshness = freshness,
    )
}

private fun ThreadSummary.isActive(now: Instant): Boolean =
    status == FleetStatus.Working || status == FleetStatus.Compacting || lastActivity?.let { now - it < INACTIVE_AFTER } == true

/** Filters a device cannot evaluate from summaries; their chips read "Needs server search". */
val SERVER_ONLY_KEYS: Set<String> = setOf("commit", "pr", "repo")

val QueryFilter.Key.needsServer: Boolean get() = key in SERVER_ONLY_KEYS

/**
 * Local search over cached summaries, used until `/api/threads/find` is observed (screens.md "Activity and search").
 * Archived threads match only when the query names `archived:`. Server-only filters pass every thread.
 * [self] resolves `author:me`.
 */
fun ThreadQuery.matches(summary: ThreadSummary, now: Instant, self: UserId?): Boolean {
    if (filters.none { it is QueryFilter.Key && it.key == "archived" } && summary.archived) return false
    window.duration?.let { span -> if (summary.lastActivity?.let { now - it <= span } != true) return false }
    val words = text.lowercase().split(' ').filter { it.isNotBlank() }
    val title = summary.title.orEmpty().lowercase()
    if (words.any { it !in title }) return false
    return filters.all { filter ->
        when (filter) {
            is QueryFilter.Key -> filter.needsServer || filter.holdsFor(summary, now, self) != filter.negated
        }
    }
}

private val TimeWindow.duration: Duration?
    get() = when (this) {
        TimeWindow.H24 -> 24.hours
        TimeWindow.H72 -> 72.hours
        TimeWindow.D7 -> 7.days
        TimeWindow.All -> null
    }

private fun QueryFilter.Key.holdsFor(summary: ThreadSummary, now: Instant, self: UserId?): Boolean {
    val wanted = value.lowercase()
    return when (key) {
        "id" -> summary.id.raw.equals(value, ignoreCase = true)
        "label" -> summary.labels.any { it.raw == wanted }
        "parent" -> summary.parent?.raw.equals(value, ignoreCase = true)
        "project" -> (summary.project as? ThreadProject.Linked)?.name.equals(value, ignoreCase = true)
        "author" -> if (wanted == "me") self != null && summary.creator == self else summary.creator?.raw == value
        "visibility" -> summary.visibility?.queryName == wanted
        "archived" -> summary.archived == wanted.toBooleanStrictOrNull()
        "snoozed" -> (summary.snooze is SnoozeState.Snoozed) == wanted.toBooleanStrictOrNull()
        "pinned" -> summary.pinned == wanted.toBooleanStrictOrNull()
        "puck" -> summary.isPuck == wanted.toBooleanStrictOrNull()
        "after", "updated_after" -> instantOf(wanted, now)?.let { at -> summary.lastActivity?.let { it >= at } } == true
        "before", "updated_before" -> instantOf(wanted, now)?.let { at -> summary.lastActivity?.let { it < at } } == true
        else -> true
    }
}

/** The `visibility:` value for each visibility. */
val Visibility.queryName: String
    get() = when (this) {
        Visibility.Private -> "private"
        Visibility.Workspace -> "workspace"
        is Visibility.Groups -> "group"
        Visibility.Unlisted -> "unlisted"
    }

private val RELATIVE = Regex("^(\\d+)([dw])$")

/** An ISO date (`2026-10-20`, midnight UTC) or a relative span back from [now] (`3d`, `2w`). */
private fun instantOf(value: String, now: Instant): Instant? {
    RELATIVE.matchEntire(value)?.let { match ->
        val count = match.groupValues[1].toLong()
        return now - if (match.groupValues[2] == "w") (count * 7).days else count.days
    }
    val date = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
    return Instant.fromEpochSeconds(date.atStartOfDay().toEpochSecond(ZoneOffset.UTC))
}
