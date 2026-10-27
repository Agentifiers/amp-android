package com.agentifiers.amp.thread

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.ShellTags
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.model.AgentActivity
import com.agentifiers.amp.model.Draft
import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.OutboxEntry
import com.agentifiers.amp.model.OutboxState
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.SendMode
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.TranscriptView
import com.agentifiers.amp.model.WebSurface
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.thread.gates.GateDock
import com.agentifiers.amp.design.organize.OrganizeDialog
import com.agentifiers.amp.thread.presence.multiplayerControl
import com.agentifiers.amp.thread.presence.presenceControl
import com.agentifiers.amp.thread.presence.TypingIndicator
import com.agentifiers.amp.thread.share.shareControl
import com.agentifiers.amp.thread.share.ThreadOrganizeDialogs
import com.agentifiers.amp.thread.share.organizeMenuItems
import androidx.compose.runtime.key
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.sync.api.ThreadHandle
import com.agentifiers.amp.thread.composer.Composer
import com.agentifiers.amp.thread.composer.modeWord
import com.agentifiers.amp.thread.composer.QueueStrip
import com.agentifiers.amp.thread.composer.rememberComposerText
import com.agentifiers.amp.thread.toolcards.ToolCardContext
import com.agentifiers.amp.thread.transcript.FindBar
import com.agentifiers.amp.thread.transcript.FindState
import com.agentifiers.amp.thread.transcript.ThreadTranscript
import com.agentifiers.amp.thread.transcript.TranscriptLinks
import com.agentifiers.amp.thread.transcript.findHits
import com.agentifiers.amp.thread.schedule.ScheduleSheet
import com.agentifiers.amp.thread.schedule.rememberThreadSchedule
import com.agentifiers.amp.thread.watch.watchMenuItem
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpEmptyState
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.noThreadsYet

/** Title bar, transcript, queue strip, gate dock and composer for one thread. :app holds the viewer lease. */
@Composable
fun ThreadPane(
    dest: PaneDestination.Thread,
    scope: AccountScope,
    menu: List<ThreadMenuItem>,
    navigate: (PaneDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val handle = scope.thread(dest.id)
    val state by handle.state.collectAsStateWithLifecycle()
    val account by scope.account.collectAsStateWithLifecycle()
    val find = remember(dest.id) { FindState() }
    val ready = state.transcript as? TranscriptView.Ready
    val hits = remember(ready, find.query) { ready?.let { findHits(it, find.query) }.orEmpty() }
    val links = transcriptLinks(dest.id, account.server.ampBaseUrl, scope, navigate)
    var organizing by remember { mutableStateOf<OrganizeDialog?>(null) }
    val items = threadMenu(dest.id, state, account.server.ampBaseUrl, account.id, find, navigate, organizeMenuItems(scope, dest.id) { organizing = it } + menu + watchMenuItem(scope, dest.id))

    Column(
        modifier.background(LocalPaneSurface.current).testTag(ShellTags.pane("thread")).onPreviewKeyEvent { event ->
            val ctrlF = event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.F
            if (ctrlF) find.show()
            ctrlF
        },
    ) {
        if (find.open) {
            FindBar(find, hits.size, ready?.earlier)
        } else {
            ThreadTitleBar(
                title = state.title ?: "Untitled",
                status = statusLine(state),
                menu = items,
                statusColor = statusColor(state),
                words = listOfNotNull(executorChip(state.executor)?.let { executorWord(it) }, modeWord(state, scope)),
                controls = titleControls(scope, handle, state),
            )
        }
        val schedule = rememberThreadSchedule(scope, dest.id, state.activity is AgentActivity.Busy)
        var openSchedule by remember(dest.id) { mutableStateOf<String?>(null) }
        ThreadTranscript(state, handle, scope.images.coil, find, hits, links, schedule, { openSchedule = schedule?.automation?.id }, Modifier.weight(1f))
        schedule?.takeIf { it.automation.id == openSchedule }?.let { ScheduleSheet(scope, it) { openSchedule = null } }
        val composerText = rememberComposerText(handle.id)
        QueueStrip(state, handle, composerText)
        key(dest.id) {
            GateDock(state, handle::dispatch, onAnswerOnWeb = { navigate(answerOnWeb(scope.account.value.id, dest.id)) })
        }
        TypingIndicator(state)
        Composer(state, handle, composerText, scope)
    }
    ThreadOrganizeDialogs(scope, organizing) { organizing = null }
}

/** The title bar's controls in screens.md's leave order: Share, Multiplayer, presence. The executor and mode are status words. */
@Composable
private fun titleControls(scope: AccountScope, handle: ThreadHandle, state: ThreadState): List<TitleBarControl> = listOfNotNull(
    shareControl(handle, scope),
    multiplayerControl(scope, handle, state),
    presenceControl(state),
)

/** UnsupportedGate's [Answer on web]: the thread on ampcode.com in the account's own WebView profile. */
private fun answerOnWeb(account: AccountId, thread: ThreadId) =
    PaneDestination.Web(WebSurface.AccountPage(AccountWebPage.ThreadOnWeb, account, "/${thread.raw}"))

/**
 * The thread slot before any thread is open. An account with no threads leaves it blank: the Sidebar's "No threads yet"
 * carries the one New Thread (UX-08), so a new user does not see two.
 */
@Composable
fun EmptyThreadPane(scope: AccountScope, menu: List<ThreadMenuItem>, modifier: Modifier = Modifier) {
    val sidebar by scope.fleet.sidebar.collectAsStateWithLifecycle()
    Column(modifier.background(LocalPaneSurface.current).testTag(ShellTags.pane("thread"))) {
        ThreadTitleBar(title = "No thread selected", status = null, menu = menu)
        if (!sidebar.noThreadsYet) {
            AmpEmptyState(
                icon = AmpIcons.MessagesSquare,
                title = "Pick a thread",
                guidance = "Choose one from the list or the rail to read it here.",
                testTag = ThreadTags.noSelection,
            )
        }
    }
}

/**
 * Links to this account's threads open in the app, absolute paths open in the Files tab, and everything else goes to
 * the system's browser.
 */
@Composable
private fun transcriptLinks(thread: ThreadId, server: String, scope: AccountScope, navigate: (PaneDestination) -> Unit): TranscriptLinks {
    val uri = LocalUriHandler.current
    val threadPrefix = server.trimEnd('/') + "/threads/"
    return remember(thread, server, navigate) {
        val open: (String) -> Unit = { url ->
            val linked = url.takeIf { it.startsWith(threadPrefix) }?.removePrefix(threadPrefix)?.substringBefore('?')?.substringBefore('#')?.let(ThreadId::parse)
            when {
                linked != null -> navigate(PaneDestination.Thread(linked))
                url.startsWith("/") -> navigate(PaneDestination.Machine(thread, MachineTab.Files(url)))
                else -> runCatching { uri.openUri(url) }
            }
        }
        TranscriptLinks(open, openOnWeb = { navigate(answerOnWeb(scope.account.value.id, thread)) }) { highlight -> ToolCardContext(scope.images.coil, highlight, open, navigate, thread) }
    }
}
