package com.agentifiers.amp.changes

import com.agentifiers.amp.design.ChangesTags
import com.agentifiers.amp.model.FileOrder

/** What sits under the Changes toolbar: the file list, one file's diff with Back, or the Review diff. */
internal enum class PaneMode { List, File, Review }

/** A toolbar icon action, with its tag in the row and its tag once it moves into More Actions. */
internal enum class ToolbarAction(val tag: String, val menuTag: String, val jump: Boolean = false) {
    PrevHunk(ChangesTags.prevHunk, ChangesTags.prevHunkMenu, jump = true),
    NextHunk(ChangesTags.nextHunk, ChangesTags.nextHunkMenu, jump = true),
    PrevFile(ChangesTags.prevFile, ChangesTags.prevFileMenu, jump = true),
    NextFile(ChangesTags.nextFile, ChangesTags.nextFileMenu, jump = true),
    FileList(ChangesTags.fileList, ChangesTags.fileListMenu),
    Layout(ChangesTags.layout, ChangesTags.layoutMenu),
    Wrap(ChangesTags.wrap, ChangesTags.wrapMenu),
    Refresh(ChangesTags.refresh, ChangesTags.refreshMenu),
}

/** In priority order: a narrow pane's header moves the last ones into More Actions first. Only Review offers split. */
internal fun toolbarActions(mode: PaneMode): List<ToolbarAction> = when (mode) {
    PaneMode.List -> listOf(ToolbarAction.Refresh)
    PaneMode.File -> listOf(ToolbarAction.PrevHunk, ToolbarAction.NextHunk, ToolbarAction.Wrap, ToolbarAction.Refresh)
    PaneMode.Review -> listOf(
        ToolbarAction.PrevHunk, ToolbarAction.NextHunk, ToolbarAction.PrevFile, ToolbarAction.NextFile,
        ToolbarAction.FileList, ToolbarAction.Layout, ToolbarAction.Wrap, ToolbarAction.Refresh,
    )
}

internal fun ToolbarAction.label(split: Boolean, wrap: Boolean): String = when (this) {
    ToolbarAction.PrevHunk -> "Previous Hunk"
    ToolbarAction.NextHunk -> "Next Hunk"
    ToolbarAction.PrevFile -> "Previous File"
    ToolbarAction.NextFile -> "Next File"
    ToolbarAction.FileList -> "File List"
    ToolbarAction.Layout -> if (split) "Show Unified Diff" else "Show Split Diff"
    ToolbarAction.Wrap -> if (wrap) "Disable Line Wrapping" else "Enable Line Wrapping"
    ToolbarAction.Refresh -> "Refresh"
}

/** The row each Previous or Next action jumps to from the first visible row [first]. An action with nothing left that way is absent. */
internal fun jumps(first: Int, hunks: List<Int>, files: List<Int>): Map<ToolbarAction, Int> = buildMap {
    hunks.lastOrNull { it < first }?.let { put(ToolbarAction.PrevHunk, it) }
    hunks.firstOrNull { it > first }?.let { put(ToolbarAction.NextHunk, it) }
    files.lastOrNull { it < first }?.let { put(ToolbarAction.PrevFile, it) }
    files.firstOrNull { it > first }?.let { put(ToolbarAction.NextFile, it) }
}

internal enum class MenuAction { Order, Tree, FoldAll }

internal data class ToolbarMenuItem(val action: MenuAction, val label: String, val tag: String, val enabled: Boolean = true)

/** The items that always live in More Actions. Folding every file only exists in Review. */
internal fun toolbarMenu(order: FileOrder, tree: Boolean, mode: PaneMode, anyExpanded: Boolean): List<ToolbarMenuItem> = buildList {
    add(ToolbarMenuItem(MenuAction.Order, orderLabel(order), ChangesTags.order, enabled = orderRequest(order) != null))
    if (order !is FileOrder.Intelligent) add(ToolbarMenuItem(MenuAction.Tree, if (tree) "Show as List" else "Show as Tree", ChangesTags.tree))
    if (mode == PaneMode.Review) {
        add(
            if (anyExpanded) ToolbarMenuItem(MenuAction.FoldAll, "Collapse All Files", ChangesTags.collapseAll)
            else ToolbarMenuItem(MenuAction.FoldAll, "Expand All Files", ChangesTags.expandAll),
        )
    }
}
