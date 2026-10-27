package com.agentifiers.amp.design.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntRect

/**
 * The part of the window a popup may cover, in window pixels: the window less its system bars, cutout, and keyboard.
 * A PopupPositionProvider gets its anchor in window coordinates but a windowSize that is the visible display frame's
 * size, which already leaves the bars out without saying where; measured against the anchor, that size ends a status
 * bar's height above the real bottom. Position against this area instead.
 */
@Composable
fun popupArea(): IntRect {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val window = LocalWindowInfo.current.containerSize
    val insets = WindowInsets.safeDrawing
    return IntRect(
        left = insets.getLeft(density, direction),
        top = insets.getTop(density),
        right = window.width - insets.getRight(density, direction),
        bottom = window.height - insets.getBottom(density),
    )
}
