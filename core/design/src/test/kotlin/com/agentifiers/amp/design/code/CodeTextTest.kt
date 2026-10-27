package com.agentifiers.amp.design.code

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.agentifiers.amp.design.theme.Forest
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeTextTest {
    private fun AnnotatedString.spans() = spanStyles.map { Triple(it.item, it.start, it.end) }
    private val ansi = Forest.code.ansi

    @Test
    fun ansiRedThenResetKeepsTheTextAndColorsTheRedRun() {
        val text = ansiToAnnotatedString("\u001B[31mred\u001B[0m plain", ansi)

        assertEquals("red plain", text.text)
        assertEquals(listOf(Triple(SpanStyle(color = Color(0xFFD67670)), 0, 3)), text.spans())
    }

    @Test
    fun ansiStripsTitleAndEraseSequencesAndReadsBold256Color() {
        val text = ansiToAnnotatedString("\u001B]0;title\u0007\u001B[1;38;5;196mX\u001B[22;39m y\u001B[K\r\n", ansi)

        assertEquals("X y\n", text.text)
        assertEquals(listOf(Triple(SpanStyle(color = Color(255, 0, 0), fontWeight = FontWeight.Bold), 0, 1)), text.spans())
    }

    @Test
    fun highlightMarksEveryCaseInsensitiveMatch() {
        val plain = AnnotatedString("Foo foo")
        val mark = SpanStyle(background = Color(0xFF28382F))

        assertEquals(listOf(Triple(mark, 0, 3), Triple(mark, 4, 7)), highlighted(plain, "FOO", Color(0xFF28382F)).spans())
        assertEquals(emptyList<Triple<SpanStyle, Int, Int>>(), highlighted(plain, null, Color(0xFF28382F)).spans())
    }

    @Test
    fun fencedDiffDropsTheFenceAndClassifiesEachLine() {
        val diff = parseDiff("```diff\n--- a/f\n+++ b/f\n@@ -1,2 +1,2 @@\n-old\n+new\n ctx\n```", Forest.code.diff)

        assertEquals("--- a/f\n+++ b/f\n@@ -1,2 +1,2 @@\n-old\n+new\n ctx", diff.text.text)
        assertEquals(
            listOf(DiffLine.Header, DiffLine.Header, DiffLine.Hunk, DiffLine.Removed, DiffLine.Added, DiffLine.Context),
            diff.lines,
        )
    }

    @Test
    fun collapseCutsAfterTheLineCountOnlyWhenTextIsLonger() {
        assertEquals(3, collapseOffset("a\nb\nc", 2))
        assertEquals(null, collapseOffset("a\nb\n", 2))
    }
}
