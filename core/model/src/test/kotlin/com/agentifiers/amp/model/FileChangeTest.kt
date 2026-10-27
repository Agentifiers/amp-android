package com.agentifiers.amp.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FileChangeTest {
    private fun file(path: String, change: ChangeKind, added: Int, deleted: Int, muted: Boolean = false) =
        FileChange(path, path, change, DiffStat(added, deleted, 0), binary = false, muted = muted, stage = StageState.Unstaged, oldSize = null, newSize = null, omittedReason = null)

    @Test
    fun `row text speaks only the counts the row shows`() {
        assertEquals(
            listOf(
                "A notes/plan.md added +3",
                "D old.py deleted \u221212",
                "M calc.py modified +2 \u22121",
                "M build.bin modified muted",
            ),
            listOf(
                file("notes/plan.md", ChangeKind.Added, 3, 0).rowText,
                file("old.py", ChangeKind.Deleted, 0, 12).rowText,
                file("calc.py", ChangeKind.Modified, 2, 1).rowText,
                file("build.bin", ChangeKind.Modified, 0, 0, muted = true).rowText,
            ),
        )
    }

    @Test
    fun `a zero count has no label`() {
        assertEquals(listOf("+3", null, null, "\u22122"), listOf(DiffStat(3, 0, 0).addedLabel, DiffStat(3, 0, 0).deletedLabel, DiffStat(0, 2, 0).addedLabel, DiffStat(0, 2, 0).deletedLabel))
    }
}
