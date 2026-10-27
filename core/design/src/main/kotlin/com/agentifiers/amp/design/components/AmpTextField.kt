package com.agentifiers.amp.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.agentifiers.amp.design.AmpTheme

/**
 * A form field: the [label] above in the label role, then a raised box at least 40dp tall (radius 6, 12dp sides) that
 * grows with multi-line text, body text with the [placeholder] in inkMuted while empty, then [errorText] in danger or
 * else [supportingText] in inkMuted. Focus outlines the box in the focus token; an error outlines it in danger. The
 * editable node carries [testTag] and [modifier], so weight, focus requesters, and key handlers reach the field.
 * [leading] and [trailing] keep their 48dp touch targets but count as at most 40dp, so a button does not grow the box.
 */
@Composable
fun AmpTextField(
    value: String,
    onValueChange: (String) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    errorTestTag: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.testTag(testTag),
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = fieldText(enabled),
        cursorBrush = SolidColor(AmpTheme.colors.accents.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        decorationBox = { field ->
            FieldDecoration(label, placeholder.takeIf { value.isEmpty() }, supportingText, errorText, errorTestTag, focused, leading, trailing, field)
        },
    )
}

/** [AmpTextField] over a [TextFieldState], for callers that keep the text in state and act on [onKeyboardAction]. */
@Composable
fun AmpTextField(
    state: TextFieldState,
    testTag: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    errorTestTag: String? = null,
    enabled: Boolean = true,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        state = state,
        modifier = modifier.testTag(testTag),
        enabled = enabled,
        textStyle = fieldText(enabled),
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        lineLimits = lineLimits,
        interactionSource = interaction,
        cursorBrush = SolidColor(AmpTheme.colors.accents.accent),
        decorator = { field ->
            FieldDecoration(label, placeholder.takeIf { state.text.isEmpty() }, supportingText, errorText, errorTestTag, focused, leading, trailing, field)
        },
    )
}

/** [AmpTextField] for a secret: one line, obscured per [obfuscation] (the last typed character shows by default). */
@Composable
fun AmpSecureTextField(
    state: TextFieldState,
    testTag: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    errorTestTag: String? = null,
    enabled: Boolean = true,
    obfuscation: TextObfuscationMode = TextObfuscationMode.RevealLastTyped,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicSecureTextField(
        state = state,
        modifier = modifier.testTag(testTag),
        enabled = enabled,
        textStyle = fieldText(enabled),
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        interactionSource = interaction,
        cursorBrush = SolidColor(AmpTheme.colors.accents.accent),
        textObfuscationMode = obfuscation,
        decorator = { field ->
            FieldDecoration(label, placeholder.takeIf { state.text.isEmpty() }, supportingText, errorText, errorTestTag, focused, null, trailing, field)
        },
    )
}

@Composable
private fun fieldText(enabled: Boolean) =
    AmpTheme.type.body.copy(color = if (enabled) AmpTheme.colors.text.ink else AmpTheme.colors.text.inkMuted)

@Composable
private fun FieldDecoration(
    label: String?,
    placeholder: String?,
    supportingText: String?,
    errorText: String?,
    errorTestTag: String?,
    focused: Boolean,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    field: @Composable () -> Unit,
) {
    val colors = AmpTheme.colors
    val spacing = AmpTheme.spacing
    val outline = when {
        errorText != null -> colors.status.danger
        focused -> colors.accents.focus
        else -> Color.Transparent
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.fieldLabelGap)) {
        if (label != null) Text(label, style = AmpTheme.type.label, color = colors.text.inkMuted)
        Row(
            Modifier
                .heightIn(min = AmpTheme.metrics.field)
                .background(AmpTheme.surfaceRoles.field, AmpTheme.shapes.control)
                .border(AmpTheme.metrics.divider, outline, AmpTheme.shapes.control)
                .padding(start = spacing.fieldPadX, end = if (trailing == null) spacing.fieldPadX else spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) Box(Modifier.overhang(AmpTheme.metrics.field)) { leading() }
            Box(Modifier.weight(1f).padding(vertical = spacing.fieldPadY), contentAlignment = Alignment.CenterStart) {
                if (placeholder != null) Text(placeholder, style = AmpTheme.type.body, color = colors.text.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                field()
            }
            if (trailing != null) Box(Modifier.overhang(AmpTheme.metrics.field)) { trailing() }
        }
        when {
            errorText != null -> Text(
                errorText,
                style = AmpTheme.type.secondary,
                color = colors.status.danger,
                modifier = if (errorTestTag != null) Modifier.testTag(errorTestTag) else Modifier,
            )
            supportingText != null -> Text(supportingText, style = AmpTheme.type.secondary, color = colors.text.inkMuted)
        }
    }
}
