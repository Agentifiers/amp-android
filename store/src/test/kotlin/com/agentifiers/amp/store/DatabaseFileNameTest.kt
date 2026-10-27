package com.agentifiers.amp.store

import com.agentifiers.amp.model.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DatabaseFileNameTest {

    @Test
    fun eachAccountGetsItsOwnFile() {
        assertEquals("amp-0f8c2b1e-acct_2.db", databaseFileName(AccountId("0f8c2b1e-acct_2")))
    }

    @Test
    fun anIdThatNamesAPathIsRejected() {
        for (raw in listOf("../other", "a/b", "x\\y", ".hidden", "")) {
            assertThrows(raw, IllegalArgumentException::class.java) { databaseFileName(AccountId(raw)) }
        }
    }
}
