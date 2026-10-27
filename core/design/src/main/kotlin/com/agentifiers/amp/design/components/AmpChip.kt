package com.agentifiers.amp.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.agentifiers.amp.design.AmpTheme

/**
 * A filter or a picker: a 24dp pill (a minimum, so a larger font grows it) inside a 48dp touch target. A picker passes
 * `trailingIcon = AmpIcons.ChevronDown` and opens its menu from [onClick]. The Tab role is what makes Android report
 * [selected] as `selected` rather than `checked`, which agent-device's `is selected` reads (the same choice as the window
 * bar's arrangement items). A label too long for the room ellipsizes and the icons keep their size; the intrinsic width
 * bounds the Row inside a horizontal scroll, where the weighted label would otherwise get no width. The pill is the web
 * composer's selector pill: a 1dp rim and a 1dp glint along the inside of its top edge.
 */
@Composable
fun AmpChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = AmpTheme.colors
    val shape = AmpTheme.shapes.chip
    val content = if (selected) colors.accents.accent else colors.text.ink
    val glyph = Modifier.size(AmpTheme.metrics.iconSmall)
    val edge = AmpTheme.metrics.divider
    Row(
        modifier
            .testTag(testTag)
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else DisabledAlpha)
            .heightIn(min = AmpTheme.metrics.chip)
            .width(IntrinsicSize.Max)
            .clip(shape)
            .background(if (selected) colors.accents.accentMuted else AmpTheme.surfaceRoles.chip)
            .drawBehind {
                val pill = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
                val lowered = Path().apply { addPath(pill, Offset(0f, edge.toPx())) }
                drawPath(Path().apply { op(pill, lowered, PathOperation.Difference) }, colors.surfaces.glint)
            }
            .border(edge, colors.surfaces.rim, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = AmpTheme.spacing.chipPadX),
        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) Icon(leadingIcon, contentDescription = null, tint = content, modifier = glyph)
        Text(label, style = AmpTheme.type.label, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (trailingIcon != null) Icon(trailingIcon, contentDescription = null, tint = content, modifier = glyph)
    }
}
