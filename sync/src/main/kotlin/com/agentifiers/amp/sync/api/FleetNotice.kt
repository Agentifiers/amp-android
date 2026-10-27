package com.agentifiers.amp.sync.api

import com.agentifiers.amp.model.ThreadId
import kotlinx.collections.immutable.PersistentList

/** One-shot results of organize writes that the Sidebar announces in a snackbar. */
public sealed interface FleetNotice {
    /** The threads just archived, so the snackbar can offer Undo. */
    public data class Archived(val ids: PersistentList<ThreadId>) : FleetNotice

    /** The server refused [action]; the row is back to the server's state. */
    public data class Failed(val thread: ThreadId, val action: String, val reason: String) : FleetNotice
}
