package com.agentifiers.amp.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.components.AmpDropdownMenu
import com.agentifiers.amp.design.components.AmpHeaderRow
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.ExecutorStatus
import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.StopReason
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.fleetStatus
import com.agentifiers.amp.model.FleetStatus

/**
 * An item in the title bar's More Actions menu. [checked] non-null renders a checkbox. A [destructive] item renders
 * last, under a divider, in the danger color, wherever it sits in the list.
 */
class ThreadMenuItem(val label: String, val tag: String, val checked: Boolean?, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * A title bar control after the title. Controls are listed in screens.md's leave order, highest priority first.
 * [trigger] is the control in the row, a full 48dp target. One that does not fit leaves for the top of More Actions as
 * [menuLabel] with [menuTag], where [open] does what a tap on [trigger] does; null makes the entry information only.
 * [surface] is the sheet, dialog or menu the control opens. It is composed beside More Actions whether or not the
 * trigger is in the row, so it opens from either place.
 */
class TitleBarControl(
    val menuTag: String,
    val menuLabel: String,
    val open: (() -> Unit)?,
    val surface: (@Composable () -> Unit)? = null,
    val trigger: @Composable () -> Unit,
)

/**
 * A word in the status line after the agent's state, A2 C's `chips: meta`: the executor, the mode. An optional status
 * [dot] leads it, and [description] replaces what TalkBack reads. A non-null [note] lists it in More Actions.
 */
class StatusWord(
    val text: String,
    val testTag: String,
    val description: String = text,
    val dot: Color? = null,
    val note: StatusWordNote? = null,
)

/**
 * A status word's read-only entry under "In the title bar" in More Actions: the word's description over [text], a 48dp
 * row tagged [menuTag]. The word itself is only as tall as the status line, too small to tap, so it opens nothing.
 */
class StatusWordNote(val menuTag: String, val text: String)

/** The executor as a word with its state's dot. [text] spells both out for TalkBack. */
data class ExecutorChip(val name: String, val state: String?) {
    val text: String get() = listOfNotNull(name, state).joinToString(" · ")
}

@Composable
fun executorWord(chip: ExecutorChip) = StatusWord(chip.name, ThreadTags.executor, description = chip.text, dot = chip.state?.let { executorDot(it) })

@Composable
private fun executorDot(state: String): Color = when (state) {
    "running" -> AmpTheme.colors.status.success
    "failed" -> AmpTheme.colors.status.danger
    else -> AmpTheme.colors.status.idle
}

@Composable
private fun StatusWordView(word: StatusWord) {
    Row(
        Modifier.testTag(word.testTag)
            .clearAndSetSemantics { contentDescription = word.description },
        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        word.dot?.let { Box(Modifier.size(AmpTheme.metrics.chipDot).clip(CircleShape).background(it)) }
        Text(word.text, style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted, maxLines = 1)
    }
}

/** A 24dp chip in a full 48dp target. A null [onClick] makes the chip a label. */
@Composable
internal fun TitleChip(label: String, testTag: String, onClick: (() -> Unit)? = null) {
    val shape = AmpTheme.shapes.chip
    Box(
        Modifier
            .sizeIn(minWidth = AmpTheme.metrics.touch, minHeight = AmpTheme.metrics.touch)
            .testTag(testTag)
            .then(if (onClick != null) Modifier.clip(shape).clickable(role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = AmpTheme.metrics.chip).background(AmpTheme.surfaceRoles.chip, shape).padding(horizontal = AmpTheme.spacing.chipPadX),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = AmpTheme.type.label, color = AmpTheme.colors.text.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * One header row, 48dp at the default font scale: the title with the status line under it on the shared header
 * baseline, then [controls], then More Actions (screens.md "Title bar"). The status line is the agent's [status], then
 * [words]. A title without a status line centers on the row. [titleBarFit] decides which controls stay. The rest are
 * not placed, so they leave no hit area in the row, and appear under "In the title bar" in More Actions.
 */
@Composable
fun ThreadTitleBar(
    title: String,
    status: String?,
    menu: List<ThreadMenuItem>,
    modifier: Modifier = Modifier,
    statusColor: Color = AmpTheme.colors.text.inkMuted,
    words: List<StatusWord> = emptyList(),
    controls: List<TitleBarControl> = emptyList(),
) {
    var shown by remember { mutableIntStateOf(controls.size) }
    AmpHeaderRow(modifier, testTag = null, kind = "thread") {
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                Column {
                    Text(title, style = AmpTheme.type.threadTitle, color = AmpTheme.colors.text.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(ThreadTags.title))
                    if (status != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(status, style = AmpTheme.type.meta, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag(ThreadTags.agentStatus))
                            words.forEach { word ->
                                Text(" · ", style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted, maxLines = 1)
                                StatusWordView(word)
                            }
                        }
                    }
                }
                controls.forEach { control -> Box { control.trigger() } }
                MoreActions(menu, controls, moved = controls.drop(shown), words)
            },
        ) { measurables, constraints ->
            val loose = Constraints()
            val heading = measurables.first()
            val triggers = measurables.subList(1, measurables.size - 1)
            val fit = titleBarFit(
                width = constraints.maxWidth.toDp(),
                title = heading.maxIntrinsicWidth(Constraints.Infinity).toDp(),
                controls = triggers.map { it.maxIntrinsicWidth(Constraints.Infinity).toDp() },
                menu = menu.isNotEmpty() || words.any { it.note != null },
            )
            if (fit != shown) shown = fit
            val more = measurables.last().measure(loose)
            val placed = triggers.take(fit).map { it.measure(loose) }
            val start = TitleStart.roundToPx()
            val titleWidth = (constraints.maxWidth - start - TitleEnd.roundToPx() - more.width - placed.sumOf { it.width }).coerceAtLeast(0)
            val headingPlaced = heading.measure(Constraints(maxWidth = titleWidth))
            val row = AmpTheme.metrics.paneHeader.roundToPx()
            val titleTop = if (status == null) (row - headingPlaced.height) / 2 else AmpTheme.metrics.headerBaseline.roundToPx() - headingPlaced[FirstBaseline]
            val height = maxOf(row, titleTop + headingPlaced.height, more.height, placed.maxOfOrNull { it.height } ?: 0)
            layout(constraints.maxWidth, height) {
                headingPlaced.place(start, titleTop)
                var x = start + titleWidth
                placed.forEach { trigger ->
                    trigger.place(x, (height - trigger.height) / 2)
                    x += trigger.width
                }
                more.place(x, (height - more.height) / 2)
            }
        }
    }
}

@Composable
private fun MoreActions(menu: List<ThreadMenuItem>, controls: List<TitleBarControl>, moved: List<TitleBarControl>, words: List<StatusWord>) {
    var open by remember { mutableStateOf(false) }
    val notes = words.mapNotNull { word -> word.note?.let { word.description to it } }
    Box {
        if (menu.isNotEmpty() || moved.isNotEmpty() || notes.isNotEmpty()) {
            AmpIconButton(AmpIcons.EllipsisVertical, "More Actions", { open = true }, ThreadTags.menu)
        }
        AmpDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (moved.isNotEmpty() || notes.isNotEmpty()) {
                Text(
                    "In the title bar",
                    style = AmpTheme.type.label,
                    color = AmpTheme.colors.text.inkMuted,
                    modifier = Modifier.padding(horizontal = AmpTheme.spacing.md, vertical = AmpTheme.spacing.sm).semantics { heading() },
                )
                moved.forEach { control ->
                    DropdownMenuItem(
                        text = { Text(control.menuLabel, style = AmpTheme.type.body) },
                        enabled = control.open != null,
                        onClick = {
                            open = false
                            control.open?.invoke()
                        },
                        modifier = Modifier.testTag(control.menuTag),
                    )
                }
                notes.forEach { (label, note) ->
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.touch).testTag(note.menuTag).semantics(mergeDescendants = true) {}
                            .padding(horizontal = AmpTheme.spacing.md, vertical = AmpTheme.spacing.sm),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(label, style = AmpTheme.type.body, color = AmpTheme.colors.text.ink)
                        Text(note.text, style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted)
                    }
                }
                if (menu.isNotEmpty()) HorizontalDivider(color = AmpTheme.colors.surfaces.line)
            }
            val ordered = menu.sortedBy { it.destructive }
            ordered.forEachIndexed { index, item ->
                if (item.destructive && index > 0 && !ordered[index - 1].destructive) HorizontalDivider(color = AmpTheme.colors.surfaces.line)
                DropdownMenuItem(
                    text = { Text(item.label, style = AmpTheme.type.body, color = if (item.destructive) AmpTheme.colors.status.danger else Color.Unspecified) },
                    trailingIcon = item.checked?.let { checked -> { Checkbox(checked = checked, onCheckedChange = null) } },
                    onClick = {
                        item.onClick()
                        open = false
                    },
                    modifier = Modifier.testTag(item.tag),
                )
            }
        }
        controls.forEach { control -> control.surface?.invoke() }
    }
}

private val TitleStart = AmpTheme.spacing.paneX
private val TitleEnd = AmpTheme.spacing.xs

/**
 * How many [controls] stay in a title bar [width] wide, in priority order. The title keeps
 * `AmpTheme.metrics.titleMin`, or its own [title] width when that is shorter, and More Actions keeps one 48dp target
 * unless [menu] is empty and every control fits. A control that does not fit takes every lower one with it, so the
 * row never shows a lower priority control in place of a higher one.
 */
internal fun titleBarFit(width: Dp, title: Dp, controls: List<Dp>, menu: Boolean): Int {
    val room = width - TitleStart - TitleEnd - minOf(title, AmpTheme.metrics.titleMin)
    if (!menu && controls.fold(0.dp) { sum, control -> sum + control } <= room) return controls.size
    var left = room - AmpTheme.metrics.touch
    controls.forEachIndexed { index, control ->
        left -= control
        if (left < 0.dp) return index
    }
    return controls.size
}

/** The status under the title. A live socket reports the agent in the sidebar row's words; other states describe the socket. */
fun statusLine(state: ThreadState): String = when (val link = state.link) {
    Link.Live -> state.fleetStatus(unread = false)?.word ?: "connected"
    Link.Resuming -> "catching up"
    Link.Connecting -> "connecting"
    is Link.Reconnecting -> "reconnecting, attempt ${link.attempt}: ${link.cause}"
    Link.Closed -> "not connected"
    is Link.Stopped -> when (link.reason) {
        StopReason.Archived -> "archived"
        StopReason.MissingRivetToken -> "this build has no Rivet token"
    }
}

/** The status word's color: the sidebar row's color for the agent's state on a live socket, muted otherwise. */
@Composable
fun statusColor(state: ThreadState): Color {
    val colors = AmpTheme.colors
    if (state.link != Link.Live) return colors.text.inkMuted
    return when (state.fleetStatus(unread = false)) {
        is FleetStatus.Waiting -> colors.status.needsYou
        FleetStatus.Working, FleetStatus.Compacting -> colors.status.working
        FleetStatus.Error -> colors.status.error
        else -> colors.text.inkMuted
    }
}

/** The executor chip for the kind and state the `observers` and `executor_status` events report. */
fun executorChip(executor: ExecutorStatus): ExecutorChip? = when (executor) {
    ExecutorStatus.Unknown -> null
    ExecutorStatus.None -> ExecutorChip("No executor", null)
    ExecutorStatus.OrbAsleep, is ExecutorStatus.OrbWakeFailed -> ExecutorChip("Orb asleep", null)
    ExecutorStatus.OrbWaking -> ExecutorChip("Waking orb", null)
    is ExecutorStatus.Attached -> if (executor.kind == null && executor.status == null) null else ExecutorChip(executorKind(executor.kind), executor.status)
}

private fun executorKind(kind: String?): String = when (kind) {
    null -> "Executor"
    "local-client" -> "Runner"
    else -> kind.replaceFirstChar(Char::uppercase)
}
