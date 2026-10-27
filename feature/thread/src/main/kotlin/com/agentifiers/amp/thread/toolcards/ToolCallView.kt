package com.agentifiers.amp.thread.toolcards

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.code.rememberHighlighted
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolRun
import com.agentifiers.amp.model.ToolRunKind

/**
 * A call in an open step group: a borderless row indented under the group's glyph (status glyph, tool name, its
 * summary in small mono, cut per [summaryOverflow], then the status word unless the call succeeded), then the card's
 * body, or the generic one, while [expanded]. A2 drew it 28dp; it stays a full 48dp tap target, since a finger opens
 * it. A card that opens scrolls fully into view, so its body never stays under the composer.
 */
@Composable
fun ToolCallView(call: ToolCall, expanded: Boolean, onToggle: () -> Unit, ctx: ToolCardContext, modifier: Modifier = Modifier) {
    val decoded = remember(call) { decoded(call) }
    val label = remember(call) { toolLabel(call) }
    val summary = remember(call, decoded) { decoded?.summary(call.run) ?: GenericToolCard.summary(call) }
    val spacing = AmpTheme.spacing
    val reveal = remember { BringIntoViewRequester() }
    // Seeded with the first value, so a card that was already open and scrolls back into composition does not jump.
    val wasExpanded = remember { booleanArrayOf(expanded) }
    LaunchedEffect(expanded) {
        if (expanded && !wasExpanded[0]) reveal.bringIntoView()
        wasExpanded[0] = expanded
    }
    Column(modifier.testTag(ThreadTags.toolCard(call.id)).fillMaxWidth().bringIntoViewRequester(reveal)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.touch).testTag(ThreadTags.toolToggle(call.id))
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand", onClick = onToggle)
                .padding(start = spacing.childIndent, end = spacing.toolPadX),
            horizontalArrangement = Arrangement.spacedBy(spacing.toolInnerGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolStatusGlyph(call.run.kind)
            Text(
                rememberHighlighted(AnnotatedString(label), ctx.highlight),
                style = AmpTheme.type.tool, color = AmpTheme.colors.text.ink, maxLines = 1,
            )
            Text(
                rememberHighlighted(AnnotatedString(summary), ctx.highlight),
                Modifier.weight(1f), style = AmpTheme.type.monoSmall, color = AmpTheme.colors.text.inkMuted, maxLines = 1, overflow = summaryOverflow(summary),
            )
            statusText(call.run)?.let { status ->
                Text(
                    status, Modifier.widthIn(max = 240.dp), color = toolStatusColor(call.run.kind),
                    style = AmpTheme.type.toolMeta, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = spacing.childIndent, top = spacing.xs, bottom = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (decoded != null) decoded.Body(call.run, ctx) else GenericToolCard.Body(call, ctx)
                runNote(call.run)?.let { FindText(it, ctx, color = AmpTheme.colors.text.inkMuted) }
            }
        }
    }
}

/** The status word; an error carries its message (screens.md, "Status text"). None for a call that succeeded: its check says so. */
internal fun statusText(run: ToolRun): String? = when (run) {
    is ToolRun.Done -> null
    is ToolRun.Error -> run.message?.let { "error: $it" } ?: run.kind.word
    else -> run.kind.word
}

/** Paths and URLs keep both ends, so the file name survives the cut; prose and commands read from their start. */
internal fun summaryOverflow(summary: String): TextOverflow =
    if (summary.substringBefore(' ').any { it == '/' || it == '\\' }) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis

/** Why a call stopped without a result: "User canceled" for an interrupt, or the user's rejection feedback. */
private fun runNote(run: ToolRun): String? = when (run) {
    is ToolRun.Cancelled -> run.reason
    is ToolRun.Rejected -> run.feedback
    else -> null
}?.takeIf { it.isNotBlank() }

/** The glyph at the start of a tool row: a check when done, a spinner while running, a cross when it stopped short. */
@Composable
fun ToolStatusGlyph(kind: ToolRunKind) {
    val icon: ImageVector = when (kind) {
        ToolRunKind.Done -> AmpIcons.CircleCheck
        ToolRunKind.Active, ToolRunKind.Queued, ToolRunKind.CancelRequested -> AmpIcons.LoaderCircle
        ToolRunKind.Blocked -> AmpIcons.CircleAlert
        ToolRunKind.Error, ToolRunKind.Cancelled, ToolRunKind.Rejected -> AmpIcons.CircleX
    }
    val tint = when (kind) {
        ToolRunKind.Done, ToolRunKind.Cancelled, ToolRunKind.Rejected -> AmpTheme.colors.text.inkFaint
        else -> toolStatusColor(kind)
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(AmpTheme.metrics.iconSmall))
}

@Composable
fun toolStatusColor(kind: ToolRunKind): Color = when (kind) {
    ToolRunKind.Error -> AmpTheme.colors.status.error
    ToolRunKind.Blocked -> AmpTheme.colors.status.needsYou
    ToolRunKind.Active, ToolRunKind.Queued, ToolRunKind.CancelRequested -> AmpTheme.colors.status.working
    ToolRunKind.Done, ToolRunKind.Cancelled, ToolRunKind.Rejected -> AmpTheme.colors.text.inkMuted
}
