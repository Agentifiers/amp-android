package com.agentifiers.amp.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Grounds, from the window back to the menu in front, plus dividers and the dimming behind an overlay. [control] is the
 * fill of a raised pill; [rim] rings it and [glint] lights its top edge, both translucent so they read on any ground.
 */
@Immutable
data class SurfaceTokens(
    val canvas: Color,
    val pane: Color,
    val raised: Color,
    val overlay: Color,
    val control: Color,
    val line: Color,
    val rim: Color,
    val glint: Color,
    val scrim: Color,
)

@Immutable
data class TextTokens(
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val onAccent: Color,
)

/** [accent] marks actions, [accentMuted] selections and selected rows, [brandMark] the wordmark and decoration. */
@Immutable
data class AccentTokens(
    val accent: Color,
    val accentMuted: Color,
    val focus: Color,
    val brandMark: Color,
)

/** Thread states after screens.md "Status text", and Amp's web feedback colors. Every one is also a text color. */
@Immutable
data class StatusTokens(
    val working: Color,
    val needsYou: Color,
    val done: Color,
    val error: Color,
    val idle: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val link: Color,
    val mention: Color,
)

@Immutable
data class SyntaxTokens(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val function: Color,
    val type: Color,
    val comment: Color,
)

/** The strong backgrounds mark the changed words inside a changed line. */
@Immutable
data class DiffTokens(
    val addedBackground: Color,
    val addedText: Color,
    val addedStrongBackground: Color,
    val removedBackground: Color,
    val removedText: Color,
    val removedStrongBackground: Color,
    val meta: Color,
)

/** [ansi] is the 16-color terminal table, SGR 30-37 then 90-97, for the Terminal pane and shell output alike. */
@Immutable
data class CodeTokens(
    val editorBackground: Color,
    val editorText: Color,
    val syntax: SyntaxTokens,
    val diff: DiffTokens,
    val ansi: List<Color>,
) {
    init {
        require(ansi.size == 16) { "ansi holds 16 colors, not ${ansi.size}" }
    }
}

/** The named thread colors a user picks for the stripe on a thread row. */
@Immutable
data class ThreadColorTokens(
    val red: Color,
    val orange: Color,
    val yellow: Color,
    val green: Color,
    val blue: Color,
    val purple: Color,
    val gray: Color,
)

/** Neutral grounds for looking at images and video, which must not tint what they frame. */
@Immutable
data class MediaTokens(
    val letterbox: Color,
    val lightboxDark: Color,
    val lightboxLight: Color,
    val lightboxChecker: Color,
)

@Immutable
data class DebugTokens(val crease: Color)

/** Every color the app draws. Composables read it through `AmpTheme.colors`. */
@Immutable
data class AmpPalette(
    val surfaces: SurfaceTokens,
    val text: TextTokens,
    val accents: AccentTokens,
    val status: StatusTokens,
    val code: CodeTokens,
    val threadColors: ThreadColorTokens,
    val media: MediaTokens,
    val debug: DebugTokens,
)
