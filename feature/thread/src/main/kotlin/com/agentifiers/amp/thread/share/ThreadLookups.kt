package com.agentifiers.amp.thread.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.model.Account
import com.agentifiers.amp.model.FleetView
import com.agentifiers.amp.model.Sidebar
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.FleetNotice

internal fun threadUrl(account: Account, id: ThreadId): String = account.server.ampBaseUrl.trimEnd('/') + "/threads/" + id.raw

/** The Sidebar carries organize writes optimistically; the flat list also covers children and Puck conversations. */
@Composable
internal fun rememberSummary(scope: AccountScope, id: ThreadId): ThreadSummary? {
    val sidebar by scope.fleet.sidebar.collectAsStateWithLifecycle()
    val threads by scope.fleet.threads.collectAsStateWithLifecycle()
    return sidebar.find(id) ?: (threads as? FleetView.Ready)?.threads?.firstOrNull { it.id == id }
}

private fun Sidebar.find(id: ThreadId): ThreadSummary? =
    (needsYou + pinned + active.flatMap { it.threads } + inactive72h + snoozed + archived).firstOrNull { it.id == id }

/** The `FleetNotice.Failed.action` names :sync reports for share and multiplayer writes. */
internal enum class SharingWrite(val action: String) { Visibility("Visibility"), Multiplayer("Multiplayer") }

/** The latest refusal of one of [writes] on [thread]; the caller clears it when the user tries again. */
@Composable
internal fun rememberSharingFailure(scope: AccountScope, thread: ThreadId, writes: Set<SharingWrite>): MutableState<String?> {
    val failure = remember(thread) { mutableStateOf<String?>(null) }
    LaunchedEffect(scope, thread, writes) {
        val actions = writes.map { it.action }
        scope.fleet.notices.collect { notice ->
            if (notice is FleetNotice.Failed && notice.thread == thread && notice.action in actions) failure.value = notice.reason
        }
    }
    return failure
}
