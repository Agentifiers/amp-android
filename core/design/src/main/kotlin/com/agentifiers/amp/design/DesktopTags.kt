package com.agentifiers.amp.design

/** The Desktop pane. */
object DesktopTags {
    const val surface = "desktop-surface"
    const val status = "desktop-status"
    const val stats = "desktop-stats"
    const val takeOver = "desktop-take-over"
    const val handBack = "desktop-hand-back"
    const val inputMode = "desktop-input-mode"
    const val inputDirect = "desktop-input-direct"
    const val inputTrackpad = "desktop-input-trackpad"
    const val keyboard = "desktop-keyboard"
    const val keyboardInput = "desktop-keyboard-input"
    const val displayPicker = "desktop-display-picker"
    fun display(id: String) = "desktop-display-$id"
    const val sendClipboard = "desktop-send-clipboard"
    const val remoteClipboard = "desktop-remote-clipboard"
    const val copyRemoteClipboard = "desktop-copy-remote-clipboard"
    const val reconnect = "desktop-reconnect"
    const val ended = "desktop-ended"
}
