package com.agentifiers.amp.terminal

import android.view.KeyEvent
import com.agentifiers.amp.model.TerminalSessionKey
import com.agentifiers.amp.vt.VtKey
import com.agentifiers.amp.vt.VtMods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalRulesTest {
    @Test
    fun gridFitsTheLayoutPrototypesColumns() {
        // 360dp pane at density 2.0 is 672px of grid after 24dp of padding; a 14px cell gives the measured 48 columns.
        assertEquals(GridSize(48, 28), gridSize(672f, 900f, 14f, 32f))
        assertEquals(false, gridSize(27f, 900f, 14f, 32f).usable)
        assertEquals(false, gridSize(672f, 31f, 14f, 32f).usable)
    }

    @Test
    fun latchLocksOnADoubleTapAndReleasesAfterOneKey() {
        val once = Latch().tap(1_000)
        assertEquals(Latch.State.Once, once.state)
        assertEquals(Latch.State.Off, once.used().state)
        val locked = once.tap(1_300)
        assertEquals(Latch.State.Locked, locked.state)
        assertEquals(Latch.State.Locked, locked.used().state)
        assertEquals(Latch.State.Off, locked.tap(5_000).state)
        assertEquals(Latch.State.Off, Latch().tap(1_000).tap(1_301).state)
    }

    @Test
    fun multiLinePastesCountTheLinesTheyRun() {
        assertEquals(3, pasteLineCount("one\ntwo\nthree"))
        assertEquals(1, pasteLineCount("ls -la\n"))
        assertEquals(2, pasteLineCount("a\r\nb"))
        assertEquals(1, pasteLineCount("pwd"))
    }

    @Test
    fun hardwareKeysMapToTheTerminalOrTheApp() {
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        assertEquals(KeyAction.Send(VtKey.Char('r'.code), VtMods(ctrl = true)), keyAction(KeyEvent.KEYCODE_R, ctrl, 'r'.code, 'r'.code))
        assertEquals(KeyAction.Shortcut(TerminalShortcut.Paste), keyAction(KeyEvent.KEYCODE_V, ctrl or shift, 'V'.code, 'V'.code))
        assertEquals(KeyAction.Pass, keyAction(KeyEvent.KEYCODE_P, ctrl or shift, 'P'.code, 'P'.code))
        assertEquals(KeyAction.Shortcut(TerminalShortcut.Copy), keyAction(KeyEvent.KEYCODE_C, KeyEvent.META_META_ON, 'c'.code, 'c'.code))
        assertEquals(KeyAction.Pass, keyAction(KeyEvent.KEYCODE_H, KeyEvent.META_META_ON, 'h'.code, 'h'.code))
        assertEquals(KeyAction.Send(VtKey.Named.Tab, VtMods(shift = true)), keyAction(KeyEvent.KEYCODE_TAB, shift, 0, 0))
        val altGr = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
        assertEquals(KeyAction.Send(VtKey.Char('@'.code), VtMods.None), keyAction(KeyEvent.KEYCODE_Q, altGr, 'q'.code, '@'.code))
        val leftAlt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        assertEquals(KeyAction.Send(VtKey.Char('b'.code), VtMods(alt = true)), keyAction(KeyEvent.KEYCODE_B, leftAlt, 'b'.code, 'b'.code))
        assertEquals(KeyAction.Pass, keyAction(KeyEvent.KEYCODE_SHIFT_LEFT, shift, 0, 0))
    }

    @Test
    fun tabNumbersUseTheWebsSessionKeys() {
        assertNull(TerminalTabNumber.of(1)?.key)
        assertEquals("tab-3", TerminalTabNumber.of(3)?.key?.raw)
        assertEquals(4, TerminalTabNumber.of(TerminalSessionKey.parse("tab-4"))?.value)
        assertNull(TerminalTabNumber.of(TerminalSessionKey.parse("external-agent")))
        assertNull(TerminalTabNumber.of(6))
        assertEquals("Terminal 2", TerminalTabNumber.of(2)?.label(""))
        assertEquals("vim notes.md", TerminalTabNumber.of(1)?.label("vim notes.md"))
    }
}
