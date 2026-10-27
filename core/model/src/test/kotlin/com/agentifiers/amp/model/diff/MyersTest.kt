package com.agentifiers.amp.model.diff

import com.agentifiers.amp.model.diff.Edit.Delete
import com.agentifiers.amp.model.diff.Edit.Insert
import com.agentifiers.amp.model.diff.Edit.Keep
import org.junit.Assert.assertEquals
import org.junit.Test

class MyersTest {
    private fun apply(a: List<Char>, b: List<Char>, edits: List<Edit>): List<Char> {
        val out = ArrayList<Char>()
        var i = 0
        var j = 0
        for (edit in edits) {
            when (edit) {
                Keep -> { out += a[i++]; j++ }
                Delete -> i++
                Insert -> out += b[j++]
            }
        }
        return out
    }

    @Test
    fun paperExampleHasEditDistanceFive() {
        val a = "ABCABBA".toList()
        val b = "CBABAC".toList()
        val edits = Myers.diff(a, b)

        assertEquals(5, edits.count { it != Keep })
        assertEquals("CBABAC".toList(), apply(a, b, edits))
    }

    @Test
    fun deletionsComeBeforeInsertionsInAChangedRegion() {
        assertEquals(listOf(Keep, Delete, Delete, Insert, Insert, Keep), Myers.diff("axyb".toList(), "auvb".toList()))
    }

    @Test
    fun emptySides() {
        assertEquals(listOf(Insert, Insert), Myers.diff(emptyList(), "ab".toList()))
        assertEquals(listOf(Delete), Myers.diff("a".toList(), emptyList()))
        assertEquals(emptyList<Edit>(), Myers.diff(emptyList<Char>(), emptyList()))
    }

    @Test
    fun customEqualityIsUsed() {
        assertEquals(listOf(Keep, Keep), Myers.diff(listOf("A", "b"), listOf("a", "B")) { x, y -> x.equals(y, ignoreCase = true) })
    }
}
