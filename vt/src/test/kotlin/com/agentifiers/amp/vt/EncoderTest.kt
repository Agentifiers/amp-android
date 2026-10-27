package com.agentifiers.amp.vt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncoderTest {
    private fun vt(vararg setup: String) = VtEngine(80, 24).apply { setup.forEach { feed(it.toByteArray()) } }

    private fun ByteArray?.text(): String? = this?.let { String(it, Charsets.ISO_8859_1) }

    private val ctrl = VtMods(ctrl = true)
    private val alt = VtMods(alt = true)
    private val shift = VtMods(shift = true)

    @Test
    fun namedKeysInNormalCursorMode() {
        val vt = vt()
        val keys = listOf(
            VtKey.Named.Enter, VtKey.Named.Backspace, VtKey.Named.Tab, VtKey.Named.Esc,
            VtKey.Named.Up, VtKey.Named.Down, VtKey.Named.Right, VtKey.Named.Left, VtKey.Named.Home, VtKey.Named.End,
            VtKey.Named.Ins, VtKey.Named.Del, VtKey.Named.PgUp, VtKey.Named.PgDn,
            VtKey.Named.F1, VtKey.Named.F2, VtKey.Named.F3, VtKey.Named.F4, VtKey.Named.F5, VtKey.Named.F6,
            VtKey.Named.F7, VtKey.Named.F8, VtKey.Named.F9, VtKey.Named.F10, VtKey.Named.F11, VtKey.Named.F12,
        )
        assertEquals(
            listOf(
                "\r", "\u007f", "\t", "\u001b",
                "\u001b[A", "\u001b[B", "\u001b[C", "\u001b[D", "\u001b[H", "\u001b[F",
                "\u001b[2~", "\u001b[3~", "\u001b[5~", "\u001b[6~",
                "\u001bOP", "\u001bOQ", "\u001bOR", "\u001bOS", "\u001b[15~", "\u001b[17~",
                "\u001b[18~", "\u001b[19~", "\u001b[20~", "\u001b[21~", "\u001b[23~", "\u001b[24~",
            ),
            keys.map { vt.encodeKey(it, VtMods.None).text() },
        )
    }

    @Test
    fun applicationCursorModeUsesSs3ForArrowsHomeAndEnd() {
        val vt = vt("\u001b[?1h")
        assertEquals(
            listOf("\u001bOA", "\u001bOD", "\u001bOH", "\u001bOF", "\u001b[1;5A"),
            listOf(
                vt.encodeKey(VtKey.Named.Up, VtMods.None),
                vt.encodeKey(VtKey.Named.Left, VtMods.None),
                vt.encodeKey(VtKey.Named.Home, VtMods.None),
                vt.encodeKey(VtKey.Named.End, VtMods.None),
                vt.encodeKey(VtKey.Named.Up, ctrl),
            ).map { it.text() },
        )
    }

    @Test
    fun modifiedSpecialKeysCarryTheXtermModifierParameter() {
        val vt = vt()
        assertEquals(
            listOf("\u001b[1;6C", "\u001b[1;6D", "\u001b[1;3A", "\u001b[1;2H", "\u001b[5;2~", "\u001b[3;5~", "\u001b[1;5P", "\u001b[24;3~", "\u001b[15;8~", "\u001b[Z"),
            listOf(
                vt.encodeKey(VtKey.Named.Right, VtMods(ctrl = true, shift = true)),
                vt.encodeKey(VtKey.Named.Left, VtMods(ctrl = true, shift = true)),
                vt.encodeKey(VtKey.Named.Up, alt),
                vt.encodeKey(VtKey.Named.Home, shift),
                vt.encodeKey(VtKey.Named.PgUp, shift),
                vt.encodeKey(VtKey.Named.Del, ctrl),
                vt.encodeKey(VtKey.Named.F1, ctrl),
                vt.encodeKey(VtKey.Named.F12, alt),
                vt.encodeKey(VtKey.Named.F5, VtMods(ctrl = true, alt = true, shift = true)),
                vt.encodeKey(VtKey.Named.Tab, shift),
            ).map { it.text() },
        )
    }

    @Test
    fun ctrlAndAltOnCharacters() {
        val vt = vt()
        fun key(c: Char, mods: VtMods) = vt.encodeKey(VtKey.Char(c.code), mods).text()
        assertEquals(
            listOf("\u0003", "\u0003", "\u001a", "\u0000", "\u001b", "\u001c", "\u001d", "\u001e", "\u001f", "\u001bx", "\u001b\u0001", "\u001b\r", "1"),
            listOf(
                key('c', ctrl), key('C', ctrl), key('z', ctrl), key(' ', ctrl), key('[', ctrl), key('\\', ctrl),
                key(']', ctrl), key('^', ctrl), key('_', ctrl), key('x', alt), key('a', VtMods(ctrl = true, alt = true)),
                vt.encodeKey(VtKey.Named.Enter, alt).text(), key('1', ctrl),
            ),
        )
        assertEquals("c3 a9", vt.encodeKey(VtKey.Char('é'.code), VtMods.None).joinToString(" ") { "%02x".format(it) })
    }

    @Test
    fun metaIsNeverSent() {
        val vt = vt()
        assertEquals(0, vt.encodeKey(VtKey.Char('v'.code), VtMods(meta = true)).size)
        assertEquals(0, vt.encodeKey(VtKey.Named.Up, VtMods(meta = true, ctrl = true)).size)
        assertEquals("v", vt.encodeKey(VtKey.Char('v'.code), VtMods.None).text())
    }

    @Test
    fun pasteConvertsNewlinesAndBracketsWhenAsked() {
        assertEquals("a\rb\rc", vt().encodePaste("a\r\nb\nc").text())
        assertEquals("\u001b[200~x\ry\u001b[201~", vt("\u001b[?2004h").encodePaste("x\u001b[201~\ny").text())
        assertEquals("\u001b[200~ab\u001b[201~", vt("\u001b[?2004h").encodePaste("a\u001b[20\u001b[201~1~b").text())
    }

    @Test
    fun mouseIsNullUntilTheApplicationAsks() {
        assertNull(vt().encodeMouse(VtMouse(0, 0, MouseAction.Press, MouseButton.Left)))
        assertNull(vt("\u001b[?1000h").encodeMouse(VtMouse(0, 0, MouseAction.Move, MouseButton.Left)))
        assertNull(vt("\u001b[?1002h").encodeMouse(VtMouse(0, 0, MouseAction.Move, MouseButton.None)))
        assertNull(vt("\u001b[?1000h\u001b[?1000l").encodeMouse(VtMouse(0, 0, MouseAction.Press, MouseButton.Left)))
        assertNull(vt("\u001b[?1000h").encodeMouse(VtMouse(0, 0, MouseAction.Release, MouseButton.WheelUp)))
    }

    @Test
    fun normalMouseEncoding() {
        val click = vt("\u001b[?1000h")
        val drag = vt("\u001b[?1002h")
        val any = vt("\u001b[?1003h")
        assertEquals(
            listOf("\u001b[M !!", "\u001b[M#!!", "\u001b[M\"*%", "\u001b[M`!!", "\u001b[Ma!!", "\u001b[M@!!", "\u001b[MC!!", "\u001b[M4!!"),
            listOf(
                click.encodeMouse(VtMouse(0, 0, MouseAction.Press, MouseButton.Left)),
                click.encodeMouse(VtMouse(0, 0, MouseAction.Release, MouseButton.Left)),
                click.encodeMouse(VtMouse(9, 4, MouseAction.Press, MouseButton.Right)),
                click.encodeMouse(VtMouse(0, 0, MouseAction.Press, MouseButton.WheelUp)),
                click.encodeMouse(VtMouse(0, 0, MouseAction.Press, MouseButton.WheelDown)),
                drag.encodeMouse(VtMouse(0, 0, MouseAction.Move, MouseButton.Left)),
                any.encodeMouse(VtMouse(0, 0, MouseAction.Move, MouseButton.None)),
                click.encodeMouse(VtMouse(0, 0, MouseAction.Press, MouseButton.Left, VtMods(ctrl = true, shift = true))),
            ).map { it.text() },
        )
    }

    @Test
    fun sgrMouseEncoding() {
        val vt = vt("\u001b[?1002h\u001b[?1006h")
        assertEquals(
            listOf("\u001b[<0;10;5M", "\u001b[<0;10;5m", "\u001b[<64;300;5M", "\u001b[<34;1;1M", "\u001b[<24;1;1M"),
            listOf(
                vt.encodeMouse(VtMouse(9, 4, MouseAction.Press, MouseButton.Left)),
                vt.encodeMouse(VtMouse(9, 4, MouseAction.Release, MouseButton.Left)),
                vt.encodeMouse(VtMouse(299, 4, MouseAction.Press, MouseButton.WheelUp)),
                vt.encodeMouse(VtMouse(0, 0, MouseAction.Move, MouseButton.Right)),
                vt.encodeMouse(VtMouse(0, 0, MouseAction.Press, MouseButton.Left, VtMods(ctrl = true, alt = true))),
            ).map { it.text() },
        )
    }

    @Test
    fun focusReportsOnlyWhenEnabled() {
        assertNull(vt().encodeFocus(true))
        val vt = vt("\u001b[?1004h")
        assertEquals(listOf("\u001b[I", "\u001b[O"), listOf(vt.encodeFocus(true).text(), vt.encodeFocus(false).text()))
    }
}
