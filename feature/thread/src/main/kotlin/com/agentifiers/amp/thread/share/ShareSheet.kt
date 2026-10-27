package com.agentifiers.amp.thread.share

import com.agentifiers.amp.design.AmpTheme
import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.model.Multiplayer
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.Visibility
import com.agentifiers.amp.model.WorkspaceRef
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.ThreadHandle
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpSheet
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.thread.TitleBarControl
import kotlinx.coroutines.launch

/** Multiplayer's longest duration, which Contribute turns on (screens.md "Multiplayer"). */
internal const val SEVEN_DAYS_SECONDS = 604_800

/** The title bar's Share control: an icon button that opens the share sheet. */
@Composable
fun shareControl(handle: ThreadHandle, scope: AccountScope): TitleBarControl {
    val openState = remember(handle.id) { mutableStateOf(false) }
    val show = { openState.value = true }
    return TitleBarControl(ThreadTags.menuShare, "Share", show, surface = { if (openState.value) ShareSheet(handle, scope, onDismiss = { openState.value = false }) }) {
        AmpIconButton(AmpIcons.Share2, "Share", show, ThreadTags.share)
    }
}

private enum class VisibilityChoice(val label: String, val tag: String) {
    Private("Private", ThreadTags.shareVisibilityPrivate),
    Workspace("Workspace", ThreadTags.shareVisibilityWorkspace),
    Group("Group", ThreadTags.shareVisibilityGroup),
    Unlisted("Unlisted", ThreadTags.shareVisibilityUnlisted),
}

private fun Visibility.choice(): VisibilityChoice = when (this) {
    Visibility.Private -> VisibilityChoice.Private
    Visibility.Workspace -> VisibilityChoice.Workspace
    is Visibility.Groups -> VisibilityChoice.Group
    Visibility.Unlisted -> VisibilityChoice.Unlisted
}

@Composable
private fun ShareSheet(handle: ThreadHandle, scope: AccountScope, onDismiss: () -> Unit) {
    val account by scope.account.collectAsStateWithLifecycle()
    val state by handle.state.collectAsStateWithLifecycle()
    val summary = rememberSummary(scope, handle.id)
    val url = threadUrl(account, handle.id)
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val coroutines = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<VisibilityChoice?>(null) }
    var contribute by remember { mutableStateOf<Boolean?>(null) }
    var failure by rememberSharingFailure(scope, handle.id, setOf(SharingWrite.Visibility, SharingWrite.Multiplayer))

    val shown = picked.takeIf { failure == null } ?: summary?.visibility?.choice()
    val contributing = contribute.takeIf { failure == null } ?: (state.multiplayer is Multiplayer.Open)

    fun choose(choice: VisibilityChoice) {
        val visibility = when (choice) {
            VisibilityChoice.Private -> Visibility.Private
            VisibilityChoice.Workspace -> Visibility.Workspace
            VisibilityChoice.Unlisted -> Visibility.Unlisted
            // The group list is not reachable with a user token, so groups are picked on web.
            VisibilityChoice.Group -> return uriHandler.openUri(url)
        }
        failure = null
        picked = choice
        handle.dispatch(ThreadIntent.SetVisibility(visibility))
    }

    fun setContribute(on: Boolean) {
        failure = null
        contribute = on
        handle.dispatch(ThreadIntent.OpenToCollaborators(if (on) SEVEN_DAYS_SECONDS else null))
    }

    AmpSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AmpTheme.spacing.lg, vertical = AmpTheme.spacing.sm).testTag(ThreadTags.shareSheet), verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
            Text("Share", style = AmpTheme.type.paneTitle, color = AmpTheme.colors.text.ink)
            Text(url, style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                AmpButton(
                    "Copy Link",
                    {
                        coroutines.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Thread link", url)))
                            copied = true
                        }
                    },
                    ThreadTags.shareCopy,
                )
                AmpButton(
                    "Share…",
                    {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                        context.startActivity(Intent.createChooser(send, null))
                    },
                    ThreadTags.shareSystem,
                )
                if (copied) Text("Link copied", style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted, modifier = Modifier.testTag(ThreadTags.shareStatus))
            }

            Text("Visibility", style = AmpTheme.type.rowTitle, color = AmpTheme.colors.text.ink)
            Column(Modifier.selectableGroup()) {
                VisibilityChoice.entries.forEach { choice ->
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = AmpTheme.metrics.touch)
                            .selectable(selected = shown == choice, role = Role.RadioButton, onClick = { choose(choice) })
                            .testTag(choice.tag),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = shown == choice, onClick = null)
                        Text(if (choice == VisibilityChoice.Group) "Group (opens on web)" else choice.label, style = AmpTheme.type.body, color = AmpTheme.colors.text.ink, modifier = Modifier.padding(start = AmpTheme.spacing.sm))
                    }
                }
            }

            if (shown == VisibilityChoice.Workspace || shown == VisibilityChoice.Group) {
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = AmpTheme.metrics.touch)
                        .toggleable(value = contributing, role = Role.Checkbox, onValueChange = ::setContribute)
                        .testTag(ThreadTags.shareContribute),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = contributing, onCheckedChange = null)
                    Column(Modifier.padding(start = AmpTheme.spacing.sm)) {
                        Text("Contribute", style = AmpTheme.type.body, color = AmpTheme.colors.text.ink)
                        Text("Members can prompt this thread for 7 days (multiplayer).", style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted)
                    }
                }
            }

            if (account.workspace == WorkspaceRef.Personal) {
                AmpButton(
                    "Create Workspace",
                    { uriHandler.openUri(account.server.ampBaseUrl.trimEnd('/') + AccountWebPage.WorkspaceAdmin.path) },
                    ThreadTags.shareCreateWorkspace,
                    rank = AmpButtonRank.Link,
                )
            }

            failure?.let { reason ->
                Text("Sharing did not change: $reason", style = AmpTheme.type.secondary, color = AmpTheme.colors.status.danger, modifier = Modifier.testTag(ThreadTags.shareFailure))
                AmpButton("Open Share on web", { uriHandler.openUri(url) }, ThreadTags.shareOpenWeb, rank = AmpButtonRank.Link)
            }
        }
    }
}
