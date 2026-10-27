package com.agentifiers.amp.surfaces

import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AccountRoster
import com.agentifiers.amp.model.AuthHealth
import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.FleetView
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.sync.api.Loadable

/** What the home-screen widget and the launcher's thread shortcuts show: the threads that want attention now. */
sealed interface FleetGlance {
    data object Starting : FleetGlance
    data object SignedOut : FleetGlance

    data class Ready(
        val account: AccountId,
        val needsYou: List<GlanceRow>,
        val working: List<GlanceRow>,
        /** The last refresh failed; the rows are from the refresh before it. */
        val problem: String?,
    ) : FleetGlance
}

data class GlanceRow(val thread: ThreadId, val title: String, val status: String)

/** The account a window would open: AmpRoot shows the first account whose credential still works. */
fun glanceAccount(roster: Loadable<AccountRoster>): AccountId? =
    (roster as? Loadable.Ready)?.value?.accounts?.firstOrNull { it.auth == AuthHealth.Ok || it.auth == AuthHealth.Refreshing }?.id

fun fleetGlance(roster: Loadable<AccountRoster>, fleet: FleetView?): FleetGlance {
    if (roster !is Loadable.Ready) return FleetGlance.Starting
    val account = glanceAccount(roster) ?: return FleetGlance.SignedOut
    return when (fleet) {
        null, FleetView.Loading -> FleetGlance.Starting
        is FleetView.Failed -> FleetGlance.Ready(account, emptyList(), emptyList(), fleet.reason)
        is FleetView.Ready -> {
            val lanes = fleet.threads.groupBy { it.status.lane() }
            FleetGlance.Ready(
                account = account,
                needsYou = lanes[Lane.NeedsYou].orEmpty().map { it.row() },
                working = lanes[Lane.Working].orEmpty().map { it.row() },
                problem = fleet.refreshError,
            )
        }
    }
}

private enum class Lane { NeedsYou, Working }

private fun FleetStatus.lane(): Lane? = when (this) {
    FleetStatus.Working, FleetStatus.Compacting -> Lane.Working
    is FleetStatus.Waiting -> Lane.NeedsYou
    FleetStatus.Done, FleetStatus.Idle, FleetStatus.Error, FleetStatus.Inactive, is FleetStatus.Reported -> null
}

private fun ThreadSummary.row() = GlanceRow(id, title?.takeIf { it.isNotBlank() } ?: "Untitled thread", status.word)
