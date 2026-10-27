package com.agentifiers.amp.sync.internal.terminal

import com.agentifiers.amp.sync.api.TerminalFailure
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.serialization.json.JsonObject

/** Who runs the thread's executor, from `executor_status.details.executionEnvironment`, as the web reads it (a78eb0b8.js:660297). */
internal sealed interface ExecutorKind {
    data class Runner(val runnerId: String?) : ExecutorKind
    data object CliSession : ExecutorKind
    data object Unknown : ExecutorKind

    companion object {
        fun of(environment: JsonObject): ExecutorKind {
            val provider = environment.string("provider")
            return when {
                provider == "runner" || "runnerAssociation" in environment ->
                    Runner(environment.string("environmentId")?.takeIf { it.startsWith(RUNNER_PREFIX) }?.removePrefix(RUNNER_PREFIX))
                provider == null -> CliSession
                else -> Unknown
            }
        }

        private const val RUNNER_PREFIX = "runner:"
    }
}

internal sealed interface ExitVerdict {
    /** Reopen after 3 s while a viewer is present, otherwise on the next attach. */
    data object Retry : ExitVerdict
    data class Final(val failure: TerminalFailure) : ExitVerdict
}

/** terminal.md, "What the exit error means", after the web's rules (a78eb0b8.js:659803-660297). */
internal fun classifyExit(error: String?, executor: ExecutorKind): ExitVerdict = when {
    error == null -> ExitVerdict.Retry
    error.startsWith("Terminal access is disabled") || error == "Remote control terminal is disabled" ||
        error.startsWith("Remote terminal control is disabled") || error.startsWith("Remote terminal access is disabled") ->
        ExitVerdict.Final(TerminalFailure.Disabled(disabledMessage(executor)))
    error.endsWith("requires sudo passkey authentication") -> ExitVerdict.Final(TerminalFailure.PasskeyRequired)
    error.startsWith("tmux is required for the Amp terminal but was not found on this machine") -> ExitVerdict.Final(TerminalFailure.TmuxMissing)
    error.startsWith("Unable to start terminal session") -> ExitVerdict.Final(TerminalFailure.StartFailed(error))
    else -> ExitVerdict.Retry
}

/** terminal.md, "Runner terminal disabled": the web's text with Retry in place of reloading the page. */
private fun disabledMessage(executor: ExecutorKind): String = when (executor) {
    is ExecutorKind.Runner ->
        "Terminal access is off on the runner${executor.runnerId?.let { " $it" }.orEmpty()}. If the Amp app runs this runner, turn on " +
            "Allow Terminal Access in the app's Settings > Runner. If you started it yourself, restart it with " +
            "amp --no-tui --remote-control-terminal. Then tap Retry. You can continue using this thread."
    ExecutorKind.CliSession ->
        "Terminal access is off for this thread. The thread runs in the amp you started in a terminal window, so the Amp app's " +
            "Allow Terminal Access setting does not affect it. Quit amp in that window, start it again with " +
            "amp --remote-control-terminal, and tap Retry. You can continue using this thread."
    ExecutorKind.Unknown ->
        "Terminal access is disabled. Restart Amp with --remote-control-terminal, for example amp --no-tui --remote-control-terminal, " +
            "then tap Retry. AMP_REMOTE_CONTROL_TERMINAL=1 also works."
}
