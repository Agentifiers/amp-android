package com.agentifiers.amp.thread.transcript

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpSearchField
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.EarlierHistory

/**
 * Covers the title bar while open, on the header's height and edges: the 36dp search field, then Previous, Next and
 * Close. The count line shows under it once there is a query. Counts hits across every loaded page; Search Older
 * Messages pages backward until a new hit appears or the first message is loaded.
 */
@Composable
fun FindBar(find: FindState, total: Int, earlier: EarlierHistory?) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxWidth().testTag(ThreadTags.find)) {
        Row(Modifier.heightIn(min = AmpTheme.metrics.paneHeader).padding(start = AmpTheme.spacing.paneX, end = AmpTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            AmpSearchField(
                value = find.query,
                onValueChange = find::search,
                placeholder = "Find in thread",
                testTag = ThreadTags.findInput,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { find.next(total) }),
                modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter -> {
                            if (event.isShiftPressed) find.previous(total) else find.next(total)
                            true
                        }
                        Key.Escape -> {
                            find.close()
                            true
                        }
                        else -> false
                    }
                },
            )
            AmpIconButton(AmpIcons.ChevronUp, "Previous", { find.previous(total) }, ThreadTags.findPrevious, enabled = total > 0)
            AmpIconButton(AmpIcons.ChevronDown, "Next", { find.next(total) }, ThreadTags.findNext, enabled = total > 0)
            AmpIconButton(AmpIcons.X, "Close", find::close, ThreadTags.findClose)
        }
        if (find.query.isBlank() && !find.searchingOlder) return@Column
        Row(Modifier.padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            val count = when {
                find.query.isBlank() -> ""
                total == 0 -> "No matches in loaded messages"
                else -> "${find.current + 1} of $total in loaded messages"
            }
            Text(count, style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted, modifier = Modifier.weight(1f).testTag(ThreadTags.findCount))
            when {
                find.searchingOlder -> {
                    Text("Searching older messages…", style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted)
                    AmpButton("Stop", { find.searchingOlder = false }, ThreadTags.findStop, rank = AmpButtonRank.Secondary)
                }
                find.query.isNotBlank() && (earlier == EarlierHistory.Available || earlier is EarlierHistory.Failed) -> AmpButton(
                    "Search Older Messages",
                    {
                        find.olderBaseline = total
                        find.searchingOlder = true
                    },
                    ThreadTags.findOlder,
                    rank = AmpButtonRank.Tertiary,
                )
            }
        }
    }
}
