package com.agentifiers.amp.thread.gates

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.agentifiers.amp.design.GateTags
import com.agentifiers.amp.design.Mono
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpTextField
import com.agentifiers.amp.model.ElicitField
import com.agentifiers.amp.model.ElicitationGate
import com.agentifiers.amp.model.GateResponse
import com.agentifiers.amp.model.JsonValue
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Field drafts are kept as strings keyed by field key so they survive rotation in a Bundle; [valueOf] turns each
 * into its wire value. A null there blocks Submit: pressing it then marks every field still missing (UX-30).
 */
@Composable
internal fun ElicitationFormCard(gate: ElicitationGate.Form, answer: (GateResponse) -> Unit) {
    var drafts by rememberSaveable(gate.id.raw) { mutableStateOf(mapOf<String, String>()) }
    var attempted by rememberSaveable(gate.id.raw) { mutableStateOf(false) }
    val remaining = rememberRemaining(gate.expiresAt)
    val live = remaining.isPositive()
    val editable = gate.answerable && live

    GateFrame(
        gate,
        "MCP question",
        actions = {
            val values = gate.fields.mapNotNull { field -> field.valueOf(drafts[field.key])?.let { field.key to it } }.toMap()
            val blocker = submitBlocker(gate.fields, drafts)
            if (live && attempted && blocker != null) {
                Text(blocker, style = AmpTheme.type.meta, color = AmpTheme.colors.status.danger, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().testTag(GateTags.submitBlocker))
            }
            GateActions {
                AmpButton("Decline", onClick = { answer(gate.decline()) }, testTag = GateTags.decline, enabled = live)
                AmpButton(
                    "Submit",
                    onClick = { if (blocker == null) answer(gate.submit(values)) else { attempted = true } },
                    testTag = GateTags.submit,
                    rank = AmpButtonRank.Primary,
                    enabled = live,
                )
            }
        },
    ) {
        Text(gate.message, style = AmpTheme.type.body, color = AmpTheme.colors.text.ink)
        Column(verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.md)) {
            gate.fields.forEach { field ->
                val raw = drafts[field.key]
                val set = { value: String -> drafts = drafts + (field.key to value) }
                val tag = GateTags.field(field.key)
                when (field) {
                    is ElicitField.Text -> AmpTextField(
                        value = raw.orEmpty(),
                        onValueChange = set,
                        testTag = tag,
                        modifier = Modifier.fillMaxWidth(),
                        label = field.markedLabel(),
                        enabled = editable,
                    )
                    is ElicitField.Number -> AmpTextField(
                        value = raw.orEmpty(),
                        onValueChange = set,
                        testTag = tag,
                        modifier = Modifier.fillMaxWidth(),
                        label = field.markedLabel(),
                        errorText = fieldError(field, raw, attempted),
                        enabled = editable,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    is ElicitField.YesNo -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(field.markedLabel(), style = AmpTheme.type.body, color = AmpTheme.colors.text.ink, modifier = Modifier.weight(1f))
                        Switch(checked = raw == true.toString(), onCheckedChange = { set(it.toString()) }, enabled = editable, modifier = Modifier.testTag(tag))
                    }
                    is ElicitField.OneOf -> Column {
                        Text(field.markedLabel(), style = AmpTheme.type.label, color = AmpTheme.colors.text.inkMuted)
                        field.options.forEachIndexed { n, option ->
                            RadioRow(option, selected = raw == n.toString(), enabled = editable, onSelect = { set(n.toString()) }, modifier = Modifier.testTag(GateTags.fieldOption(field.key, n)))
                        }
                        fieldError(field, raw, attempted)?.let { Text(it, style = AmpTheme.type.secondary, color = AmpTheme.colors.status.danger) }
                    }
                }
            }
        }
        CountdownText(remaining)
    }
}

private fun ElicitField.valueOf(raw: String?): JsonValue? = when (this) {
    is ElicitField.Text -> JsonValue.Str(raw.orEmpty())
    is ElicitField.Number -> raw?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(JsonValue::Num)
    is ElicitField.YesNo -> JsonValue.Bool(raw == true.toString())
    is ElicitField.OneOf -> raw?.toIntOrNull()?.let(options::getOrNull)?.let(JsonValue::Str)
}

/** An empty draft has no wire value, so Submit waits on the field. Text and yes-or-no fields always have one. */
private val ElicitField.required: Boolean get() = valueOf(null) == null

private val ElicitField.fieldLabel: String
    get() = when (this) {
        is ElicitField.Text -> label
        is ElicitField.Number -> label
        is ElicitField.YesNo -> label
        is ElicitField.OneOf -> label
    }

/** The field's label, marked in words rather than color when Submit waits on it (UX-30). */
internal fun ElicitField.markedLabel(): String = if (required) "$fieldLabel (required)" else fieldLabel

/** Why Submit is blocked: the first field without a value, or null when every field has one (UX-30). */
internal fun submitBlocker(fields: List<ElicitField>, drafts: Map<String, String>): String? {
    val field = fields.firstOrNull { it.valueOf(drafts[it.key]) == null } ?: return null
    return when {
        field is ElicitField.OneOf -> "Choose ${field.fieldLabel} to submit"
        drafts[field.key].isNullOrBlank() -> "Fill in ${field.fieldLabel} to submit"
        else -> "Enter a number in ${field.fieldLabel} to submit"
    }
}

/** The line under [field]: a number that does not parse says so at once; an empty required field only once Submit was [attempted]. */
internal fun fieldError(field: ElicitField, raw: String?, attempted: Boolean): String? = when {
    !field.required -> null
    raw.isNullOrBlank() -> if (attempted) "${field.fieldLabel} is required" else null
    field.valueOf(raw) == null -> "Enter a number."
    else -> null
}

/** The URL was validated when the gate was decoded (SafeElicitUrl), so it opens as is. */
@Composable
internal fun ElicitationOpenUrlCard(gate: ElicitationGate.OpenUrl, answer: (GateResponse) -> Unit) {
    var opened by rememberSaveable(gate.id.raw) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val remaining = rememberRemaining(gate.expiresAt)
    val live = remaining.isPositive()

    GateFrame(
        gate,
        "Open a page",
        actions = {
            GateActions {
                AmpButton("Decline", onClick = { answer(gate.decline()) }, testTag = GateTags.decline, enabled = live)
                if (opened) {
                    AmpButton("Continue", onClick = { answer(gate.continueAfterOpening()) }, testTag = GateTags.submit, rank = AmpButtonRank.Primary, enabled = live)
                } else {
                    AmpButton(
                        "Open in Browser",
                        onClick = {
                            uriHandler.openUri(gate.url.url)
                            opened = true
                        },
                        testTag = GateTags.openUrl,
                        rank = AmpButtonRank.Primary,
                        enabled = live,
                    )
                }
            }
        },
    ) {
        Text(gate.message, style = AmpTheme.type.body, color = AmpTheme.colors.text.ink)
        Text(gate.url.url, style = Mono)
        CountdownText(remaining)
    }
}

@Composable
private fun rememberRemaining(expiresAt: Instant): Duration {
    var remaining by remember(expiresAt) { mutableStateOf(timeLeft(expiresAt)) }
    LaunchedEffect(expiresAt) {
        while (remaining.isPositive()) {
            val toNextSecond = remaining.inWholeMilliseconds % 1000
            delay(if (toNextSecond == 0L) 1000L else toNextSecond)
            remaining = timeLeft(expiresAt)
        }
    }
    return remaining
}

private fun timeLeft(expiresAt: Instant): Duration = (expiresAt - Clock.System.now()).coerceAtLeast(Duration.ZERO)

@Composable
private fun CountdownText(remaining: Duration) {
    val text = if (remaining.isPositive()) {
        val seconds = (remaining.inWholeMilliseconds + 999) / 1000
        "Expires in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    } else {
        "Expired"
    }
    Text(text, style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted, modifier = Modifier.testTag(GateTags.countdown))
}
