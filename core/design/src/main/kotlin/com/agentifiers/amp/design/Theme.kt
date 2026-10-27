package com.agentifiers.amp.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import com.agentifiers.amp.design.theme.AmpMetrics
import com.agentifiers.amp.design.theme.AmpPalette
import com.agentifiers.amp.design.theme.AmpShapes
import com.agentifiers.amp.design.theme.AmpSpacing
import com.agentifiers.amp.design.theme.AmpType
import com.agentifiers.amp.design.theme.Forest
import com.agentifiers.amp.design.theme.SurfaceRoles
import com.agentifiers.amp.design.theme.roles

object AmpTheme {
    val colors: AmpPalette = Forest
    val surfaceRoles: SurfaceRoles = Forest.surfaces.roles()
    val spacing: AmpSpacing = AmpSpacing()
    val metrics: AmpMetrics = AmpMetrics()
    val shapes: AmpShapes = AmpShapes()
    val type: AmpType = AmpType()
}

val Mono = AmpTheme.type.mono.copy(color = AmpTheme.colors.text.ink)

@Composable
fun AmpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AmpColorScheme, typography = AmpTheme.type.material, content = content)
}

/** Every Material role, so no component falls back to the baseline purple. */
private val AmpColorScheme: ColorScheme = with(AmpTheme.colors) {
    darkColorScheme(
        primary = accents.accent,
        onPrimary = text.onAccent,
        primaryContainer = accents.accentMuted,
        onPrimaryContainer = text.ink,
        inversePrimary = accents.accent,
        secondary = accents.accent,
        onSecondary = text.onAccent,
        secondaryContainer = accents.accentMuted,
        onSecondaryContainer = text.ink,
        tertiary = status.link,
        onTertiary = surfaces.canvas,
        tertiaryContainer = accents.accentMuted,
        onTertiaryContainer = text.ink,
        background = surfaces.canvas,
        onBackground = text.ink,
        surface = surfaces.pane,
        onSurface = text.ink,
        surfaceVariant = surfaces.raised,
        onSurfaceVariant = text.inkMuted,
        surfaceTint = surfaces.pane,
        inverseSurface = text.ink,
        inverseOnSurface = surfaces.canvas,
        error = status.danger,
        onError = surfaces.canvas,
        errorContainer = code.diff.removedBackground,
        onErrorContainer = text.ink,
        outline = surfaces.line,
        outlineVariant = surfaces.line,
        scrim = surfaces.scrim.copy(alpha = 1f),
        surfaceBright = surfaces.overlay,
        surfaceDim = surfaces.canvas,
        surfaceContainerLowest = surfaces.canvas,
        surfaceContainerLow = surfaces.pane,
        surfaceContainer = surfaces.pane,
        surfaceContainerHigh = surfaces.raised,
        surfaceContainerHighest = surfaces.overlay,
    )
}
