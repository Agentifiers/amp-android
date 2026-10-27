@file:OptIn(ExperimentalMaterial3Api::class)

package com.agentifiers.amp.design.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.LocalPaneSpan
import com.agentifiers.amp.design.LocalWindowCreases
import com.agentifiers.amp.design.tagsAsResourceIds

/** Where an overlay sits across the window: its left edge's window x and its width. */
data class PanelSpan(val start: Dp, val width: Dp)

/**
 * The widest stretch of [pane] (window x) between the window's [creases], capped at [maxWidth] and centered in that
 * stretch, so an overlay never crosses a fold. On the TriFold the thread pane gives 382dp at 335 to 717.
 */
fun panelSpan(pane: ClosedRange<Dp>, creases: List<Dp>, maxWidth: Dp): PanelSpan {
    val inside = creases.filter { it > pane.start && it < pane.endInclusive }.sorted()
    val (from, to) = (listOf(pane.start) + inside + pane.endInclusive).zipWithNext().maxBy { (from, to) -> to - from }
    val width = minOf(to - from, maxWidth)
    return PanelSpan(from + (to - from - width) / 2, width)
}

/** [panelSpan] for the pane being composed ([LocalPaneSpan]), or for the whole window outside a pane. */
@Composable
fun currentPanelSpan(maxWidth: Dp): PanelSpan {
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return panelSpan(LocalPaneSpan.current ?: 0.dp..window, LocalWindowCreases.current, maxWidth)
}

/**
 * A modal bottom sheet that stays inside the panel of the pane it opens from ([currentPanelSpan]), where Material's sheet
 * centers on the window and crosses both TriFold creases. It opens fully expanded on the pane surface, and it is its
 * own window, so it carries testTagsAsResourceId.
 */
@Composable
fun AmpSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val span = currentPanelSpan(BottomSheetDefaults.SheetMaxWidth)
    val windowCenter = LocalWindowInfo.current.containerSize.width / 2
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.offset { IntOffset((span.start + span.width / 2).roundToPx() - windowCenter, 0) }.tagsAsResourceIds(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = span.width,
        containerColor = AmpTheme.colors.surfaces.pane,
        content = content,
    )
}
