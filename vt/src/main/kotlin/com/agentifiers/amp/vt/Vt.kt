package com.agentifiers.amp.vt

/**
 * Parser and screen model for one terminal (docs/design/terminal.md, "What the VT core handles"). Not thread-safe:
 * one owner feeds it and reads it. :sync's TerminalSession owns one per session and confines it to one dispatcher.
 */
interface VtEngine {
    val cols: Int
    val rows: Int

    /** The OSC 0 or OSC 2 title, empty until the application sets one. */
    val title: String
    val modes: VtModes

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)
    fun resize(cols: Int, rows: Int)

    /** Immutable; rows that did not change are shared with the previous snapshot. */
    fun snapshot(): ScreenSnapshot

    /** Bytes the terminal owes the PTY (device attributes, cursor position reports), oldest first. Drains them. */
    fun takeReplies(): ByteArray

    /** Bell, title and OSC 52 events since the last call, oldest first. Drains them. */
    fun takeEvents(): List<VtEvent>

    /** Appends a dim `[Reconnected]` row to scrollback, so a reader scrolling back sees the gap. */
    fun markReconnected()

    /** Writes [text] in dim on its own line at the cursor, as the web does for its no-output notice. */
    fun notice(text: String)

    /** Honors cursor key mode (DECSET 1). */
    fun encodeKey(key: VtKey, mods: VtMods): ByteArray

    /** Converts `\r\n` and `\n` to `\r`, then brackets the text when DECSET 2004 is on, with any `ESC[201~` removed. */
    fun encodePaste(text: String): ByteArray

    /** Null when the application has not enabled mouse reporting for this kind of event. */
    fun encodeMouse(event: VtMouse): ByteArray?

    /** Null unless DECSET 1004 is on. */
    fun encodeFocus(focused: Boolean): ByteArray?
}

/** The Kotlin VT core with a scrollback ring of [scrollbackRows]. */
fun VtEngine(cols: Int, rows: Int, scrollbackRows: Int = 10_000): VtEngine = KotlinVt(cols, rows, scrollbackRows)

data class VtModes(
    val cursorKeysApplication: Boolean = false,
    val bracketedPaste: Boolean = false,
    val mouseTracking: MouseTracking = MouseTracking.Off,
    val sgrMouse: Boolean = false,
    val focusEvents: Boolean = false,
    val altScreen: Boolean = false,
    val cursorVisible: Boolean = true,
)

/** DECSET 1000, 1002 and 1003. */
enum class MouseTracking { Off, Click, Drag, Any }

sealed interface VtEvent {
    data object Bell : VtEvent
    data class Title(val text: String) : VtEvent

    /** A decoded OSC 52 write. Reads (`?`) never become events. */
    data class ClipboardWrite(val text: String) : VtEvent
}

/**
 * [lines] holds exactly [rows] visible rows. [history] is the primary screen's scrollback, oldest first. A
 * [GridPoint] line counts from the top of [history], so line `history.size` is the first visible row.
 */
class ScreenSnapshot(
    val cols: Int,
    val rows: Int,
    val lines: List<Row>,
    val history: List<Row>,
    val cursor: Cursor,
    val modes: VtModes,
    val title: String,
    val revision: Long,
) {
    val scrollback: Int get() = history.size
    val totalLines: Int get() = history.size + lines.size

    fun line(index: Int): Row = if (index < history.size) history[index] else lines[index - history.size]

    /** The visible screen as text: rows joined by `\n`, trailing spaces trimmed. The accessibility mirror reads this. */
    val text: String get() = lines.joinToString("\n") { it.text }

    /** Text from [from] to [to] inclusive, in reading order. Wrapped rows join without a newline. */
    fun selectionText(from: GridPoint, to: GridPoint): String = selectText(this, from, to)

    /** The word under [point] as a selection range, or null on a blank cell. */
    fun wordAt(point: GridPoint): Pair<GridPoint, GridPoint>? = wordRange(this, point)

    /** The OSC 8 target or the plain `http(s)://` URL under [point]. */
    fun linkAt(point: GridPoint): String? = linkUnder(this, point)
}

data class GridPoint(val line: Int, val col: Int) : Comparable<GridPoint> {
    override fun compareTo(other: GridPoint): Int = compareValuesBy(this, other, GridPoint::line, GridPoint::col)
}

/**
 * One row of cells. A wide glyph's head cell has width 2 and the cell after it width 0. Scrollback rows may hold
 * fewer [columns] than the screen; cells past [columns] are blank. Scrollback keeps 10,000 of these, so a row
 * stores Latin-1 text as bytes and its styles as runs, and copies what it is built from.
 */
class Row internal constructor(
    codepoints: IntArray,
    styles: LongArray,
    extras: Map<Int, String>?,
    links: Map<Int, String>?,
    val wrapped: Boolean,
    /** How many leading cells of [codepoints] and [styles] belong to the row. */
    length: Int = codepoints.size,
) {
    val columns: Int = length
    private val latin1: ByteArray?
    private val wideCells: IntArray?
    private val runStarts: IntArray
    private val runStyles: LongArray
    private val extras: Map<Int, String>? = extras?.filterKeys { it < length }?.takeIf { it.isNotEmpty() }
    private val links: Map<Int, String>? = links?.filterKeys { it < length }?.takeIf { it.isNotEmpty() }
    private var cachedText: String? = null

    init {
        var narrow = true
        var runs = if (length > 0) 1 else 0
        for (col in 0 until length) {
            if (codepoints[col] !in 0..0xff) narrow = false
            if (col > 0 && styles[col] != styles[col - 1]) runs++
        }
        latin1 = if (narrow) ByteArray(length) { codepoints[it].toByte() } else null
        wideCells = if (narrow) null else codepoints.copyOf(length)
        runStarts = IntArray(runs)
        runStyles = LongArray(runs)
        var run = -1
        for (col in 0 until length) {
            if (col > 0 && styles[col] == styles[col - 1]) continue
            run++
            runStarts[run] = col
            runStyles[run] = styles[col]
        }
    }

    private fun cell(col: Int): Int = latin1?.let { it[col].toInt() and 0xff } ?: wideCells!![col]

    /** 0 for a blank cell and for the tail of a wide glyph. */
    fun codepoint(col: Int): Int = if (col < columns) cell(col).let { if (it == WIDE_TAIL) 0 else it } else 0

    fun width(col: Int): Int = when {
        col >= columns || latin1 != null -> 1
        cell(col) == WIDE_TAIL -> 0
        col + 1 < columns && cell(col + 1) == WIDE_TAIL -> 2
        else -> 1
    }

    /** The cell's base glyph plus any combining marks; empty for a blank cell or a wide tail. */
    fun grapheme(col: Int): String {
        val cp = codepoint(col)
        if (cp == 0) return ""
        return extras?.get(col) ?: String(Character.toChars(cp))
    }

    fun style(col: Int): Style {
        if (col >= columns) return Style.Default
        var lo = 0
        var hi = runStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (runStarts[mid] <= col) lo = mid else hi = mid - 1
        }
        return Style(runStyles[lo])
    }

    /** The OSC 8 hyperlink target of the cell. */
    fun link(col: Int): String? = links?.get(col)

    /** Blank cells read as spaces; trailing spaces are trimmed. */
    val text: String
        get() = cachedText ?: buildString {
            for (col in 0 until columns) {
                if (width(col) == 0) continue
                append(grapheme(col).ifEmpty { " " })
            }
        }.trimEnd(' ').also { cachedText = it }

    internal companion object {
        /** Marks the second cell of a wide glyph. Not a valid Unicode scalar. */
        const val WIDE_TAIL = -1
    }
}

/** Cell style packed in a Long: foreground in bits 0-25, background in 26-51, attributes from 52. */
@JvmInline
value class Style(val packed: Long) {
    val fg: VtColor get() = VtColor((packed and COLOR_MASK).toInt())
    val bg: VtColor get() = VtColor(((packed ushr 26) and COLOR_MASK).toInt())
    val bold: Boolean get() = has(BOLD)
    val dim: Boolean get() = has(DIM)
    val italic: Boolean get() = has(ITALIC)
    val underline: Boolean get() = has(UNDERLINE)
    val blink: Boolean get() = has(BLINK)
    val inverse: Boolean get() = has(INVERSE)
    val invisible: Boolean get() = has(INVISIBLE)
    val strikethrough: Boolean get() = has(STRIKETHROUGH)

    private fun has(flag: Long) = packed and flag != 0L

    fun with(fg: VtColor = this.fg, bg: VtColor = this.bg): Style =
        Style((packed and (COLOR_MASK or (COLOR_MASK shl 26)).inv()) or fg.packed.toLong() or (bg.packed.toLong() shl 26))

    fun with(flag: Long, on: Boolean): Style = Style(if (on) packed or flag else packed and flag.inv())

    companion object {
        val Default = Style(0L)
        private const val COLOR_MASK = (1L shl 26) - 1
        const val BOLD = 1L shl 52
        const val DIM = 1L shl 53
        const val ITALIC = 1L shl 54
        const val UNDERLINE = 1L shl 55
        const val BLINK = 1L shl 56
        const val INVERSE = 1L shl 57
        const val INVISIBLE = 1L shl 58
        const val STRIKETHROUGH = 1L shl 59
    }
}

/** Default, one of the 256 palette entries, or 24-bit RGB, in 26 bits. */
@JvmInline
value class VtColor(val packed: Int) {
    val isDefault: Boolean get() = packed == 0
    val paletteIndex: Int? get() = if (packed ushr 24 == 1) packed and 0xff else null
    val rgb: Int? get() = if (packed ushr 24 == 2) packed and 0xffffff else null

    companion object {
        val Default = VtColor(0)
        fun palette(index: Int) = VtColor((1 shl 24) or (index and 0xff))
        fun rgb(r: Int, g: Int, b: Int) = VtColor((2 shl 24) or ((r and 0xff) shl 16) or ((g and 0xff) shl 8) or (b and 0xff))
    }
}

data class Cursor(val col: Int, val row: Int, val visible: Boolean, val shape: CursorShape)

/** DECSCUSR. */
enum class CursorShape { Block, Underline, Bar }

sealed interface VtKey {
    /** A printable character; Ctrl and Alt apply the xterm legacy encoding. */
    data class Char(val codepoint: Int) : VtKey

    enum class Named : VtKey { Enter, Tab, Esc, Backspace, Up, Down, Left, Right, Home, End, PgUp, PgDn, Ins, Del, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 }
}

data class VtMods(val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false, val meta: Boolean = false) {
    companion object {
        val None = VtMods()
    }
}

/** 0-based cell position on the visible screen. */
data class VtMouse(val col: Int, val row: Int, val action: MouseAction, val button: MouseButton, val mods: VtMods = VtMods.None)

enum class MouseAction { Press, Release, Move }

enum class MouseButton { Left, Middle, Right, None, WheelUp, WheelDown }
