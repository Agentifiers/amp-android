package com.agentifiers.amp.model.diff

import com.agentifiers.amp.model.DiffLineKind
import com.agentifiers.amp.model.FileDiffView

/** One row of a split diff: indices into the line store, null where that side is blank. */
data class SplitRow(val old: Int?, val new: Int?)

/**
 * Split rows for store indices [from] until [until]. Context shows on both sides; inside a change block the i-th
 * removed line sits beside the i-th added line. A `NoNewline` marker stays on the side of the line it follows.
 */
fun splitRows(diff: FileDiffView.Ready, from: Int, until: Int): List<SplitRow> {
    val rows = ArrayList<SplitRow>()
    val removed = ArrayList<Int>()
    val added = ArrayList<Int>()
    fun flush() {
        for (k in 0 until maxOf(removed.size, added.size)) rows += SplitRow(removed.getOrNull(k), added.getOrNull(k))
        removed.clear()
        added.clear()
    }
    for (index in from until until) {
        when (diff.lines[index].kind) {
            DiffLineKind.Removed -> removed += index
            DiffLineKind.Added -> added += index
            DiffLineKind.Context -> {
                flush()
                rows += SplitRow(index, index)
            }
            DiffLineKind.NoNewline -> when (if (index > 0) diff.lines[index - 1].kind else DiffLineKind.Context) {
                DiffLineKind.Removed -> removed += index
                DiffLineKind.Added -> added += index
                else -> {
                    flush()
                    rows += SplitRow(index, index)
                }
            }
        }
    }
    flush()
    return rows
}
