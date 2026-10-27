package com.agentifiers.amp.changes

import com.agentifiers.amp.model.FileOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarTest {
    private fun row(mode: PaneMode, split: Boolean = false, wrap: Boolean = true) =
        toolbarActions(mode).map { it.label(split, wrap) to it.tag }

    @Test
    fun eachModeListsItsActionsInPriorityOrder() {
        assertEquals(listOf("Refresh" to "changes-refresh"), row(PaneMode.List))
        assertEquals(
            listOf(
                "Previous Hunk" to "diff-prev-hunk",
                "Next Hunk" to "diff-next-hunk",
                "Disable Line Wrapping" to "changes-wrap",
                "Refresh" to "changes-refresh",
            ),
            row(PaneMode.File),
        )
        assertEquals(
            listOf(
                "Previous Hunk" to "diff-prev-hunk",
                "Next Hunk" to "diff-next-hunk",
                "Previous File" to "diff-prev-file",
                "Next File" to "diff-next-file",
                "File List" to "diff-file-list",
                "Show Unified Diff" to "changes-layout",
                "Enable Line Wrapping" to "changes-wrap",
                "Refresh" to "changes-refresh",
            ),
            row(PaneMode.Review, split = true, wrap = false),
        )
    }

    @Test
    fun movedActionsCarryTheirMenuTags() {
        assertEquals(
            listOf(
                "diff-prev-hunk-menu", "diff-next-hunk-menu", "diff-prev-file-menu", "diff-next-file-menu",
                "diff-file-list-menu", "changes-layout-menu", "changes-wrap-menu", "changes-refresh-menu",
            ),
            toolbarActions(PaneMode.Review).map { it.menuTag },
        )
    }

    @Test
    fun jumpsGoToTheNearestRowEachWayFromTheFirstVisibleRow() {
        val hunks = listOf(1, 5, 9)
        val files = listOf(0, 8)
        assertEquals(
            mapOf(ToolbarAction.PrevHunk to 1, ToolbarAction.NextHunk to 9, ToolbarAction.PrevFile to 0, ToolbarAction.NextFile to 8),
            jumps(first = 5, hunks = hunks, files = files),
        )
        assertEquals(mapOf(ToolbarAction.NextHunk to 1, ToolbarAction.NextFile to 8), jumps(first = 0, hunks = hunks, files = files))
        assertEquals(mapOf(ToolbarAction.PrevHunk to 9, ToolbarAction.PrevFile to 8), jumps(first = 12, hunks = hunks, files = files))
    }

    @Test
    fun menuHoldsOrderThenTreeThenFolding() {
        assertEquals(
            listOf(ToolbarMenuItem(MenuAction.Order, "Order Files Alphabetically", "changes-order")),
            toolbarMenu(FileOrder.Intelligent(updateAvailable = false), tree = false, mode = PaneMode.File, anyExpanded = false),
        )
        assertEquals(
            listOf(
                ToolbarMenuItem(MenuAction.Order, "Order Files Intelligently", "changes-order"),
                ToolbarMenuItem(MenuAction.Tree, "Show as Tree", "changes-tree"),
                ToolbarMenuItem(MenuAction.FoldAll, "Expand All Files", "diff-expand-all"),
            ),
            toolbarMenu(FileOrder.Alphabetical, tree = false, mode = PaneMode.Review, anyExpanded = false),
        )
        assertEquals(
            listOf(
                ToolbarMenuItem(MenuAction.Order, "Intelligent File Order Unavailable", "changes-order", enabled = false),
                ToolbarMenuItem(MenuAction.Tree, "Show as List", "changes-tree"),
                ToolbarMenuItem(MenuAction.FoldAll, "Collapse All Files", "diff-collapse-all"),
            ),
            toolbarMenu(FileOrder.Unavailable("No model"), tree = true, mode = PaneMode.Review, anyExpanded = true),
        )
    }
}
