package com.agentifiers.amp.design.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Every text style in the app, named by what the text is, at the A2 density direction's sizes
 * (with C's [section]). Pick by meaning: a thread row's title is [rowTitle] even where [body] has the same size.
 * [section] is drawn uppercase by the code that shows it; Compose has no text transform.
 *
 * Roles A2 lacks, added for text the prototype does not show: [headline] for a full-screen or dialog heading (sign-in,
 * the lock screen, sheets), [heading1] to [heading3] for markdown headings, and [deviceCode] for the sign-in code.
 */
@Immutable
data class AmpType(
    val threadTitle: TextStyle = role(15, 20, FontWeight.SemiBold),
    val paneTitle: TextStyle = role(15, 20, FontWeight.SemiBold),
    val body: TextStyle = role(14, 20),
    val rowTitle: TextStyle = role(14, 20, FontWeight.Medium),
    val secondary: TextStyle = role(13, 18),
    val label: TextStyle = role(12, 16, FontWeight.Medium),
    val meta: TextStyle = role(12, 16),
    val section: TextStyle = role(11, 16, FontWeight.SemiBold, tracking = 0.08.em),
    val mono: TextStyle = role(13, 18, family = FontFamily.Monospace),
    val monoSmall: TextStyle = role(12, 16, family = FontFamily.Monospace),
    val tab: TextStyle = role(13, 18, FontWeight.Medium),
    val button: TextStyle = role(13, 18, FontWeight.Medium),
    val nav: TextStyle = role(12, 16, FontWeight.Medium),
    val thinking: TextStyle = role(13, 18, italic = true),
    val tool: TextStyle = role(13, 18, FontWeight.Medium),
    val toolMeta: TextStyle = role(12, 16),
    val headline: TextStyle = role(20, 28, FontWeight.SemiBold),
    val heading1: TextStyle = role(16, 22, FontWeight.SemiBold),
    val heading2: TextStyle = role(15, 20, FontWeight.SemiBold),
    val heading3: TextStyle = role(14, 20, FontWeight.SemiBold),
    val deviceCode: TextStyle = role(32, 40, FontWeight.SemiBold, family = FontFamily.Monospace, tracking = 0.15.em),
    /** Code inside prose: monospace at 0.93 of the text around it, so it sits in the line without towering over it. */
    val inlineCode: SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.93.em),
) {
    /**
     * Material's roles resolved to A2's, so stock components match: plain `Text` and text fields read bodyLarge,
     * menu items labelLarge, tabs titleSmall, dialog titles headlineSmall, list supporting text bodyMedium.
     */
    val material: Typography = Typography(
        displayLarge = headline,
        displayMedium = headline,
        displaySmall = headline,
        headlineLarge = headline,
        headlineMedium = headline,
        headlineSmall = headline,
        titleLarge = threadTitle,
        titleMedium = paneTitle,
        titleSmall = tab,
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = secondary,
        labelLarge = body,
        labelMedium = label,
        labelSmall = meta,
    )
}

/** A line box exactly [line] tall with the glyphs centered, as CSS lays out the prototype's text. */
private fun role(
    size: Int,
    line: Int,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = FontFamily.SansSerif,
    italic: Boolean = false,
    tracking: TextUnit = 0.sp,
) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    letterSpacing = tracking,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)
