package com.agentifiers.amp.files

import org.junit.Assert.assertEquals
import org.junit.Test

class TreeTitleTest {
    @Test
    fun anOpenedFolderShowsItsNameAndTheExecutorRootShowsNone() {
        assertEquals("amp-scratch", treeTitle("/private/tmp/amp-scratch"))
        assertEquals("src", treeTitle("app/src/"))
        assertEquals(null, treeTitle("."))
        assertEquals(null, treeTitle("/"))
    }
}
