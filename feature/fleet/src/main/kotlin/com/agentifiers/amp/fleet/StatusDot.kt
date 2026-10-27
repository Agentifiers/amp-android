package com.agentifiers.amp.fleet

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.theme.StatusTokens
import com.agentifiers.amp.model.FleetStatus
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos

internal enum class DotMark { Fill, Outline, Pulse, None }

internal data class StatusDot(val color: Color, val mark: DotMark)

/** The one status-to-dot table, after screens.md "Status text". The status word takes the dot's color. */
internal fun FleetStatus.dot(status: StatusTokens): StatusDot = when (this) {
    is FleetStatus.Waiting -> StatusDot(status.needsYou, DotMark.Fill)
    FleetStatus.Working, FleetStatus.Compacting -> StatusDot(status.working, DotMark.Pulse)
    FleetStatus.Done -> StatusDot(status.done, DotMark.Fill)
    FleetStatus.Idle, FleetStatus.Inactive -> StatusDot(status.idle, DotMark.None)
    FleetStatus.Error -> StatusDot(status.error, DotMark.Outline)
    is FleetStatus.Reported -> StatusDot(status.idle, DotMark.Fill)
}

internal val FleetStatus.dot: StatusDot
    @Composable get() = dot(AmpTheme.colors.status)

/** One breath of the working dot. */
internal const val PULSE_PERIOD_MS = 2_400L

/** The pulse changes 10 times a second, so a working row costs the window 10 frames a second instead of 60. */
internal const val PULSE_STEP_MS = 100L

/** The dot's opacity at wall-clock [nowMs]: full at the start of each breath, 0.35 halfway through. */
internal fun pulseAlpha(nowMs: Long): Float {
    val phase = (nowMs % PULSE_PERIOD_MS).toDouble() / PULSE_PERIOD_MS
    return (0.35 + 0.65 * (0.5 + 0.5 * cos(2 * PI * phase))).toFloat()
}

/** [modifier] sets the size; a status with no dot still takes that space so rows stay aligned. */
@Composable
internal fun StatusDot(status: FleetStatus, modifier: Modifier) {
    val dot = status.dot
    when (dot.mark) {
        DotMark.Fill -> Box(modifier.clip(CircleShape).background(dot.color))
        DotMark.Outline -> Box(modifier.border(1.5.dp, dot.color, CircleShape))
        DotMark.Pulse -> {
            val alpha = remember { mutableFloatStateOf(pulseAlpha(System.currentTimeMillis())) }
            // Steps land on a shared wall-clock grid, so every working dot on screen changes in the same frame.
            LaunchedEffect(Unit) {
                while (true) {
                    val now = System.currentTimeMillis()
                    alpha.floatValue = pulseAlpha(now)
                    delay(PULSE_STEP_MS - now % PULSE_STEP_MS)
                }
            }
            Box(modifier.graphicsLayer { this.alpha = alpha.floatValue }.clip(CircleShape).background(dot.color))
        }
        DotMark.None -> Box(modifier)
    }
}
