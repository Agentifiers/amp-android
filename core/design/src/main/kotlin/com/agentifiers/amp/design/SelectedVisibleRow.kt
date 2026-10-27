package com.agentifiers.amp.design

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt

/** One item's horizontal extent in the scrolled content, in px, end exclusive. */
internal data class Span(val start: Int, val end: Int)

/**
 * The scroll offset that shows item [selected] whole, the item before it whole, and [peek] px of the item after it.
 * The offset is always 0 or an item's start, so no label loses its first letters; only the trailing edge cuts a
 * label, at its end. Of the offsets that work, the one nearest [current] wins. A viewport too narrow for the
 * neighbors shows the selected item alone. The offset can pass the content's end; the row pads the gap.
 */
internal fun scrollShowing(current: Int, viewport: Int, spans: List<Span>, selected: Int, peek: Int): Int {
    val item = spans[selected]
    val left = spans.getOrNull(selected - 1)?.start ?: item.start
    val right = spans.getOrNull(selected + 1)?.let { minOf(it.start + peek, it.end) } ?: item.end
    val starts = listOf(0) + spans.take(selected + 1).map { it.start }
    fun nearest(low: Int, high: Int): Int? = starts.filter { it in low..high }.minByOrNull { abs(it - current) }
    return nearest(right - viewport, left) ?: nearest(item.end - viewport, item.start) ?: item.start
}

/**
 * A row of tabs that scrolls when it overflows its pane and keeps the selected tab readable per [scrollShowing].
 * [item] draws item `index` and must apply the modifier it is given.
 */
@Composable
fun SelectedVisibleRow(
    selected: Int,
    count: Int,
    modifier: Modifier = Modifier,
    edge: Dp = 0.dp,
    spacing: Dp = 0.dp,
    item: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    val scroll = rememberScrollState()
    val spans = remember(count) { mutableStateListOf<Span?>().apply { repeat(count) { add(null) } } }
    var slack by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val edgePx = with(density) { edge.roundToPx() }
    val peek = with(density) { NEXT_ITEM_PEEK.roundToPx() }
    LaunchedEffect(selected, spans) {
        snapshotFlow { spans.toList() to scroll.viewportSize }.collectLatest { (laid, viewport) ->
            val placed = laid.filterNotNull().takeIf { it.size == laid.size && selected in it.indices } ?: return@collectLatest
            if (viewport == 0) return@collectLatest
            val target = scrollShowing(Snapshot.withoutReadObservation { scroll.value }, viewport, placed, selected, peek)
            val needed = (target + viewport - (placed.last().end + edgePx)).coerceAtLeast(0)
            if (needed > slack) slack = needed
            snapshotFlow { scroll.maxValue }.first { it >= target }
            scroll.animateScrollTo(target)
            slack = needed
        }
    }
    Row(
        modifier.horizontalScroll(scroll).padding(horizontal = edge).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            item(
                index,
                Modifier.onPlaced { placed ->
                    val start = edgePx + placed.positionInParent().x.roundToInt()
                    val span = Span(start, start + placed.size.width)
                    if (index < spans.size && spans[index] != span) spans[index] = span
                },
            )
        }
        if (slack > 0) Spacer(Modifier.width(with(density) { slack.toDp() }))
    }
}

/** Enough of the next tab to read its first letters past a Material tab's 16dp inset. */
private val NEXT_ITEM_PEEK = 40.dp
