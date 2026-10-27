package com.agentifiers.amp.window

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import com.agentifiers.amp.layout.Arrangement
import com.agentifiers.amp.model.MachineTab

/** What a window command does. [WindowController.run] carries each one out. */
sealed interface WindowAction {
    data class Arrange(val arrangement: Arrangement) : WindowAction
    data class CycleFocus(val step: Int) : WindowAction
    data object ToggleExpand : WindowAction
    data object ExitFocus : WindowAction
    data object NewWindow : WindowAction
    data object Palette : WindowAction
    data object NewThread : WindowAction
    data object Settings : WindowAction
    data object WorkspaceSettings : WindowAction
    data object Activity : WindowAction
    data object TogglePuck : WindowAction
    data object Back : WindowAction
    data object Forward : WindowAction
    data class Tab(val tab: MachineTab) : WindowAction
    /** [step] null resets the app text size. */
    data class TextSize(val step: Int?) : WindowAction
}

/** One hardware key chord from screens.md "Keyboard shortcuts". */
data class KeyChord(val keyCode: Int, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false) {
    val modifiers: Int
        get() = (if (ctrl) KeyEvent.META_CTRL_ON else 0) or (if (shift) KeyEvent.META_SHIFT_ON else 0) or (if (alt) KeyEvent.META_ALT_ON else 0)

    fun matches(event: KeyEvent): Boolean =
        event.keyCode == keyCode && event.isCtrlPressed == ctrl && event.isShiftPressed == shift && event.isAltPressed == alt && !event.isMetaPressed

    val label: String
        get() = listOfNotNull("Ctrl".takeIf { ctrl }, "Alt".takeIf { alt }, "Shift".takeIf { shift }, keyLabel(keyCode)).joinToString("+")
}

/**
 * A command the window runs from a shortcut or the command palette. [inTerminal] marks the shortcuts that still
 * work while the terminal has focus (screens.md, "In terminal" column).
 */
data class WindowCommand(
    val id: String,
    val category: String,
    val title: String,
    val action: WindowAction,
    val chords: List<KeyChord> = emptyList(),
    val inTerminal: Boolean = false,
    /** False for commands only the palette lists, such as Exit Focus, which the window bar also offers. */
    val published: Boolean = true,
)

private fun ctrl(key: Int) = KeyChord(key, ctrl = true)
private fun ctrlShift(key: Int) = KeyChord(key, ctrl = true, shift = true)

private val ARRANGEMENT_KEYS = listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6)

/** Alt+1 to Alt+6 in screens.md order. Desktop (Alt+5) joins when U12 shows its tab behind the executor's desktop capability. */
private val TABS: List<Pair<String, MachineTab>?> = listOf(
    "Changes" to MachineTab.Changes(focusPath = null),
    "Files" to MachineTab.Files(path = null),
    "Terminal" to MachineTab.Terminal(session = null),
    "Portal" to MachineTab.Portal(port = null),
    null,
    "Space" to MachineTab.Space,
)

/** The window-level rows of screens.md's shortcut table, in table order. Feature panes handle their own rows. */
val WindowCommands: List<WindowCommand> = buildList {
    add(WindowCommand("thread-new", "thread", "New Thread", WindowAction.NewThread, listOf(ctrl(KeyEvent.KEYCODE_N))))
    add(WindowCommand("palette", "app", "Command Palette", WindowAction.Palette, listOf(ctrlShift(KeyEvent.KEYCODE_P), ctrl(KeyEvent.KEYCODE_O)), inTerminal = true))
    add(WindowCommand("puck-toggle", "puck", "Open or Close Puck", WindowAction.TogglePuck, listOf(ctrl(KeyEvent.KEYCODE_SLASH))))
    add(WindowCommand("settings", "app", "Settings", WindowAction.Settings, listOf(ctrl(KeyEvent.KEYCODE_COMMA))))
    add(WindowCommand("workspace-settings", "app", "Workspace Settings on Web", WindowAction.WorkspaceSettings, listOf(KeyChord(KeyEvent.KEYCODE_COMMA, ctrl = true, alt = true))))
    add(WindowCommand("back", "window", "Back", WindowAction.Back, listOf(ctrl(KeyEvent.KEYCODE_LEFT_BRACKET))))
    add(WindowCommand("forward", "window", "Forward", WindowAction.Forward, listOf(ctrl(KeyEvent.KEYCODE_RIGHT_BRACKET))))
    add(WindowCommand("text-larger", "app", "Text Size Up", WindowAction.TextSize(1), listOf(ctrl(KeyEvent.KEYCODE_EQUALS))))
    add(WindowCommand("text-smaller", "app", "Text Size Down", WindowAction.TextSize(-1), listOf(ctrl(KeyEvent.KEYCODE_MINUS))))
    add(WindowCommand("text-reset", "app", "Text Size Reset", WindowAction.TextSize(null), listOf(ctrl(KeyEvent.KEYCODE_0))))
    TABS.forEachIndexed { index, entry ->
        val (name, tab) = entry ?: return@forEachIndexed
        add(WindowCommand("tab-${name.lowercase()}", "machine", "Show $name", WindowAction.Tab(tab), listOf(KeyChord(ARRANGEMENT_KEYS[index], alt = true))))
    }
    Arrangement.entries.forEachIndexed { index, arrangement ->
        add(WindowCommand("arrange-${arrangement.name.lowercase()}", "layout", "Arrange: ${arrangement.name}", WindowAction.Arrange(arrangement), listOf(ctrlShift(ARRANGEMENT_KEYS[index])), inTerminal = true))
    }
    add(WindowCommand("focus-previous", "layout", "Focus Previous Pane", WindowAction.CycleFocus(-1), listOf(ctrlShift(KeyEvent.KEYCODE_DPAD_LEFT)), inTerminal = true))
    add(WindowCommand("focus-next", "layout", "Focus Next Pane", WindowAction.CycleFocus(1), listOf(ctrlShift(KeyEvent.KEYCODE_DPAD_RIGHT)), inTerminal = true))
    add(WindowCommand("expand", "layout", "Expand or Collapse Machine Pane", WindowAction.ToggleExpand, listOf(ctrlShift(KeyEvent.KEYCODE_E)), inTerminal = true))
    add(WindowCommand("activity", "thread", "Open Activity", WindowAction.Activity, listOf(ctrlShift(KeyEvent.KEYCODE_A)), inTerminal = true))
    add(WindowCommand("window-new", "window", "New Window", WindowAction.NewWindow, listOf(ctrlShift(KeyEvent.KEYCODE_N)), inTerminal = true))
    add(WindowCommand("exit-focus", "layout", "Exit Focus", WindowAction.ExitFocus, published = false))
}

/** The command whose chord [event] is, honoring the terminal's claim on every other chord while it has focus. */
fun commandFor(event: KeyEvent, terminalFocused: Boolean): WindowCommand? =
    WindowCommands.firstOrNull { command -> (command.inTerminal || !terminalFocused) && command.chords.any { it.matches(event) } }

/** The shortcut list the system shows with Meta+/ (layout.md "Keyboard, mouse, and trackpad"). */
fun shortcutGroups(): List<KeyboardShortcutGroup> =
    WindowCommands.filter { it.published && it.chords.isNotEmpty() }
        .groupBy { it.category }
        .map { (category, commands) ->
            KeyboardShortcutGroup(
                "Amp: ${category.replaceFirstChar(Char::uppercase)}",
                commands.flatMap { command -> command.chords.map { KeyboardShortcutInfo(command.title, it.keyCode, it.modifiers) } },
            )
        }

private fun keyLabel(keyCode: Int): String = when (keyCode) {
    KeyEvent.KEYCODE_SLASH -> "/"
    KeyEvent.KEYCODE_COMMA -> ","
    KeyEvent.KEYCODE_LEFT_BRACKET -> "["
    KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
    KeyEvent.KEYCODE_EQUALS -> "="
    KeyEvent.KEYCODE_MINUS -> "-"
    KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
    else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
}
