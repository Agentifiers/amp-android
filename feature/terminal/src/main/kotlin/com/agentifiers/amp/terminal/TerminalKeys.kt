package com.agentifiers.amp.terminal

import android.view.KeyEvent
import com.agentifiers.amp.vt.VtKey
import com.agentifiers.amp.vt.VtMods

/** The terminal's own keyboard shortcuts (screens.md, "Keyboard shortcuts", rows whose Where is Terminal). */
internal enum class TerminalShortcut { Copy, Paste, NewTab, CloseTab, TextLarger, TextSmaller, TextDefault }

internal sealed interface KeyAction {
    data class Send(val key: VtKey, val mods: VtMods) : KeyAction
    data class Shortcut(val shortcut: TerminalShortcut) : KeyAction

    /** Not the terminal's: app shortcuts with Ctrl+Shift, system Meta chords, bare modifier keys. */
    data object Pass : KeyAction
}

private val named: Map<Int, VtKey.Named> = mapOf(
    KeyEvent.KEYCODE_ENTER to VtKey.Named.Enter,
    KeyEvent.KEYCODE_NUMPAD_ENTER to VtKey.Named.Enter,
    KeyEvent.KEYCODE_TAB to VtKey.Named.Tab,
    KeyEvent.KEYCODE_ESCAPE to VtKey.Named.Esc,
    KeyEvent.KEYCODE_DEL to VtKey.Named.Backspace,
    KeyEvent.KEYCODE_FORWARD_DEL to VtKey.Named.Del,
    KeyEvent.KEYCODE_INSERT to VtKey.Named.Ins,
    KeyEvent.KEYCODE_DPAD_UP to VtKey.Named.Up,
    KeyEvent.KEYCODE_DPAD_DOWN to VtKey.Named.Down,
    KeyEvent.KEYCODE_DPAD_LEFT to VtKey.Named.Left,
    KeyEvent.KEYCODE_DPAD_RIGHT to VtKey.Named.Right,
    KeyEvent.KEYCODE_MOVE_HOME to VtKey.Named.Home,
    KeyEvent.KEYCODE_MOVE_END to VtKey.Named.End,
    KeyEvent.KEYCODE_PAGE_UP to VtKey.Named.PgUp,
    KeyEvent.KEYCODE_PAGE_DOWN to VtKey.Named.PgDn,
    KeyEvent.KEYCODE_F1 to VtKey.Named.F1,
    KeyEvent.KEYCODE_F2 to VtKey.Named.F2,
    KeyEvent.KEYCODE_F3 to VtKey.Named.F3,
    KeyEvent.KEYCODE_F4 to VtKey.Named.F4,
    KeyEvent.KEYCODE_F5 to VtKey.Named.F5,
    KeyEvent.KEYCODE_F6 to VtKey.Named.F6,
    KeyEvent.KEYCODE_F7 to VtKey.Named.F7,
    KeyEvent.KEYCODE_F8 to VtKey.Named.F8,
    KeyEvent.KEYCODE_F9 to VtKey.Named.F9,
    KeyEvent.KEYCODE_F10 to VtKey.Named.F10,
    KeyEvent.KEYCODE_F11 to VtKey.Named.F11,
    KeyEvent.KEYCODE_F12 to VtKey.Named.F12,
)

private val ctrlShiftShortcuts: Map<Int, TerminalShortcut> = mapOf(
    KeyEvent.KEYCODE_C to TerminalShortcut.Copy,
    KeyEvent.KEYCODE_V to TerminalShortcut.Paste,
    KeyEvent.KEYCODE_T to TerminalShortcut.NewTab,
    KeyEvent.KEYCODE_W to TerminalShortcut.CloseTab,
    KeyEvent.KEYCODE_EQUALS to TerminalShortcut.TextLarger,
    KeyEvent.KEYCODE_PLUS to TerminalShortcut.TextLarger,
    KeyEvent.KEYCODE_NUMPAD_ADD to TerminalShortcut.TextLarger,
    KeyEvent.KEYCODE_MINUS to TerminalShortcut.TextSmaller,
    KeyEvent.KEYCODE_NUMPAD_SUBTRACT to TerminalShortcut.TextSmaller,
    KeyEvent.KEYCODE_0 to TerminalShortcut.TextDefault,
)

private val metaShortcuts: Map<Int, TerminalShortcut> = mapOf(
    KeyEvent.KEYCODE_C to TerminalShortcut.Copy,
    KeyEvent.KEYCODE_V to TerminalShortcut.Paste,
)

/**
 * One key-down while the terminal has focus (terminal.md, "Hardware keyboard and DeX"). [char] is the key map's
 * character with Shift only applied, and [altChar] with Alt as well; both 0 when the key prints nothing. Right Alt
 * that prints through the key map is AltGr on international layouts and sends the character without ESC.
 */
internal fun keyAction(keyCode: Int, metaState: Int, char: Int, altChar: Int): KeyAction {
    val ctrl = metaState and KeyEvent.META_CTRL_ON != 0
    val shift = metaState and KeyEvent.META_SHIFT_ON != 0
    val alt = metaState and KeyEvent.META_ALT_ON != 0
    if (metaState and KeyEvent.META_META_ON != 0) return metaShortcuts[keyCode]?.let(KeyAction::Shortcut) ?: KeyAction.Pass
    if (ctrl && shift) return ctrlShiftShortcuts[keyCode]?.let(KeyAction::Shortcut) ?: KeyAction.Pass
    named[keyCode]?.let { return KeyAction.Send(it, VtMods(ctrl = ctrl, alt = alt, shift = shift)) }
    val altGr = alt && !ctrl && metaState and KeyEvent.META_ALT_RIGHT_ON != 0 && altChar != 0 && altChar != char
    return when {
        altGr -> KeyAction.Send(VtKey.Char(altChar), VtMods.None)
        char != 0 -> KeyAction.Send(VtKey.Char(char), VtMods(ctrl = ctrl, alt = alt))
        else -> KeyAction.Pass
    }
}

/** Every key that reaches the terminal as a key-down; key-ups and repeats of shortcuts are not sent again. */
internal fun keyAction(event: KeyEvent): KeyAction {
    val meta = event.metaState
    val plain = event.getUnicodeChar(meta and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv())
    val withAlt = event.getUnicodeChar(meta and (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK).inv())
    fun printable(c: Int) = if (c and android.view.KeyCharacterMap.COMBINING_ACCENT != 0 || c < 0x20) 0 else c
    return keyAction(event.keyCode, meta, printable(plain), printable(withAlt))
}

/** Soft-row Ctrl and Alt: a tap latches for one key, a second tap within 300 ms locks, a tap when locked releases. */
internal data class Latch(val state: State = State.Off, val tappedAtMs: Long = Long.MIN_VALUE) {
    enum class State { Off, Once, Locked }

    val active: Boolean get() = state != State.Off

    fun tap(nowMs: Long): Latch = when (state) {
        State.Off -> Latch(State.Once, nowMs)
        State.Once -> if (nowMs - tappedAtMs <= DOUBLE_TAP_MS) Latch(State.Locked, nowMs) else Latch(State.Off, nowMs)
        State.Locked -> Latch(State.Off, nowMs)
    }

    /** After one key used the latch. */
    fun used(): Latch = if (state == State.Once) Latch(State.Off, tappedAtMs) else this

    companion object {
        const val DOUBLE_TAP_MS = 300L
    }
}
