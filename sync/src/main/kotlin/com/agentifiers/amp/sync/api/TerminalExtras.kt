package com.agentifiers.amp.sync.api

/** A final `exit` error, classified as the web does (docs/design/terminal.md, "What the exit error means"). */
public sealed interface TerminalFailure {
    /** The runner or CLI was started without remote terminal control; [message] is the fix text to show. */
    public data class Disabled(val message: String) : TerminalFailure
    public data object PasskeyRequired : TerminalFailure
    public data object TmuxMissing : TerminalFailure
    public data class StartFailed(val message: String) : TerminalFailure

    /** No `ready` within 10 s of `open`. */
    public data object NoResponse : TerminalFailure
}

public sealed interface TerminalEvent {
    public data object Bell : TerminalEvent

    /** OSC 52. The UI copies it only when the terminal has focus, and otherwise asks. */
    public data class ClipboardWrite(val text: String) : TerminalEvent

    /** Input typed before `ready` overflowed the 196,608-byte queue and was dropped. */
    public data object InputDropped : TerminalEvent
}

public data class FileStaging(val name: String, val phase: Phase) {
    public enum class Phase { Uploading, Saving }
}
