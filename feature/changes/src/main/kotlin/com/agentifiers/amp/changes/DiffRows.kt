package com.agentifiers.amp.changes

import com.agentifiers.amp.model.ChangeKind
import com.agentifiers.amp.model.ChangeSection
import com.agentifiers.amp.model.ChangesView
import com.agentifiers.amp.model.DiffLine
import com.agentifiers.amp.model.DiffLineKind
import com.agentifiers.amp.model.DiffSelection
import com.agentifiers.amp.model.DiffSide
import com.agentifiers.amp.model.FileChange
import com.agentifiers.amp.model.FileDiffView
import com.agentifiers.amp.model.Hunk
import com.agentifiers.amp.model.RepoChanges
import com.agentifiers.amp.model.SectionId
import com.agentifiers.amp.model.SectionKind
import com.agentifiers.amp.model.diff.splitRows

/** Lines longer than this show their start and a Show Full Line button (git-and-diffs.md, "Long lines"). */
internal const val MAX_LINE_CHARS = 20_000
internal const val GAP_STEP = 25

/** One file's ready diff plus the per-file measures every line row of that file shares. */
internal class FileDiff(val file: FileChange, val diff: FileDiffView.Ready) {
    /** Digits per line number: 4, plus one per digit beyond 9,999. */
    val digits: Int = maxOf(4, (diff.lines.maxOfOrNull { maxOf(it.oldNo ?: 0, it.newNo ?: 0) } ?: 0).toString().length)
    val maxChars: Int = diff.lines.maxOfOrNull { minOf(it.text.length, MAX_LINE_CHARS) } ?: 0
}

/**
 * One item of the pane's LazyColumn. A hunk is never an item: it contributes a [HunkRow] and one row per line,
 * so the list composes only the visible lines.
 */
internal sealed interface PaneRow {
    val key: String
}

internal data class RepoRow(val repo: RepoChanges) : PaneRow {
    override val key get() = "repo:${repo.name}"
}

internal data class SectionRow(val section: ChangeSection, val title: String) : PaneRow {
    override val key get() = "section:${section.id.raw}"
}

internal data class DirRow(val section: SectionId, val dir: String) : PaneRow {
    override val key get() = "dir:${section.raw}:$dir"
}

/** [expanded] is null in the Triage list, where a row opens the file instead of folding it. */
internal data class FileRow(val file: FileChange, val section: SectionKind, val expanded: Boolean?) : PaneRow {
    override val key get() = "file:${file.id}"
}

internal data class NoticeRow(val fileId: String, val text: String) : PaneRow {
    override val key get() = "$fileId:notice"
}

internal data class HunkRow(val fd: FileDiff, val index: Int) : PaneRow {
    override val key get() = "${fd.file.id}:h$index"
    val hunk: Hunk get() = fd.diff.hunks[index]
}

internal data class ExplanationRow(val fileId: String, val path: String, val hunk: Int, val text: String) : PaneRow {
    override val key get() = "$fileId:x$hunk"
}

internal data class LineRow(val fd: FileDiff, val index: Int) : PaneRow {
    override val key get() = "${fd.file.id}:$index"
}

/** A split row: indices into the line store for the old and the new side; a blank side is null. */
internal data class SplitLineRow(val fd: FileDiff, val old: Int?, val new: Int?) : PaneRow {
    override val key get() = "${fd.file.id}:${old ?: new}"
}

/** Unchanged lines between hunks that are not shown yet. [step] is what Show More reveals next. */
internal data class GapRow(val fd: FileDiff, val gap: Int, val hidden: Int, val step: Int) : PaneRow {
    override val key get() = "${fd.file.id}:g$gap"
}

internal fun rowKey(fileId: String, index: Int) = "$fileId:$index"

/** The revealed count after one more Show More press: 25, then 50 more, then 100 more. */
internal fun nextReveal(shown: Int): Int = shown * 2 + GAP_STEP

/**
 * Headings and file rows for every repository and section. A sole repository's heading sits in the toolbar row, so
 * only several repositories get [RepoRow]s. [expanded] is null for the Triage list; otherwise an expanded file is
 * followed by its [body].
 */
internal fun paneRows(
    view: ChangesView.Ready,
    tree: Boolean,
    expanded: ((FileChange) -> Boolean)?,
    body: (FileChange) -> List<PaneRow>,
): List<PaneRow> = buildList {
    for (repo in view.repos) {
        if (view.repos.size > 1) add(RepoRow(repo))
        for (section in repo.sections) {
            add(SectionRow(section, if (view.repos.size > 1) "${repo.name} · ${section.title}" else section.title))
            val groups = if (tree) {
                section.files.groupBy { parentDir(it.path).orEmpty() }.toSortedMap().entries.map { it.key to it.value }
            } else {
                listOf("" to section.files)
            }
            for ((dir, files) in groups) {
                if (dir.isNotEmpty()) add(DirRow(section.id, dir))
                for (file in files) {
                    val open = expanded?.invoke(file)
                    add(FileRow(file, section.kind, open))
                    if (open == true) addAll(body(file))
                }
            }
        }
    }
}

/** The rows under one file's header: a notice, or hunk headers, explanations, gaps and lines. */
internal fun fileBodyRows(
    file: FileChange,
    view: FileDiffView,
    split: Boolean,
    revealed: Map<String, Int>,
    explanations: Map<String, String>,
): List<PaneRow> = when (view) {
    FileDiffView.Loading -> listOf(NoticeRow(file.id, "Loading diff…"))
    is FileDiffView.Binary -> listOf(NoticeRow(file.id, binaryText(view.oldSize, view.newSize)))
    is FileDiffView.TooLarge -> listOf(NoticeRow(file.id, view.reason))
    is FileDiffView.Omitted -> listOf(NoticeRow(file.id, view.reason))
    is FileDiffView.Failed -> listOf(NoticeRow(file.id, view.message))
    is FileDiffView.Ready -> when {
        view.hunks.isNotEmpty() -> readyRows(FileDiff(file, view), split, revealed, explanations)
        file.change is ChangeKind.Renamed -> listOf(NoticeRow(file.id, "Renamed without changes"))
        else -> emptyList()
    }
}

private fun readyRows(fd: FileDiff, split: Boolean, revealed: Map<String, Int>, explanations: Map<String, String>): List<PaneRow> {
    val diff = fd.diff
    val id = fd.file.id
    val rows = ArrayList<PaneRow>(diff.lines.size + diff.hunks.size * 2 + 1)

    fun lines(from: Int, until: Int) {
        if (from >= until) return
        if (split) splitRows(diff, from, until).forEach { rows += SplitLineRow(fd, it.old, it.new) }
        else for (index in from until until) rows += LineRow(fd, index)
    }

    fun gap(index: Int, from: Int, until: Int, revealsUpward: Boolean) {
        val size = until - from
        if (size <= 0) return
        val shown = minOf(revealed[rowKey(id, index)] ?: 0, size)
        val hidden = size - shown
        val row = if (hidden > 0) GapRow(fd, index, hidden, minOf(nextReveal(shown) - shown, hidden)) else null
        if (revealsUpward) {
            row?.let(rows::add)
            lines(until - shown, until)
        } else {
            lines(from, from + shown)
            row?.let(rows::add)
        }
    }

    var previousEnd = 0
    diff.hunks.forEachIndexed { index, hunk ->
        if (diff.expandable) gap(index, previousEnd, hunk.firstLine, revealsUpward = index == 0)
        rows += HunkRow(fd, index)
        explanations[rowKey(id, index)]?.let { rows += ExplanationRow(id, fd.file.path, index, it) }
        lines(hunk.firstLine, hunk.firstLine + hunk.lineCount)
        previousEnd = hunk.firstLine + hunk.lineCount
    }
    if (diff.expandable) gap(diff.hunks.size, previousEnd, diff.lines.size, revealsUpward = false)
    return rows
}

/** The side a unified line belongs to for tags and selection; a no-newline marker has none. */
internal fun DiffLine.unifiedSide(): DiffSide? = when (kind) {
    DiffLineKind.Removed -> DiffSide.Old
    DiffLineKind.Added, DiffLineKind.Context -> DiffSide.New
    DiffLineKind.NoNewline -> null
}

internal fun DiffLine.number(side: DiffSide): Int? = if (side == DiffSide.Old) oldNo else newNo

internal fun DiffLine.displayText(): String = if (text.length > MAX_LINE_CHARS) text.take(MAX_LINE_CHARS) else text

/** A unified line's accessibility text: old number, new number, marker, code, e.g. `1 1   def add(a, b):`. */
internal fun unifiedText(line: DiffLine): String =
    (listOfNotNull(line.oldNo, line.newNo) + "${line.kind.marker} ${line.displayText()}").joinToString(" ")

/** One side of a split row: that side's number, marker, code. */
internal fun sideText(line: DiffLine, side: DiffSide): String =
    listOfNotNull(line.number(side), "${line.kind.marker} ${line.displayText()}").joinToString(" ")

/** The 10-column unified gutter (6 per split side), widened per digit beyond 9,999. */
internal fun unifiedGutter(line: DiffLine, digits: Int): String =
    (line.oldNo?.toString() ?: "").padStart(digits) + (line.newNo?.toString() ?: "").padStart(digits) + line.kind.marker + ' '

internal fun sideGutter(line: DiffLine, side: DiffSide, digits: Int): String =
    (line.number(side)?.toString() ?: "").padStart(digits) + line.kind.marker + ' '

/** A gutter selection: [anchor] is the first tapped line, [focus] the last; both are line numbers on [side]. */
internal data class LineSelection(val fileId: String, val path: String, val side: DiffSide, val anchor: Int, val focus: Int) {
    val start: Int get() = minOf(anchor, focus)
    val end: Int get() = maxOf(anchor, focus)

    fun covers(fileId: String, side: DiffSide, line: Int?): Boolean =
        line != null && fileId == this.fileId && side == this.side && line in start..end

    fun toDiffSelection(): DiffSelection = DiffSelection(path, side, start, end)
}

/**
 * A gutter tap. Another line on the same side of the same file extends the range from the anchor; tapping the
 * only selected line clears it; anything else starts a new selection.
 */
internal fun LineSelection?.tap(fileId: String, path: String, side: DiffSide, line: Int): LineSelection? = when {
    this == null || this.fileId != fileId || this.side != side -> LineSelection(fileId, path, side, line, line)
    anchor == focus && anchor == line -> null
    else -> copy(focus = line)
}

/** Comment on Hunk: the hunk's new-side lines, or its old-side lines when it only deletes. */
internal fun hunkSelection(fileId: String, path: String, hunk: Hunk): LineSelection =
    if (hunk.newCount > 0) LineSelection(fileId, path, DiffSide.New, hunk.newStart, hunk.newStart + hunk.newCount - 1)
    else LineSelection(fileId, path, DiffSide.Old, hunk.oldStart, hunk.oldStart + hunk.oldCount - 1)

/** The hunk a selection's explanation shows under: the last hunk starting at or before its first line. */
internal fun hunkIndexOf(diff: FileDiffView.Ready, side: DiffSide, line: Int): Int =
    diff.hunks.indexOfLast { (if (side == DiffSide.Old) it.oldStart else it.newStart) <= line }.coerceAtLeast(0)

/** The code of the selected lines, one per line, for Copy. */
internal fun selectedText(diff: FileDiffView.Ready, selection: LineSelection): String =
    diff.lines.filter { it.kind != DiffLineKind.NoNewline && selection.covers(selection.fileId, selection.side, it.number(selection.side)) }
        .joinToString("\n") { it.text }
