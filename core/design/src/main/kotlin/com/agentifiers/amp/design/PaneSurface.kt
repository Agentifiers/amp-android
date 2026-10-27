package com.agentifiers.amp.design

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The ground of the pane being composed: the window shell provides `AmpTheme.surfaceRoles.sidebar`, `thread`, or
 * `machine` per column. A pane's root and its full-width strips (action bars, toolbars, fades) draw on it, so the
 * same pane sits on the right ground in any column. Sheets and cards keep their own surfaces.
 */
val LocalPaneSurface: ProvidableCompositionLocal<Color> = staticCompositionLocalOf { AmpTheme.surfaceRoles.thread }
