package com.agentifiers.amp.model.diff

import com.agentifiers.amp.model.TextSpan
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/** Changed character ranges for each line of one change block, in the block's order. */
data class BlockSpans(val removed: List<PersistentList<TextSpan>>, val added: List<PersistentList<TextSpan>>)

/** Word-level highlights inside a change block: pair lines, diff their tokens, and join changed words into spans. */
object WordDiff {
    private const val MIN_MATCH = 0.3

    /** Unequal blocks larger than this pair by position instead of comparing every removed line with every added one. */
    private const val MAX_SIMILARITY_COMPARISONS = 4_096

    fun block(removed: List<String>, added: List<String>): BlockSpans {
        val removedSpans = MutableList<PersistentList<TextSpan>>(removed.size) { persistentListOf() }
        val addedSpans = MutableList<PersistentList<TextSpan>>(added.size) { persistentListOf() }
        val removedTokens = removed.map(::tokens)
        val addedTokens = added.map(::tokens)
        for ((r, a) in pairs(removedTokens, addedTokens)) {
            if (removed[r].length > HIGHLIGHT_LINE_LIMIT || added[a].length > HIGHLIGHT_LINE_LIMIT) continue
            val edits = Myers.diff(removedTokens[r], addedTokens[a]) { x, y -> x.text == y.text }
            val (oldChanged, newChanged) = changedTokens(edits, removedTokens[r].size, addedTokens[a].size)
            removedSpans[r] = spans(removedTokens[r], oldChanged)
            addedSpans[a] = spans(addedTokens[a], newChanged)
        }
        return BlockSpans(removedSpans, addedSpans)
    }

    private class Token(val start: Int, val end: Int, val text: String) {
        val isWhitespace: Boolean get() = text[0].isWhitespace()
    }

    private fun tokens(line: String): List<Token> {
        if (line.length > HIGHLIGHT_LINE_LIMIT) return emptyList()
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < line.length) {
            val start = i
            val c = line[i]
            when {
                c.isWordPart() -> while (i < line.length && line[i].isWordPart()) i++
                c.isWhitespace() -> while (i < line.length && line[i].isWhitespace()) i++
                else -> i++
            }
            tokens += Token(start, i, line.substring(start, i))
        }
        return tokens
    }

    private fun Char.isWordPart() = isLetterOrDigit() || this == '_'

    private fun pairs(removed: List<List<Token>>, added: List<List<Token>>): List<Pair<Int, Int>> {
        if (removed.size == added.size) return removed.indices.map { it to it }
        if (removed.size * added.size > MAX_SIMILARITY_COMPARISONS) {
            return (0 until minOf(removed.size, added.size)).map { it to it }.filter { (r, a) -> similarity(removed[r], added[a]) != null }
        }
        val candidates = ArrayList<Triple<Int, Int, Double>>()
        for (r in removed.indices) for (a in added.indices) {
            similarity(removed[r], added[a])?.let { candidates += Triple(r, a, it) }
        }
        candidates.sortWith(compareByDescending<Triple<Int, Int, Double>> { it.third }.thenBy { it.first }.thenBy { it.second })
        val chosen = ArrayList<Pair<Int, Int>>()
        for ((r, a) in candidates) {
            val compatible = chosen.all { (cr, ca) -> (r < cr && a < ca) || (r > cr && a > ca) }
            if (compatible) chosen += r to a
        }
        return chosen.sortedBy { it.first }
    }

    /** Dice similarity of the two token lists, or null when either line has under 30% of its tokens matched. */
    private fun similarity(old: List<Token>, new: List<Token>): Double? {
        if (old.isEmpty() || new.isEmpty()) return null
        val matched = Myers.diff(old, new) { x, y -> x.text == y.text }.count { it == Edit.Keep }
        if (matched < MIN_MATCH * old.size || matched < MIN_MATCH * new.size) return null
        return 2.0 * matched / (old.size + new.size)
    }

    private fun changedTokens(edits: List<Edit>, oldSize: Int, newSize: Int): Pair<BooleanArray, BooleanArray> {
        val oldChanged = BooleanArray(oldSize)
        val newChanged = BooleanArray(newSize)
        var i = 0
        var j = 0
        for (edit in edits) {
            when (edit) {
                Edit.Keep -> { i++; j++ }
                Edit.Delete -> oldChanged[i++] = true
                Edit.Insert -> newChanged[j++] = true
            }
        }
        return oldChanged to newChanged
    }

    private fun spans(tokens: List<Token>, changed: BooleanArray): PersistentList<TextSpan> {
        val spans = ArrayList<TextSpan>()
        var k = 0
        while (k < tokens.size) {
            if (!changed[k]) {
                k++
                continue
            }
            val start = tokens[k].start
            var last = k
            var next = k + 1
            while (next < tokens.size) {
                if (changed[next]) {
                    last = next
                    next++
                } else if (tokens[next].isWhitespace && next + 1 < tokens.size && changed[next + 1]) {
                    last = next + 1
                    next += 2
                } else {
                    break
                }
            }
            spans += TextSpan(start, tokens[last].end)
            k = next
        }
        return spans.toPersistentList()
    }
}
