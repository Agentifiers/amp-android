package com.agentifiers.amp.changes

import com.agentifiers.amp.model.ChangeKind
import com.agentifiers.amp.model.ChangeScope
import com.agentifiers.amp.model.ChangeSection
import com.agentifiers.amp.model.ChangeSource
import com.agentifiers.amp.model.ChangesFacts
import com.agentifiers.amp.model.ChangesView
import com.agentifiers.amp.model.DiffLine
import com.agentifiers.amp.model.DiffLineKind
import com.agentifiers.amp.model.DiffSelection
import com.agentifiers.amp.model.DiffSide
import com.agentifiers.amp.model.DiffStat
import com.agentifiers.amp.model.FileChange
import com.agentifiers.amp.model.FileDiffView
import com.agentifiers.amp.model.FileOrder
import com.agentifiers.amp.model.Hunk
import com.agentifiers.amp.model.Language
import com.agentifiers.amp.model.RepoChanges
import com.agentifiers.amp.model.SectionId
import com.agentifiers.amp.model.SectionKind
import com.agentifiers.amp.model.ShipBehavior
import com.agentifiers.amp.model.ShipTarget
import com.agentifiers.amp.model.StageState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Test

class DiffRowsTest {
    private fun file(path: String, change: ChangeKind = ChangeKind.Modified) =
        FileChange("u-$path", path, change, DiffStat(1, 1, 0), false, false, StageState.Unstaged, null, null, null)

    private fun line(kind: DiffLineKind, oldNo: Int?, newNo: Int?, text: String) =
        DiffLine(kind, oldNo, newNo, text, persistentListOf(), persistentListOf())

    /** A 60-line file whose line 31 changed: store indices 0..29 and 32..60 are context, 30 removed, 31 added. */
    private fun calcDiff(expandable: Boolean = true): FileDiffView.Ready {
        val lines = (1..30).map { line(DiffLineKind.Context, it, it, "line $it") } +
            line(DiffLineKind.Removed, 31, null, "old 31") +
            line(DiffLineKind.Added, null, 31, "new 31") +
            (32..60).map { line(DiffLineKind.Context, it, it, "line $it") }
        val hunk = Hunk(oldStart = 28, oldCount = 7, newStart = 28, newCount = 7, header = "def add", firstLine = 27, lineCount = 8)
        return FileDiffView.Ready("calc.py", persistentListOf(hunk), lines.toPersistentList(), expandable, Language.Python)
    }

    private val calc = file("calc.py")
    private val hunkLines = (27..34).map { "u-calc.py:$it" }

    @Test
    fun hiddenGapsSitAroundTheHunkWithTheFirstStepOf25() {
        val rows = fileBodyRows(calc, calcDiff(), split = false, revealed = emptyMap(), explanations = emptyMap())
        assertEquals(listOf("u-calc.py:g0", "u-calc.py:h0") + hunkLines + "u-calc.py:g1", rows.map { it.key })
        assertEquals(listOf(27 to 25, 26 to 25), rows.filterIsInstance<GapRow>().map { it.hidden to it.step })
    }

    @Test
    fun revealingTheFirstGapShowsTheLinesNextToTheHunkAndLaterGapsShowTheirTop() {
        val rows = fileBodyRows(calc, calcDiff(), false, mapOf("u-calc.py:0" to 25, "u-calc.py:1" to 25), emptyMap())
        assertEquals(
            listOf("u-calc.py:g0") + (2..26).map { "u-calc.py:$it" } + "u-calc.py:h0" + hunkLines + (35..59).map { "u-calc.py:$it" } + "u-calc.py:g1",
            rows.map { it.key },
        )
        assertEquals(listOf(2 to 2, 1 to 1), rows.filterIsInstance<GapRow>().map { it.hidden to it.step })
    }

    @Test
    fun showMoreDoublesEachPress() {
        assertEquals(listOf(25, 75, 175), generateSequence(nextReveal(0), ::nextReveal).take(3).toList())
    }

    @Test
    fun showAllRemovesTheGapRowAndANonExpandableDiffHasNoGaps() {
        val all = fileBodyRows(calc, calcDiff(), false, mapOf("u-calc.py:0" to Int.MAX_VALUE), emptyMap())
        assertEquals((0..26).map { "u-calc.py:$it" } + "u-calc.py:h0", all.map { it.key }.take(28))
        assertEquals(listOf("u-calc.py:h0") + hunkLines, fileBodyRows(calc, calcDiff(expandable = false), false, emptyMap(), emptyMap()).map { it.key })
    }

    @Test
    fun anExplanationSitsUnderItsHunkHeader() {
        val rows = fileBodyRows(calc, calcDiff(expandable = false), false, emptyMap(), mapOf("u-calc.py:0" to "Adds the carry."))
        assertEquals(listOf("u-calc.py:h0", "u-calc.py:x0", "u-calc.py:27"), rows.take(3).map { it.key })
        assertEquals(ExplanationRow("u-calc.py", "calc.py", 0, "Adds the carry."), rows[1])
    }

    @Test
    fun bodiesWithoutLinesShowTheirText() {
        val renamed = file("src/new.py", ChangeKind.Renamed("src/old.py"))
        val emptyReady = FileDiffView.Ready("src/new.py", persistentListOf(), persistentListOf(), false, Language.Python)
        assertEquals(
            listOf(
                NoticeRow("u-src/new.py", "Renamed without changes"),
                NoticeRow("u-src/new.py", "Loading diff…"),
                NoticeRow("u-src/new.py", "Binary file · old 120 bytes · new 251 bytes"),
                NoticeRow("u-src/new.py", "File too large to display"),
            ),
            listOf(
                emptyReady,
                FileDiffView.Loading,
                FileDiffView.Binary(120, 251),
                FileDiffView.TooLarge("File too large to display"),
            ).flatMap { fileBodyRows(renamed, it, false, emptyMap(), emptyMap()) },
        )
    }

    @Test
    fun lineTextIsNumbersMarkerAndCode() {
        assertEquals("1 1   def add(a, b):", unifiedText(line(DiffLineKind.Context, 1, 1, "def add(a, b):")))
        assertEquals("42 + for i in 0..64 {", unifiedText(line(DiffLineKind.Added, null, 42, "for i in 0..64 {")))
        assertEquals("7 - return a", sideText(line(DiffLineKind.Removed, 7, null, "return a"), DiffSide.Old))
        assertEquals("   1   1  ", unifiedGutter(line(DiffLineKind.Context, 1, 1, "x"), 4))
        assertEquals("  42+ ", sideGutter(line(DiffLineKind.Added, null, 42, "x"), DiffSide.New, 4))
    }

    @Test
    fun gutterWidensPastFourDigits() {
        val big = FileDiffView.Ready(
            "big.txt",
            persistentListOf(Hunk(12345, 1, 12345, 1, "", 0, 1)),
            persistentListOf(line(DiffLineKind.Context, 12345, 12345, "x")),
            false,
            Language.Plain,
        )
        assertEquals(listOf(5, 4), listOf(FileDiff(file("big.txt"), big).digits, FileDiff(calc, calcDiff()).digits))
    }

    @Test
    fun gutterTapsSelectExtendAndClear() {
        val first = null.tap("u-calc.py", "calc.py", DiffSide.New, 30)
        val extended = first.tap("u-calc.py", "calc.py", DiffSide.New, 28)
        assertEquals(DiffSelection("calc.py", DiffSide.New, 28, 30), extended?.toDiffSelection())
        assertEquals(LineSelection("u-calc.py", "calc.py", DiffSide.Old, 31, 31), extended.tap("u-calc.py", "calc.py", DiffSide.Old, 31))
        assertEquals(null, first.tap("u-calc.py", "calc.py", DiffSide.New, 30))
    }

    @Test
    fun commentOnHunkTakesTheNewSideUnlessTheHunkOnlyDeletes() {
        assertEquals(LineSelection("f", "a.py", DiffSide.New, 28, 34), hunkSelection("f", "a.py", Hunk(28, 7, 28, 7, "", 27, 8)))
        assertEquals(LineSelection("f", "a.py", DiffSide.Old, 10, 12), hunkSelection("f", "a.py", Hunk(10, 3, 9, 0, "", 0, 3)))
    }

    @Test
    fun copyTakesTheSelectedSidesCode() {
        val diff = calcDiff()
        assertEquals("line 30\nnew 31", selectedText(diff, LineSelection("u-calc.py", "calc.py", DiffSide.New, 30, 31)))
        assertEquals("line 30\nold 31", selectedText(diff, LineSelection("u-calc.py", "calc.py", DiffSide.Old, 30, 31)))
        assertEquals(0, hunkIndexOf(diff, DiffSide.New, 50))
    }

    private fun treeView(): ChangesView.Ready {
        val files = listOf(file("README.md"), file("src/a.py"), file("src/b.py"))
        val section = ChangeSection(SectionId("unstaged"), SectionKind.Unstaged, "h1", files.toPersistentList(), null, null)
        return ChangesView.Ready(
            source = ChangeSource.LiveGit("abc"),
            scopes = persistentListOf(ChangeScope.Uncommitted),
            scope = ChangeScope.Uncommitted.id,
            repos = persistentListOf(RepoChanges("amp", "main", 0, persistentListOf(section))),
            order = FileOrder.Alphabetical,
            facts = ChangesFacts(ShipTarget.Unknown, ShipBehavior.Ship, false, "main", "main", true, 0, false, null, null),
            canStage = true,
            stale = false,
            notice = null,
            noticeDetail = null,
        )
    }

    @Test
    fun treeGroupsFilesByDirectoryAndExpandedFilesCarryTheirBody() {
        val rows = paneRows(treeView(), tree = true, expanded = { it.path == "src/a.py" }) { listOf(NoticeRow(it.id, "Loading diff…")) }
        assertEquals(
            listOf("section:unstaged", "file:u-README.md", "dir:unstaged:src", "file:u-src/a.py", "u-src/a.py:notice", "file:u-src/b.py"),
            rows.map { it.key },
        )
        assertEquals(listOf(false, true, false), rows.filterIsInstance<FileRow>().map { it.expanded })
    }

    @Test
    fun onlySeveralRepositoriesGetHeadingRows() {
        val one = treeView()
        val two = one.copy(repos = persistentListOf(one.repos[0], RepoChanges("dct", null, 0, persistentListOf())))
        assertEquals(listOf("repo:amp", "section:unstaged", "repo:dct"), paneRows(two, tree = false, expanded = null) { emptyList() }.filter { it !is FileRow }.map { it.key })
    }
}
