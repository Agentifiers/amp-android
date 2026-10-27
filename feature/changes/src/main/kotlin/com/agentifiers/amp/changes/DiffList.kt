package com.agentifiers.amp.changes

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.agentifiers.amp.design.ChangesTags
import com.agentifiers.amp.design.Mono
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.DiffLine
import com.agentifiers.amp.model.DiffLineKind
import com.agentifiers.amp.model.DiffSide
import com.agentifiers.amp.model.FileChange
import com.agentifiers.amp.model.SyntaxKind
import com.agentifiers.amp.design.theme.CodeTokens
import com.agentifiers.amp.design.theme.SyntaxTokens

/** What every row of one diff list shares: the controller, the layout choices, and the measured column width. */
@Stable
internal data class DiffUi(
    val controller: PaneController,
    val wrap: Boolean,
    val charWidth: Dp,
    /** Width of the old side of a split row: the first crease, or half the pane. */
    val splitAt: Dp,
    val canStage: Boolean,
    /** Triage opens the file; Review folds it; null inside a single file's view. */
    val onFileClick: ((FileChange) -> Unit)?,
)

internal fun LazyListScope.paneItems(rows: List<PaneRow>, ui: DiffUi) {
    items(rows, key = { it.key }, contentType = { it::class }) { row ->
        when (row) {
            is RepoRow -> RepoHeading(row)
            is SectionRow -> SectionHeading(row)
            is DirRow -> DirHeading(row)
            is FileRow -> FileRowItem(row, ui)
            is NoticeRow -> Text(
                row.text, style = AmpTheme.type.body, color = AmpTheme.colors.text.inkMuted,
                modifier = Modifier.fillMaxWidth().padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.sm),
            )
            is HunkRow -> HunkHeader(row, ui)
            is ExplanationRow -> Text(
                row.text,
                style = AmpTheme.type.body,
                color = AmpTheme.colors.text.ink,
                modifier = Modifier.fillMaxWidth().background(AmpTheme.colors.surfaces.raised)
                    .padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.sm)
                    .testTag(ChangesTags.explanation(row.path, row.hunk)),
            )
            is LineRow -> UnifiedLine(row, ui)
            is SplitLineRow -> Row(Modifier.fillMaxWidth()) {
                SplitSide(row.fd, row.old, DiffSide.Old, ui, Modifier.width(ui.splitAt))
                SplitSide(row.fd, row.new, DiffSide.New, ui, Modifier.weight(1f))
            }
            is GapRow -> GapItem(row, ui)
        }
    }
}

@Composable
private fun HunkHeader(row: HunkRow, ui: DiffUi) {
    val c = ui.controller
    val path = row.fd.file.path
    val index = row.index
    Row(
        Modifier.fillMaxWidth().background(AmpTheme.colors.surfaces.raised).padding(start = AmpTheme.spacing.paneX),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.hunk.range, style = Mono, color = AmpTheme.colors.code.diff.meta, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(ChangesTags.hunk(path, index)),
        )
        AmpIconButton(AmpIcons.MessageSquare, "Comment on Hunk", { c.commentOnHunk(row.fd, index) }, ChangesTags.hunkComment(path, index))
        if (!c.explainBroken) {
            AmpIconButton(AmpIcons.Sparkles, "Explain", { c.explainHunk(row.fd, index) }, ChangesTags.hunkExplain(path, index), enabled = !c.explaining)
        }
        AmpIconButton(AmpIcons.Copy, "Copy Hunk", { c.copyHunk(row.fd, index) }, ChangesTags.hunkCopy(path, index))
    }
}

@Composable
private fun GapItem(row: GapRow, ui: DiffUi) {
    val c = ui.controller
    val file = row.fd.file
    Row(
        Modifier.fillMaxWidth().background(AmpTheme.colors.surfaces.raised.copy(alpha = 0.5f)).padding(horizontal = AmpTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs),
    ) {
        if (row.step < row.hidden) {
            AmpButton("Show ${row.step} More Lines", { c.revealMore(file.id, row.gap) }, ChangesTags.gapMore(file.path, row.gap), rank = AmpButtonRank.Tertiary)
        }
        AmpButton("Show All", { c.revealAll(file.id, row.gap) }, ChangesTags.gapAll(file.path, row.gap), rank = AmpButtonRank.Tertiary)
    }
}

@Composable
private fun UnifiedLine(row: LineRow, ui: DiffUi) {
    val fd = row.fd
    val line = fd.diff.lines[row.index]
    val side = line.unifiedSide()
    val number = side?.let(line::number)
    val c = ui.controller
    LineCell(
        line = line,
        index = row.index,
        fd = fd,
        gutter = remember(line, fd.digits) { unifiedGutter(line, fd.digits) },
        gutterColumns = fd.digits * 2 + 2,
        tag = side?.let { lineTag(fd.file.path, it, number) },
        semanticsText = remember(line) { unifiedText(line) },
        selected = side != null && c.selection?.covers(fd.file.id, side, number) == true,
        onTap = if (side != null && number != null) ({ c.tapLine(fd.file.id, fd.file.path, side, number) }) else null,
        scroll = if (ui.wrap) null else c.horizontalScroll(fd.file.id, null),
        ui = ui,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SplitSide(fd: FileDiff, index: Int?, side: DiffSide, ui: DiffUi, modifier: Modifier) {
    if (index == null) {
        Box(modifier)
        return
    }
    val line = fd.diff.lines[index]
    val number = line.number(side)
    val c = ui.controller
    LineCell(
        line = line,
        index = index,
        fd = fd,
        gutter = remember(line, side, fd.digits) { sideGutter(line, side, fd.digits) },
        gutterColumns = fd.digits + 2,
        tag = lineTag(fd.file.path, side, number),
        semanticsText = remember(line, side) { sideText(line, side) },
        selected = c.selection?.covers(fd.file.id, side, number) == true,
        onTap = number?.let { { c.tapLine(fd.file.id, fd.file.path, side, it) } },
        scroll = if (ui.wrap) null else c.horizontalScroll(fd.file.id, side),
        ui = ui,
        modifier = modifier,
    )
}

private fun lineTag(path: String, side: DiffSide, number: Int?): String? = number?.let {
    if (side == DiffSide.Old) ChangesTags.lineOld(path, it) else ChangesTags.lineNew(path, it)
}

/**
 * One line, one accessibility node: the gutter and code merge into [semanticsText], so a 3,000-line diff stays
 * far under agent-device's 5,000-node cap.
 */
@Composable
private fun LineCell(
    line: DiffLine,
    index: Int,
    fd: FileDiff,
    gutter: String,
    gutterColumns: Int,
    tag: String?,
    semanticsText: String,
    selected: Boolean,
    onTap: (() -> Unit)?,
    scroll: ScrollState?,
    ui: DiffUi,
    modifier: Modifier,
) {
    val colors = AmpTheme.colors
    val diff = colors.code.diff
    val code = remember(line, colors.code) { codeText(line, colors.code) }
    val background = when {
        selected -> colors.accents.accentMuted
        line.kind == DiffLineKind.Added -> diff.addedBackground
        line.kind == DiffLineKind.Removed -> diff.removedBackground
        else -> Color.Transparent
    }
    val gutterColor = when (line.kind) {
        DiffLineKind.Added -> diff.addedText
        DiffLineKind.Removed -> diff.removedText
        else -> colors.text.inkMuted
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().background(background)
                .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
                .clearAndSetSemantics {
                    text = AnnotatedString(semanticsText)
                    if (tag != null) testTag = tag
                    if (onTap != null) {
                        this.selected = selected
                        onClick { onTap(); true }
                    }
                },
        ) {
            Text(gutter, style = Mono, color = gutterColor, maxLines = 1, softWrap = false, modifier = Modifier.width(ui.charWidth * gutterColumns))
            if (scroll == null) {
                Text(code, style = Mono, modifier = Modifier.weight(1f))
            } else {
                Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                    Text(
                        code, style = Mono, maxLines = 1, softWrap = false,
                        modifier = Modifier.width(ui.charWidth * (minOf(fd.maxChars, MAX_SCROLL_COLUMNS) + 1)),
                    )
                }
            }
        }
        if (line.text.length > MAX_LINE_CHARS) {
            AmpButton("Show Full Line", { ui.controller.fullLine = line.text }, ChangesTags.fullLine(fd.file.path, index), rank = AmpButtonRank.Tertiary)
        }
    }
}

/**
 * Compose caps a layout at 2^18 px wide; at 7dp a column and up to 3.5x density this many columns stays under it.
 * Wider unwrapped lines are clipped, and wrapping shows them whole.
 */
private const val MAX_SCROLL_COLUMNS = 8_000

private fun codeText(line: DiffLine, colors: CodeTokens): AnnotatedString {
    val text = line.displayText()
    val strong = when (line.kind) {
        DiffLineKind.Added -> colors.diff.addedStrongBackground
        DiffLineKind.Removed -> colors.diff.removedStrongBackground
        else -> null
    }
    return buildAnnotatedString {
        append(text)
        for (token in line.tokens) {
            val end = minOf(token.end, text.length)
            if (token.start < end) addStyle(SpanStyle(color = colors.syntax.of(token.kind)), token.start, end)
        }
        if (strong != null) {
            for (span in line.intraline) {
                val end = minOf(span.end, text.length)
                if (span.start < end) addStyle(SpanStyle(background = strong), span.start, end)
            }
        }
    }
}

private fun SyntaxTokens.of(kind: SyntaxKind): Color = when (kind) {
    SyntaxKind.Keyword -> keyword
    SyntaxKind.Literal -> string
    SyntaxKind.Comment -> comment
    SyntaxKind.Number -> number
    SyntaxKind.Type -> type
}
