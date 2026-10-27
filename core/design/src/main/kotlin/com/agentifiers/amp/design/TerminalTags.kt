package com.agentifiers.amp.design

import com.agentifiers.amp.model.MessageId

/** The Terminal pane and live terminal cards (docs/design/terminal.md, "Accessibility and agent-device"). */
object TerminalTags {
    const val status = "terminal-status"
    const val screen = "terminal-screen"
    fun row(index: Int) = "terminal-row-$index"
    const val input = "terminal-input"

    const val softkeys = "terminal-softkeys"
    const val keyEsc = "terminal-key-esc"
    const val keyCtrl = "terminal-key-ctrl"
    const val keyAlt = "terminal-key-alt"
    const val keyTab = "terminal-key-tab"
    const val keyLeft = "terminal-key-left"
    const val keyUp = "terminal-key-up"
    const val keyDown = "terminal-key-down"
    const val keyRight = "terminal-key-right"
    const val keyPipe = "terminal-key-pipe"
    const val keyTilde = "terminal-key-tilde"
    const val keySlash = "terminal-key-slash"
    const val keyCtrlC = "terminal-key-ctrl-c"
    const val select = "terminal-select"
    const val paste = "terminal-paste"
    const val copy = "terminal-copy"
    const val pasteConfirmPaste = "paste-confirm-paste"
    const val pasteConfirmCancel = "paste-confirm-cancel"
    const val osc52Copy = "terminal-osc52-copy"

    /** [number] is the tab's 1-based position; tab 1 has no session key. */
    fun tab(number: Int) = "terminal-tab-$number"
    const val tabNew = "terminal-tab-new"
    fun tabClose(number: Int) = "terminal-tab-close-$number"

    const val reconnecting = "terminal-reconnecting"
    const val empty = "terminal-empty"
    const val emptyNew = "terminal-empty-new"
    const val unavailable = "terminal-unavailable"
    const val retry = "terminal-retry"
    const val openWeb = "terminal-open-web"
    const val upload = "terminal-upload"
    const val uploadProgress = "terminal-upload-progress"
    const val fontSmaller = "terminal-font-smaller"
    const val fontLarger = "terminal-font-larger"

    const val menu = "terminal-menu"
    const val keyboardMode = "terminal-keyboard-mode"
    const val pinKeys = "terminal-pin-keys"
    const val reconnectingClose = "terminal-reconnecting-close"
    const val uploadCancel = "terminal-upload-cancel"
    const val linkOpen = "terminal-link-open"
    const val linkCancel = "terminal-link-cancel"

    fun embed(id: MessageId) = "terminal-embed-${id.raw}"
    fun embedOpen(id: MessageId) = "terminal-embed-open-${id.raw}"
}
