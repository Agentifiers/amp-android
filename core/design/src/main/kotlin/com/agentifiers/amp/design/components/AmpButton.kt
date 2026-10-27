package com.agentifiers.amp.design.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.icons.AmpIcons

/**
 * Rank says how much a button matters on its screen. [Link] is a
 * navigation link or a disclosure toggle: muted text with a chevron, so accent stays for the primary, the selection,
 * and a row's call to action ([Tertiary]).
 */
enum class AmpButtonRank { Primary, Secondary, Tertiary, Link, Destructive }

private class RankStyle(val container: Color, val content: Color)

private fun AmpButtonRank.style(): RankStyle = with(AmpTheme.colors) {
    when (this@style) {
        AmpButtonRank.Primary -> RankStyle(accents.accent, text.onAccent)
        AmpButtonRank.Secondary -> RankStyle(AmpTheme.surfaceRoles.buttonSecondary, text.ink)
        AmpButtonRank.Tertiary -> RankStyle(Color.Transparent, accents.accent)
        AmpButtonRank.Link -> RankStyle(Color.Transparent, text.inkMuted)
        AmpButtonRank.Destructive -> RankStyle(status.danger, text.onAccent)
    }
}

/** Amp's web buttons dim to half opacity when disabled. */
internal const val DisabledAlpha = 0.5f

/**
 * A labeled button: 32dp tall (a minimum, so a larger font grows it) inside a 48dp touch target. While [loading] it
 * keeps its colors, shows a spinner in place of [leadingIcon], ignores taps, and reports "Loading" as its state.
 */
@Composable
fun AmpButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    rank: AmpButtonRank = AmpButtonRank.Secondary,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = if (rank == AmpButtonRank.Link) AmpIcons.ChevronRight else null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val style = rank.style()
    val spacing = AmpTheme.spacing
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = AmpTheme.metrics.button)
            .alpha(if (enabled) 1f else DisabledAlpha)
            .testTag(testTag)
            .semantics { if (loading) stateDescription = "Loading" },
        enabled = enabled && !loading,
        shape = AmpTheme.shapes.control,
        colors = ButtonDefaults.buttonColors(
            containerColor = style.container,
            contentColor = style.content,
            disabledContainerColor = style.container,
            disabledContentColor = style.content,
        ),
        contentPadding = PaddingValues(horizontal = if (rank == AmpButtonRank.Tertiary || rank == AmpButtonRank.Link) spacing.sm else spacing.buttonPadX),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(AmpTheme.metrics.iconSmall), color = style.content, strokeWidth = 2.dp)
        } else if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(AmpTheme.metrics.icon))
        }
        if (loading || leadingIcon != null) Spacer(Modifier.width(spacing.xs))
        Text(text, style = AmpTheme.type.button, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailingIcon != null) {
            Spacer(Modifier.width(spacing.xs))
            Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(AmpTheme.metrics.iconSmall))
        }
    }
}
