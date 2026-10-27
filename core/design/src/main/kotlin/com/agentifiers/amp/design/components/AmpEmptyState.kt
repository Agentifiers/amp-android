package com.agentifiers.amp.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.LocalPaneCreases
import kotlin.math.roundToInt

/**
 * What a pane shows when it has nothing: an icon, a one-line [title], a line of [guidance], and an optional [action] such
 * as an AmpButton. It fills what it is given and sits in one panel of it, the widest stretch between the
 * [LocalPaneCreases], never across a fold. The icon, title, and guidance center on one line [EmptyStateAnchor] of the way
 * down the window, the same line in every pane whatever its header, so empty states side by side line up; the action
 * hangs below, its children stacked and centered. With no height bound, as in a lazy list item not given
 * fillParentMaxSize, it sits at the top.
 */
@Composable
fun AmpEmptyState(
    icon: ImageVector,
    title: String,
    guidance: String,
    testTag: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = AmpTheme.colors
    val spacing = AmpTheme.spacing
    val creases = LocalPaneCreases.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    Layout(
        content = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Icon(icon, contentDescription = null, tint = colors.text.inkMuted, modifier = Modifier.size(32.dp))
                Text(
                    title,
                    style = AmpTheme.type.paneTitle,
                    color = colors.text.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = spacing.xs),
                )
                Text(guidance, style = AmpTheme.type.body, color = colors.text.inkMuted, textAlign = TextAlign.Center)
            }
            if (action != null) {
                Column(
                    Modifier.padding(top = spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) { action() }
            }
        },
        modifier = modifier.fillMaxSize().testTag(testTag),
    ) { measurables, constraints ->
        val margin = spacing.xl.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else measurables[0].maxIntrinsicWidth(Constraints.Infinity) + 2 * margin
        val panel = panelSpan(0.dp..width.toDp(), creases, maxWidth = width.toDp())
        val inner = Constraints(maxWidth = (panel.width.roundToPx() - 2 * margin).coerceAtLeast(0))
        val block = measurables[0].measure(inner)
        val hanging = measurables.getOrNull(1)?.measure(inner)
        val total = block.height + (hanging?.height ?: 0)
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else total + 2 * margin
        val center = panel.start.roundToPx() + panel.width.roundToPx() / 2
        layout(width, height) {
            val ownTop = coordinates?.positionInWindow()?.y ?: 0f
            val anchor = if (constraints.hasBoundedHeight) windowHeight * EmptyStateAnchor - ownTop else 0f
            val top = (anchor - block.height / 2f).roundToInt().coerceIn(margin, maxOf(margin, height - total - margin))
            block.place(center - block.width / 2, top)
            hanging?.place(center - hanging.width / 2, top + block.height)
        }
    }
}

/** Where an empty state's icon, title, and guidance center, as a fraction of the window's height from its top. */
private const val EmptyStateAnchor = 0.4f
