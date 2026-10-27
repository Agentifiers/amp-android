package com.agentifiers.amp.thread.transcript

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptEndTest {
    private class Item(override val index: Int, override val offset: Int, override val size: Int) : LazyListItemInfo {
        override val key: Any = index
    }

    /** A 1000px list with 8px of bottom padding, so rows end at 992 when it rests at the end. */
    private class Layout(override val totalItemsCount: Int, override val visibleItemsInfo: List<Item>) : LazyListLayoutInfo {
        override val viewportStartOffset = 0
        override val viewportEndOffset = 1000
        override val afterContentPadding = 8
    }

    @Test
    fun `the last row counts as at the end until its bottom sits more than the slack below the content area`() {
        fun lastRowEndingAt(end: Int) = Layout(40, listOf(Item(38, end - 700, 300), Item(39, end - 400, 400)))

        assertEquals(true, lastRowEndingAt(992).atEnd(slack = 48))
        assertEquals(true, lastRowEndingAt(1040).atEnd(slack = 48))
        assertEquals(false, lastRowEndingAt(1041).atEnd(slack = 48))
    }

    @Test
    fun `a list whose last row is not laid out is away from the end, and an empty list is at it`() {
        assertEquals(false, Layout(40, listOf(Item(30, 0, 500), Item(31, 500, 500))).atEnd(slack = 48))
        assertEquals(true, Layout(0, emptyList()).atEnd(slack = 48))
    }
}
