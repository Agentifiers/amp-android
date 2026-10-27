package com.agentifiers.amp.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Which ground each part of the window sits on. */
@Immutable
data class SurfaceRoles(
    val window: Color,
    val sidebar: Color,
    val thread: Color,
    val machine: Color,
    val search: Color,
    val field: Color,
    val composer: Color,
    val selected: Color,
    val userMessage: Color,
    val buttonSecondary: Color,
    val chip: Color,
)

/**
 * Amp's web card panes: the sidebar and every pane sit on the one surface, and the canvas shows only in the window bar
 * and the gutters between panes. Secondary buttons take the web's `--secondary`.
 */
fun SurfaceTokens.roles(): SurfaceRoles = SurfaceRoles(
    window = canvas,
    sidebar = pane,
    thread = pane,
    machine = pane,
    search = raised,
    field = raised,
    composer = raised,
    selected = raised,
    userMessage = raised,
    buttonSecondary = overlay,
    chip = control,
)
