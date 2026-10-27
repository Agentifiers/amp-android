package com.agentifiers.amp.model.diff

import com.agentifiers.amp.model.DiffLine
import com.agentifiers.amp.model.DiffLineKind
import com.agentifiers.amp.model.FileDiffView
import com.agentifiers.amp.model.Hunk
import com.agentifiers.amp.model.Language
import com.agentifiers.amp.model.SyntaxSpan
import com.agentifiers.amp.model.TextSpan
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Builds [FileDiffView]s from file contents or from a git patch, and renders them back as unified diff text. */
object DiffEngine {
    const val TOO_LARGE = "This file is too large to diff on this device."
    const val UNREADABLE_PATCH = "The diff could not be read."
    private const val NO_NEWLINE = "\\ No newline at end of file"

    private const val MAX_SIDE_BYTES = 5L * 1024 * 1024
    private const val MAX_SIDE_LINES = 50_000
    private const val MAX_HIGHLIGHT_SIDE_BYTES = 1L * 1024 * 1024
    private const val MAX_HIGHLIGHT_SIDE_LINES = 20_000
    private const val CONTEXT = 3
    private const val MAX_MERGE_GAP = 2 * CONTEXT

    private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@ ?(.*)$""")
    private val PATCH_HEADERS = listOf(
        "diff ", "index ", "new file mode", "deleted file mode", "old mode", "new mode", "similarity index",
        "dissimilarity index", "rename from", "rename to", "copy from", "copy to", "--- ", "+++ ",
    )

    /** `old == null` means the file was added; `new == null` means it was deleted. */
    fun fromContents(path: String, old: String?, new: String?): FileDiffView {
        val before = old?.let(::sideOf) ?: emptyList()
        val after = new?.let(::sideOf) ?: emptyList()
        if (old != null && tooLarge(old, before) || new != null && tooLarge(new, after)) return FileDiffView.TooLarge(TOO_LARGE)
        val store = ArrayList<StoreLine>(maxOf(before.size, after.size) + 2)
        var i = 0
        var j = 0
        for (edit in Myers.diff(before, after)) {
            val source = if (edit == Edit.Insert) after[j] else before[i]
            store += when (edit) {
                Edit.Keep -> StoreLine(DiffLineKind.Context, i + 1, j + 1, source.text)
                Edit.Delete -> StoreLine(DiffLineKind.Removed, i + 1, null, source.text)
                Edit.Insert -> StoreLine(DiffLineKind.Added, null, j + 1, source.text)
            }
            if (edit != Edit.Insert) i++
            if (edit != Edit.Delete) j++
            if (!source.terminated) store += StoreLine(DiffLineKind.NoNewline, null, null, NO_NEWLINE)
        }
        return ready(path, store, hunksOf(store), expandable = true)
    }

    fun fromPatch(path: String, patch: String): FileDiffView {
        val rows = patch.split('\n').let { if (patch.endsWith('\n')) it.dropLast(1) else it }
        val store = ArrayList<StoreLine>()
        val hunks = ArrayList<Hunk>()
        var open: Hunk? = null
        var oldLeft = 0
        var newLeft = 0
        var oldNo = 0
        var newNo = 0
        fun close() {
            open?.let { hunks += it.copy(lineCount = store.size - it.firstLine) }
            open = null
        }
        for (row in rows) {
            if (oldLeft > 0 || newLeft > 0) {
                store += when (row.firstOrNull()) {
                    ' ', null -> StoreLine(DiffLineKind.Context, ++oldNo, ++newNo, row.drop(1)).also { oldLeft--; newLeft-- }
                    '-' -> StoreLine(DiffLineKind.Removed, ++oldNo, null, row.substring(1)).also { oldLeft-- }
                    '+' -> StoreLine(DiffLineKind.Added, null, ++newNo, row.substring(1)).also { newLeft-- }
                    '\\' -> StoreLine(DiffLineKind.NoNewline, null, null, row)
                    else -> return FileDiffView.Failed(UNREADABLE_PATCH)
                }
                if (oldLeft < 0 || newLeft < 0) return FileDiffView.Failed(UNREADABLE_PATCH)
                continue
            }
            when {
                row.startsWith("@@") -> {
                    close()
                    val match = HUNK_HEADER.matchEntire(row) ?: return FileDiffView.Failed(UNREADABLE_PATCH)
                    val (oldStart, oldCount, newStart, newCount) =
                        (1..4).map { match.groupValues[it].ifEmpty { "1" }.toIntOrNull() ?: return FileDiffView.Failed(UNREADABLE_PATCH) }
                    oldLeft = oldCount
                    newLeft = newCount
                    oldNo = oldStart - 1
                    newNo = newStart - 1
                    open = Hunk(oldStart, oldCount, newStart, newCount, match.groupValues[5], store.size, 0)
                }
                row.startsWith("\\") && open != null -> store += StoreLine(DiffLineKind.NoNewline, null, null, row)
                row.startsWith("Binary files ") -> return FileDiffView.Binary(null, null)
                PATCH_HEADERS.any { row.startsWith(it) } -> Unit
                else -> return FileDiffView.Failed(UNREADABLE_PATCH)
            }
        }
        if (oldLeft > 0 || newLeft > 0) return FileDiffView.Failed(UNREADABLE_PATCH)
        close()
        return ready(path, store, hunks, expandable = false)
    }

    /** Strict UTF-8, or null when the bytes are binary: malformed UTF-8 or any NUL byte. */
    fun decode(bytes: ByteArray): String? {
        if (bytes.any { it == 0.toByte() }) return null
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** Every hunk as git prints it, starting at its `@@` line. */
    fun toUnified(diff: FileDiffView.Ready): String = buildString {
        for (hunk in diff.hunks) appendHunk(diff, hunk)
    }

    fun hunkText(diff: FileDiffView.Ready, hunkIndex: Int): String = buildString { appendHunk(diff, diff.hunks[hunkIndex]) }

    private fun StringBuilder.appendHunk(diff: FileDiffView.Ready, hunk: Hunk) {
        append("@@ -").append(range(hunk.oldStart, hunk.oldCount)).append(" +").append(range(hunk.newStart, hunk.newCount)).append(" @@")
        if (hunk.header.isNotEmpty()) append(' ').append(hunk.header)
        append('\n')
        for (index in hunk.firstLine until hunk.firstLine + hunk.lineCount) {
            val line = diff.lines[index]
            if (line.kind != DiffLineKind.NoNewline) append(line.kind.marker)
            append(line.text).append('\n')
        }
    }

    /** Git leaves out the count when it is 1. */
    private fun range(start: Int, count: Int): String = if (count == 1) "$start" else "$start,$count"

    private data class SourceLine(val text: String, val terminated: Boolean)

    private class StoreLine(val kind: DiffLineKind, val oldNo: Int?, val newNo: Int?, val text: String)

    private fun sideOf(content: String): List<SourceLine> {
        if (content.isEmpty()) return emptyList()
        val text = if ('\r' in content) content.replace("\r\n", "\n").replace('\r', '\n') else content
        val parts = text.split('\n')
        val terminated = text.endsWith('\n')
        val count = if (terminated) parts.size - 1 else parts.size
        return List(count) { SourceLine(parts[it], terminated || it < count - 1) }
    }

    private fun tooLarge(content: String, lines: List<SourceLine>): Boolean =
        lines.size > MAX_SIDE_LINES || utf8Length(content) > MAX_SIDE_BYTES

    private fun utf8Length(text: CharSequence): Long {
        var bytes = 0L
        for (c in text) {
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isSurrogate(c) -> 2
                else -> 3
            }
        }
        return bytes
    }

    /** Groups changes no more than [MAX_MERGE_GAP] unchanged lines apart, with [CONTEXT] lines around each group. */
    private fun hunksOf(store: List<StoreLine>): List<Hunk> {
        val hunks = ArrayList<Hunk>()
        var first = -1
        var last = -1
        var gap = 0
        for ((index, line) in store.withIndex()) {
            when (line.kind) {
                DiffLineKind.Added, DiffLineKind.Removed -> {
                    if (first >= 0 && gap > MAX_MERGE_GAP) {
                        hunks += hunk(store, first, last)
                        first = -1
                    }
                    if (first < 0) first = index
                    last = index
                    gap = 0
                }
                DiffLineKind.Context -> gap++
                DiffLineKind.NoNewline -> Unit
            }
        }
        if (first >= 0) hunks += hunk(store, first, last)
        return hunks
    }

    private fun hunk(store: List<StoreLine>, firstChange: Int, lastChange: Int): Hunk {
        var start = firstChange
        while (start > 0 && firstChange - start < CONTEXT && store[start - 1].kind == DiffLineKind.Context) start--
        var end = lastChange + 1
        var trailing = 0
        while (end < store.size) {
            val kind = store[end].kind
            if (kind == DiffLineKind.NoNewline || (kind == DiffLineKind.Context && trailing++ < CONTEXT)) end++ else break
        }
        val lines = store.subList(start, end)
        val oldCount = lines.count { it.oldNo != null }
        val newCount = lines.count { it.newNo != null }
        val oldBefore = store.subList(0, start).lastOrNull { it.oldNo != null }?.oldNo ?: 0
        val newBefore = store.subList(0, start).lastOrNull { it.newNo != null }?.newNo ?: 0
        return Hunk(
            oldStart = if (oldCount == 0) oldBefore else oldBefore + 1,
            oldCount = oldCount,
            newStart = if (newCount == 0) newBefore else newBefore + 1,
            newCount = newCount,
            header = "",
            firstLine = start,
            lineCount = end - start,
        )
    }

    private fun ready(path: String, store: List<StoreLine>, hunks: List<Hunk>, expandable: Boolean): FileDiffView.Ready {
        val language = languageOf(path)
        val tokens = tokens(store, language)
        val intraline = intraline(store)
        val lines = store.mapIndexed { index, line ->
            DiffLine(line.kind, line.oldNo, line.newNo, line.text, intraline[index] ?: persistentListOf(), tokens[index] ?: persistentListOf())
        }
        return FileDiffView.Ready(path, hunks.toPersistentList(), lines.toPersistentList(), expandable, language)
    }

    /**
     * Tokenizes each side's lines in store order, so a side whose store holds the whole file is lexed as a whole.
     * Context lines take the new side's tokens.
     */
    private fun tokens(store: List<StoreLine>, language: Language): Array<PersistentList<SyntaxSpan>?> {
        val result = arrayOfNulls<PersistentList<SyntaxSpan>>(store.size)
        if (language == Language.Plain) return result
        for (side in listOf(DiffLineKind.Removed, DiffLineKind.Added)) {
            val indices = store.indices.filter {
                val kind = store[it].kind
                kind == side || kind == DiffLineKind.Context
            }
            val texts = indices.map { store[it].text }
            if (texts.size > MAX_HIGHLIGHT_SIDE_LINES || texts.sumOf { utf8Length(it) + 1 } > MAX_HIGHLIGHT_SIDE_BYTES) continue
            Syntax.tokenize(language, texts).forEachIndexed { k, spans -> result[indices[k]] = spans }
        }
        return result
    }

    private fun intraline(store: List<StoreLine>): Array<PersistentList<TextSpan>?> {
        val result = arrayOfNulls<PersistentList<TextSpan>>(store.size)
        var index = 0
        while (index < store.size) {
            if (store[index].kind == DiffLineKind.Context || store[index].kind == DiffLineKind.NoNewline) {
                index++
                continue
            }
            val removed = ArrayList<Int>()
            val added = ArrayList<Int>()
            while (index < store.size && store[index].kind != DiffLineKind.Context) {
                when (store[index].kind) {
                    DiffLineKind.Removed -> removed += index
                    DiffLineKind.Added -> added += index
                    else -> Unit
                }
                index++
            }
            val spans = WordDiff.block(removed.map { store[it].text }, added.map { store[it].text })
            removed.forEachIndexed { k, line -> result[line] = spans.removed[k] }
            added.forEachIndexed { k, line -> result[line] = spans.added[k] }
        }
        return result
    }
}
