package com.agentifiers.amp.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.SelectedVisibleRow

/** One tab: its label and the tag its node carries. */
class AmpTab(val label: String, val testTag: String)

/**
 * A row of text tabs in the tab role, 40dp tall at the default font scale: the selected tab in the accent with a 2dp
 * accent bar under its label, the rest in inkMuted. When the tabs overflow, the row scrolls so the selected tab is
 * always whole ([SelectedVisibleRow]). A pane header passes `height = AmpTheme.metrics.paneHeader` and
 * `labelBaseline = AmpTheme.metrics.headerBaseline` so the labels sit on the header baseline beside the other panes'
 * titles; otherwise labels center.
 */
@Composable
fun AmpTabRow(
    tabs: List<AmpTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = AmpTheme.metrics.tab,
    labelBaseline: Dp? = null,
    tabModifier: (index: Int) -> Modifier = { Modifier },
) {
    val spacing = AmpTheme.spacing
    val accent = AmpTheme.colors.accents.accent
    val indicator = AmpTheme.metrics.tabIndicator
    SelectedVisibleRow(selected, tabs.size, modifier.heightIn(min = height), edge = spacing.paneX - spacing.tabPadX, spacing = spacing.tabGap) { index, place ->
        val tab = tabs[index]
        val chosen = index == selected
        Box(
            place
                .then(tabModifier(index))
                .heightIn(min = height)
                .testTag(tab.testTag)
                .selectable(selected = chosen, role = Role.Tab, onClick = { onSelect(index) })
                .drawBehind {
                    if (chosen) {
                        val bar = indicator.toPx()
                        drawRoundRect(accent, Offset(0f, size.height - bar), Size(size.width, bar), CornerRadius(bar, bar))
                    }
                }
                .padding(horizontal = spacing.tabPadX),
            contentAlignment = if (labelBaseline == null) Alignment.Center else Alignment.TopStart,
        ) {
            Text(
                tab.label,
                style = AmpTheme.type.tab,
                color = if (chosen) accent else AmpTheme.colors.text.inkMuted,
                maxLines = 1,
                modifier = if (labelBaseline == null) Modifier else Modifier.paddingFromBaseline(top = labelBaseline),
            )
        }
    }
}
