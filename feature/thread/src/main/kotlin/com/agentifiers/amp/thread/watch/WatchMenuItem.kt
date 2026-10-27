package com.agentifiers.amp.thread.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.thread.ThreadMenuItem

/** Watch in More Actions: :notify sees the change and shows the thread as a Live Update while it works. */
@Composable
fun watchMenuItem(scope: AccountScope, thread: ThreadId): ThreadMenuItem {
    val watched by scope.fleet.watched.collectAsStateWithLifecycle()
    val watching = thread in watched
    return ThreadMenuItem("Watch", ThreadTags.menuWatch, watching) { scope.fleet.setWatched(thread, !watching) }
}
