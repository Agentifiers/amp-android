package com.agentifiers.amp.thread.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.components.AmpSuggestionChip
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.SuggestedReply

/**
 * The agent's suggested replies above the composer, where Amp's web and Android apps put them. A tap sends the reply,
 * as on the web; a long press puts it in the composer to edit first, the touch form of the web's Cmd-click. The words
 * the agent bolded stay bold, and the whole reply wraps instead of the web's shortened label.
 */
@Composable
internal fun SuggestedReplies(replies: List<SuggestedReply>, onSend: (SuggestedReply) -> Unit, onEdit: (SuggestedReply) -> Unit, modifier: Modifier = Modifier) {
    if (replies.isEmpty()) return
    FlowRow(modifier.fillMaxWidth().testTag(ThreadTags.suggestedReplies), horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
        replies.forEachIndexed { index, reply ->
            AmpSuggestionChip(
                label = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(reply.lead) }
                    withStyle(SpanStyle(fontWeight = FontWeight.Normal)) { append(reply.rest) }
                },
                onClick = { onSend(reply) },
                testTag = ThreadTags.suggestedReply(index),
                leadingIcon = AmpIcons.ArrowUp,
                onClickLabel = "Send",
                onLongClick = { onEdit(reply) },
                onLongClickLabel = "Edit before sending",
            )
        }
    }
}
