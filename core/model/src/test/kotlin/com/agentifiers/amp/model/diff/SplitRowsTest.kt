package com.agentifiers.amp.model.diff

import com.agentifiers.amp.model.FileDiffView
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitRowsTest {
    private fun rows(old: String, new: String): List<SplitRow> {
        val diff = DiffEngine.fromContents("a.txt", old, new) as FileDiffView.Ready
        return splitRows(diff, 0, diff.lineCount)
    }

    @Test
    fun calcPairsRemovedWithAddedByPosition() {
        assertEquals(
            listOf(SplitRow(0, 2), SplitRow(1, 3)),
            rows("def add(a, b):\n    return a - b\n", "def add_numbers(a, b):\n    return a + b\n"),
        )
    }

    @Test
    fun extraAddedLinesLeaveTheOldSideBlank() {
        assertEquals(
            listOf(SplitRow(0, 0), SplitRow(1, 2), SplitRow(null, 3), SplitRow(null, 4), SplitRow(5, 5)),
            rows("a\nx\nz\n", "a\ny1\ny2\ny3\nz\n"),
        )
    }

    @Test
    fun noNewlineMarkersStayOnTheirSide() {
        assertEquals(listOf(SplitRow(0, 0), SplitRow(1, 3), SplitRow(2, 4)), rows("a\nb", "a\nc"))
        assertEquals(listOf(SplitRow(0, 0), SplitRow(1, 3), SplitRow(2, null)), rows("a\nb", "a\nb\n"))
    }

    @Test
    fun rowsCoverOnlyTheRequestedRange() {
        val diff = DiffEngine.fromContents("a.txt", "a\nx\nz\n", "a\ny1\ny2\ny3\nz\n") as FileDiffView.Ready

        assertEquals(listOf(SplitRow(1, 2), SplitRow(null, 3)), splitRows(diff, 1, 4))
    }
}
