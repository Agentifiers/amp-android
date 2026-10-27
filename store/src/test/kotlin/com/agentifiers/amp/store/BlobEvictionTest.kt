package com.agentifiers.amp.store

import org.junit.Assert.assertEquals
import org.junit.Test

class BlobEvictionTest {

    @Test
    fun leastRecentlyUsedBlobsGoFirstUntilTheRestFit() {
        val usages = listOf(
            BlobUsage("old", size = 40, lastUsedMs = 1_000),
            BlobUsage("newest", size = 50, lastUsedMs = 3_000),
            BlobUsage("middle", size = 30, lastUsedMs = 2_000),
            BlobUsage("oldest", size = 5, lastUsedMs = 500),
        )

        assertEquals(listOf("old", "oldest"), blobsToEvict(usages, budgetBytes = 100))
    }

    @Test
    fun nothingIsEvictedWhileTheCacheFits() {
        val usages = listOf(BlobUsage("a", 60, 1_000), BlobUsage("b", 40, 2_000))

        assertEquals(listOf<String>(), blobsToEvict(usages, budgetBytes = 100))
        assertEquals(listOf("a"), blobsToEvict(usages, budgetBytes = 99))
    }

    @Test
    fun tiesKeepTheLowestShaFirst() {
        val usages = listOf(BlobUsage("bbb", 10, 1_000), BlobUsage("aaa", 10, 1_000), BlobUsage("ccc", 10, 1_000))

        assertEquals(listOf("bbb", "ccc"), blobsToEvict(usages, budgetBytes = 10))
    }
}
