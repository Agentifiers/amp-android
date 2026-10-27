package com.agentifiers.amp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ThreadScheduleTest {
    private val now = Instant.parse("2026-10-27T12:00:00Z")
    private val thread = ThreadId.parse("T-01a16702-3202-73fb-8f52-e4498ccd3721")!!
    private val other = ThreadId.parse("T-01a16738-2ec2-7354-98e9-688bc413f079")!!

    private fun automation(
        id: String,
        status: AutomationStatus = AutomationStatus.Active,
        nextRun: Instant? = null,
        lastRun: Instant? = null,
        on: ThreadId = thread,
        title: String = "Rollout check",
    ) = AutomationInfo(id, on, title, "Every 15 minutes", status, nextRun, lastRun, null)

    @Test
    fun countdownMatchesTheWebFormat() {
        assertEquals("<1m", countdown(59.seconds))
        assertEquals("<1m", countdown((-5).minutes))
        assertEquals("1m", countdown(1.minutes))
        assertEquals("5m", countdown(5.minutes + 59.seconds))
        assertEquals("2h", countdown(2.hours + 30.seconds))
        assertEquals("2h 5m", countdown(2.hours + 5.minutes))
        assertEquals("1d", countdown(1.days + 59.minutes))
        assertEquals("1d 3h", countdown(1.days + 3.hours + 25.minutes))
        assertEquals("12d 23h", countdown(12.days + 23.hours + 59.minutes))
    }

    @Test
    fun agoTakesTheLargestWholeUnit() {
        assertEquals("<1m", ago(59.seconds))
        assertEquals("<1m", ago((-2).minutes))
        assertEquals("1m", ago(1.minutes))
        assertEquals("59m", ago(59.minutes + 59.seconds))
        assertEquals("2h", ago(2.hours + 59.minutes))
        assertEquals("3d", ago(3.days + 23.hours))
        assertEquals("1mo", ago(30.days))
        assertEquals("11mo", ago(359.days))
        assertEquals("1y", ago(360.days))
    }

    @Test
    fun anActiveScheduleCountsDownAndNamesTheLastRun() {
        val schedule = listOf(automation("a", nextRun = now + 5.minutes + 59.seconds, lastRun = now - 2.hours - 10.minutes)).scheduleFor(thread, now)!!
        assertEquals(ScheduleLine("Next run in 5m", "Last run 2h ago", attention = false), schedule.line(now))
        assertEquals(ScheduleLine("Next run in 4m", "Last run 2h ago", attention = false), schedule.line(now + 1.minutes))
        assertEquals(ScheduleLine("Next run in <1m", "Last run 2h ago", attention = false), schedule.line(now + 10.minutes))
    }

    @Test
    fun stoppedSchedulesNameWhy() {
        val paused = listOf(automation("p", AutomationStatus.Paused, lastRun = now - 3.days)).scheduleFor(thread, now)!!
        assertEquals(ScheduleLine("Automation paused", "Last run 3d ago", attention = false), paused.line(now))
        val failed = listOf(automation("f", AutomationStatus.Failed)).scheduleFor(thread, now)!!
        assertEquals(ScheduleLine("Automation paused · last run failed", null, attention = true), failed.line(now))
    }

    @Test
    fun theLineHidesWhenNothingIsScheduledOrTheScheduleFinishedBeforeTheViewOpened() {
        assertNull(emptyList<AutomationInfo>().scheduleFor(thread, now))
        assertNull(listOf(automation("o", nextRun = now + 1.hours, on = other)).scheduleFor(thread, now))
        assertNull(listOf(automation("a")).scheduleFor(thread, now))
        assertNull(listOf(automation("c", AutomationStatus.Completed, lastRun = now - 1.minutes)).scheduleFor(thread, now))
        val ranSinceOpening = listOf(automation("c", AutomationStatus.Completed, lastRun = now + 1.minutes)).scheduleFor(thread, now)!!
        assertEquals(ScheduleLine("Automation completed", "Last run <1m ago", attention = false), ranSinceOpening.line(now + 1.minutes))
    }

    @Test
    fun severalAutomationsResolveEnabledThenSoonestThenTitle() {
        val list = listOf(
            automation("paused", AutomationStatus.Paused, nextRun = now + 1.minutes),
            automation("later", nextRun = now + 2.hours),
            automation("sooner", nextRun = now + 10.minutes),
            automation("unscheduled"),
        )
        assertEquals("sooner", list.scheduleFor(thread, now)?.automation?.id)
        val stopped = listOf(automation("b", AutomationStatus.Paused, title = "Beta"), automation("a", AutomationStatus.Failed, title = "Alpha"))
        assertEquals("a", stopped.scheduleFor(thread, now)?.automation?.id)
    }

    @Test
    fun theLineChangesAtTheNextMinuteOfEitherPart() {
        val schedule = listOf(automation("a", nextRun = now + 5.minutes + 20.seconds, lastRun = now - 45.seconds)).scheduleFor(thread, now)!!
        assertEquals(15.seconds, schedule.nextChange(now))
        assertEquals(5.seconds, schedule.nextChange(now + 10.seconds))
        val countdownOnly = listOf(automation("n", nextRun = now + 5.minutes + 20.seconds)).scheduleFor(thread, now)!!
        assertEquals(20.seconds + 1.milliseconds, countdownOnly.nextChange(now))
        val pastDue = listOf(automation("p", nextRun = now - 1.minutes)).scheduleFor(thread, now)!!
        assertNull(pastDue.nextChange(now))
    }
}
