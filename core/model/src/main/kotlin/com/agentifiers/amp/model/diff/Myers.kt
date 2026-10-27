package com.agentifiers.amp.model.diff

/** One step of an edit script that turns list `a` into list `b`. */
enum class Edit { Keep, Delete, Insert }

/**
 * Myers' O(ND) diff in linear space (middle-snake bisection). Within each changed region the script lists every
 * [Edit.Delete] before any [Edit.Insert], the order `git diff` prints them in.
 */
object Myers {
    fun <T> diff(a: List<T>, b: List<T>, equal: (T, T) -> Boolean = { x, y -> x == y }): List<Edit> {
        val keptA = BooleanArray(a.size)
        val keptB = BooleanArray(b.size)
        Bisection(a, b, equal, keptA, keptB).compare(0, a.size, 0, b.size)
        val script = ArrayList<Edit>(maxOf(a.size, b.size))
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            if (i < a.size && j < b.size && keptA[i] && keptB[j]) {
                script += Edit.Keep
                i++
                j++
                continue
            }
            while (i < a.size && !keptA[i]) {
                script += Edit.Delete
                i++
            }
            while (j < b.size && !keptB[j]) {
                script += Edit.Insert
                j++
            }
        }
        return script
    }

    private class Bisection<T>(
        val a: List<T>,
        val b: List<T>,
        val equal: (T, T) -> Boolean,
        val keptA: BooleanArray,
        val keptB: BooleanArray,
    ) {
        fun compare(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int) {
            var aLo = aStart
            var bLo = bStart
            var aHi = aEnd
            var bHi = bEnd
            while (aLo < aHi && bLo < bHi && equal(a[aLo], b[bLo])) {
                keptA[aLo++] = true
                keptB[bLo++] = true
            }
            while (aLo < aHi && bLo < bHi && equal(a[aHi - 1], b[bHi - 1])) {
                keptA[--aHi] = true
                keptB[--bHi] = true
            }
            if (aLo == aHi || bLo == bHi) return
            val split = middleSnake(aLo, aHi, bLo, bHi) ?: return
            compare(aLo, aLo + split.x, bLo, bLo + split.y)
            compare(aLo + split.x, aHi, bLo + split.y, bHi)
        }

        private class Point(val x: Int, val y: Int)

        /** Returns the split point relative to (aLo, bLo), or null when the ranges share nothing. */
        private fun middleSnake(aLo: Int, aHi: Int, bLo: Int, bHi: Int): Point? {
            val n = aHi - aLo
            val m = bHi - bLo
            val maxD = (n + m + 1) / 2
            val offset = maxD
            val size = 2 * maxD + 2
            val forward = IntArray(size) { -1 }
            val backward = IntArray(size) { -1 }
            forward[offset + 1] = 0
            backward[offset + 1] = 0
            val delta = n - m
            val oddDelta = delta % 2 != 0
            var forwardStart = 0
            var forwardEnd = 0
            var backwardStart = 0
            var backwardEnd = 0
            for (d in 0 until maxD) {
                var k1 = -d + forwardStart
                while (k1 <= d - forwardEnd) {
                    val k1Offset = offset + k1
                    var x1 = if (k1 == -d || (k1 != d && forward[k1Offset - 1] < forward[k1Offset + 1])) {
                        forward[k1Offset + 1]
                    } else {
                        forward[k1Offset - 1] + 1
                    }
                    var y1 = x1 - k1
                    while (x1 < n && y1 < m && equal(a[aLo + x1], b[bLo + y1])) {
                        x1++
                        y1++
                    }
                    forward[k1Offset] = x1
                    if (x1 > n) {
                        forwardEnd += 2
                    } else if (y1 > m) {
                        forwardStart += 2
                    } else if (oddDelta) {
                        val k2Offset = offset + delta - k1
                        if (k2Offset in 0 until size && backward[k2Offset] != -1 && x1 >= n - backward[k2Offset]) {
                            return Point(x1, y1)
                        }
                    }
                    k1 += 2
                }
                var k2 = -d + backwardStart
                while (k2 <= d - backwardEnd) {
                    val k2Offset = offset + k2
                    var x2 = if (k2 == -d || (k2 != d && backward[k2Offset - 1] < backward[k2Offset + 1])) {
                        backward[k2Offset + 1]
                    } else {
                        backward[k2Offset - 1] + 1
                    }
                    var y2 = x2 - k2
                    while (x2 < n && y2 < m && equal(a[aHi - 1 - x2], b[bHi - 1 - y2])) {
                        x2++
                        y2++
                    }
                    backward[k2Offset] = x2
                    if (x2 > n) {
                        backwardEnd += 2
                    } else if (y2 > m) {
                        backwardStart += 2
                    } else if (!oddDelta) {
                        val k1Offset = offset + delta - k2
                        if (k1Offset in 0 until size && forward[k1Offset] != -1) {
                            val x1 = forward[k1Offset]
                            val y1 = offset + x1 - k1Offset
                            if (x1 >= n - x2) return Point(x1, y1)
                        }
                    }
                    k2 += 2
                }
            }
            return null
        }
    }
}
