package com.agentifiers.amp.ui

import com.agentifiers.amp.model.Account
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AccountRoster
import com.agentifiers.amp.model.AuthHealth
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.model.WorkspaceRef
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowAccountTest {
    private fun account(id: String) =
        Account(AccountId(id), "$id@example.com", ServerEnvironment.Production, WorkspaceRef.Personal, AuthHealth.Ok)

    private val ada = account("A-ada")
    private val grace = account("A-grace")
    private val roster = AccountRoster(persistentListOf(ada, grace), lastUsed = AccountId("A-grace"))

    @Test
    fun aWindowKeepsItsOwnAccount() {
        assertEquals("A-ada", roster.accountFor(AccountId("A-ada"))?.id?.raw)
    }

    @Test
    fun aNewWindowOpensTheLastUsedAccount() {
        assertEquals("A-grace", roster.accountFor(null)?.id?.raw)
    }

    @Test
    fun aWindowWhoseAccountSignedOutFallsToTheLastUsed() {
        assertEquals("A-grace", roster.accountFor(AccountId("A-gone"))?.id?.raw)
    }

    @Test
    fun withNoLastUsedTheFirstAccountWins() {
        assertEquals("A-ada", roster.copy(lastUsed = AccountId("A-gone")).accountFor(AccountId("A-gone"))?.id?.raw)
    }

    @Test
    fun anEmptyRosterLeavesTheWindowWithoutAnAccount() {
        assertEquals(null, AccountRoster(persistentListOf(), null).accountFor(AccountId("A-ada")))
    }
}
