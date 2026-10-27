package com.agentifiers.amp.surfaces

import android.content.Context
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.glance.appwidget.updateAll
import com.agentifiers.amp.R
import com.agentifiers.amp.sync.api.AmpEngines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the home-screen surfaces in step with the fleet for as long as the process lives: the widget re-renders
 * and the launcher's thread shortcuts are replaced whenever [glance] changes. Replacing the whole dynamic set each
 * time means a signed-out account's shortcuts disappear with no separate teardown.
 */
class FleetSurfaces(private val context: Context, private val engines: AmpEngines) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutable = MutableStateFlow<FleetGlance>(FleetGlance.Starting)
    val glance: StateFlow<FleetGlance> = mutable.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        scope.launch {
            engines.roster
                .flatMapLatest { roster ->
                    val account = glanceAccount(roster)
                    if (account == null) {
                        flowOf(fleetGlance(roster, null))
                    } else {
                        val fleet = engines.scope(account).fleet
                        fleet.refresh()
                        fleet.threads.map { fleetGlance(roster, it) }
                    }
                }
                .distinctUntilChanged()
                .collect { next ->
                    mutable.value = next
                    publish(next)
                }
        }
    }

    /** The widget's Refresh button and its periodic update. A no-op while no account is signed in. */
    fun refresh() {
        val account = glanceAccount(engines.roster.value) ?: return
        engines.scope(account).fleet.refresh()
    }

    private suspend fun publish(glance: FleetGlance) {
        FleetWidget().updateAll(context)
        ShortcutManagerCompat.setDynamicShortcuts(context, threadShortcuts(glance))
        Log.i(TAG, describe(glance))
    }

    private fun threadShortcuts(glance: FleetGlance): List<ShortcutInfoCompat> {
        if (glance !is FleetGlance.Ready) return emptyList()
        val room = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - STATIC_SHORTCUTS).coerceAtLeast(0)
        return (glance.needsYou + glance.working).take(room).mapIndexed { rank, row ->
            ShortcutInfoCompat.Builder(context, "${glance.account.raw}/${row.thread.raw}")
                .setShortLabel(row.title.take(SHORT_LABEL))
                .setLongLabel("${row.title} · ${row.status}")
                .setIcon(IconCompat.createWithResource(context, R.drawable.surface_thread))
                .setIntent(SurfaceAction.OpenThread(row.thread).intent(context))
                .setRank(rank)
                .build()
        }
    }

    private fun describe(glance: FleetGlance): String = when (glance) {
        FleetGlance.Starting -> "glance starting"
        FleetGlance.SignedOut -> "glance signed out"
        is FleetGlance.Ready -> "glance needs-you ${glance.needsYou.size} working ${glance.working.size}" +
            (glance.problem?.let { " problem $it" } ?: "")
    }

    private companion object {
        const val TAG = "AmpSurface"

        /** New Thread and Call Puck in res/xml/shortcuts.xml. */
        const val STATIC_SHORTCUTS = 2
        const val SHORT_LABEL = 25
    }
}
