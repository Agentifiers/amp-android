package com.agentifiers.amp.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.agentifiers.amp.design.AmpTheme

/**
 * A [glyph]-sized icon on a 32dp visual inside a 48dp target. [contentDescription] is required and has no default: it
 * is what TalkBack reads, what agent-device matches on the node tagged [testTag], and the tooltip a long press or a
 * mouse hover shows. [primary] fills the 32dp circle with the accent, for the one main action of a row, such as Send;
 * disabled, it reads as off, a `raised` circle with an `inkMuted` glyph, as the thread composer's Send does.
 * A row's quiet secondary button passes `glyph = AmpTheme.metrics.iconSmall` and `tint = AmpTheme.colors.text.inkFaint`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmpIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    tint: Color = if (primary && enabled) AmpTheme.colors.text.onAccent else AmpTheme.colors.text.inkMuted,
    glyph: Dp = AmpTheme.metrics.icon,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(contentDescription, style = AmpTheme.type.label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        val metrics = AmpTheme.metrics
        val off = primary && !enabled
        val alpha = if (enabled || off) 1f else DisabledAlpha
        // The fill sits on the clickable node: a shaped background on a child is a semantics child, which splits the tag from the label.
        Box(
            Modifier
                .size(metrics.touch)
                .testTag(testTag)
                .clickable(
                    interactionSource = null,
                    indication = ripple(bounded = false, radius = metrics.iconButton / 2),
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics { this.contentDescription = contentDescription }
                .padding((metrics.touch - metrics.iconButton) / 2)
                .then(if (primary) Modifier.background(if (off) AmpTheme.colors.surfaces.raised else AmpTheme.colors.accents.accent, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint.copy(alpha = tint.alpha * alpha), modifier = Modifier.size(glyph))
        }
    }
}
