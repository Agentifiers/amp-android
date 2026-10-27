package com.agentifiers.amp.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import com.agentifiers.amp.design.AmpTheme

/**
 * Something to act on in one tap, such as a reply the agent suggested: AmpChip's pill and surface inside a 48dp touch
 * target, with a button role and a label that wraps rather than ellipsizes, so a long suggestion reads in full. Ink, not
 * accent, so it never competes with the pane's primary.
 */
@Composable
fun AmpSuggestionChip(
    label: AnnotatedString,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val colors = AmpTheme.colors
    val spacing = AmpTheme.spacing
    Row(
        modifier
            .testTag(testTag)
            .minimumInteractiveComponentSize()
            .heightIn(min = AmpTheme.metrics.chip)
            .clip(AmpTheme.shapes.chip)
            .background(AmpTheme.surfaceRoles.chip)
            .combinedClickable(role = Role.Button, onClickLabel = onClickLabel, onLongClickLabel = onLongClickLabel, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = spacing.chipPadX, vertical = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) Icon(leadingIcon, contentDescription = null, tint = colors.text.ink, modifier = Modifier.size(AmpTheme.metrics.iconSmall))
        Text(label, style = AmpTheme.type.label, color = colors.text.ink)
    }
}
