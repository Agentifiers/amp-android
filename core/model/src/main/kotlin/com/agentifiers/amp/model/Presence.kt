package com.agentifiers.amp.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.time.Instant

/**
 * Other people in the thread, from the socket's `observers` and `prompt_drafts` snapshots. The signed-in user is
 * left out, so an empty [viewers] means the user is alone. Volatile: it clears when the socket ends.
 */
data class Presence(val viewers: PersistentList<Viewer>, val typing: PersistentList<Viewer>) {
    companion object {
        val Alone = Presence(persistentListOf(), persistentListOf())
    }
}

/** `observers[]{userId, name}`. [name] falls back to the user id, which the S10 capture shows for the owner. */
data class Viewer(val user: UserId, val name: String)

/** `thread_open_state{openExpiresAt}`: whether teammates may prompt this thread (multiplayer). */
sealed interface Multiplayer {
    /** No `thread_open_state` has arrived on this socket. */
    data object Unknown : Multiplayer
    data object Off : Multiplayer
    data class Open(val until: Instant) : Multiplayer
}
