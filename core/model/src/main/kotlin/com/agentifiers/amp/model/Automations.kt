package com.agentifiers.amp.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** One `listAutomations` row, as the Amp CLI reads it. [trigger] is the schedule's label, as the CLI shows it. */
data class AutomationInfo(
    val id: String,
    val thread: ThreadId,
    val title: String,
    val trigger: String,
    val status: AutomationStatus,
    val nextRun: Instant?,
    val lastRun: Instant?,
    val lastRunError: String?,
) {
    val enabled: Boolean get() = status == AutomationStatus.Active
}

/**
 * The web's automation indicator (chunk fa551f92.js `X`): enabled is [Active], otherwise `disabledReason` names why.
 * [summary] is the web's line when no run is scheduled (`rt`), [explanation] the popover's note under it (`it`).
 */
enum class AutomationStatus(val summary: String?, val explanation: String?) {
    Active(null, null),
    Paused("Automation paused", null),
    Failed("Automation paused · last run failed", "Paused because the last run failed. Re-enable it to resume runs."),
    Completed("Automation completed", "The schedule has no upcoming runs."),
}

/** The thread view's schedule line: the automation behind it and what leads the line. */
data class ThreadSchedule(val automation: AutomationInfo, val lead: ScheduleLead)

sealed interface ScheduleLead {
    data class NextRun(val at: Instant) : ScheduleLead
    data class Stopped(val summary: String) : ScheduleLead
}

/** The line's two parts at one moment, as the web renders them: "Next run in 5m" and "Last run 2h ago". */
data class ScheduleLine(val lead: String, val lastRun: String?, val attention: Boolean)

/**
 * The schedule [thread]'s view shows, or null to hide the line. Several automations on one thread resolve by the CLI's
 * order: enabled first, then the soonest run, then the title (`xK`). The web shows the line while a run is scheduled or
 * the automation is stopped, and a completed one only when it ran after the view opened at [openedAt] (chunk
 * e2ccdf0e.js, `$a`).
 */
fun List<AutomationInfo>.scheduleFor(thread: ThreadId, openedAt: Instant): ThreadSchedule? {
    val automation = filter { it.thread == thread }.minWithOrNull(CLI_ORDER) ?: return null
    if (automation.status == AutomationStatus.Completed && (automation.lastRun == null || automation.lastRun < openedAt)) return null
    val lead = automation.nextRun?.let(ScheduleLead::NextRun) ?: automation.status.summary?.let(ScheduleLead::Stopped) ?: return null
    return ThreadSchedule(automation, lead)
}

private val CLI_ORDER = compareBy<AutomationInfo> { it.status != AutomationStatus.Active }
    .thenBy(nullsLast()) { it.nextRun }
    .thenBy { it.title }

fun ThreadSchedule.line(now: Instant): ScheduleLine = ScheduleLine(
    lead = when (lead) {
        is ScheduleLead.NextRun -> "Next run in ${countdown(lead.at - now)}"
        is ScheduleLead.Stopped -> lead.summary
    },
    lastRun = automation.lastRun?.let { "Last run ${ago(now - it)} ago" },
    attention = automation.status == AutomationStatus.Failed,
)

/** How long until [line] can read differently, or null when it never will (a past-due run and no last run). */
fun ThreadSchedule.nextChange(now: Instant): Duration? {
    val countdown = (lead as? ScheduleLead.NextRun)?.let { it.at - now }?.takeIf { it >= 1.minutes }?.let { it.inWholeMilliseconds.mod(MINUTE_MS).milliseconds + 1.milliseconds }
    val ago = automation.lastRun?.let { (MINUTE_MS - (now - it).inWholeMilliseconds.mod(MINUTE_MS)).milliseconds }
    return listOfNotNull(countdown, ago).minOrNull()
}

private const val MINUTE_MS = 60_000L

/** The web's countdown (chunk c24136b7.js `u`, the CLI's `vzt`): "<1m", "5m", "2h", "2h 5m", "1d", "1d 3h". */
fun countdown(remaining: Duration): String {
    if (remaining < 1.minutes) return "<1m"
    val minutes = remaining.inWholeMinutes
    val days = minutes / 1_440
    val hours = minutes % 1_440 / 60
    val rest = minutes % 60
    return when {
        days > 0 -> if (hours > 0) "${days}d ${hours}h" else "${days}d"
        hours > 0 -> if (rest > 0) "${hours}h ${rest}m" else "${hours}h"
        else -> "${rest}m"
    }
}

/** The web's approximate age (chunk c24136b7.js `i` with `approximate`): "<1m", then the largest whole unit, "5m", "2h", "3d", "1mo", "1y". */
fun ago(elapsed: Duration): String {
    if (elapsed < 1.minutes) return "<1m"
    val minutes = elapsed.inWholeMinutes
    val hours = minutes / 60
    val days = hours / 24
    val months = days / 30
    val years = months / 12
    return when {
        years > 0 -> "${years}y"
        months > 0 -> "${months}mo"
        days > 0 -> "${days}d"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}
