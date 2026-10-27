package com.agentifiers.amp.layout

import kotlin.math.abs

/**
 * Where a transcript [measureDp] wide starts in this column, as an offset from the column's start, or null when the
 * column is no wider than the measure. It starts on a crease (the column's own start counts when it sits on one), the
 * one nearest the centered position that still fits the measure, so the text lines up with a fold; with no crease to
 * use it centers. On the TriFold that is the crease at 335 in both Read and Focus, so switching between them leaves the
 * transcript where it was.
 */
fun Column.readingOffsetDp(creasesDp: List<Float>, measureDp: Float): Float? {
    if (widthDp <= measureDp) return null
    val centered = startDp + (widthDp - measureDp) / 2
    val edge = creasesDp.filter { it >= startDp - 1f && it + measureDp <= endDp }.minByOrNull { abs(it - centered) }
    return ((edge ?: centered) - startDp).coerceAtLeast(0f)
}
