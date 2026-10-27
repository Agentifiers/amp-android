package com.agentifiers.amp.design.organize

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import kotlin.time.Instant

class OrganizeRulesTest {
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun eightAmIsLaterTodayBeforeEightAndTomorrowAfter() {
        assertEquals(Instant.parse("2026-10-23T12:00:00Z"), nextEightAm(Instant.parse("2026-10-23T10:00:00Z"), newYork))
        assertEquals(Instant.parse("2026-10-24T12:00:00Z"), nextEightAm(Instant.parse("2026-10-23T13:00:00Z"), newYork))
        assertEquals(Instant.parse("2026-10-24T12:00:00Z"), nextEightAm(Instant.parse("2026-10-23T12:00:00Z"), newYork))
    }

    @Test
    fun mondayMorningIsTheNextMondayAtEightLocal() {
        assertEquals(Instant.parse("2026-10-26T12:00:00Z"), nextMondayMorning(Instant.parse("2026-10-23T10:00:00Z"), newYork))
        assertEquals(Instant.parse("2026-10-26T12:00:00Z"), nextMondayMorning(Instant.parse("2026-10-26T11:00:00Z"), newYork))
        // Daylight saving ends on 2026-11-01 in New York, so the following Monday's 08:00 is 13:00 UTC.
        assertEquals(Instant.parse("2026-11-02T13:00:00Z"), nextMondayMorning(Instant.parse("2026-10-26T12:00:00Z"), newYork))
    }

    @Test
    fun labelInputKeepsOnlyLowercaseAlphanumericsAndHyphens() {
        assertEquals("bug-fix2", normalizeLabelInput("Bug-Fix_2 !"))
        assertEquals("a".repeat(32), normalizeLabelInput("A".repeat(40)))
    }
}
