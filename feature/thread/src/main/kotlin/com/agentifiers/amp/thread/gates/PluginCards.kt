package com.agentifiers.amp.thread.gates

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.agentifiers.amp.design.GateTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpTextField
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.GateResponse
import com.agentifiers.amp.model.PluginGate
import com.agentifiers.amp.model.SecretPluginAnswer

private const val CANCELLED = "Cancelled"
private const val VALUE_FIELD = "value"

@Composable
internal fun PluginConfirmCard(gate: PluginGate.Confirm, answer: (GateResponse) -> Unit) {
    GateFrame(
        gate,
        gate.title,
        actions = {
            GateActions {
                AmpButton("Decline", onClick = { answer(gate.respond(false, null)) }, testTag = GateTags.decline)
                AmpButton(gate.confirmLabel, onClick = { answer(gate.respond(true, null)) }, testTag = GateTags.submit, rank = AmpButtonRank.Primary)
            }
        },
    ) {
        if (gate.body.isNotBlank()) Text(gate.body, style = AmpTheme.type.body, color = AmpTheme.colors.text.ink)
    }
}

@Composable
internal fun PluginInputCard(gate: PluginGate.Input, answer: (GateResponse) -> Unit) {
    var text by rememberSaveable(gate.id.raw) { mutableStateOf(gate.initialValue) }
    GateFrame(
        gate,
        gate.title,
        actions = {
            GateActions {
                AmpButton("Cancel", onClick = { answer(gate.cancel(CANCELLED)) }, testTag = GateTags.decline)
                AmpButton(gate.submitLabel, onClick = { answer(gate.respond(text)) }, testTag = GateTags.submit, rank = AmpButtonRank.Primary)
            }
        },
    ) {
        HelpText(gate.helpText)
        AmpTextField(
            value = text,
            onValueChange = { text = it },
            testTag = GateTags.field(VALUE_FIELD),
            modifier = Modifier.fillMaxWidth(),
            placeholder = gate.placeholder,
            enabled = gate.answerable,
        )
    }
}

/** Plain remember, never rememberSaveable: the secret must not reach a saved-state Bundle. */
@Composable
internal fun PluginSecretInputCard(gate: PluginGate.SecretInput, answer: (GateResponse) -> Unit, onSecret: (SecretPluginAnswer) -> Unit) {
    var text by remember(gate.id) { mutableStateOf("") }
    var shown by remember(gate.id) { mutableStateOf(false) }
    GateFrame(
        gate,
        gate.title,
        actions = {
            GateActions {
                AmpButton("Cancel", onClick = { answer(gate.cancel(CANCELLED)) }, testTag = GateTags.decline)
                AmpButton(gate.submitLabel, onClick = { onSecret(gate.respond(text)) }, testTag = GateTags.submit, rank = AmpButtonRank.Primary)
            }
        },
    ) {
        HelpText(gate.helpText)
        AmpTextField(
            value = text,
            onValueChange = { text = it },
            testTag = GateTags.field(VALUE_FIELD),
            modifier = Modifier.fillMaxWidth(),
            placeholder = gate.placeholder,
            enabled = gate.answerable,
            singleLine = true,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailing = if (gate.answerable) {
                { AmpIconButton(AmpIcons.Eye, if (shown) "Hide" else "Show", { shown = !shown }, GateTags.secretToggle) }
            } else {
                null
            },
        )
    }
}

@Composable
internal fun PluginSelectCard(gate: PluginGate.Select, answer: (GateResponse) -> Unit) {
    var picked by rememberSaveable(gate.id.raw) { mutableStateOf<Int?>(null) }
    GateFrame(
        gate,
        gate.title,
        actions = {
            GateActions {
                AmpButton("Cancel", onClick = { answer(gate.cancel(CANCELLED)) }, testTag = GateTags.decline)
                AmpButton("Submit", onClick = { picked?.let { answer(gate.respond(it)) } }, testTag = GateTags.submit, rank = AmpButtonRank.Primary, enabled = picked != null)
            }
        },
    ) {
        Column {
            gate.options.forEachIndexed { n, option ->
                RadioRow(option, selected = picked == n, enabled = gate.answerable, onSelect = { picked = n }, modifier = Modifier.testTag(GateTags.option(n)))
            }
        }
    }
}

@Composable
private fun HelpText(text: String) {
    if (text.isNotBlank()) Text(text, style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted)
}

@Composable
internal fun RadioRow(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.touch).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, style = AmpTheme.type.body, color = AmpTheme.colors.text.ink)
    }
}

@Composable
internal fun CheckboxRow(label: String, checked: Boolean, enabled: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.touch).toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }),
        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = AmpTheme.type.body, color = AmpTheme.colors.text.ink)
    }
}
