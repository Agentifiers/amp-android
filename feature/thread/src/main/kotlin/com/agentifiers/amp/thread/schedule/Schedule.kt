package com.agentifiers.amp.thread.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpSheet
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.tagsAsResourceIds
import com.agentifiers.amp.model.AccountCommand
import com.agentifiers.amp.model.AccountResource
import com.agentifiers.amp.model.AutomationStatus
import com.agentifiers.amp.model.IntentId
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadSchedule
import com.agentifiers.amp.model.line
import com.agentifiers.amp.model.nextChange
import com.agentifiers.amp.model.scheduleFor
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.Loadable
import com.agentifiers.amp.sync.api.Outcome
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * [thread]'s schedule from the account's automations. The list is read again when the view opens and when a run ends,
 * which is when the web reads its own (chunk e2ccdf0e.js, `Oa(!0)` on mount and when `isRunning` goes false).
 */
@Composable
internal fun rememberThreadSchedule(scope: AccountScope, thread: ThreadId, busy: Boolean): ThreadSchedule? {
    val read = remember(scope) { scope.read(AccountResource.Automations) }
    val automations by read.collectAsStateWithLifecycle()
    val openedAt = remember(thread) { Clock.System.now() }
    LaunchedEffect(scope, thread) {
        if (read.value is Loadable.Ready) scope.refresh(AccountResource.Automations)
    }
    var ran by remember(thread) { mutableStateOf(false) }
    LaunchedEffect(scope, thread, busy) {
        when {
            busy -> ran = true
            ran -> {
                ran = false
                scope.refresh(AccountResource.Automations)
            }
        }
    }
    return remember(automations, thread, openedAt) { (automations as? Loadable.Ready)?.value?.scheduleFor(thread, openedAt) }
}

/**
 * The web's automation summary at the end of an idle transcript: "Next run in 5m · Last run 2h ago", or why the
 * schedule stopped. A tap opens [ScheduleSheet], as the web's line opens its popover.
 */
@Composable
internal fun ScheduleRow(schedule: ThreadSchedule, onOpen: () -> Unit) {
    val line = schedule.line(rememberNow(schedule))
    val color = if (line.attention) AmpTheme.colors.status.danger else AmpTheme.colors.text.inkMuted
    Row(
        Modifier.heightIn(min = AmpTheme.metrics.touch).clip(AmpTheme.shapes.control)
            .clickable(role = Role.Button, onClickLabel = "View automation", onClick = onOpen)
            .padding(horizontal = AmpTheme.spacing.toolPadX).testTag(ThreadTags.schedule),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.toolInnerGap),
    ) {
        Icon(schedule.automation.status.icon, contentDescription = null, tint = color, modifier = Modifier.size(AmpTheme.metrics.iconSmall))
        Text(listOfNotNull(line.lead, line.lastRun).joinToString(" · "), style = AmpTheme.type.meta, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The web's automation popover (chunk fa551f92.js `pt`) as a sheet: the title with the pause switch, the schedule and
 * its line, why it stopped, and Delete with the web's confirmation. Writes go through [AccountScope.dispatch], which
 * reads the list again, so the line under the sheet updates when the write lands.
 */
@Composable
internal fun ScheduleSheet(scope: AccountScope, schedule: ThreadSchedule, onDismiss: () -> Unit) {
    val automation = schedule.automation
    val line = schedule.line(rememberNow(schedule))
    var pending by remember { mutableStateOf<Pair<IntentId, String>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(scope) {
        scope.commandResults.collect { result ->
            val (intent, message) = pending ?: return@collect
            if (result.intent != intent) return@collect
            pending = null
            if (result.outcome !is Outcome.Ok) failure = message
        }
    }
    fun write(command: AccountCommand, message: String) {
        failure = null
        pending = scope.dispatch(command) to message
    }

    AmpSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = AmpTheme.spacing.paneX).padding(bottom = AmpTheme.spacing.md).testTag(ThreadTags.scheduleSheet),
            verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs),
        ) {
            val switchLabel = if (automation.enabled) "Pause automation" else "Enable automation"
            Row(
                Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.touch).clip(AmpTheme.shapes.control)
                    .toggleable(value = automation.enabled, enabled = pending == null, role = Role.Switch) { on ->
                        write(AccountCommand.SetAutomationEnabled(automation.id, on), if (on) "Could not enable automation" else "Could not pause automation")
                    }
                    .semantics { contentDescription = switchLabel }.testTag(ThreadTags.scheduleEnabled),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm),
            ) {
                Text(automation.title, style = AmpTheme.type.rowTitle, color = AmpTheme.colors.text.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Switch(checked = automation.enabled, onCheckedChange = null, enabled = pending == null)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs)) {
                Icon(AmpIcons.Clock, contentDescription = null, tint = AmpTheme.colors.text.inkMuted, modifier = Modifier.padding(top = AmpTheme.spacing.xs / 2).size(AmpTheme.metrics.iconSmall))
                Text(listOfNotNull(automation.trigger, line.lead, line.lastRun).joinToString(" · "), style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted)
            }
            automation.status.explanation?.let {
                Text(it, style = AmpTheme.type.secondary, color = if (line.attention) AmpTheme.colors.status.danger else AmpTheme.colors.text.inkMuted)
            }
            automation.lastRunError?.takeIf { automation.status == AutomationStatus.Failed }?.let {
                Text(it, style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            failure?.let { Text(it, style = AmpTheme.type.secondary, color = AmpTheme.colors.status.danger, modifier = Modifier.testTag(ThreadTags.scheduleFailure)) }
            AmpButton("Delete Automation", { deleting = true }, ThreadTags.scheduleDelete, enabled = pending == null)
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            modifier = Modifier.tagsAsResourceIds(),
            title = { Text("Delete Automation?") },
            text = { Text("“${automation.title}” will be permanently deleted and will no longer run.") },
            confirmButton = {
                AmpButton(
                    "Delete Automation",
                    {
                        deleting = false
                        write(AccountCommand.DeleteAutomation(automation.id), "Could not delete automation")
                    },
                    ThreadTags.scheduleDeleteConfirm,
                    rank = AmpButtonRank.Destructive,
                )
            },
            dismissButton = { AmpButton("Cancel", { deleting = false }, ThreadTags.scheduleDeleteCancel) },
        )
    }
}

/** The web's indicator icons (chunk fa551f92.js `mt`); the paused one is the clock, since the icon set has no pause glyph. */
private val AutomationStatus.icon: ImageVector
    get() = when (this) {
        AutomationStatus.Active, AutomationStatus.Paused -> AmpIcons.Clock
        AutomationStatus.Failed -> AmpIcons.CircleAlert
        AutomationStatus.Completed -> AmpIcons.CircleCheck
    }

/** The clock the line reads, moved only when its text can change ([nextChange]) and only while the window is started. */
@Composable
private fun rememberNow(schedule: ThreadSchedule): Instant {
    var now by remember { mutableStateOf(Clock.System.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(schedule, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = Clock.System.now()
                delay(schedule.nextChange(now) ?: awaitCancellation())
            }
        }
    }
    return now
}
