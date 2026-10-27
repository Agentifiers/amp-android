package com.agentifiers.amp.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.agentifiers.amp.design.AmpTheme

/**
 * The raised input bar: one container at least 48dp tall with a 24dp radius, [leading] icon buttons, the field, and
 * [trailing] icon buttons, 2dp apart. It grows with its text up to [maxLines]; the buttons stay on the last line. The
 * composer and any other single-line input bar use it, so every bar in the app has the same height, radius, and insets.
 */
@Composable
fun AmpInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    fieldModifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = AmpTheme.colors
    val spacing = AmpTheme.spacing
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = AmpTheme.metrics.composer)
            .background(AmpTheme.surfaceRoles.composer, AmpTheme.shapes.composer)
            .padding(horizontal = spacing.composerPadX),
        horizontalArrangement = Arrangement.spacedBy(spacing.composerIconGap),
        verticalAlignment = Alignment.Bottom,
    ) {
        leading?.invoke(this)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier.weight(1f).align(Alignment.CenterVertically).testTag(testTag)
                .padding(horizontal = spacing.composerTextInset, vertical = spacing.sm),
            enabled = enabled,
            singleLine = maxLines == 1,
            maxLines = maxLines,
            textStyle = AmpTheme.type.body.copy(color = colors.text.ink),
            cursorBrush = SolidColor(colors.accents.accent),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = AmpTheme.type.body, color = colors.text.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    field()
                }
            },
        )
        trailing?.invoke(this)
    }
}
