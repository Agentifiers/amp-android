package com.agentifiers.amp.ui

import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.ShellTags
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.layout.Creases
import kotlin.math.roundToInt

/** Debug-only edits to the layout's creases. [place] commits crease `index` at a dp position. */
internal class CreaseEditor(val place: (index: Int, dp: Float) -> Unit, val reset: () -> Unit)

private const val CONTROL_WIDTH_DP = 168f

/**
 * Dashed lines are the creases the layout uses, each with a draggable handle at mid-height and 1 px nudge
 * buttons. Dotted lines are what WindowManager reported. Only the handles and buttons take touches.
 */
@Composable
internal fun CreaseOverlay(creases: Creases, editor: CreaseEditor) {
    val density = LocalDensity.current.density
    val currentEditor by rememberUpdatedState(editor)
    var drafts by remember(creases.positionsDp) { mutableStateOf(creases.positionsDp) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag(ShellTags.creaseOverlay)) {
        val mid = maxHeight.value / 2
        val crease = AmpTheme.colors.debug.crease
        val reported = AmpTheme.colors.text.ink
        Canvas(Modifier.fillMaxSize()) {
            val dashed = PathEffect.dashPathEffect(floatArrayOf(24f, 14f))
            val dotted = PathEffect.dashPathEffect(floatArrayOf(4f, 10f))
            drafts.forEach { x ->
                drawLine(crease, Offset(x * density, 0f), Offset(x * density, size.height), strokeWidth = 3f, pathEffect = dashed)
            }
            creases.reportedDp.forEach { x ->
                drawLine(reported, Offset(x * density, 0f), Offset(x * density, size.height), strokeWidth = 4f, pathEffect = dotted)
            }
        }
        creases.reportedDp.forEach { x ->
            Text(
                "reported ${x.toInt()}dp", style = AmpTheme.type.meta, color = AmpTheme.colors.text.ink,
                modifier = Modifier.offset(x = (x + 4).dp, y = 72.dp).background(AmpTheme.colors.surfaces.canvas).padding(2.dp),
            )
        }
        drafts.forEachIndexed { index, x ->
            val px = (x * density).roundToInt()
            Column(
                Modifier.offset(x = (x - CONTROL_WIDTH_DP / 2).dp, y = (mid - 24).dp).width(CONTROL_WIDTH_DP.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(AmpTheme.metrics.touch).clip(CircleShape).background(AmpTheme.colors.debug.crease.copy(alpha = 0.35f)).border(2.dp, AmpTheme.colors.debug.crease, CircleShape)
                        .testTag(ShellTags.creaseHandle(index))
                        .pointerInput(index, density, creases.positionsDp) {
                            detectHorizontalDragGestures(
                                onDragEnd = { currentEditor.place(index, drafts[index]) },
                                onDragCancel = { drafts = creases.positionsDp },
                            ) { change, dragPx ->
                                change.consume()
                                drafts = drafts.toMutableList().also { it[index] += dragPx / density }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("⇔", style = AmpTheme.type.headline, color = AmpTheme.colors.text.ink) }
                Text(
                    "${CreaseCalibrator.formatDp(px / density)} dp · $px px",
                    style = AmpTheme.type.label, color = AmpTheme.colors.debug.crease,
                    modifier = Modifier.padding(top = AmpTheme.spacing.xs).background(AmpTheme.colors.surfaces.canvas, RoundedCornerShape(4.dp))
                        .padding(horizontal = AmpTheme.spacing.xs, vertical = 2.dp)
                        .testTag(ShellTags.creaseLabel(index)),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
                    NudgeButton("−", ShellTags.creaseNudgeMinus(index)) { editor.place(index, creases.positionsDp[index] - 1 / density) }
                    NudgeButton("+", ShellTags.creaseNudgePlus(index)) { editor.place(index, creases.positionsDp[index] + 1 / density) }
                }
            }
        }
    }
}

@Composable
private fun NudgeButton(label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(AmpTheme.metrics.touch).clip(RoundedCornerShape(8.dp)).background(AmpTheme.colors.surfaces.raised)
            .border(AmpTheme.metrics.divider, AmpTheme.colors.debug.crease, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = AmpTheme.type.headline, color = AmpTheme.colors.text.ink) }
}
