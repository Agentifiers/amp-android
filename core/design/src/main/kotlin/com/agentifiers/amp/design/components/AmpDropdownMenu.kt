package com.agentifiers.amp.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.LocalWindowCreases
import com.agentifiers.amp.design.tagsAsResourceIds

/**
 * A popup menu on the overlay surface with a 1dp line border and a level 2 shadow, so it stands off the pane under it
 * (Material's default container is the pane color). It opens inside the panel its anchor sits in, between the
 * [LocalWindowCreases] on either side, where Material's menu keeps only to the window and can straddle a fold. The menu
 * is its own window, so it carries testTagsAsResourceId.
 */
@Composable
fun AmpDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = expanded
    if (!shown.currentState && !shown.targetState) return
    val density = LocalDensity.current
    val creases = LocalWindowCreases.current
    val area = popupArea()
    val position = remember(creases, density, area.top, area.bottom) {
        with(density) { PanelMenuPosition(creases.map { it.roundToPx() }, area.top, area.bottom) }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            shown,
            enter = fadeIn(tween(InMillis)) + scaleIn(tween(InMillis), initialScale = ClosedScale, transformOrigin = TransformOrigin(0.5f, 0f)),
            exit = fadeOut(tween(OutMillis)),
        ) {
            Surface(
                shape = AmpTheme.shapes.control,
                color = AmpTheme.colors.surfaces.overlay,
                shadowElevation = MenuDefaults.ShadowElevation,
                border = BorderStroke(AmpTheme.metrics.divider, AmpTheme.colors.surfaces.line),
            ) {
                Column(
                    Modifier.tagsAsResourceIds().padding(vertical = AmpTheme.spacing.sm).width(IntrinsicSize.Max).verticalScroll(rememberScrollState()),
                    content = content,
                )
            }
        }
    }
}

/**
 * Places a menu under its anchor, or over it when there is no room below, as Material's menu does, but horizontally
 * within the panel the anchor's center sits in: from the nearest crease (or the window edge) on its left to the
 * nearest on its right. The menu starts at the anchor's start edge, else ends at its end edge, else hugs the panel.
 * [creases], [top], and [bottom] are window pixels; the menu keeps between [top] and [bottom] ([popupArea]'s).
 */
internal class PanelMenuPosition(private val creases: List<Int>, private val top: Int, private val bottom: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val center = anchorBounds.center.x
        val left = creases.filter { it <= center }.maxOrNull() ?: 0
        val right = creases.filter { it > center }.minOrNull() ?: windowSize.width
        val width = popupContentSize.width
        val starts = listOf(anchorBounds.left, anchorBounds.right - width).let { if (layoutDirection == LayoutDirection.Ltr) it else it.reversed() }
        val x = starts.firstOrNull { it >= left && it + width <= right } ?: anchorBounds.left.coerceIn(left, maxOf(left, right - width))

        val height = popupContentSize.height
        val tops = listOf(
            anchorBounds.bottom,
            anchorBounds.top - height,
            anchorBounds.top - height / 2,
            if (anchorBounds.center.y < (top + bottom) / 2) top else bottom - height,
        )
        val y = tops.firstOrNull { it >= top && it + height <= bottom } ?: tops.last()
        return IntOffset(x, y)
    }
}

private const val ClosedScale = 0.8f
private const val InMillis = 120
private const val OutMillis = 75
