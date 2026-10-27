package com.agentifiers.amp.fleet

import androidx.compose.ui.graphics.toArgb
import com.agentifiers.amp.design.theme.Forest
import com.agentifiers.amp.model.Answerability
import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.GateSource
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.ToolApprovalGate
import com.agentifiers.amp.model.ToolCallId
import com.agentifiers.amp.model.ToolName
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

class StatusDotTest {
    private fun StatusDot.described() = "#%06X".format(color.toArgb() and 0xFFFFFF) to mark

    private val approval = ToolApprovalGate(
        GateId("toolu_01"), Instant.parse("2026-10-24T15:40:00Z"), Answerability.Answerable, GateSource.BuiltIn,
        ToolCallId("toolu_01"), ToolName("Bash"), "rm -rf build", JsonValue.Null,
    )

    @Test
    fun `each status word has the dot screens md gives it`() {
        val statuses = listOf(
            FleetStatus.NeedsApproval(approval),
            FleetStatus.NeedsInput("Bash: ./gradlew migrate"),
            FleetStatus.Working,
            FleetStatus.Compacting,
            FleetStatus.Done,
            FleetStatus.Idle,
            FleetStatus.Inactive,
            FleetStatus.Error,
            FleetStatus.Reported("unknown"),
        )
        assertEquals(
            listOf(
                "needs approval" to ("#D67670" to DotMark.Fill),
                "needs input" to ("#D67670" to DotMark.Fill),
                "working" to ("#D6A038" to DotMark.Pulse),
                "compacting" to ("#D6A038" to DotMark.Pulse),
                "done" to ("#6CA0E0" to DotMark.Fill),
                "idle" to ("#8E968E" to DotMark.None),
                "inactive" to ("#8E968E" to DotMark.None),
                "error" to ("#D67670" to DotMark.Outline),
                "unknown" to ("#8E968E" to DotMark.Fill),
            ),
            statuses.map { it.word to it.dot(Forest.status).described() },
        )
    }

    @Test
    fun `the working dot breathes once every 2_4 s`() {
        assertEquals(
            listOf(1.0f, 0.675f, 0.35f, 0.675f, 1.0f),
            listOf(0L, 600L, 1_200L, 1_800L, 2_400L).map { Math.round(pulseAlpha(it) * 1000) / 1000f },
        )
    }
}
