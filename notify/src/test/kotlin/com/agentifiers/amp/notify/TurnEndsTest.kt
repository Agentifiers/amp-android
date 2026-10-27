package com.agentifiers.amp.notify

import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.sync.api.PushEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import kotlin.time.Instant

class TurnEndsTest {
    private val thread = ThreadId.parse("T-01a16702-3202-73fb-8f52-e4498ccd3721")!!
    private val since = Instant.parse("2026-10-23T04:26:00Z")

    private fun summary(status: FleetStatus, reply: String?, ended: String?) = ThreadSummary(
        thread, "Rename add to add_numbers", status, "android-spike",
        lastReply = reply?.let(::MessageId), lastTurnEndedAt = ended?.let(Instant::parse),
    )

    @Test
    fun `the tag is the one Amp's thread_idle push carries`() {
        assertEquals(
            "amp-thread-T-01a16702-3202-73fb-8f52-e4498ccd3721-idle-M-034kiP8AxWxnVnvjIMJRlJ",
            TurnEnds.tag(thread, MessageId("M-034kiP8AxWxnVnvjIMJRlJ")),
        )
    }

    @Test
    fun `a turn from working through streaming to idle finishes once it is idle, under its last reply`() {
        val turn = listOf(
            summary(FleetStatus.Working, "M-034kiAak6S0JQZzZ1yZHxo", "2026-10-23T04:27:06.179Z"),
            summary(FleetStatus.Working, "M-034kiAfy1rKdVQ87sMxBdO", "2026-10-23T04:27:06.179Z"),
            summary(FleetStatus.Done, "M-034kiAfy1rKdVQ87sMxBdO", "2026-10-23T04:27:09.248Z"),
        )
        assertEquals(
            listOf(null, null, PushEvent.ThreadIdle(thread, "amp-thread-T-01a16702-3202-73fb-8f52-e4498ccd3721-idle-M-034kiAfy1rKdVQ87sMxBdO", "Rename add to add_numbers", "")),
            turn.map { TurnEnds.finished(it, since) },
        )
        assertEquals(listOf(true, true, false), turn.map(TurnEnds::working))
    }

    @Test
    fun `an idle read with no reply yet, or a turn that ended before the checks began, posts nothing`() {
        assertEquals(null, TurnEnds.finished(summary(FleetStatus.Idle, null, "2026-10-23T04:26:15.555Z"), since))
        assertEquals(null, TurnEnds.finished(summary(FleetStatus.Idle, "M-034khTplTkVpTIedS5znj6", "2026-10-23T03:58:58.869Z"), since))
        assertEquals(null, TurnEnds.finished(summary(FleetStatus.Idle, "M-034khTplTkVpTIedS5znj6", null), since))
    }

    @Test
    fun `an idle thread that is read, unread, failed, or compacting`() {
        val ended = "2026-10-23T04:28:11.894Z"
        assertEquals(
            listOf(
                "amp-thread-T-01a16702-3202-73fb-8f52-e4498ccd3721-idle-M-034kiCEn9EejZYUFnelYwW",
                "amp-thread-T-01a16702-3202-73fb-8f52-e4498ccd3721-idle-M-034kiCEn9EejZYUFnelYwW",
                null,
                null,
            ),
            listOf(FleetStatus.Idle, FleetStatus.Done, FleetStatus.Error, FleetStatus.Compacting)
                .map { TurnEnds.finished(summary(it, "M-034kiCEn9EejZYUFnelYwW", ended), since)?.tag },
        )
        assertEquals(true, TurnEnds.working(summary(FleetStatus.Compacting, null, null)))
    }

    @Test
    fun `a waiting dialog is a Needs you for the day and neither a finish nor a running turn`() {
        val waiting = summary(FleetStatus.Reported("action-required"), "M-034kiCEn9EejZYUFnelYwW", "2026-10-23T04:28:11.894Z")
        assertEquals("amp-thread-T-01a16702-3202-73fb-8f52-e4498ccd3721-action-required-2026-10-23", TurnEnds.needsYouTag(waiting, LocalDate.of(2026, 10, 23)))
        assertEquals(null, TurnEnds.finished(waiting, since))
        assertEquals(false, TurnEnds.working(waiting))
        assertEquals(null, TurnEnds.needsYouTag(summary(FleetStatus.Working, null, null), LocalDate.of(2026, 10, 23)))
    }
}
