package com.agentifiers.amp.model.diff

import com.agentifiers.amp.model.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class WordDiffTest {
    @Test
    fun operatorChangeHighlightsOnlyTheOperator() {
        val spans = WordDiff.block(listOf("    return a - b"), listOf("    return a + b"))

        assertEquals(listOf(listOf(TextSpan(13, 14))), spans.removed)
        assertEquals(listOf(listOf(TextSpan(13, 14))), spans.added)
    }

    @Test
    fun renamedIdentifierHighlightsTheWholeWord() {
        val spans = WordDiff.block(listOf("def add(a, b):"), listOf("def add_numbers(a, b):"))

        assertEquals(listOf(listOf(TextSpan(4, 7))), spans.removed)
        assertEquals(listOf(listOf(TextSpan(4, 15))), spans.added)
    }

    @Test
    fun changedPhraseJoinsAcrossOneSpace() {
        val spans = WordDiff.block(listOf("the quick fox"), listOf("a slow fox"))

        assertEquals(listOf(listOf(TextSpan(0, 9))), spans.removed)
        assertEquals(listOf(listOf(TextSpan(0, 6))), spans.added)
    }

    @Test
    fun dissimilarLineInAnUnequalBlockStaysUnpaired() {
        val spans = WordDiff.block(listOf("    return a - b"), listOf("    return a + b", "xyz()"))

        assertEquals(listOf(listOf(TextSpan(13, 14))), spans.removed)
        assertEquals(listOf(listOf(TextSpan(13, 14)), emptyList()), spans.added)
    }

    @Test
    fun unequalBlockPairsBySimilarityNotPosition() {
        val spans = WordDiff.block(listOf("val total = price * count"), listOf("// recompute", "val total = price * amount"))

        assertEquals(listOf(listOf(TextSpan(20, 25))), spans.removed)
        assertEquals(listOf(emptyList(), listOf(TextSpan(20, 26))), spans.added)
    }
}
