package com.agentifiers.amp.thread.composer

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.ThreadTags.QueuedAction
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.QueuedMessage
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.sync.api.ThreadHandle
import kotlinx.collections.immutable.toPersistentList

/**
 * Messages waiting to reach the agent, one 48dp `raised` card each so none reads as a transcript line: "Queued" and
 * when it goes under the message, then Steer Now, Move Up, Move Down and Remove as icon buttons.
 */
@Composable
fun QueueStrip(state: ThreadState, handle: ThreadHandle, composerText: MutableState<String>, modifier: Modifier = Modifier) {
    val queue = state.queue
    if (queue.isEmpty()) return

    fun move(index: Int, by: Int) {
        val order = queue.map { it.id }.toMutableList()
        order[index] = order[index + by].also { order[index + by] = order[index] }
        handle.dispatch(ThreadIntent.ReorderQueue(order.toPersistentList()))
    }

    fun remove(queued: QueuedMessage) {
        handle.dispatch(ThreadIntent.RemoveQueued(queued.id))
        if (composerText.value.isBlank()) composerText.value = queued.text
    }

    Column(
        modifier.fillMaxWidth().background(LocalPaneSurface.current).padding(horizontal = AmpTheme.spacing.paneX).testTag(ThreadTags.queue),
        verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs),
    ) {
        queue.forEachIndexed { index, queued ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.touch).clip(AmpTheme.shapes.userMessage).background(AmpTheme.colors.surfaces.raised)
                    .padding(start = AmpTheme.spacing.userPadX).testTag(ThreadTags.queued(queued.id)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(queued.text, style = AmpTheme.type.body, color = AmpTheme.colors.text.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row {
                        Text("Queued · ", style = AmpTheme.type.label, color = AmpTheme.colors.text.inkMuted, maxLines = 1)
                        Text(if (queued.steer) "Steers at next step" else "After this turn", style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted, maxLines = 1)
                    }
                }
                if (!queued.steer) {
                    AmpIconButton(AmpIcons.CornerDownRight, "Steer Now", { handle.dispatch(ThreadIntent.SteerQueued(queued.id)) }, ThreadTags.queuedAction(queued.id, QueuedAction.Steer))
                }
                if (index > 0) AmpIconButton(AmpIcons.ChevronUp, "Move Up", { move(index, -1) }, ThreadTags.queuedAction(queued.id, QueuedAction.Up))
                if (index < queue.lastIndex) {
                    AmpIconButton(AmpIcons.ChevronDown, "Move Down", { move(index, 1) }, ThreadTags.queuedAction(queued.id, QueuedAction.Down))
                }
                AmpIconButton(AmpIcons.X, "Remove", { remove(queued) }, ThreadTags.queuedAction(queued.id, QueuedAction.Remove))
            }
        }
    }
}
