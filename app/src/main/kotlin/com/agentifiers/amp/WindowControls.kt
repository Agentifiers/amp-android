package com.agentifiers.amp

import com.agentifiers.amp.layout.Arrangement
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ServerEnvironment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The server a new sign-in goes to. Release builds never leave [Production]; debug builds switch it with `use-server`. */
@Singleton
class ServerChoice @Inject constructor() {
    private val mutable = MutableStateFlow(Production)
    val current: StateFlow<ServerEnvironment> = mutable.asStateFlow()

    fun use(server: ServerEnvironment) {
        mutable.value = server
    }

    companion object {
        val Production = ServerEnvironment(ampBaseUrl = "https://ampcode.com", actorsBaseUrl = "https://ampcode.com/actors")
    }
}

/** A pane asked for from outside the window. A non-null [account] means only a window showing that account opens it. */
data class Navigation(val dest: PaneDestination, val account: AccountId?)

/**
 * Destinations asked for from outside the window: app events, launcher surfaces, and notification taps. The
 * window applies the latest request once and clears it, so a request made before sign-in lands after it.
 */
@Singleton
class WindowNavigator @Inject constructor() {
    private val mutable = MutableStateFlow<Navigation?>(null)
    val pending: StateFlow<Navigation?> = mutable.asStateFlow()

    fun open(dest: PaneDestination, account: AccountId? = null) {
        mutable.value = Navigation(dest, account)
    }

    fun consumed(navigation: Navigation) {
        mutable.compareAndSet(navigation, null)
    }
}

/** Lays the window out as if it had [widthDp] and [creasesDp], so one emulator covers the layout matrix. */
data class GeometryOverride(val widthDp: Float, val creasesDp: List<Float>)

/** Debug app events that steer the layout: a geometry to lay out as, and an arrangement for a window to apply once. */
@Singleton
class LayoutOverrides @Inject constructor() {
    val geometry = MutableStateFlow<GeometryOverride?>(null)
    val arrangement = MutableStateFlow<Arrangement?>(null)
}
