package com.agentifiers.amp.thread.transcript

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Find in Thread: the query, which hit is current, and whether older pages are being searched. */
@Stable
class FindState {
    var open by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var current by mutableIntStateOf(0)
        private set
    var searchingOlder by mutableStateOf(false)

    /** The hit count when Search Older Messages started, so the search stops at the first new hit. */
    var olderBaseline by mutableIntStateOf(0)

    /** Bumped by every move, so the list scrolls even when the hit index repeats. */
    var moves by mutableIntStateOf(0)
        private set

    val highlight: String? get() = query.takeIf { open && it.isNotBlank() }

    fun show() {
        open = true
    }

    fun close() {
        open = false
        query = ""
        current = 0
        searchingOlder = false
    }

    /** A new query jumps to its first hit. */
    fun search(query: String) {
        this.query = query
        current = 0
        moves++
    }

    fun next(total: Int) = step(total, 1)

    fun previous(total: Int) = step(total, -1)

    private fun step(total: Int, by: Int) {
        if (total == 0) return
        current = Math.floorMod(current + by, total)
        moves++
    }
}
