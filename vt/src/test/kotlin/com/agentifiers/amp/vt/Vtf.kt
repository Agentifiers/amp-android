package com.agentifiers.amp.vt

import java.io.ByteArrayOutputStream
import java.io.File

/** One `.vtf` fixture (contract "VT fixture format"). */
internal class Fixture(
    val suite: String,
    val name: String,
    val source: String,
    val cols: Int,
    val rows: Int,
    val input: ByteArray,
    val expectations: List<Expectation>,
) {
    val id: String get() = "$suite/$name"

    /** Null when every expectation holds, else the first one that failed and what the engine showed. */
    fun run(): String? {
        val vt = VtEngine(cols, rows)
        vt.feed(input)
        val outcome = Outcome(vt.snapshot(), vt.takeReplies(), vt.title)
        for (e in expectations) {
            val problem = e.check(outcome) ?: continue
            return "${e.line} -> $problem"
        }
        return null
    }
}

internal class Outcome(val screen: ScreenSnapshot, val replies: ByteArray, val title: String)

internal class Expectation(val line: String, val check: (Outcome) -> String?)

internal fun loadFixtures(root: File): List<Fixture> =
    root.walkTopDown().filter { it.isFile && it.extension == "vtf" }.sortedBy { it.path }.flatMap { file ->
        val suite = file.parentFile.relativeTo(root).path.ifEmpty { file.nameWithoutExtension }
        parseFixtures(suite, file.name, file.readText())
    }.toList()

internal fun parseFixtures(suite: String, file: String, text: String): List<Fixture> =
    splitFixtures(text).map { parseFixture(suite, file, it) }

private fun splitFixtures(text: String): List<List<String>> {
    val groups = mutableListOf(mutableListOf<String>())
    for (line in text.lines()) {
        if (line.trim() == "---") groups.add(mutableListOf()) else groups.last().add(line)
    }
    return groups.map { group -> group.filter { it.isNotBlank() && !it.startsWith("#") } }.filter { it.isNotEmpty() }
}

private fun parseFixture(suite: String, file: String, lines: List<String>): Fixture {
    var name: String? = null
    var source: String? = null
    var size: Pair<Int, Int>? = null
    val input = ByteArrayOutputStream()
    val expectations = mutableListOf<Expectation>()
    for (line in lines) {
        val key = line.substringBefore(':').trim()
        val value = line.substringAfter(':', "").removePrefix(" ")
        when {
            key == "name" -> name = value
            key == "source" -> source = value
            key == "size" -> size = value.trim().split('x').let { it[0].toInt() to it[1].toInt() }
            key == "input" -> input.write(unescape(value))
            else -> expectations += expectation(key, value, line)
        }
    }
    val (cols, rows) = requireNotNull(size) { "$file: ${name ?: "?"} has no size" }
    return Fixture(
        suite,
        requireNotNull(name) { "$file: fixture without a name" },
        requireNotNull(source) { "$file: $name has no source" },
        cols,
        rows,
        input.toByteArray(),
        expectations,
    )
}

private fun expectation(key: String, value: String, line: String): Expectation {
    val parts = key.split(' ')
    fun expect(check: (Outcome) -> String?) = Expectation(line, check)
    return when (parts[0]) {
        "cursor" -> {
            val (c, r) = value.trim().split(',').map { it.trim().toInt() }
            expect { o -> mismatch("$c,$r", "${o.screen.cursor.col},${o.screen.cursor.row}") }
        }
        "row" -> {
            val n = parts[1].toInt()
            val text = piped(value)
            expect { o -> mismatch(text, o.screen.lines[n].text) }
        }
        "history" -> {
            val n = value.trim().toInt()
            expect { o -> mismatch(n.toString(), o.screen.history.size.toString()) }
        }
        "history-row" -> {
            val i = parts[1].toInt()
            val text = piped(value)
            expect { o ->
                if (i >= o.screen.history.size) "history has ${o.screen.history.size} rows" else mismatch(text, o.screen.history[i].text)
            }
        }
        "wrapped" -> {
            val n = parts[1].toInt()
            val want = value.trim().toBooleanStrict()
            expect { o -> mismatch(want.toString(), o.screen.lines[n].wrapped.toString()) }
        }
        "reply" -> {
            val want = unescape(value)
            expect { o -> mismatch(escape(want), escape(o.replies)) }
        }
        "title" -> {
            val want = String(unescape(value), Charsets.UTF_8)
            expect { o -> mismatch(want, o.title) }
        }
        "mode" -> {
            val (modeName, state) = value.trim().split(' ')
            require(state == "on" || state == "off") { "bad mode state: $line" }
            val read = modeReader(modeName) ?: error("unknown mode: $line")
            expect { o -> mismatch("$modeName $state", "$modeName ${if (read(o.screen.modes)) "on" else "off"}") }
        }
        "cell" -> {
            val (c, r) = parts[1].split(',').map { it.toInt() }
            val props = value.trim().split(' ').filter { it.isNotEmpty() }.map { cellProp(it, line) }
            expect { o -> props.firstNotNullOfOrNull { it(o.screen.lines[r], c) } }
        }
        else -> error("unknown expectation: $line")
    }
}

private fun modeReader(name: String): ((VtModes) -> Boolean)? = when (name) {
    "cursor-keys-app" -> { m -> m.cursorKeysApplication }
    "bracketed-paste" -> { m -> m.bracketedPaste }
    "mouse-click" -> { m -> m.mouseTracking == MouseTracking.Click }
    "mouse-drag" -> { m -> m.mouseTracking == MouseTracking.Drag }
    "mouse-any" -> { m -> m.mouseTracking == MouseTracking.Any }
    "sgr-mouse" -> { m -> m.sgrMouse }
    "focus-events" -> { m -> m.focusEvents }
    "alt-screen" -> { m -> m.altScreen }
    "cursor-visible" -> { m -> m.cursorVisible }
    else -> null
}

private fun cellProp(prop: String, line: String): (Row, Int) -> String? {
    val key = prop.substringBefore('=')
    val value = prop.substringAfter('=', "")
    return when (key) {
        "char" -> {
            val want = String(unescape(value), Charsets.UTF_8)
            if (want.isEmpty()) {
                { row, c -> row.grapheme(c).let { if (it.isEmpty() || it == " ") null else "char=${it} want blank" } }
            } else {
                { row, c -> mismatch("char=$want", "char=${row.grapheme(c)}") }
            }
        }
        "bold" -> flag(key) { it.bold }
        "dim" -> flag(key) { it.dim }
        "italic" -> flag(key) { it.italic }
        "underline" -> flag(key) { it.underline }
        "inverse" -> flag(key) { it.inverse }
        "strike" -> flag(key) { it.strikethrough }
        "plain" -> { row, c -> if (row.style(c).packed == 0L) null else "not plain: ${describe(row.style(c))}" }
        "fg" -> { row, c -> mismatch("fg=$value", "fg=${colorName(row.style(c).fg)}") }
        "bg" -> { row, c -> mismatch("bg=$value", "bg=${colorName(row.style(c).bg)}") }
        "width" -> { row, c -> mismatch("width=$value", "width=${row.width(c)}") }
        else -> error("unknown cell prop: $line")
    }
}

private fun flag(name: String, read: (Style) -> Boolean): (Row, Int) -> String? =
    { row, c -> if (read(row.style(c))) null else "not $name: ${describe(row.style(c))}" }

private fun describe(s: Style): String = buildList {
    if (s.bold) add("bold")
    if (s.dim) add("dim")
    if (s.italic) add("italic")
    if (s.underline) add("underline")
    if (s.blink) add("blink")
    if (s.inverse) add("inverse")
    if (s.invisible) add("invisible")
    if (s.strikethrough) add("strike")
    add("fg=${colorName(s.fg)}")
    add("bg=${colorName(s.bg)}")
}.joinToString(" ")

private fun colorName(c: VtColor): String =
    c.paletteIndex?.toString() ?: c.rgb?.let { "#%06x".format(it) } ?: "default"

private fun mismatch(want: String, got: String): String? = if (want == got) null else "got |$got| want |$want|"

private fun piped(value: String): String {
    val v = value.trim()
    require(v.length >= 2 && v.first() == '|' && v.last() == '|') { "row text must sit between pipes: $value" }
    return String(unescape(v.substring(1, v.length - 1)), Charsets.UTF_8)
}

/** The fixture escapes: `\e \r \n \t \b \a \\ \xHH \u{H..}`; every other character is its UTF-8. */
internal fun unescape(text: String): ByteArray {
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c != '\\') {
            val end = if (Character.isHighSurrogate(c) && i + 1 < text.length) i + 2 else i + 1
            out.write(text.substring(i, end).toByteArray(Charsets.UTF_8))
            i = end
            continue
        }
        require(i + 1 < text.length) { "dangling backslash in $text" }
        when (val e = text[i + 1]) {
            'e' -> out.write(0x1b)
            'r' -> out.write(0x0d)
            'n' -> out.write(0x0a)
            't' -> out.write(0x09)
            'b' -> out.write(0x08)
            'a' -> out.write(0x07)
            '\\' -> out.write('\\'.code)
            'x' -> {
                out.write(text.substring(i + 2, i + 4).toInt(16))
                i += 2
            }
            'u' -> {
                val close = text.indexOf('}', i)
                require(text[i + 2] == '{' && close > 0) { "bad \\u escape in $text" }
                out.write(String(Character.toChars(text.substring(i + 3, close).toInt(16))).toByteArray(Charsets.UTF_8))
                i = close - 1
            }
            else -> error("unknown escape \\$e in $text")
        }
        i += 2
    }
    return out.toByteArray()
}

private fun escape(bytes: ByteArray): String = buildString {
    for (b in bytes) {
        val v = b.toInt() and 0xff
        when {
            v == 0x1b -> append("\\e")
            v == '\\'.code -> append("\\\\")
            v in 0x20..0x7e -> append(v.toChar())
            else -> append("\\x%02x".format(v))
        }
    }
}
