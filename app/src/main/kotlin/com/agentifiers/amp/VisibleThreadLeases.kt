package com.agentifiers.amp

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.LeaseIssuer

/**
 * One viewer lease per thread a visible slot shows, bound to the window's Lifecycle rather than the
 * composition: held while STARTED, all released on ON_STOP, re-taken on ON_START.
 */
class VisibleThreadLeases(
    private val windowKey: String,
    private val lifecycle: Lifecycle,
    private val issuer: LeaseIssuer,
) : DefaultLifecycleObserver {
    private var scope: AccountScope? = null
    private var wanted: Set<ThreadId> = emptySet()
    private var heldScope: AccountScope? = null
    private val held = HashMap<ThreadId, AutoCloseable>()

    init {
        lifecycle.addObserver(this)
    }

    fun show(scope: AccountScope, threads: Set<ThreadId>) {
        this.scope = scope
        wanted = threads
        reconcile()
    }

    override fun onStart(owner: LifecycleOwner) = reconcile()

    override fun onStop(owner: LifecycleOwner) = reconcile()

    private fun reconcile() {
        val current = scope
        val started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (heldScope !== current) releaseAll()
        val target = if (started && current != null) wanted else emptySet()
        (held.keys - target).forEach { held.remove(it)?.close() }
        if (current == null) return
        heldScope = current
        (target - held.keys).forEach { held[it] = current.thread(it).attach(issuer.issue(windowKey)) }
    }

    private fun releaseAll() {
        held.values.forEach(AutoCloseable::close)
        held.clear()
        heldScope = null
    }
}
