package com.agentifiers.amp.thread.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.model.AgentMode
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.thread.StatusWord
import com.agentifiers.amp.thread.StatusWordNote

fun modeDisplay(key: String, modes: List<AgentMode>): String = modes.firstOrNull { it.key == key }?.display ?: key

/** The thread's mode as a status line word. Amp fixes it at the first message, so More Actions lists it read-only. */
@Composable
fun modeWord(state: ThreadState, scope: AccountScope): StatusWord? {
    val key = state.mode ?: return null
    val modes by scope.catalog.modes.collectAsStateWithLifecycle()
    val display = modeDisplay(key, modes)
    return StatusWord(display, ThreadTags.mode, description = "Mode: $display", note = StatusWordNote(ThreadTags.menuMode, "Fixed after the first message"))
}
