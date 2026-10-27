package com.agentifiers.amp.thread.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.organize.OrganizeDialog
import com.agentifiers.amp.design.organize.OrganizeDialogHost
import com.agentifiers.amp.model.SnoozeState
import com.agentifiers.amp.model.ThreadColor
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.thread.ThreadMenuItem
import kotlin.time.Clock

/** The organize items of the thread's More Actions menu (screens.md "Organize actions"), in that table's order. */
@Composable
fun organizeMenuItems(scope: AccountScope, thread: ThreadId, open: (OrganizeDialog) -> Unit): List<ThreadMenuItem> {
    val handle = scope.thread(thread)
    val state by handle.state.collectAsStateWithLifecycle()
    val summary = rememberSummary(scope, thread)
    val pinned = summary?.pinned == true
    val archived = summary?.archived == true
    return listOf(
        ThreadMenuItem(if (pinned) "Unpin" else "Pin", ThreadTags.menuPin, checked = null) { handle.dispatch(ThreadIntent.Pin(!pinned)) },
        ThreadMenuItem("Color", ThreadTags.menuColor, checked = null) { open(OrganizeDialog.Color(thread, summary?.color ?: ThreadColor.None)) },
        ThreadMenuItem("Labels", ThreadTags.menuLabels, checked = null) { open(OrganizeDialog.Labels(thread, summary?.labels.orEmpty())) },
        ThreadMenuItem("Rename", ThreadTags.menuRename, checked = null) {
            open(OrganizeDialog.Rename(thread, summary?.title ?: state.title.orEmpty()))
        },
        ThreadMenuItem("Snooze", ThreadTags.menuSnooze, checked = null) {
            open(OrganizeDialog.Snooze(thread, snoozed = summary?.snooze is SnoozeState.Snoozed))
        },
        ThreadMenuItem(if (archived) "Unarchive" else "Archive", ThreadTags.menuArchive, checked = null) {
            handle.dispatch(ThreadIntent.Archive(!archived))
        },
        ThreadMenuItem("Delete", ThreadTags.menuDelete, checked = null, destructive = true) { open(OrganizeDialog.Delete(thread)) },
        ThreadMenuItem("Mark as Unread", ThreadTags.menuMarkUnread, checked = null) { handle.dispatch(ThreadIntent.MarkUnread) },
    )
}

/** Hosts the dialog an [organizeMenuItems] item opened. */
@Composable
fun ThreadOrganizeDialogs(scope: AccountScope, dialog: OrganizeDialog?, onDismiss: () -> Unit) {
    val labels by remember(scope) { scope.fleet.labels() }.collectAsStateWithLifecycle()
    OrganizeDialogHost(
        dialog = dialog,
        knownLabels = labels,
        now = Clock.System::now,
        onIntent = { thread, intent -> scope.thread(thread).dispatch(intent) },
        onDismiss = onDismiss,
    )
}
