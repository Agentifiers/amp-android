package com.agentifiers.amp.sync.api

import com.agentifiers.amp.sync.internal.DefaultEngines

/** The sockets a debug `drop-connection` app event can close. */
public enum class DebugSocket { Thread, User }

/**
 * Debug builds only: closes the given sockets as a network drop would, so reconnect and resume run.
 * Returns how many were dropped. Slice 1 opens no user actor socket, so [DebugSocket.User] drops none.
 */
public fun AmpEngines.dropConnections(target: DebugSocket): Int = when (target) {
    DebugSocket.Thread -> (this as DefaultEngines).dropThreadSockets()
    DebugSocket.User -> 0
}
