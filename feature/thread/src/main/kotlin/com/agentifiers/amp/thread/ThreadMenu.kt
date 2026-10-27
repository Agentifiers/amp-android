package com.agentifiers.amp.thread

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.TranscriptView
import com.agentifiers.amp.model.WebSurface
import com.agentifiers.amp.thread.transcript.FindState
import com.agentifiers.amp.thread.transcript.transcriptMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The thread's own More Actions items, followed by [extras] from :app. Other units add their items to this list. */
@Composable
fun threadMenu(
    thread: ThreadId,
    state: ThreadState,
    server: String,
    account: AccountId,
    find: FindState,
    navigate: (PaneDestination) -> Unit,
    extras: List<ThreadMenuItem>,
): List<ThreadMenuItem> {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val markdown = { (state.transcript as? TranscriptView.Ready)?.let { transcriptMarkdown(state.title, it) } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val text = markdown() ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.encodeToByteArray()) } } }
    }
    fun copy(label: String, text: String) {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) }
    }
    val link = server.trimEnd('/') + "/threads/" + thread.raw
    return listOf(
        ThreadMenuItem("Copy Link", ThreadTags.menuCopyLink, null) { copy("Thread link", link) },
        ThreadMenuItem("Copy as Markdown", ThreadTags.menuCopyMarkdown, null) { markdown()?.let { copy("Thread", it) } },
        ThreadMenuItem("Export", ThreadTags.menuExport, null) { export.launch("${state.title ?: thread.raw}.md") },
        ThreadMenuItem("Find in Thread", ThreadTags.menuFind, null) { find.show() },
        ThreadMenuItem("Open on web", ThreadTags.menuOpenWeb, null) {
            navigate(PaneDestination.Web(WebSurface.AccountPage(AccountWebPage.ThreadOnWeb, account, "/" + thread.raw)))
        },
    ) + extras
}
