// :vt  package amp.vt   (pure Kotlin)  +  :feature:terminal  package amp.terminal
// Native terminal: bytes from the relay (amp-terminal-v1, spike S8 VERIFIED) → VtEngine (parser +
// screen) inside :sync's TerminalSession → ScreenSnapshot → Compose Canvas. Input: IME, hardware
// keyboard, soft keys, paste, drop-to-stage. The runner PTY is tmux (S8), so mouse reporting and alt screen matter.
package amp.vt

/** Parser + screen model. Single-threaded; owned by one TerminalSession in :sync, shared by every window. */
interface VtEngine {
    fun feed(bytes: ByteArray)
    fun resize(cols: Int, rows: Int)
    /** Immutable snapshot for one frame; cheap structural sharing of unchanged rows. */
    fun snapshot(): ScreenSnapshot
    /** Bytes to send for a key, honoring application cursor/keypad modes and bracketed paste. */
    fun encodeKey(key: VtKey, mods: VtMods): ByteArray
    fun encodePaste(text: String): ByteArray
    fun encodeMouse(event: VtMouse): ByteArray?          // null when the app hasn't enabled mouse reporting
    val title: String
    val pendingClipboardWrites: List<String>             // OSC 52, surfaced for user confirmation
}
data class ScreenSnapshot(val cols: Int, val rows: Int, val lines: List<Row>, val cursor: Cursor, val altScreen: Boolean, val scrollback: Int, val revision: Long)
data class Row(val cells: List<Cell>, val wrapped: Boolean) { val text: String get() = TODO() }
data class Cell(val codepoint: Int, val width: Int, val fg: Int, val bg: Int, val attrs: Int)
data class Cursor(val col: Int, val row: Int, val visible: Boolean, val shape: CursorShape)
enum class CursorShape { Block, Underline, Bar }
enum class VtKey { Char, Enter, Tab, Esc, Backspace, Up, Down, Left, Right, Home, End, PgUp, PgDn, Ins, Del, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 }
data class VtMods(val ctrl: Boolean, val alt: Boolean, val shift: Boolean, val meta: Boolean)
data class VtMouse(val col: Int, val row: Int, val button: Int, val press: Boolean, val wheel: Int)

// ── :feature:terminal ─────────────────────────────────────────────────────────

/**
 * The Terminal tab (package amp.terminal). It renders scope.thread(dest.thread).machine.terminal(tab.session),
 * a TerminalSession owned by :sync, and holds a TerminalViewer while visible in a STARTED window.
 * Tab 1 has session = null; tabs 2 to 5 use tab-2 to tab-5 (terminal.md).
 *
 * One Canvas node for glyphs plus a semantics mirror: each visible row is an invisible Text node
 * tagged AmpTag.Machine.terminalRow(n), so agent-device can `get text` a row (≤ 80 rows → far below
 * 5000 nodes). Extra-keys row above the soft keyboard, real buttons per terminal.md: Esc, Ctrl, Alt,
 * Tab, ← ↑ ↓ →, |, ~, /, ^C, Select, Paste. Ctrl and Alt latch for one key and lock on a double tap.
 * Touch: one-finger vertical drag scrolls local scrollback (on the alternate screen it sends arrows),
 * pinch changes the font size, long-press starts a selection. Cols/rows from the slot size; the
 * prototype measured 48 cols in a 360dp third and 98 cols in the 714dp V3 machine slot.
 * Drop or paste a file: TerminalSession.stageFile (attachment upload, then amp-exec-v1 saves it on the
 * executor), which types the path, or the attachment URL when staging fails.
 * Runner without --remote-control-terminal → TerminalStatus.Unavailable(RunnerFlagMissing) with fix text.
 */
@androidx.compose.runtime.Composable
fun TerminalTab(dest: amp.model.PaneDestination.Machine, tab: amp.model.MachineTab.Terminal, scope: amp.sync.api.AccountScope): Unit = TODO(
    "scope.thread(dest.thread).machine.terminal(tab.session) → attachViewer while STARTED → Canvas over session.screen"
)
