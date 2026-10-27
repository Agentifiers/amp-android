package com.agentifiers.amp.changes

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.ChangesTags
import com.agentifiers.amp.design.Mono
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpTextField
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.DiffComment

/** Above the action bar: the comment field, or the selection bar, then the pending comment pills. */
@Composable
internal fun ReviewTray(comments: List<DiffComment>, c: PaneController) {
    val draft = c.draft
    val selection = c.selection
    when {
        draft != null -> CommentField(draft, c)
        selection != null -> SelectionBar(selection, c)
    }
    if (comments.isNotEmpty()) PendingComments(comments, c)
}

private fun location(selection: LineSelection) = "${selection.path}:${lineRef(selection.toDiffSelection())}"

@Composable
private fun SelectionBar(selection: LineSelection, c: PaneController) {
    Row(
        Modifier.fillMaxWidth().background(AmpTheme.colors.surfaces.raised).padding(horizontal = AmpTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs),
    ) {
        Text(location(selection), style = Mono, color = AmpTheme.colors.text.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        AmpButton("Comment", { c.startComment() }, ChangesTags.selectionComment)
        if (!c.explainBroken) AmpButton("Explain", { c.explainSelection() }, ChangesTags.selectionExplain, enabled = !c.explaining)
        AmpButton("Copy", { c.copySelection() }, ChangesTags.selectionCopy)
    }
}

@Composable
private fun CommentField(draft: CommentDraft, c: PaneController) {
    Column(Modifier.fillMaxWidth().background(AmpTheme.colors.surfaces.raised).padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(location(draft.selection), style = Mono, color = AmpTheme.colors.text.inkMuted)
        AmpTextField(
            value = draft.text,
            onValueChange = c::editComment,
            testTag = ChangesTags.commentInput,
            modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
            minLines = 2,
        )
        draft.error?.let { Text(it, style = AmpTheme.type.secondary, color = AmpTheme.colors.status.danger, modifier = Modifier.testTag(ChangesTags.commentError)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm, Alignment.End)) {
            AmpButton("Cancel", c::cancelComment, ChangesTags.commentCancel)
            AmpButton(
                "Add Comment", c::addComment, ChangesTags.commentAdd,
                rank = AmpButtonRank.Primary, enabled = draft.text.isNotBlank() && !draft.adding,
            )
        }
    }
}

@Composable
private fun PendingComments(comments: List<DiffComment>, c: PaneController) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = AmpTheme.spacing.sm, vertical = AmpTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AmpButton("Send Comments", { c.sendComments(comments) }, ChangesTags.commentsSend, rank = AmpButtonRank.Primary)
        comments.forEach { comment ->
            Row(
                Modifier.border(AmpTheme.metrics.divider, AmpTheme.colors.surfaces.line, AmpTheme.shapes.chip).testTag(ChangesTags.comment(comment.id))
                    .semantics(mergeDescendants = true) {}.padding(start = AmpTheme.spacing.chipPadX),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(pillLabel(comment), style = AmpTheme.type.label, color = AmpTheme.colors.text.ink, maxLines = 1)
                AmpIconButton(AmpIcons.X, "Remove comment", { c.removeComment(comment.id) }, ChangesTags.commentRemove(comment.id))
            }
        }
    }
}
