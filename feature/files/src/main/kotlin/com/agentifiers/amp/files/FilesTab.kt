package com.agentifiers.amp.files

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.FilesTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.model.FileNode
import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.WebSurface
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.Outcome
import kotlinx.coroutines.launch

/** The Files machine tab: tree, viewer, upload, save and share. Tree and viewer sit side by side from 600dp. */
@Composable
fun FilesTab(
    dest: PaneDestination.Machine,
    tab: MachineTab.Files,
    scope: AccountScope,
    openWeb: (WebSurface) -> Unit,
    modifier: Modifier = Modifier,
) {
    val files = remember(scope, dest.thread) { scope.thread(dest.thread).machine.files }
    val account by scope.account.collectAsStateWithLifecycle()
    val openFilesOnWeb = { openWeb(WebSurface.AccountPage(AccountWebPage.ThreadOnWeb, account.id, "/${dest.thread.raw}")) }
    val context = LocalContext.current
    val ui = rememberCoroutineScope()
    val root = tab.path ?: "."
    var expanded by rememberSaveable(dest.thread, root) { mutableStateOf(listOf<String>()) }
    var selectedDir by rememberSaveable(dest.thread, root) { mutableStateOf<String?>(null) }
    var viewer by remember(dest.thread, root) { mutableStateOf<ViewerState?>(null) }
    var transfer by remember(dest.thread) { mutableStateOf<Transfer>(Transfer.Idle) }
    val target = selectedDir ?: root

    val listings = HashMap<String, FileNode>()
    (listOf(root) + expanded).forEach { dir ->
        key(dir) { listings[dir] = remember(files, dir) { files.tree(dir) }.collectAsStateWithLifecycle().value }
    }
    val rows = flatten(root, listings, expanded)

    val reading = viewer as? ViewerState.Reading
    LaunchedEffect(reading) {
        if (reading == null) return@LaunchedEffect
        val path = reading.path
        viewer = when (val outcome = files.read(path)) {
            is Outcome.Ok -> ViewerState.Shown(path, outcome.value)
            is Outcome.Failed -> ViewerState.Unavailable(path, describe(outcome.error))
            Outcome.Unknown -> ViewerState.Unavailable(path, "The connection dropped before the runner answered.")
            is Outcome.Refused -> ViewerState.Unavailable(path, outcome.why)
        }
    }

    fun send(pick: Pick, overwrite: Boolean) {
        transfer = Transfer.Sending(pick)
        ui.launch { transfer = upload(files, context.contentResolver, pick, overwrite) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val dir = target
        ui.launch {
            val (name, size) = context.contentResolver.describePick(uri)
            if (size == null) {
                transfer = Transfer.Settled("The document provider did not report the size of $name.", offerWeb = false)
            } else {
                send(Pick(uri, name, size, dir), overwrite = false)
            }
        }
    }

    (transfer as? Transfer.AskReplace)?.let { ask ->
        AlertDialog(
            onDismissRequest = { transfer = Transfer.Idle },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            text = { Text("${ask.pick.name} already exists. Replace it?") },
            confirmButton = {
                AmpButton("Replace", { send(ask.pick, overwrite = true) }, FilesTags.replaceConfirm, rank = AmpButtonRank.Destructive)
            },
            dismissButton = {
                AmpButton("Cancel", { transfer = Transfer.Idle }, FilesTags.replaceCancel)
            },
        )
    }

    BoxWithConstraints(modifier.fillMaxSize().background(LocalPaneSurface.current)) {
        val wide = maxWidth >= 600.dp
        val open = viewer
        BackHandler(enabled = open != null && !wide) { viewer = null }
        Row(Modifier.fillMaxSize()) {
            if (open == null || wide) {
                Column(Modifier.weight(if (open == null) 1f else 0.4f)) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.subRow).padding(horizontal = AmpTheme.spacing.paneX),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm),
                    ) {
                        val title = treeTitle(root)
                        if (title != null) {
                            Text(title, Modifier.weight(1f), style = AmpTheme.type.rowTitle, color = AmpTheme.colors.text.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        AmpButton(
                            if (target == root) "Upload" else "Upload to ${fileName(target)}",
                            { picker.launch(arrayOf("*/*")) },
                            FilesTags.upload,
                            enabled = transfer !is Transfer.Sending,
                        )
                    }
                    UploadStatus(transfer, openFilesOnWeb, onDismiss = { transfer = Transfer.Idle })
                    FileTree(
                        rows = rows,
                        selectedDir = selectedDir,
                        openFile = open?.path,
                        onDir = { dir ->
                            selectedDir = dir
                            expanded = if (dir in expanded) expanded.filterNot { it == dir || it.startsWith("$dir/") } else expanded + dir
                        },
                        onFile = { path -> viewer = opening(path) },
                        openFilesOnWeb = openFilesOnWeb,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (open != null) {
                if (wide) VerticalDivider(color = AmpTheme.colors.surfaces.line)
                FileViewer(
                    state = open,
                    onOpenAnyway = { viewer = ViewerState.Reading(open.path) },
                    onClose = { viewer = null },
                    openFilesOnWeb = openFilesOnWeb,
                    modifier = Modifier.weight(if (wide) 0.6f else 1f),
                )
            }
        }
    }
}

@Composable
private fun UploadStatus(transfer: Transfer, openFilesOnWeb: () -> Unit, onDismiss: () -> Unit) {
    val (message, settled) = when (transfer) {
        is Transfer.Sending -> "Uploading ${transfer.pick.name} to ${transfer.pick.dir}…" to null
        is Transfer.Settled -> transfer.message to transfer
        Transfer.Idle, is Transfer.AskReplace -> return
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.subRow).padding(horizontal = AmpTheme.spacing.paneX),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            Modifier.weight(1f).testTag(FilesTags.uploadStatus),
            color = if (settled == null) AmpTheme.colors.text.inkMuted else AmpTheme.colors.text.ink,
            style = AmpTheme.type.secondary,
        )
        if (settled?.offerWeb == true) {
            AmpButton("Open Files on web", openFilesOnWeb, FilesTags.openWeb, rank = AmpButtonRank.Link)
        }
        if (settled != null) AmpButton("Dismiss", onDismiss, FilesTags.uploadDismiss)
    }
}
