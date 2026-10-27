package com.agentifiers.amp.design.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.icons.AmpIcons

/**
 * A two-line list row, 52dp at the default font scale and taller when the text needs it: a [leading] slot centered on
 * the title line (usually [AmpStatusDot]), the [title] in rowTitle with [meta] (an age or a count) after it, an optional
 * [secondLine] in the meta size, and [trailing] buttons such as [AmpRowMore]. [selected] fills the row with the
 * selected surface.
 *
 * A [titleAction] sits on the title line between the title and [meta], so the meta keeps the column it has in rows
 * without one and the title gets whatever the action leaves. The second line runs under both to [trailing]. The title
 * line then centers 24dp from the row's top, so the action's 48dp target and the trailing buttons, which align to the
 * top, stay inside the row and center on the title.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AmpListRow(
    title: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    meta: String? = null,
    titleStyle: TextStyle = AmpTheme.type.rowTitle,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    secondLine: (@Composable RowScope.() -> Unit)? = null,
    titleAction: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val spacing = AmpTheme.spacing
    val titleLine = with(LocalDensity.current) { titleStyle.lineHeight.toDp() }
    val textPadding = if (titleAction == null) Modifier.padding(vertical = spacing.xs) else Modifier.padding(top = (AmpTheme.metrics.touch - titleLine) / 2, bottom = spacing.xs)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = AmpTheme.metrics.row)
            .clip(AmpTheme.shapes.control)
            .background(if (selected) AmpTheme.surfaceRoles.selected else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag(testTag)
            // Trailing buttons bring their own 48dp target inset; bare text would otherwise touch the row's edge.
            .padding(start = spacing.rowPadX, end = if (trailing == null) spacing.rowPadX else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading and text center as one block; inside it the leading slot is one title line tall at the top.
        Row(Modifier.weight(1f).then(textPadding)) {
            if (leading != null) {
                Box(Modifier.heightIn(min = titleLine), contentAlignment = Alignment.Center) { leading() }
                Box(Modifier.width(spacing.rowDotGap))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.rowTitleMetaGap)) {
                    Text(title, Modifier.weight(1f), style = titleStyle, color = AmpTheme.colors.text.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (titleAction != null) Box(Modifier.overhang(titleLine)) { titleAction() }
                    if (meta != null) Text(meta, style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted, maxLines = 1)
                }
                if (secondLine != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sectionBottom), content = secondLine)
                }
            }
        }
        if (trailing != null) {
            if (titleAction == null) trailing() else Row(Modifier.align(Alignment.Top), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

/** An 8dp status dot in [color], for an [AmpListRow]'s leading slot. */
@Composable
fun AmpStatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(AmpTheme.metrics.dot).background(color, CircleShape))
}

/** A row's quiet overflow button: a 14dp ⋮ in inkFaint inside a 48dp target. */
@Composable
fun AmpRowMore(onClick: () -> Unit, testTag: String, contentDescription: String = "More actions") {
    AmpIconButton(AmpIcons.EllipsisVertical, contentDescription, onClick, testTag, glyph = AmpTheme.metrics.iconSmall, tint = AmpTheme.colors.text.inkFaint)
}
