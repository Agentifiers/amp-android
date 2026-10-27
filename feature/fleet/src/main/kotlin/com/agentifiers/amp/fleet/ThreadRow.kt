package com.agentifiers.amp.fleet

import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.components.AmpDropdownMenu
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.FleetTags
import com.agentifiers.amp.design.FleetTags.RowAction
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpListRow
import com.agentifiers.amp.design.components.AmpRowMore
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.organize.OrganizeDialog
import com.agentifiers.amp.design.organize.swatch
import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.SnoozeState
import com.agentifiers.amp.model.ThreadColor
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadProject
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.sync.api.AccountScope
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What a Sidebar row can do: open its thread here or in a new window, send an organize intent, or ask for an organize dialog. */
internal class RowActions(
    val open: (ThreadId) -> Unit,
    val intent: (ThreadId, ThreadIntent) -> Unit,
    val dialog: (OrganizeDialog) -> Unit,
    val openWindow: (ThreadId) -> Unit,
)

/** The action a Needs you row puts on its title line; it learns whether the row's gate summary shows in full. */
internal typealias RowTrailing = @Composable (summaryFits: Boolean) -> Unit

/** A Sidebar row plus its child threads, collapsed under a "N subthreads" toggle. */
@Composable
internal fun ThreadTree(
    summary: ThreadSummary,
    scope: AccountScope,
    selected: ThreadId?,
    showProject: Boolean,
    usualExecutor: String?,
    now: Instant,
    actions: RowActions,
    depth: Int = 0,
    trailing: RowTrailing? = null,
) {
    val children by remember(summary.id) { scope.fleet.children(summary.id) }.collectAsStateWithLifecycle()
    var open by rememberSaveable(summary.id.raw) { mutableStateOf(false) }
    Column {
        ThreadRow(summary, summary.id == selected, showProject, usualExecutor, now, actions, depth, trailing)
        if (children.isNotEmpty()) {
            AmpButton(
                text = if (children.size == 1) "1 subthread" else "${children.size} subthreads",
                onClick = { open = !open },
                testTag = FleetTags.rowChildren(summary.id),
                modifier = Modifier.padding(start = AmpTheme.spacing.lg * (depth + 1)),
                rank = AmpButtonRank.Link,
                trailingIcon = if (open) AmpIcons.ChevronDown else AmpIcons.ChevronRight,
            )
            if (open) {
                children.forEach { child -> ThreadTree(child, scope, selected, showProject, usualExecutor, now, actions, depth + 1) }
            }
        }
    }
}

/** A Sidebar or search row: [ThreadListRow] with the row menu, and a Needs you row's [trailing] action on its title line. */
@Composable
internal fun ThreadRow(
    summary: ThreadSummary,
    selected: Boolean,
    showProject: Boolean,
    usualExecutor: String?,
    now: Instant,
    actions: RowActions,
    depth: Int = 0,
    trailing: RowTrailing? = null,
) {
    var summaryFits by remember(summary.id) { mutableStateOf(true) }
    ThreadListRow(
        summary = summary,
        now = now,
        onClick = { actions.open(summary.id) },
        testTag = FleetTags.row(summary.id),
        modifier = Modifier.padding(start = AmpTheme.spacing.lg * depth),
        selected = selected,
        showProject = showProject,
        usualExecutor = usualExecutor,
        onSummaryFits = { summaryFits = it },
        titleAction = trailing?.let { action -> { action(summaryFits) } },
    ) {
        RowMenu(summary, actions)
    }
}

/**
 * One thread on an [AmpListRow], shared by the Sidebar, search, Activity, and Running: the status dot, the title (semibold
 * while unread) and its age, and a second line with the gate summary for a row that waits on the user, else the status
 * word (none for idle), the executor when it is not [usualExecutor], the project, and labels, then the [note]. A row with
 * nothing to say there is one line. A colored thread gets a stripe on the row's leading edge.
 */
@Composable
internal fun ThreadListRow(
    summary: ThreadSummary,
    now: Instant,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    showProject: Boolean = true,
    usualExecutor: String? = null,
    note: String? = null,
    onSummaryFits: (Boolean) -> Unit = {},
    titleAction: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val status = summary.status
    val dot = status.dot
    val word = status.word.takeUnless { status == FleetStatus.Idle || status == FleetStatus.Inactive }
    val waiting = status as? FleetStatus.Waiting
    val executor = summary.executor?.takeIf { it != usualExecutor }
    val project = summary.project.displayName.takeIf { showProject }
    val labels = summary.labels.take(2)
    val type = AmpTheme.type
    val muted = AmpTheme.colors.text.inkMuted
    val hasSecondLine = word != null || waiting != null || executor != null || project != null || labels.isNotEmpty() || note != null
    Box(modifier) {
        AmpListRow(
            title = summary.title ?: "Untitled",
            onClick = onClick,
            testTag = testTag,
            modifier = Modifier.semantics {
                this.selected = selected
                stateDescription = listOfNotNull(status.word, "unread".takeIf { summary.unread }).joinToString(", ")
            },
            selected = selected,
            meta = relativeTime(now, summary.lastActivity).ifEmpty { null },
            // Unread shows as weight, not a second dot beside the status dot (UX-23).
            titleStyle = if (summary.unread) type.rowTitle.copy(fontWeight = FontWeight.SemiBold) else type.rowTitle,
            leading = { StatusDot(status, Modifier.size(AmpTheme.metrics.dot)) },
            secondLine = if (!hasSecondLine) null else {
                {
                    word?.let { Text(it, color = dot.color, style = type.meta, maxLines = 1, modifier = Modifier.align(Alignment.Top).testTag(FleetTags.rowStatus(summary.id, it))) }
                    if (waiting != null) {
                        Text(
                            waiting.summary,
                            color = AmpTheme.colors.text.ink,
                            style = type.meta,
                            // Two lines that run to the row menu; Allow shows only for a summary that fits whole.
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { onSummaryFits(!it.hasVisualOverflow) },
                            modifier = Modifier.weight(1f, fill = false).testTag(FleetTags.rowSummary(summary.id)),
                        )
                    } else {
                        // The executor and project split the width the status word and labels leave, so neither pushes the other off the row.
                        executor?.let {
                            Icon(AmpIcons.Server, contentDescription = null, tint = muted, modifier = Modifier.size(AmpTheme.metrics.iconSmall))
                            Text(it, color = muted, style = type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        }
                        project?.let {
                            Text(it, color = muted, style = type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag(FleetTags.rowProject(summary.id)))
                        }
                        labels.forEach { label ->
                            Text(
                                label.raw,
                                color = AmpTheme.colors.text.ink,
                                style = type.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = LABEL_MAX).clip(AmpTheme.shapes.control).background(AmpTheme.colors.surfaces.raised)
                                    .padding(horizontal = AmpTheme.spacing.xs),
                            )
                        }
                    }
                    note?.let { Text(it, color = muted, style = type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                }
            },
            titleAction = titleAction,
            trailing = trailing,
        )
        if (summary.color != ThreadColor.None) {
            val name = summary.color.name.lowercase()
            Box(Modifier.matchParentSize().clip(AmpTheme.shapes.control)) {
                Box(
                    Modifier.fillMaxHeight().width(STRIPE).background(summary.color.swatch())
                        .semantics { contentDescription = "Color $name" }.testTag(FleetTags.rowStripe(summary.id, name)),
                )
            }
        }
    }
}

/** A colored thread's stripe, in rows and on the rail. */
internal val STRIPE = 3.dp
private val LABEL_MAX = 96.dp

/**
 * The executor most of the account's threads run on, which rows leave out because it says nothing (UX-06). Null
 * when no executor covers at least half of the threads that name one, so a mixed fleet keeps every badge.
 */
internal fun usualExecutor(threads: List<ThreadSummary>): String? {
    val named = threads.mapNotNull { it.executor }
    val (executor, count) = named.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
    return executor.takeIf { count * 2 >= named.size }
}

@Composable
private fun RowMenu(summary: ThreadSummary, actions: RowActions) {
    var expanded by remember { mutableStateOf(false) }
    val id = summary.id
    val snoozed = summary.snooze is SnoozeState.Snoozed
    val items: List<Triple<RowAction, String, () -> Unit>> = listOf(
        Triple(RowAction.Pin, if (summary.pinned) "Unpin" else "Pin") { actions.intent(id, ThreadIntent.Pin(!summary.pinned)) },
        Triple(RowAction.Color, "Color…") { actions.dialog(OrganizeDialog.Color(id, summary.color)) },
        Triple(RowAction.Labels, "Labels…") { actions.dialog(OrganizeDialog.Labels(id, summary.labels)) },
        Triple(RowAction.Rename, "Rename…") { actions.dialog(OrganizeDialog.Rename(id, summary.title.orEmpty())) },
        Triple(RowAction.Snooze, if (snoozed) "Unsnooze" else "Snooze…") {
            if (snoozed) actions.intent(id, ThreadIntent.Snooze(null)) else actions.dialog(OrganizeDialog.Snooze(id, snoozed = false))
        },
        Triple(RowAction.Archive, if (summary.archived) "Unarchive" else "Archive") { actions.intent(id, ThreadIntent.Archive(!summary.archived)) },
        Triple(RowAction.MarkUnread, "Mark as Unread") { actions.intent(id, ThreadIntent.MarkUnread) },
        Triple(RowAction.NewWindow, "Open in New Window") { actions.openWindow(id) },
        Triple(RowAction.Delete, "Delete…") { actions.dialog(OrganizeDialog.Delete(id)) },
    )
    Box {
        AmpRowMore({ expanded = true }, FleetTags.rowMore(id), "More actions for ${summary.title ?: "Untitled"}")
        AmpDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { (action, label, run) ->
                if (action == RowAction.Delete) HorizontalDivider(color = AmpTheme.colors.surfaces.line)
                DropdownMenuItem(
                    text = { Text(label, color = if (action == RowAction.Delete) AmpTheme.colors.status.danger else AmpTheme.colors.text.ink) },
                    onClick = {
                        expanded = false
                        run()
                    },
                    modifier = Modifier.testTag(FleetTags.rowAction(id, action)),
                )
            }
        }
    }
}

internal val ThreadProject.displayName: String
    get() = when (this) {
        ThreadProject.None -> "No Project"
        is ThreadProject.Linked -> name
    }

/** "now", "5m", "3h", "2d" or "4w" since [at]; blank when the time is unknown. */
internal fun relativeTime(now: Instant, at: Instant?): String {
    if (at == null) return ""
    val age = now - at
    return when {
        age < 1.minutes -> "now"
        age < 1.hours -> "${age.inWholeMinutes}m"
        age < 1.days -> "${age.inWholeHours}h"
        age < 7.days -> "${age.inWholeDays}d"
        else -> "${age.inWholeDays / 7}w"
    }
}

/** Wall clock for relative times, ticking often enough that "now" becomes "1m" on time. */
@Composable
internal fun rememberNow(): Instant {
    val now by produceState(Clock.System.now()) {
        while (true) {
            delay(30_000)
            value = Clock.System.now()
        }
    }
    return now
}
