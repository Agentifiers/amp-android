package com.agentifiers.amp.thread.gates

/** A notice's lead-in and, when the rest of it is a JSON object, one `key: value` line per leaf, nested keys dotted. */
internal class NoticePayload(val lead: String, val lines: List<String>)

/** Null unless [text] ends in one JSON object. A plugin's notice is free text, so anything else shows as written. */
internal fun noticePayload(text: String): NoticePayload? {
    val start = text.indexOf('{').takeIf { it >= 0 } ?: return null
    val lines = ArrayList<String>()
    runCatching { NoticeJson(text, start).run { leaves("", lines); end() } }.getOrElse { return null }
    return NoticePayload(text.substring(0, start).trim(), lines)
}

/**
 * Just enough JSON to flatten a notice: objects become dotted keys, arrays join their items, scalars print bare. A
 * notice is display text, not a wire type, so it is read here rather than through :sync's decoder. Malformed input
 * throws, and [noticePayload] falls back to the raw text.
 */
private class NoticeJson(private val s: String, private var at: Int) {
    fun leaves(prefix: String, out: MutableList<String>) {
        expect('{')
        if (peek() == '}') {
            at++
            return
        }
        do {
            val key = string()
            expect(':')
            if (peek() == '{') leaves("$prefix$key.", out) else out += "$prefix$key: ${value()}"
        } while (comma())
        expect('}')
    }

    fun end() {
        skipSpace()
        require(at == s.length)
    }

    private fun value(): String = when (peek()) {
        '"' -> string()
        '[' -> array()
        '{' -> ArrayList<String>().also { leaves("", it) }.joinToString(", ", "{", "}")
        else -> literal()
    }

    private fun array(): String {
        expect('[')
        if (peek() == ']') {
            at++
            return ""
        }
        val items = ArrayList<String>()
        do items += value() while (comma())
        expect(']')
        return items.joinToString(", ")
    }

    private fun string(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            when (val c = s[at++]) {
                '"' -> return out.toString()
                '\\' -> when (val escaped = s[at++]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> out.append(s.substring(at, at + 4).toInt(16).toChar()).also { at += 4 }
                    else -> out.append(escaped)
                }
                else -> out.append(c)
            }
        }
    }

    private fun literal(): String {
        skipSpace()
        val begin = at
        while (at < s.length && s[at] !in ",}] \n\r\t") at++
        val word = s.substring(begin, at)
        require(word == "true" || word == "false" || word == "null" || word.toDoubleOrNull() != null)
        return word
    }

    private fun comma(): Boolean = (peek() == ',').also { if (it) at++ }

    private fun expect(c: Char) {
        require(peek() == c)
        at++
    }

    private fun peek(): Char {
        skipSpace()
        return s[at]
    }

    private fun skipSpace() {
        while (at < s.length && s[at].isWhitespace()) at++
    }
}
