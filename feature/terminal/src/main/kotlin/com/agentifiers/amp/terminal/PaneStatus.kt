package com.agentifiers.amp.terminal

import com.agentifiers.amp.model.UnavailableReason
import com.agentifiers.amp.sync.api.TerminalFailure
import com.agentifiers.amp.sync.api.TerminalStatus

internal enum class PaneAction { Retry, OpenOnWeb }

/** What the pane draws for a status (terminal.md, "What the pane shows"). */
internal sealed interface PaneView {
    data object Connecting : PaneView
    data object Live : PaneView

    /** The last screen, dimmed, under "Reconnecting terminal" with **Close tab**. */
    data object Reconnecting : PaneView
    data class Notice(val message: String, val actions: List<PaneAction>) : PaneView
}

/** [word] is the `terminal-status` text. */
internal data class PaneStatus(val word: String, val view: PaneView)

private const val UNKNOWN_EXECUTOR_DISABLED =
    "Terminal access is disabled. Restart Amp with --remote-control-terminal, for example amp --no-tui --remote-control-terminal, " +
        "then tap Retry. AMP_REMOTE_CONTROL_TERMINAL=1 also works."

internal fun paneStatus(status: TerminalStatus): PaneStatus = when (status) {
    TerminalStatus.Opening -> PaneStatus("connecting", PaneView.Connecting)
    TerminalStatus.Ready -> PaneStatus("ready", PaneView.Live)
    // Exited means no viewer was present at the exit; this pane is a viewer, so its attach reopens the channel.
    TerminalStatus.Reconnecting, is TerminalStatus.Exited -> PaneStatus("reconnecting", PaneView.Reconnecting)
    is TerminalStatus.Unavailable -> when (status.reason) {
        UnavailableReason.NoExecutor, UnavailableReason.NotOrb ->
            PaneStatus("no-executor", PaneView.Notice("This thread has no machine attached.", emptyList()))
        UnavailableReason.ReadOnlyViewer ->
            PaneStatus("read-only", PaneView.Notice("The terminal needs write access to this thread.", emptyList()))
        UnavailableReason.RunnerFlagMissing, UnavailableReason.PolicyDisabled ->
            PaneStatus("disabled", PaneView.Notice(UNKNOWN_EXECUTOR_DISABLED, listOf(PaneAction.Retry)))
        UnavailableReason.Unknown -> PaneStatus(
            "failed",
            PaneView.Notice("The terminal is not available for this thread.", listOf(PaneAction.Retry, PaneAction.OpenOnWeb)),
        )
    }
    is TerminalStatus.Failed -> when (val failure = status.failure) {
        is TerminalFailure.Disabled -> PaneStatus("disabled", PaneView.Notice(failure.message, listOf(PaneAction.Retry)))
        TerminalFailure.PasskeyRequired ->
            PaneStatus("passkey-required", PaneView.Notice("This terminal requires sudo passkey authentication.", listOf(PaneAction.OpenOnWeb)))
        TerminalFailure.TmuxMissing -> PaneStatus(
            "tmux-missing",
            PaneView.Notice("tmux is required for the Amp terminal but was not found on this machine.", listOf(PaneAction.Retry)),
        )
        is TerminalFailure.StartFailed ->
            PaneStatus("failed", PaneView.Notice(failure.message, listOf(PaneAction.Retry, PaneAction.OpenOnWeb)))
        TerminalFailure.NoResponse ->
            PaneStatus("failed", PaneView.Notice("The terminal did not respond.", listOf(PaneAction.Retry, PaneAction.OpenOnWeb)))
    }
}
