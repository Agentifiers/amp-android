package com.agentifiers.amp.design.code

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.agentifiers.amp.design.AmpTheme

/** [text] with a [background] on every case-insensitive match of [term]. */
fun highlighted(text: AnnotatedString, term: String?, background: Color): AnnotatedString {
    if (term.isNullOrEmpty()) return text
    var at = text.text.indexOf(term, ignoreCase = true)
    if (at < 0) return text
    val style = SpanStyle(background = background)
    return AnnotatedString.Builder(text).apply {
        while (at >= 0) {
            addStyle(style, at, at + term.length)
            at = text.text.indexOf(term, at + term.length, ignoreCase = true)
        }
    }.toAnnotatedString()
}

/** [highlighted] with the selection color, as find in thread marks its matches. */
@Composable
fun rememberHighlighted(text: AnnotatedString, term: String?): AnnotatedString {
    val background = AmpTheme.colors.accents.accentMuted
    return remember(text, term, background) { highlighted(text, term, background) }
}
