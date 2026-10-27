package com.agentifiers.amp.design

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp

/**
 * The vertical creases that cross the pane being composed, as x offsets from the pane's own left edge. Empty when
 * no crease crosses it. The window shell provides it per column, so a pane can put a boundary on a fold without
 * seeing window geometry.
 */
val LocalPaneCreases: ProvidableCompositionLocal<List<Dp>> = compositionLocalOf { emptyList() }

/** The window's vertical creases as x offsets from the window's left edge, for popups, which place in window coordinates. */
val LocalWindowCreases: ProvidableCompositionLocal<List<Dp>> = compositionLocalOf { emptyList() }

/**
 * The window x range of the pane being composed. The window shell provides it per column; null outside a column, as for
 * the command palette, where a sheet takes the whole window.
 */
val LocalPaneSpan: ProvidableCompositionLocal<ClosedRange<Dp>?> = compositionLocalOf { null }
