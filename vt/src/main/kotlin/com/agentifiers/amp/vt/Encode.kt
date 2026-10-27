package com.agentifiers.amp.vt

private const val ESC = "\u001b"
private val EMPTY = ByteArray(0)

/** docs/design/terminal.md, "Hardware keyboard and DeX". */
internal fun keyBytes(key: VtKey, mods: VtMods, cursorKeysApplication: Boolean): ByteArray {
    if (mods.meta) return EMPTY
    val text = when (key) {
        is VtKey.Char -> charKey(key.codepoint, mods)
        VtKey.Named.Enter -> altPrefix("\r", mods)
        VtKey.Named.Backspace -> altPrefix("\u007f", mods)
        VtKey.Named.Esc -> altPrefix(ESC, mods)
        VtKey.Named.Tab -> altPrefix(if (mods.shift) "$ESC[Z" else "\t", mods)
        VtKey.Named.Up -> cursorKey('A', mods, cursorKeysApplication)
        VtKey.Named.Down -> cursorKey('B', mods, cursorKeysApplication)
        VtKey.Named.Right -> cursorKey('C', mods, cursorKeysApplication)
        VtKey.Named.Left -> cursorKey('D', mods, cursorKeysApplication)
        VtKey.Named.Home -> cursorKey('H', mods, cursorKeysApplication)
        VtKey.Named.End -> cursorKey('F', mods, cursorKeysApplication)
        VtKey.Named.Ins -> tildeKey(2, mods)
        VtKey.Named.Del -> tildeKey(3, mods)
        VtKey.Named.PgUp -> tildeKey(5, mods)
        VtKey.Named.PgDn -> tildeKey(6, mods)
        VtKey.Named.F1 -> ss3Key('P', mods)
        VtKey.Named.F2 -> ss3Key('Q', mods)
        VtKey.Named.F3 -> ss3Key('R', mods)
        VtKey.Named.F4 -> ss3Key('S', mods)
        VtKey.Named.F5 -> tildeKey(15, mods)
        VtKey.Named.F6 -> tildeKey(17, mods)
        VtKey.Named.F7 -> tildeKey(18, mods)
        VtKey.Named.F8 -> tildeKey(19, mods)
        VtKey.Named.F9 -> tildeKey(20, mods)
        VtKey.Named.F10 -> tildeKey(21, mods)
        VtKey.Named.F11 -> tildeKey(23, mods)
        VtKey.Named.F12 -> tildeKey(24, mods)
    }
    return text.toByteArray(Charsets.UTF_8)
}

private fun charKey(cp: Int, mods: VtMods): String {
    val ctrl = if (mods.ctrl) controlCode(cp) else null
    val body = if (ctrl != null) ctrl.toChar().toString() else String(Character.toChars(cp))
    return altPrefix(body, mods)
}

private fun controlCode(cp: Int): Int? = when (cp) {
    in 'a'.code..'z'.code -> cp - 'a'.code + 1
    in 'A'.code..'Z'.code -> cp - 'A'.code + 1
    ' '.code, '@'.code -> 0
    '['.code -> 0x1b
    '\\'.code -> 0x1c
    ']'.code -> 0x1d
    '^'.code -> 0x1e
    '_'.code -> 0x1f
    else -> null
}

private fun altPrefix(body: String, mods: VtMods) = if (mods.alt) ESC + body else body

/** xterm's modifier parameter: 1 plus 1 for Shift, 2 for Alt, 4 for Ctrl; 1 means none. */
private fun modifierParam(mods: VtMods): Int = 1 + (if (mods.shift) 1 else 0) + (if (mods.alt) 2 else 0) + (if (mods.ctrl) 4 else 0)

private fun cursorKey(final: Char, mods: VtMods, application: Boolean): String {
    val m = modifierParam(mods)
    return when {
        m > 1 -> "$ESC[1;$m$final"
        application -> "${ESC}O$final"
        else -> "$ESC[$final"
    }
}

private fun ss3Key(final: Char, mods: VtMods): String {
    val m = modifierParam(mods)
    return if (m > 1) "$ESC[1;$m$final" else "${ESC}O$final"
}

private fun tildeKey(n: Int, mods: VtMods): String {
    val m = modifierParam(mods)
    return if (m > 1) "$ESC[$n;$m~" else "$ESC[$n~"
}

internal fun pasteBytes(text: String, bracketed: Boolean): ByteArray {
    val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
    if (!bracketed) return normalized.toByteArray(Charsets.UTF_8)
    var body = normalized
    while (body.contains(PASTE_END)) body = body.replace(PASTE_END, "")
    return "$ESC[200~$body$PASTE_END".toByteArray(Charsets.UTF_8)
}

private const val PASTE_END = "$ESC[201~"

internal fun mouseBytes(event: VtMouse, tracking: MouseTracking, sgr: Boolean): ByteArray? {
    val wheel = event.button == MouseButton.WheelUp || event.button == MouseButton.WheelDown
    val reported = when (event.action) {
        MouseAction.Press -> tracking != MouseTracking.Off
        MouseAction.Release -> tracking != MouseTracking.Off && !wheel
        MouseAction.Move -> when (tracking) {
            MouseTracking.Any -> true
            MouseTracking.Drag -> event.button != MouseButton.None && !wheel
            else -> false
        }
    }
    if (!reported) return null
    var code = when (event.button) {
        MouseButton.Left -> 0
        MouseButton.Middle -> 1
        MouseButton.Right -> 2
        MouseButton.None -> 3
        MouseButton.WheelUp -> 64
        MouseButton.WheelDown -> 65
    }
    if (event.action == MouseAction.Move) code += 32
    if (event.mods.shift) code += 4
    if (event.mods.alt) code += 8
    if (event.mods.ctrl) code += 16
    val x = event.col + 1
    val y = event.row + 1
    if (sgr) {
        val final = if (event.action == MouseAction.Release) 'm' else 'M'
        return "$ESC[<$code;$x;$y$final".toByteArray(Charsets.US_ASCII)
    }
    if (event.action == MouseAction.Release) code = (code and 3.inv()) or 3
    if (x + 32 > 255 || y + 32 > 255) return null
    return byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), (32 + code).toByte(), (32 + x).toByte(), (32 + y).toByte())
}

internal fun focusBytes(focused: Boolean, enabled: Boolean): ByteArray? =
    if (!enabled) null else (if (focused) "$ESC[I" else "$ESC[O").toByteArray(Charsets.US_ASCII)
