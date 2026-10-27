package com.agentifiers.amp.model

/**
 * Markdown split at Amp's `:::` container fences. Amp's web knows one container, `gallery` (the marked extension
 * `li` in its thread chunk `99d00ac2.js`, fence regex `Hr`): an opening line `:::gallery <layout>` or `:::<layout>`
 * indented at most three spaces, a body, and a closing line `:::`. The web leaves any other `:::name` as text; here it
 * opens a directive too, so no fence ever shows. Fences inside code fences are code, and a `:::` inside a line is text.
 */
sealed interface MarkdownPart {
    data class Prose(val markdown: String) : MarkdownPart

    /** [attributes] is the rest of the opening line. [closed] is false while the closing fence has not arrived. */
    data class Directive(val name: String, val attributes: String, val closed: Boolean, val children: List<MarkdownPart>) : MarkdownPart

    companion object {
        private val OPEN = Regex("""^ {0,3}:::[ \t]*([A-Za-z][A-Za-z0-9_-]*)(.*)$""")
        private val CLOSE = Regex("""^ {0,3}:::[ \t]*$""")
        private val ARRIVING = Regex("""^ {0,3}:{1,3}[ \t]*$""")
        private val CODE_FENCE = Regex("""^ {0,3}(`{3,}|~{3,})""")

        /**
         * Directives nest: an opening line inside one opens a child, and a closing line closes the innermost. A closing
         * line with nothing open is dropped. While [streaming], a last line of only colons is a fence still arriving
         * (an opening name or a closing line is on its way) and is dropped, so a directive stays open until it closes.
         */
        fun parse(markdown: String, streaming: Boolean = false): List<MarkdownPart> {
            val source = if (streaming) withoutArrivingFence(markdown) else markdown
            if (":::" !in source) return listOf(Prose(source))
            val open = ArrayDeque(listOf(Level(null, "")))
            var code: String? = null
            for (line in source.split('\n')) {
                val level = open.last()
                val fence = code
                if (fence != null) {
                    level.lines += line
                    if (line.trim().let { it.startsWith(fence) && it.all { c -> c == fence[0] } }) code = null
                    continue
                }
                val opening = OPEN.matchEntire(line)
                when {
                    opening != null -> {
                        level.flush()
                        open.addLast(Level(opening.groupValues[1], opening.groupValues[2].trim()))
                    }
                    CLOSE.matches(line) -> if (open.size > 1) open.removeLast().let { open.last().parts += it.close(closed = true) } else level.flush()
                    else -> {
                        code = CODE_FENCE.find(line)?.groupValues?.get(1)
                        level.lines += line
                    }
                }
            }
            while (open.size > 1) open.removeLast().let { open.last().parts += it.close(closed = false) }
            return open.single().apply { flush() }.parts
        }

        /** Each directive's children in its place, without its fences: the markdown Copy as Markdown, Puck and notifications show. */
        fun withoutFences(markdown: String, streaming: Boolean = false): String = markdown(parse(markdown, streaming))

        private fun markdown(parts: List<MarkdownPart>): String = parts.joinToString("\n\n") { part ->
            when (part) {
                is Prose -> part.markdown
                is Directive -> markdown(part.children)
            }
        }

        private fun withoutArrivingFence(markdown: String): String {
            val start = markdown.lastIndexOf('\n') + 1
            return if (ARRIVING.matches(markdown.substring(start))) markdown.substring(0, maxOf(start - 1, 0)) else markdown
        }
    }

    private class Level(val name: String?, val attributes: String) {
        val parts = ArrayList<MarkdownPart>()
        val lines = ArrayList<String>()

        /** Moves the lines gathered so far into [parts] as one prose run, without the blank lines around it. */
        fun flush() {
            val from = lines.indexOfFirst { it.isNotBlank() }
            if (from >= 0) parts += Prose(lines.subList(from, lines.indexOfLast { it.isNotBlank() } + 1).joinToString("\n"))
            lines.clear()
        }

        fun close(closed: Boolean): Directive {
            flush()
            return Directive(name!!, attributes, closed, parts.toList())
        }
    }
}

/** How a gallery lays out its images (web `Br` and `Yr`): an unknown or missing layout word is a grid. */
enum class GalleryLayout(val word: String) {
    SideBySide("side-by-side"),
    Grid("grid"),
    Carousel("carousel"),
    ;

    companion object {
        /** `:::gallery <word>`, or a layout word alone as the name with nothing after it; null for any other directive. */
        fun of(directive: MarkdownPart.Directive): GalleryLayout? = when {
            directive.name == "gallery" -> entries.firstOrNull { it.word == directive.attributes.lowercase() } ?: Grid
            directive.attributes.isEmpty() -> entries.firstOrNull { it.word == directive.name }
            else -> null
        }
    }
}
