package com.agentifiers.amp.store

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PreferencesSerializerTest {

    private suspend fun <T> ProtoFileSerializer<T>.roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream().also { writeTo(value, it) }.toByteArray()
        return readFrom(ByteArrayInputStream(bytes))
    }

    @Test
    fun rosterSurvivesTheFile() = runTest {
        val roster = RosterProto(
            accounts = listOf(
                AccountEntry("acct-1", "ada@example.com", "https://ampcode.com", """{"id":"ws-1"}""", "amp-acct-1"),
                AccountEntry("acct-2", "grace@example.com", "https://amp.internal.example", "", "amp-acct-2"),
            ),
            lastUsedId = "acct-2",
        )

        assertEquals(
            RosterProto(
                accounts = listOf(
                    AccountEntry("acct-1", "ada@example.com", "https://ampcode.com", """{"id":"ws-1"}""", "amp-acct-1"),
                    AccountEntry("acct-2", "grace@example.com", "https://amp.internal.example", "", "amp-acct-2"),
                ),
                lastUsedId = "acct-2",
            ),
            rosterSerializer.roundTrip(roster),
        )
    }

    @Test
    fun rosterWithNoLastUsedAccountReadsBackWithout() = runTest {
        val roster = RosterProto(listOf(AccountEntry("acct-1", "ada@example.com", "https://ampcode.com", "{}", "amp-acct-1")), null)

        assertEquals(null, rosterSerializer.roundTrip(roster).lastUsedId)
    }

    @Test
    fun workspacesKeepEachWindowsAccountAndDividers() = runTest {
        val workspaces = WorkspacesProto(
            listOf(
                WorkspaceEntry("task-7:inner", "acct-1", "TwoUp", """[["thread:T-1"],["changes:T-1"]]""", listOf(412.5f, 0f)),
                WorkspaceEntry("task-9:cover", "acct-2", "Single", """[["fleet"]]""", emptyList()),
            ),
        )

        assertEquals(
            WorkspacesProto(
                listOf(
                    WorkspaceEntry("task-7:inner", "acct-1", "TwoUp", """[["thread:T-1"],["changes:T-1"]]""", listOf(412.5f, 0f)),
                    WorkspaceEntry("task-9:cover", "acct-2", "Single", """[["fleet"]]""", emptyList()),
                ),
            ),
            workspacesSerializer.roundTrip(workspaces),
        )
    }

    @Test
    fun anEmptyFileIsTheEmptyRoster() = runTest {
        assertEquals(RosterProto(emptyList(), null), rosterSerializer.readFrom(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun garbageBytesAreCorruptionSoDataStoreCanReplaceTheFile() {
        val garbage = byteArrayOf(0x0A, 0x7F, 0x01)

        assertThrows(CorruptionException::class.java) {
            runBlocking { rosterSerializer.readFrom(ByteArrayInputStream(garbage)) }
        }
    }
}
