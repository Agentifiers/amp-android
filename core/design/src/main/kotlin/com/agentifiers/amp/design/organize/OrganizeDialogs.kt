package com.agentifiers.amp.design.organize

import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.tagsAsResourceIds
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.FleetTags
import com.agentifiers.amp.design.FleetTags.SnoozeOption
import com.agentifiers.amp.model.LabelName
import com.agentifiers.amp.model.SnoozeUntil
import com.agentifiers.amp.model.ThreadColor
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import kotlinx.collections.immutable.toPersistentList
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.time.Instant

/** The organize action dialog on screen, shared by the Sidebar row menu and the thread's More Actions. */
sealed interface OrganizeDialog {
    data class Rename(val thread: ThreadId, val current: String) : OrganizeDialog
    data class Color(val thread: ThreadId, val current: ThreadColor) : OrganizeDialog
    data class Labels(val thread: ThreadId, val current: List<LabelName>) : OrganizeDialog
    data class Snooze(val thread: ThreadId, val snoozed: Boolean) : OrganizeDialog
    data class Delete(val thread: ThreadId) : OrganizeDialog
}

/** Shows [dialog]; each choice goes out through [onIntent] and then closes the dialog with [onDismiss]. */
@Composable
fun OrganizeDialogHost(
    dialog: OrganizeDialog?,
    knownLabels: List<LabelName>,
    now: () -> Instant,
    onIntent: (ThreadId, ThreadIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    val choose: (ThreadId, ThreadIntent) -> Unit = { thread, intent ->
        onIntent(thread, intent)
        onDismiss()
    }
    when (dialog) {
        null -> Unit
        is OrganizeDialog.Rename -> RenameDialog(dialog, choose, onDismiss)
        is OrganizeDialog.Color -> ColorDialog(dialog, choose, onDismiss)
        is OrganizeDialog.Labels -> LabelsDialog(dialog, knownLabels, choose, onDismiss)
        is OrganizeDialog.Snooze -> SnoozeDialog(dialog, now, choose, onDismiss)
        is OrganizeDialog.Delete -> DeleteDialog(dialog, choose, onDismiss)
    }
}

internal const val MAX_LABEL_LENGTH = 32
internal const val MAX_LABELS = 20

private val labelChars = Regex("[^a-z0-9-]")

/** Applies Amp's label rules as the user types: lowercase, `[a-z0-9-]`, at most [MAX_LABEL_LENGTH] characters. */
internal fun normalizeLabelInput(raw: String): String = raw.lowercase().replace(labelChars, "").take(MAX_LABEL_LENGTH)

/** The next 08:00 on the wall clock of [zone] strictly after [now]. */
internal fun nextEightAm(now: Instant, zone: ZoneId): Instant {
    val local = now.atZone(zone)
    val today = local.toLocalDate().atTime(8, 0).atZone(zone)
    return (if (today.isAfter(local)) today else local.toLocalDate().plusDays(1).atTime(8, 0).atZone(zone)).toKotlinInstant()
}

/** The next Monday 08:00 on the wall clock of [zone] strictly after [now]. */
internal fun nextMondayMorning(now: Instant, zone: ZoneId): Instant {
    val local = now.atZone(zone)
    val monday = local.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
    val candidate = monday.atTime(8, 0).atZone(zone)
    return (if (candidate.isAfter(local)) candidate else monday.plusWeeks(1).atTime(8, 0).atZone(zone)).toKotlinInstant()
}

private fun Instant.atZone(zone: ZoneId): ZonedDateTime =
    java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()).atZone(zone)

private fun ZonedDateTime.toKotlinInstant(): Instant = Instant.fromEpochSeconds(toEpochSecond(), nano)

@Composable
private fun CancelButton(onDismiss: () -> Unit) {
    TextButton(onClick = onDismiss, modifier = Modifier.testTag(FleetTags.organizeCancel)) { Text("Cancel") }
}

@Composable
private fun RenameDialog(dialog: OrganizeDialog.Rename, choose: (ThreadId, ThreadIntent) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable(dialog) { mutableStateOf(dialog.current) }
    AlertDialog(
        modifier = Modifier.tagsAsResourceIds(),
        onDismissRequest = onDismiss,
        title = { Text("Rename Thread") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth().testTag(FleetTags.renameField),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { choose(dialog.thread, ThreadIntent.Rename(title.trim())) },
                enabled = title.isNotBlank(),
                modifier = Modifier.testTag(FleetTags.renameSave),
            ) { Text("Save") }
        },
        dismissButton = { CancelButton(onDismiss) },
    )
}

@Composable
private fun ColorDialog(dialog: OrganizeDialog.Color, choose: (ThreadId, ThreadIntent) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.tagsAsResourceIds(),
        onDismissRequest = onDismiss,
        title = { Text("Color") },
        text = {
            Column {
                ThreadColor.entries.forEach { color ->
                    val current = color == dialog.current
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (current) AmpTheme.colors.accents.accentMuted else Color.Transparent)
                            .clickable(role = Role.RadioButton) { choose(dialog.thread, ThreadIntent.Color(color)) }
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                            .testTag(FleetTags.color(color)).semantics { selected = current },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(color.swatch()))
                        Text(color.name, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { CancelButton(onDismiss) },
    )
}

/** Swatch for a thread color. None draws the divider color so the row still has a visible mark. */
@Composable
fun ThreadColor.swatch(): Color {
    val colors = AmpTheme.colors
    return when (this) {
        ThreadColor.None -> colors.surfaces.line
        ThreadColor.Red -> colors.threadColors.red
        ThreadColor.Orange -> colors.threadColors.orange
        ThreadColor.Yellow -> colors.threadColors.yellow
        ThreadColor.Green -> colors.threadColors.green
        ThreadColor.Blue -> colors.threadColors.blue
        ThreadColor.Purple -> colors.threadColors.purple
        ThreadColor.Gray -> colors.threadColors.gray
    }
}

@Composable
private fun LabelsDialog(
    dialog: OrganizeDialog.Labels,
    knownLabels: List<LabelName>,
    choose: (ThreadId, ThreadIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    val labels = remember(dialog) { mutableStateListOf<LabelName>().apply { addAll(dialog.current) } }
    var input by rememberSaveable(dialog) { mutableStateOf("") }
    val full = labels.size >= MAX_LABELS
    val add: (LabelName) -> Unit = { label ->
        if (!full && label.raw.isNotEmpty() && label !in labels) labels += label
        input = ""
    }
    val unused = knownLabels.filter { it !in labels }
    val suggestions = unused.filter { it.raw.startsWith(input) }.take(8)

    AlertDialog(
        modifier = Modifier.tagsAsResourceIds(),
        onDismissRequest = onDismiss,
        title = { Text("Labels") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    labels.forEach { label ->
                        Row(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(AmpTheme.colors.surfaces.overlay).padding(start = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label.raw, style = AmpTheme.type.label)
                            TextButton(
                                onClick = { labels -= label },
                                modifier = Modifier.testTag(FleetTags.labelRemove(label)).semantics { contentDescription = "Remove ${label.raw}" },
                            ) { Text("×") }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = normalizeLabelInput(it) },
                        singleLine = true,
                        enabled = !full,
                        label = { Text("Label") },
                        modifier = Modifier.weight(1f).testTag(FleetTags.labelsField),
                    )
                    TextButton(
                        onClick = { add(LabelName(input)) },
                        enabled = !full && input.isNotEmpty() && LabelName(input) !in labels,
                        modifier = Modifier.testTag(FleetTags.labelsAdd),
                    ) { Text("Add") }
                }
                Text(
                    if (full) "At most $MAX_LABELS labels per thread." else "Lowercase letters, digits, and hyphens, up to $MAX_LABEL_LENGTH characters.",
                    style = AmpTheme.type.secondary,
                    color = if (full) AmpTheme.colors.status.danger else AmpTheme.colors.text.inkMuted,
                )
                // One row of fixed height that stays while typing empties it. A dialog that changed height would
                // re-center and move Add away from the finger reaching for it.
                if (!full && unused.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().height(AmpTheme.metrics.touch).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        suggestions.forEach { label ->
                            TextButton(onClick = { add(label) }, modifier = Modifier.testTag(FleetTags.labelSuggestion(label))) { Text(label.raw) }
                        }
                        if (suggestions.isEmpty()) Text("No saved label starts with \"$input\".", style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { choose(dialog.thread, ThreadIntent.SetLabels(labels.toPersistentList())) },
                modifier = Modifier.testTag(FleetTags.labelsSave),
            ) { Text("Save") }
        },
        dismissButton = { CancelButton(onDismiss) },
    )
}

private val SnoozeOption.label: String
    get() = when (this) {
        SnoozeOption.OneHour -> "For 1 Hour"
        SnoozeOption.NextReply -> "Until Next Reply"
        SnoozeOption.EightAm -> "Until 8am"
        SnoozeOption.Monday -> "Until Monday Morning"
        SnoozeOption.Indefinitely -> "Indefinitely"
        SnoozeOption.Off -> "Unsnooze"
    }

private fun SnoozeOption.until(now: Instant, zone: ZoneId): SnoozeUntil? = when (this) {
    SnoozeOption.OneHour -> SnoozeUntil.OneHour
    SnoozeOption.NextReply -> SnoozeUntil.NextReply
    SnoozeOption.EightAm -> SnoozeUntil.At(nextEightAm(now, zone))
    SnoozeOption.Monday -> SnoozeUntil.At(nextMondayMorning(now, zone))
    SnoozeOption.Indefinitely -> SnoozeUntil.Indefinitely
    SnoozeOption.Off -> null
}

@Composable
private fun SnoozeDialog(dialog: OrganizeDialog.Snooze, now: () -> Instant, choose: (ThreadId, ThreadIntent) -> Unit, onDismiss: () -> Unit) {
    val options = SnoozeOption.entries.filter { it != SnoozeOption.Off || dialog.snoozed }
    AlertDialog(
        modifier = Modifier.tagsAsResourceIds(),
        onDismissRequest = onDismiss,
        title = { Text("Snooze") },
        text = {
            Column {
                Text(
                    "Hides this thread and mutes its notifications. Its automation keeps running without ending the snooze.",
                    style = AmpTheme.type.secondary,
                    color = AmpTheme.colors.text.inkMuted,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                options.forEach { option ->
                    TextButton(
                        onClick = { choose(dialog.thread, ThreadIntent.Snooze(option.until(now(), ZoneId.systemDefault()))) },
                        modifier = Modifier.fillMaxWidth().testTag(FleetTags.snooze(option)),
                    ) { Text(option.label, modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { CancelButton(onDismiss) },
    )
}

@Composable
private fun DeleteDialog(dialog: OrganizeDialog.Delete, choose: (ThreadId, ThreadIntent) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.tagsAsResourceIds(),
        onDismissRequest = onDismiss,
        title = { Text("Delete Thread") },
        text = { Text("Delete this thread? Deleting is permanent.") },
        confirmButton = {
            TextButton(onClick = { choose(dialog.thread, ThreadIntent.Delete) }, modifier = Modifier.testTag(FleetTags.deleteConfirm)) {
                Text("Delete", color = AmpTheme.colors.status.danger)
            }
        },
        dismissButton = { CancelButton(onDismiss) },
    )
}
