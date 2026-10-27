package com.agentifiers.amp.changes

import com.agentifiers.amp.design.components.AmpDropdownMenu
import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.agentifiers.amp.design.ChangesTags
import com.agentifiers.amp.design.Mono
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpRowMore
import com.agentifiers.amp.design.components.AmpSectionLabel
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.ChangeKind
import com.agentifiers.amp.model.FileChange
import com.agentifiers.amp.model.SectionKind

@Composable
internal fun RepoHeading(row: RepoRow) {
    Text(
        repoHeading(row.repo),
        style = AmpTheme.type.rowTitle,
        color = AmpTheme.colors.text.ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
            .padding(start = AmpTheme.spacing.paneX, end = AmpTheme.spacing.paneX, top = AmpTheme.spacing.xs)
            .semantics { heading() },
    )
}

@Composable
internal fun SectionHeading(row: SectionRow) {
    val id = row.section.id.raw
    val spacing = AmpTheme.spacing
    var tourOpen by rememberSaveable(id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = spacing.listInset)) { AmpSectionLabel(row.title, ChangesTags.sectionLabel(id)) }
        val body = Modifier.padding(horizontal = spacing.paneX)
        row.section.summary?.let {
            Text(it, style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted, modifier = body.testTag(ChangesTags.sectionSummary(id)))
        }
        if (row.section.files.isEmpty()) {
            Text(
                emptySectionText(row.section.kind), style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted,
                modifier = body.testTag(ChangesTags.sectionEmpty(id)),
            )
        }
        row.section.codeTour?.let { tour ->
            ToggleButton("Code Tour", tourOpen, ChangesTags.codeTour(id), body) { tourOpen = it }
            if (tourOpen) Text(tour, style = AmpTheme.type.body, color = AmpTheme.colors.text.ink, modifier = body.padding(bottom = spacing.sm))
        }
    }
}

@Composable
internal fun DirHeading(row: DirRow) {
    Text(
        "${row.dir}/", style = Mono, color = AmpTheme.colors.text.inkMuted,
        modifier = Modifier.fillMaxWidth().padding(start = AmpTheme.spacing.paneX, end = AmpTheme.spacing.paneX, top = AmpTheme.spacing.sm),
    )
}

/**
 * A file row in the list, or a file's foldable header in the Review diff: the change letter, the path with its
 * directory dimmed, and the counts, then Stage or Unstage and the file menu as 48dp targets. The tagged node's text is
 * [FileChange.rowText], which also names the change in words.
 */
@Composable
internal fun FileRowItem(row: FileRow, ui: DiffUi) {
    val c = ui.controller
    val file = row.file
    val onClick = ui.onFileClick
    val colors = AmpTheme.colors
    val spacing = AmpTheme.spacing
    val stageOp = c.staging[file.id]
    Column(Modifier.fillMaxWidth().background(if (row.expanded != null) colors.surfaces.raised else Color.Transparent)) {
        Row(Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.touch).alpha(if (file.muted) MutedAlpha else 1f), verticalAlignment = Alignment.CenterVertically) {
            val rowText = AnnotatedString(file.rowText)
            Row(
                Modifier.weight(1f).heightIn(min = AmpTheme.metrics.touch).testTag(ChangesTags.file(file.path))
                    .then(if (onClick != null) Modifier.clickable { onClick(file) } else Modifier)
                    .semantics(mergeDescendants = true) { text = rowText }
                    .padding(start = spacing.paneX, end = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                val quiet = Modifier.clearAndSetSemantics {}
                if (row.expanded != null) {
                    Icon(if (row.expanded) AmpIcons.ChevronDown else AmpIcons.ChevronRight, null, quiet.size(AmpTheme.metrics.iconSmall), tint = colors.text.inkMuted)
                }
                Text(file.change.letter.toString(), style = AmpTheme.type.mono, color = letterColor(file.change), modifier = quiet)
                Text(rememberPathText(file), style = AmpTheme.type.body, color = colors.text.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = quiet.weight(1f))
                rememberCountsText(file)?.let { Text(it, style = AmpTheme.type.mono, maxLines = 1, modifier = quiet) }
            }
            if (ui.canStage && row.section != SectionKind.Range) StageButton(file, row.section, stageOp, c)
            FileMenu(file, c)
        }
        if (stageOp is StageOp.Failed) {
            Text(
                stageOp.message, style = AmpTheme.type.secondary, color = colors.status.danger,
                modifier = Modifier.padding(start = spacing.paneX, end = spacing.paneX, bottom = spacing.sm),
            )
        }
    }
}

private const val MutedAlpha = 0.55f

@Composable
private fun letterColor(change: ChangeKind): Color = when (change) {
    ChangeKind.Added, ChangeKind.Untracked -> AmpTheme.colors.code.diff.addedText
    ChangeKind.Deleted -> AmpTheme.colors.code.diff.removedText
    ChangeKind.Unmerged -> AmpTheme.colors.status.danger
    else -> AmpTheme.colors.status.warning
}

/** The path with its directory dimmed. */
@Composable
private fun rememberPathText(file: FileChange): AnnotatedString {
    val dim = AmpTheme.colors.text.inkMuted
    return remember(file.path, dim) {
        buildAnnotatedString {
            append(file.path)
            val dir = file.path.substringBeforeLast('/', "")
            if (dir.isNotEmpty()) addStyle(SpanStyle(color = dim), 0, dir.length + 1)
        }
    }
}

/** "+3 −2", the added count in the diff's added color and the deleted count in its removed color; zero counts are absent. */
@Composable
private fun rememberCountsText(file: FileChange): AnnotatedString? {
    val diff = AmpTheme.colors.code.diff
    return remember(file.stat, diff) {
        val added = file.stat.addedLabel
        val deleted = file.stat.deletedLabel
        if (added == null && deleted == null) {
            null
        } else {
            buildAnnotatedString {
                added?.let { withStyle(SpanStyle(color = diff.addedText)) { append(it) } }
                if (added != null && deleted != null) append(' ')
                deleted?.let { withStyle(SpanStyle(color = diff.removedText)) { append(it) } }
            }
        }
    }
}

@Composable
private fun StageButton(file: FileChange, section: SectionKind, op: StageOp?, c: PaneController) {
    val unstage = section == SectionKind.Staged
    val running = op == StageOp.Running
    val label = when {
        unstage && running -> "Unstaging changes..."
        unstage -> "Unstage changes"
        running -> "Staging changes..."
        else -> "Stage changes"
    }
    AmpIconButton(
        if (unstage) AmpIcons.Minus else AmpIcons.Plus,
        label,
        { c.stage(file, staged = !unstage) },
        if (unstage) ChangesTags.fileUnstage(file.path) else ChangesTags.fileStage(file.path),
        enabled = !running,
    )
}

@Composable
private fun FileMenu(file: FileChange, c: PaneController) {
    var open by remember { mutableStateOf(false) }
    Box {
        AmpRowMore({ open = true }, ChangesTags.fileMenu(file.path), "File actions")
        AmpDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FileMenuItem("Copy Path", ChangesTags.fileCopyPath(file.path)) { open = false; c.copy(file.path) }
            FileMenuItem("Open File", ChangesTags.fileOpen(file.path)) { open = false; c.openInFiles(file.path) }
            FileMenuItem("Reveal in File Tree", ChangesTags.fileReveal(file.path)) { open = false; c.revealInFileTree(file.path) }
        }
    }
}

@Composable
private fun FileMenuItem(label: String, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = AmpTheme.type.body, color = AmpTheme.colors.text.ink) },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}

/** A file in the File List sheet: tapping it jumps the Review diff to that file. */
@Composable
internal fun FileListEntry(row: FileRow, onPick: (FileChange) -> Unit) {
    val file = row.file
    Row(
        Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.touch).alpha(if (file.muted) MutedAlpha else 1f)
            .testTag(ChangesTags.fileListEntry(file.path))
            .clickable { onPick(file) }
            .clearAndSetSemantics { text = AnnotatedString(file.rowText) }
            .padding(horizontal = AmpTheme.spacing.paneX),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm),
    ) {
        Text(file.change.letter.toString(), style = AmpTheme.type.mono, color = letterColor(file.change))
        Text(rememberPathText(file), style = AmpTheme.type.body, color = AmpTheme.colors.text.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
        rememberCountsText(file)?.let { Text(it, style = AmpTheme.type.mono, maxLines = 1) }
    }
}
