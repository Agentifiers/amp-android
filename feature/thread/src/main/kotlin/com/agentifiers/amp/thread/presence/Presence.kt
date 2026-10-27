package com.agentifiers.amp.thread.presence

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.Viewer
import com.agentifiers.amp.thread.TitleBarControl
import com.agentifiers.amp.design.components.AmpDropdownMenu
import com.agentifiers.amp.thread.TitleChip

/** Who else is in the thread. Tapping lists every name. Nothing shows while the user is alone. */
@Composable
fun presenceControl(state: ThreadState): TitleBarControl? {
    val openState = remember(state.id) { mutableStateOf(false) }
    val viewers = state.presence.viewers
    val label = presenceLabel(viewers) ?: return null
    val show = { openState.value = true }
    return TitleBarControl(ThreadTags.menuPresence, label, show, surface = { PresenceMenu(viewers, openState) }) {
        TitleChip(label, ThreadTags.presence, onClick = show)
    }
}

@Composable
private fun PresenceMenu(viewers: List<Viewer>, openState: MutableState<Boolean>) {
    var open by openState
    AmpDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        viewers.forEach { viewer ->
            DropdownMenuItem(text = { Text(viewer.name) }, onClick = { open = false }, modifier = Modifier.testTag(ThreadTags.presenceViewer(viewer.user)))
        }
    }
}

/** "<name> is typing" above the composer, from `prompt_drafts`. */
@Composable
fun TypingIndicator(state: ThreadState, modifier: Modifier = Modifier) {
    val label = typingLabel(state.presence.typing) ?: return
    Text(label, style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted, modifier = modifier.padding(horizontal = AmpTheme.spacing.paneX, vertical = 2.dp).testTag(ThreadTags.composerTyping))
}
