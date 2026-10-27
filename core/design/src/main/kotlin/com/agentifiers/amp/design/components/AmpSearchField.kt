package com.agentifiers.amp.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.icons.AmpIcons

/**
 * A one-line search field: 36dp on the search surface at the default font scale (a minimum, so larger text grows it),
 * radius 6, an 18dp search glyph, the [placeholder] in inkMuted while empty, and an optional [trailing] control such as
 * Clear. The text field node carries [testTag]; [modifier] reaches the field, so focus and key handlers go there.
 */
@Composable
fun AmpSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    testTag: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().testTag(testTag),
        singleLine = true,
        textStyle = AmpTheme.type.body.copy(color = AmpTheme.colors.text.ink),
        cursorBrush = SolidColor(AmpTheme.colors.accents.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { field -> SearchDecoration(value.isEmpty(), placeholder, trailing, field) },
    )
}

/** [AmpSearchField] for a caller that moves the cursor itself, such as the Sidebar turning a typed filter into a chip. */
@Composable
fun AmpSearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    testTag: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().testTag(testTag),
        singleLine = true,
        textStyle = AmpTheme.type.body.copy(color = AmpTheme.colors.text.ink),
        cursorBrush = SolidColor(AmpTheme.colors.accents.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { field -> SearchDecoration(value.text.isEmpty(), placeholder, trailing, field) },
    )
}

@Composable
private fun SearchDecoration(empty: Boolean, placeholder: String, trailing: (@Composable () -> Unit)?, field: @Composable () -> Unit) {
    val colors = AmpTheme.colors
    val spacing = AmpTheme.spacing
    Row(
        Modifier
            .heightIn(min = AmpTheme.metrics.search)
            .background(AmpTheme.surfaceRoles.search, AmpTheme.shapes.control)
            .padding(start = spacing.searchPadX, end = if (trailing == null) spacing.searchPadX else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing.searchIconGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AmpIcons.Search, contentDescription = null, tint = colors.text.inkMuted, modifier = Modifier.size(AmpTheme.metrics.icon))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (empty) Text(placeholder, style = AmpTheme.type.body, color = colors.text.inkMuted, maxLines = 1)
            field()
        }
        if (trailing != null) Box(Modifier.overhang(AmpTheme.metrics.search)) { trailing() }
    }
}

/**
 * Lays the child out at its own size but reports at most [height], centered, so a 48dp touch target inside a 36dp or
 * 40dp field keeps its hit area without making the field taller.
 */
internal fun Modifier.overhang(height: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val reported = minOf(placeable.height, height.roundToPx())
    layout(placeable.width, reported) { placeable.place(0, (reported - placeable.height) / 2) }
}
