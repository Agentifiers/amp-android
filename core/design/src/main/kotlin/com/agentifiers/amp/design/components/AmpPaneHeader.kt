package com.agentifiers.amp.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.LayoutTags
import com.agentifiers.amp.design.icons.AmpIcons

/** A header control. When the header runs out of room it moves into More Actions and carries [menuTestTag] there. */
class AmpHeaderAction(
    val icon: ImageVector,
    val label: String,
    val testTag: String,
    val menuTestTag: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** An item that always lives in More Actions. */
class AmpMenuItem(
    val label: String,
    val testTag: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** How many header actions stay in the row, in priority order, and whether More Actions shows. */
data class HeaderFit(val visible: Int, val more: Boolean)

private fun headerStart(navigation: Boolean): Dp = if (navigation) AmpTheme.spacing.xs else AmpTheme.spacing.paneX

/**
 * screens.md's title bar rule: the title keeps `AmpTheme.metrics.titleMin`, or its own [title] width when that is
 * shorter, and actions that do not fit beside it leave lowest priority first for More Actions. An action is either
 * a full 48dp target in the row or a menu item, never a narrower hit area.
 */
fun fitHeader(width: Dp, actions: Int, navigation: Boolean, menu: Boolean, title: Dp = AmpTheme.metrics.titleMin): HeaderFit {
    val sizes = AmpTheme.metrics
    val fixed = headerStart(navigation) + AmpTheme.spacing.xs + (if (navigation) sizes.touch else 0.dp) + minOf(title, sizes.titleMin)
    val slots = ((width - fixed) / sizes.touch).toInt().coerceAtLeast(0)
    return if (actions + (if (menu) 1 else 0) <= slots) {
        HeaderFit(visible = actions, more = menu)
    } else {
        HeaderFit(visible = (slots - 1).coerceAtLeast(0), more = true)
    }
}

/** The width a header's title and subtitle take on one line, which [fitHeader] needs to know. */
@Composable
internal fun rememberTitleWidth(title: String, subtitle: String?, titleStyle: TextStyle = AmpTheme.type.paneTitle): Dp {
    val subtitleStyle = AmpTheme.type.meta
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(title, subtitle, titleStyle, density) {
        val widest = maxOf(
            measurer.measure(title, titleStyle, maxLines = 1).size.width,
            subtitle?.let { measurer.measure(it, subtitleStyle, maxLines = 1).size.width } ?: 0,
        )
        with(density) { widest.toDp() }
    }
}

/**
 * A pane's title and optional [subtitle], centered on the header row beside its icons. Only the thread title with its
 * status line and the machine tab labels sit on `AmpTheme.metrics.headerBaseline` instead.
 */
@Composable
private fun AmpHeaderTitle(title: String, subtitle: String?, modifier: Modifier = Modifier, style: TextStyle = AmpTheme.type.paneTitle) {
    Column(modifier) {
        Text(title, style = style, color = AmpTheme.colors.text.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A pane header, 48dp at the default font scale and taller only when the text needs it: an optional [navigation] slot
 * for one AmpIconButton such as Back or Close, a one-line title centered on the row, an optional [subtitle],
 * then [actions] in priority order as 32dp icon buttons in 48dp targets. Actions that do not fit and every [menu] item
 * open from More Actions, tagged [moreTestTag]. [kind] tags the header `pane-header-<kind>` for the alignment check.
 */
@Composable
fun AmpPaneHeader(
    title: String,
    testTag: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: List<AmpHeaderAction> = emptyList(),
    menu: List<AmpMenuItem> = emptyList(),
    moreTestTag: String = "$testTag-more",
    titleStyle: TextStyle = AmpTheme.type.paneTitle,
    kind: String? = null,
) {
    val titleWidth = rememberTitleWidth(title, subtitle, titleStyle)
    AmpHeaderRow(modifier, testTag, kind) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fit = fitHeader(maxWidth, actions.size, navigation != null, menu.isNotEmpty(), titleWidth)
            // The row keeps the header's height even without a 48dp action, so a title centers on the same line in every pane.
            Row(
                Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.paneHeader).padding(start = headerStart(navigation != null), end = AmpTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navigation?.invoke()
                AmpHeaderTitle(title, subtitle, Modifier.weight(1f), titleStyle)
                actions.take(fit.visible).forEach {
                    AmpIconButton(it.icon, it.label, it.onClick, it.testTag, enabled = it.enabled)
                }
                if (fit.more) MoreActions(moved = actions.drop(fit.visible), menu = menu, testTag = moreTestTag)
            }
        }
    }
}

/**
 * The frame every pane header shares: full width, at least `AmpTheme.metrics.paneHeader` tall, tagged [testTag] and,
 * when [kind] is set, wrapped in a node tagged `pane-header-<kind>` that scripts/check-alignment.sh measures. Headers
 * with their own layout (the sidebar, the thread, the machine tabs) draw inside it so all three line up.
 */
@Composable
fun AmpHeaderRow(modifier: Modifier, testTag: String?, kind: String?, content: @Composable () -> Unit) {
    val tagged = kind?.let { Modifier.testTag(LayoutTags.paneHeader(it)) } ?: Modifier
    Box(modifier.fillMaxWidth().then(tagged)) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.paneHeader).then(testTag?.let { Modifier.testTag(it) } ?: Modifier),
            contentAlignment = Alignment.CenterStart,
        ) { content() }
    }
}

@Composable
private fun MoreActions(moved: List<AmpHeaderAction>, menu: List<AmpMenuItem>, testTag: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        AmpIconButton(AmpIcons.EllipsisVertical, "More actions", { open = true }, testTag)
        AmpDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val sectioned = moved.isNotEmpty() && menu.isNotEmpty()
            if (sectioned) {
                Text(
                    "In the title bar",
                    style = AmpTheme.type.label,
                    color = AmpTheme.colors.text.inkMuted,
                    modifier = Modifier.padding(horizontal = AmpTheme.spacing.md, vertical = AmpTheme.spacing.sm),
                )
            }
            moved.forEach { MenuRow(it.label, it.menuTestTag, it.icon, it.enabled) { open = false; it.onClick() } }
            if (sectioned) HorizontalDivider(color = AmpTheme.colors.surfaces.line)
            menu.forEach { MenuRow(it.label, it.testTag, it.icon, it.enabled) { open = false; it.onClick() } }
        }
    }
}

@Composable
private fun MenuRow(label: String, testTag: String, icon: ImageVector?, enabled: Boolean, onClick: () -> Unit) {
    val colors = AmpTheme.colors
    DropdownMenuItem(
        text = { Text(label, style = AmpTheme.type.body) },
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
        enabled = enabled,
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, Modifier.size(AmpTheme.metrics.icon)) } },
        colors = MenuDefaults.itemColors(
            textColor = colors.text.ink,
            leadingIconColor = colors.text.inkMuted,
            disabledTextColor = colors.text.inkMuted.copy(alpha = DisabledAlpha),
            disabledLeadingIconColor = colors.text.inkMuted.copy(alpha = DisabledAlpha),
        ),
    )
}
