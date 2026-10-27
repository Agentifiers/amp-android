package com.agentifiers.amp.waymote

import android.view.KeyEvent

/**
 * Android key codes to Linux evdev codes, following the web client's `KeyboardEvent.code` table (e88f9517.js `Ie`).
 * Back, volume, power and media keys are left out so they keep working on the device.
 */
internal object Keyboard {
    const val ENTER = 28
    const val BACKSPACE = 14

    private val evdev: Map<Int, Int> = buildMap {
        put(KeyEvent.KEYCODE_ESCAPE, 1)
        for (digit in 1..9) put(KeyEvent.KEYCODE_1 + digit - 1, 1 + digit)
        put(KeyEvent.KEYCODE_0, 11)
        put(KeyEvent.KEYCODE_MINUS, 12)
        put(KeyEvent.KEYCODE_EQUALS, 13)
        put(KeyEvent.KEYCODE_DEL, BACKSPACE)
        put(KeyEvent.KEYCODE_TAB, 15)
        "QWERTYUIOP".forEachIndexed { i, c -> put(letter(c), 16 + i) }
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 26)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 27)
        put(KeyEvent.KEYCODE_ENTER, ENTER)
        put(KeyEvent.KEYCODE_CTRL_LEFT, 29)
        "ASDFGHJKL".forEachIndexed { i, c -> put(letter(c), 30 + i) }
        put(KeyEvent.KEYCODE_SEMICOLON, 39)
        put(KeyEvent.KEYCODE_APOSTROPHE, 40)
        put(KeyEvent.KEYCODE_GRAVE, 41)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, 42)
        put(KeyEvent.KEYCODE_BACKSLASH, 43)
        "ZXCVBNM".forEachIndexed { i, c -> put(letter(c), 44 + i) }
        put(KeyEvent.KEYCODE_COMMA, 51)
        put(KeyEvent.KEYCODE_PERIOD, 52)
        put(KeyEvent.KEYCODE_SLASH, 53)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, 54)
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 55)
        put(KeyEvent.KEYCODE_ALT_LEFT, 56)
        put(KeyEvent.KEYCODE_SPACE, 57)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 58)
        for (n in 1..10) put(KeyEvent.KEYCODE_F1 + n - 1, 58 + n)
        put(KeyEvent.KEYCODE_NUM_LOCK, 69)
        put(KeyEvent.KEYCODE_SCROLL_LOCK, 70)
        put(KeyEvent.KEYCODE_NUMPAD_7, 71)
        put(KeyEvent.KEYCODE_NUMPAD_8, 72)
        put(KeyEvent.KEYCODE_NUMPAD_9, 73)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 74)
        put(KeyEvent.KEYCODE_NUMPAD_4, 75)
        put(KeyEvent.KEYCODE_NUMPAD_5, 76)
        put(KeyEvent.KEYCODE_NUMPAD_6, 77)
        put(KeyEvent.KEYCODE_NUMPAD_ADD, 78)
        put(KeyEvent.KEYCODE_NUMPAD_1, 79)
        put(KeyEvent.KEYCODE_NUMPAD_2, 80)
        put(KeyEvent.KEYCODE_NUMPAD_3, 81)
        put(KeyEvent.KEYCODE_NUMPAD_0, 82)
        put(KeyEvent.KEYCODE_NUMPAD_DOT, 83)
        put(KeyEvent.KEYCODE_F11, 87)
        put(KeyEvent.KEYCODE_F12, 88)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, 96)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, 97)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 98)
        put(KeyEvent.KEYCODE_SYSRQ, 99)
        put(KeyEvent.KEYCODE_ALT_RIGHT, 100)
        put(KeyEvent.KEYCODE_MOVE_HOME, 102)
        put(KeyEvent.KEYCODE_DPAD_UP, 103)
        put(KeyEvent.KEYCODE_PAGE_UP, 104)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 105)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 106)
        put(KeyEvent.KEYCODE_MOVE_END, 107)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 108)
        put(KeyEvent.KEYCODE_PAGE_DOWN, 109)
        put(KeyEvent.KEYCODE_INSERT, 110)
        put(KeyEvent.KEYCODE_FORWARD_DEL, 111)
        put(KeyEvent.KEYCODE_NUMPAD_EQUALS, 117)
        put(KeyEvent.KEYCODE_BREAK, 119)
        put(KeyEvent.KEYCODE_META_LEFT, 125)
        put(KeyEvent.KEYCODE_META_RIGHT, 126)
        put(KeyEvent.KEYCODE_MENU, 127)
        put(KeyEvent.KEYCODE_COPY, 133)
        put(KeyEvent.KEYCODE_PASTE, 135)
        put(KeyEvent.KEYCODE_CUT, 137)
    }

    private fun letter(c: Char): Int = KeyEvent.KEYCODE_A + (c - 'A')

    fun evdev(androidKeyCode: Int): Int? = evdev[androidKeyCode]

    /**
     * The keysym the web client sends when the server resolves keysyms (e88f9517.js `o()`): Latin-1 as itself, other
     * code points as `0x01000000 | cp`, nothing for control characters, surrogates, or chords with Ctrl, Alt or Meta
     * unless AltGr (right Alt) is held.
     */
    fun keysym(unicodeChar: Int, metaState: Int): Int {
        val altGr = metaState and KeyEvent.META_ALT_RIGHT_ON != 0
        val chord = metaState and KeyEvent.META_META_ON != 0 ||
            !altGr && metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON) != 0
        return when {
            chord -> 0
            unicodeChar < 32 || unicodeChar in 127 until 160 -> 0
            unicodeChar <= 255 -> unicodeChar
            unicodeChar in 0xD800..0xDFFF -> 0
            else -> 0x01000000 or unicodeChar
        }
    }
}
