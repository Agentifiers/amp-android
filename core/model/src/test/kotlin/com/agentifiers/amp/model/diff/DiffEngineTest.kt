package com.agentifiers.amp.model.diff

import com.agentifiers.amp.model.DiffLineKind
import com.agentifiers.amp.model.FileDiffView
import com.agentifiers.amp.model.Hunk
import com.agentifiers.amp.model.SyntaxKind
import com.agentifiers.amp.model.SyntaxSpan
import com.agentifiers.amp.model.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffEngineTest {
    private val calcOld = "def add(a, b):\n    return a - b\n"
    private val calcNew = "def add_numbers(a, b):\n    return a + b\n"

    private fun ready(view: FileDiffView): FileDiffView.Ready = view as FileDiffView.Ready

    private fun numbered(count: Int) = (1..count).map { "line $it" }

    private fun text(lines: List<String>) = lines.joinToString("\n", postfix = "\n")

    private fun unified(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    private fun shape(diff: FileDiffView.Ready) = diff.lines.map { Triple(it.kind, it.oldNo, it.newNo) }

    @Test
    fun modifiedCalcMatchesGitFileDiff() {
        val diff = ready(DiffEngine.fromContents("calc.py", calcOld, calcNew))

        assertEquals(
            "@@ -1,2 +1,2 @@\n-def add(a, b):\n-    return a - b\n+def add_numbers(a, b):\n+    return a + b\n",
            DiffEngine.toUnified(diff),
        )
        assertEquals(listOf(Hunk(1, 2, 1, 2, "", 0, 4)), diff.hunks)
        assertEquals(true, diff.expandable)
    }

    @Test
    fun addedFileIsOneHunkFromLineZero() {
        val diff = ready(DiffEngine.fromContents("PLAN.md", null, "# Plan\n\nRename add to add_numbers.\n"))

        assertEquals("@@ -0,0 +1,3 @@\n+# Plan\n+\n+Rename add to add_numbers.\n", DiffEngine.toUnified(diff))
    }

    @Test
    fun deletedFileIsOneHunkToLineZero() {
        val diff = ready(DiffEngine.fromContents("calc.py", calcOld, null))

        assertEquals("@@ -1,2 +0,0 @@\n-def add(a, b):\n-    return a - b\n", DiffEngine.toUnified(diff))
    }

    @Test
    fun identicalContentsHaveNoHunksButKeepEveryLine() {
        val diff = ready(DiffEngine.fromContents("calc.py", calcOld, calcOld))

        assertEquals("", DiffEngine.toUnified(diff))
        assertEquals(
            listOf(Triple(DiffLineKind.Context, 1, 1), Triple(DiffLineKind.Context, 2, 2)),
            shape(diff),
        )
    }

    @Test
    fun decodeRejectsNulAndMalformedUtf8() {
        assertNull(DiffEngine.decode(byteArrayOf(0x61, 0x00, 0x62)))
        assertNull(DiffEngine.decode(byteArrayOf(0xC3.toByte(), 0x28)))
        assertEquals("héllo", DiffEngine.decode("héllo".toByteArray()))
    }

    @Test
    fun binaryPatchFromS07IsBinary() {
        val patch = "diff --git a/__pycache__/calc.cpython-312.pyc b/__pycache__/calc.cpython-312.pyc\n" +
            "new file mode 100644\nindex 0000000..449e256\n" +
            "Binary files /dev/null and b/__pycache__/calc.cpython-312.pyc differ"

        assertEquals(FileDiffView.Binary(null, null), DiffEngine.fromPatch("__pycache__/calc.cpython-312.pyc", patch))
    }

    @Test
    fun crlfDiffsLikeLf() {
        val diff = ready(DiffEngine.fromContents("a.txt", "a\r\nb\r\n", "a\r\nc\r\n"))

        assertEquals("@@ -1,2 +1,2 @@\n a\n-b\n+c\n", DiffEngine.toUnified(diff))
        assertEquals(listOf("a", "b", "c"), diff.lines.map { it.text })
    }

    @Test
    fun missingFinalNewlineMarksBothSidesLikeGit() {
        val diff = ready(DiffEngine.fromContents("a.txt", "a\nb", "a\nc"))

        assertEquals(
            "@@ -1,2 +1,2 @@\n a\n-b\n\\ No newline at end of file\n+c\n\\ No newline at end of file\n",
            DiffEngine.toUnified(diff),
        )
    }

    @Test
    fun finalNewlineAddedChangesTheLastLine() {
        val diff = ready(DiffEngine.fromContents("a.txt", "a\nb", "a\nb\n"))

        assertEquals("@@ -1,2 +1,2 @@\n a\n-b\n\\ No newline at end of file\n+b\n", DiffEngine.toUnified(diff))
    }

    // Expected texts are `git diff --no-index -U3` output (git 2.50.1) with git's function-context header after the
    // second `@@` removed, because the engine leaves `Hunk.header` empty.
    @Test
    fun threeSeparatedChangesMatchGit() {
        val new = numbered(40).toMutableList().apply {
            this[2] = "line 3 changed"
            removeAt(11)
            val at = indexOf("line 30")
            this[at] = "line 30 edited"
            add(at + 1, "inserted 30a")
        }
        val diff = ready(DiffEngine.fromContents("lines.txt", text(numbered(40)), text(new)))

        assertEquals(
            unified(
                "@@ -1,6 +1,6 @@", " line 1", " line 2", "-line 3", "+line 3 changed", " line 4", " line 5", " line 6",
                "@@ -9,7 +9,6 @@", " line 9", " line 10", " line 11", "-line 12", " line 13", " line 14", " line 15",
                "@@ -27,7 +26,8 @@", " line 27", " line 28", " line 29", "-line 30", "+line 30 edited", "+inserted 30a",
                " line 31", " line 32", " line 33",
            ),
            DiffEngine.toUnified(diff),
        )
    }

    @Test
    fun changesSixLinesApartShareAHunkLikeGit() {
        val new = numbered(20).toMutableList().apply {
            this[4] = "line 5 changed"
            this[11] = "line 12 changed"
        }
        val diff = ready(DiffEngine.fromContents("lines.txt", text(numbered(20)), text(new)))

        assertEquals(
            unified(
                "@@ -2,14 +2,14 @@", " line 2", " line 3", " line 4", "-line 5", "+line 5 changed", " line 6", " line 7",
                " line 8", " line 9", " line 10", " line 11", "-line 12", "+line 12 changed", " line 13", " line 14", " line 15",
            ),
            DiffEngine.toUnified(diff),
        )
    }

    @Test
    fun changesSevenLinesApartSplitLikeGit() {
        val new = numbered(20).toMutableList().apply {
            this[4] = "line 5 changed"
            this[12] = "line 13 changed"
        }
        val diff = ready(DiffEngine.fromContents("lines.txt", text(numbered(20)), text(new)))

        assertEquals(
            unified(
                "@@ -2,7 +2,7 @@", " line 2", " line 3", " line 4", "-line 5", "+line 5 changed", " line 6", " line 7", " line 8",
                "@@ -10,7 +10,7 @@", " line 10", " line 11", " line 12", "-line 13", "+line 13 changed", " line 14", " line 15",
                " line 16",
            ),
            DiffEngine.toUnified(diff),
        )
        assertEquals(
            "@@ -10,7 +10,7 @@\n line 10\n line 11\n line 12\n-line 13\n+line 13 changed\n line 14\n line 15\n line 16\n",
            DiffEngine.hunkText(diff, 1),
        )
    }

    @Test
    fun veryLongLinesKeepTheirTextWithoutHighlights() {
        val old = "val x = 1\n" + "a".repeat(25_000) + "\n"
        val new = "val x = 1\n" + "b".repeat(25_000) + "\n"
        val diff = ready(DiffEngine.fromContents("Long.kt", old, new))

        val removed = diff.lines[1]
        assertEquals(DiffLineKind.Removed, removed.kind)
        assertEquals("a".repeat(25_000), removed.text)
        assertEquals(emptyList<TextSpan>(), removed.intraline)
        assertEquals(emptyList<SyntaxSpan>(), removed.tokens)
        assertEquals(listOf(SyntaxSpan(0, 3, SyntaxKind.Keyword), SyntaxSpan(8, 9, SyntaxKind.Number)), diff.lines[0].tokens)
    }

    @Test
    fun nineHundredCharacterLinesStillGetWordSpans() {
        val prefix = "a".repeat(895)
        val diff = ready(DiffEngine.fromContents("long.txt", "$prefix one\n", "$prefix two\n"))

        assertEquals(listOf(TextSpan(896, 899)), diff.lines[0].intraline)
        assertEquals(listOf(TextSpan(896, 899)), diff.lines[1].intraline)
    }

    @Test
    fun patchFromS07ParsesLikeContents() {
        val stdout = "diff --git a/calc.py b/calc.py\nindex 12ee743..bc2226f 100644\n--- a/calc.py\n+++ b/calc.py\n" +
            "@@ -1,2 +1,2 @@\n-def add(a, b):\n-    return a - b\n+def add_numbers(a, b):\n+    return a + b\n"
        val expected = listOf(
            Triple(DiffLineKind.Removed, 1, null),
            Triple(DiffLineKind.Removed, 2, null),
            Triple(DiffLineKind.Added, null, 1),
            Triple(DiffLineKind.Added, null, 2),
        )

        val fromPatch = ready(DiffEngine.fromPatch("calc.py", stdout))
        val fromSnapshotField = ready(DiffEngine.fromPatch("calc.py", stdout.removeSuffix("\n")))

        assertEquals(expected, shape(fromPatch))
        assertEquals(expected, shape(fromSnapshotField))
        assertEquals(expected, shape(ready(DiffEngine.fromContents("calc.py", calcOld, calcNew))))
        assertEquals(listOf(Hunk(1, 2, 1, 2, "", 0, 4)), fromPatch.hunks)
        assertEquals(false, fromPatch.expandable)
        assertEquals(listOf(TextSpan(13, 14)), fromPatch.lines[1].intraline)
    }

    @Test
    fun patchHunkWithoutCountsDefaultsToOne() {
        val diff = ready(DiffEngine.fromPatch("Main.kt", "@@ -3 +3 @@ fun main()\n-a\n\\ No newline at end of file\n+b"))

        assertEquals(listOf(Hunk(3, 1, 3, 1, "fun main()", 0, 3)), diff.hunks)
        assertEquals("@@ -3 +3 @@ fun main()\n-a\n\\ No newline at end of file\n+b\n", DiffEngine.toUnified(diff))
    }

    @Test
    fun truncatedPatchFails() {
        assertEquals(FileDiffView.Failed("The diff could not be read."), DiffEngine.fromPatch("a.txt", "@@ -1,2 +1,2 @@\n-a\n+b"))
    }

    @Test
    fun sideOverFiftyThousandLinesIsTooLarge() {
        assertEquals(
            FileDiffView.TooLarge("This file is too large to diff on this device."),
            DiffEngine.fromContents("big.txt", "x\n".repeat(50_001), "y\n"),
        )
    }

    @Test
    fun threeThousandLinesWithThreeHundredChangesDiffQuickly() {
        val old = (1..3_000).map { "    val value$it = compute($it, \"item $it\") // step $it" }
        val new = old.mapIndexed { index, line -> if ((index + 1) % 10 == 0) line.replace("compute", "computeFaster") else line }

        val started = System.nanoTime()
        val diff = ready(DiffEngine.fromContents("Big.kt", text(old), text(new)))
        val coldMs = (System.nanoTime() - started) / 1_000_000
        val warmStarted = System.nanoTime()
        DiffEngine.fromContents("Big.kt", text(old), text(new))
        val warmMs = (System.nanoTime() - warmStarted) / 1_000_000
        println("DiffEngine.fromContents 3,000 lines / 300 changes: cold ${coldMs} ms, warm ${warmMs} ms")

        assertEquals(300, diff.hunks.size)
        assertEquals(Hunk(7, 7, 7, 7, "", 6, 8), diff.hunks.first())
        assertEquals(listOf(TextSpan(18, 31)), diff.lines[diff.hunks.first().firstLine + 4].intraline)
        assertTrue("took $coldMs ms", coldMs < 500)

        val myersStarted = System.nanoTime()
        val edits = Myers.diff(old, new)
        val myersMs = (System.nanoTime() - myersStarted) / 1_000_000.0
        println("Myers.diff 3,000 lines / 300 changes: $myersMs ms")
        assertEquals(600, edits.count { it != Edit.Keep })
    }
}
