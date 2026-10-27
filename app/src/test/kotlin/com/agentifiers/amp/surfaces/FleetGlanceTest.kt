package com.agentifiers.amp.surfaces

import com.agentifiers.amp.model.Account
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AccountRoster
import com.agentifiers.amp.model.AuthHealth
import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.FleetView
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.model.WorkspaceRef
import com.agentifiers.amp.sync.api.Loadable
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

class FleetGlanceTest {
    private val server = ServerEnvironment("http://127.0.0.1:8417", "http://127.0.0.1:8417/actors")
    private val runner = ThreadId.parse("T-01a16702-3202-73fb-8f52-e4498ccd3721")!!
    private val gate = ThreadId.parse("T-01a16738-2ec2-7354-98e9-688bc413f079")!!
    private val probe = ThreadId.parse("T-01a166e9-3fa0-77ca-b925-383e88ba52ac")!!

    private fun roster(vararg accounts: Pair<String, AuthHealth>) = Loadable.Ready(
        AccountRoster(
            accounts.map { (id, auth) -> Account(AccountId(id), "fake-amp@example.com", server, WorkspaceRef.Personal, auth) }.let { persistentListOf(*it.toTypedArray()) },
            lastUsed = null,
        ),
        Instant.parse("2026-10-23T05:32:34Z"),
    )

    @Test
    fun `working and compacting threads are listed under Working, settled ones are left out`() {
        val fleet = FleetView.Ready(
            persistentListOf(
                ThreadSummary(runner, "android-spike runner", FleetStatus.Working, "devbox.local"),
                ThreadSummary(gate, null, FleetStatus.Compacting, null),
                ThreadSummary(probe, "android-design-probe", FleetStatus.Idle, null),
            ),
            refreshError = null,
        )
        assertEquals(
            FleetGlance.Ready(
                AccountId("A-9d446ef0208f9fa4"),
                needsYou = emptyList(),
                working = listOf(GlanceRow(runner, "android-spike runner", "working"), GlanceRow(gate, "Untitled thread", "compacting")),
                problem = null,
            ),
            fleetGlance(roster("A-9d446ef0208f9fa4" to AuthHealth.Ok), fleet),
        )
    }

    @Test
    fun `the glance follows the first account whose credential works, as the window does`() {
        val fleet = FleetView.Ready(persistentListOf(ThreadSummary(runner, "r", FleetStatus.Working, null)), refreshError = "Amp did not answer.")
        val glance = fleetGlance(roster("A-rejected" to AuthHealth.NeedsSignIn, "A-second" to AuthHealth.Refreshing), fleet)
        assertEquals(FleetGlance.Ready(AccountId("A-second"), emptyList(), listOf(GlanceRow(runner, "r", "working")), "Amp did not answer."), glance)
    }

    @Test
    fun `no usable account is signed out, and a roster or fleet still loading is starting`() {
        assertEquals(FleetGlance.SignedOut, fleetGlance(roster("A-rejected" to AuthHealth.NeedsSignIn), null))
        assertEquals(FleetGlance.Starting, fleetGlance(Loadable.Loading, null))
        assertEquals(FleetGlance.Starting, fleetGlance(roster("A-ok" to AuthHealth.Ok), FleetView.Loading))
    }

    @Test
    fun `a fleet that never loaded shows its failure with no rows`() {
        assertEquals(
            FleetGlance.Ready(AccountId("A-ok"), emptyList(), emptyList(), "Amp rejected the token."),
            fleetGlance(roster("A-ok" to AuthHealth.Ok), FleetView.Failed("Amp rejected the token.")),
        )
    }
}
