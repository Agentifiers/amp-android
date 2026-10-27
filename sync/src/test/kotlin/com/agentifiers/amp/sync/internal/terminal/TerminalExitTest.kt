package com.agentifiers.amp.sync.internal.terminal

import com.agentifiers.amp.sync.api.TerminalFailure
import com.agentifiers.amp.sync.internal.wire.AmpJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalExitTest {
    private val runner = ExecutorKind.Runner("android-spike")
    private val unknownDisabled = TerminalFailure.Disabled(
        "Terminal access is disabled. Restart Amp with --remote-control-terminal, for example amp --no-tui --remote-control-terminal, " +
            "then tap Retry. AMP_REMOTE_CONTROL_TERMINAL=1 also works.",
    )

    @Test
    fun classifiesEveryRowOfTheExitTable() {
        val table = listOf(
            null to ExitVerdict.Retry,
            "tmux client exited with status 1" to ExitVerdict.Retry,
            "Terminal access is disabled for this runner" to ExitVerdict.Final(unknownDisabled),
            "Remote control terminal is disabled" to ExitVerdict.Final(unknownDisabled),
            "Remote control terminal is disabled." to ExitVerdict.Retry,
            "Remote terminal control is disabled on this machine" to ExitVerdict.Final(unknownDisabled),
            "Remote terminal access is disabled" to ExitVerdict.Final(unknownDisabled),
            "sudo for alice requires sudo passkey authentication" to ExitVerdict.Final(TerminalFailure.PasskeyRequired),
            "tmux is required for the Amp terminal but was not found on this machine (PATH=/usr/bin)" to ExitVerdict.Final(TerminalFailure.TmuxMissing),
            "Unable to start terminal session: spawn failed" to ExitVerdict.Final(TerminalFailure.StartFailed("Unable to start terminal session: spawn failed")),
        )

        assertEquals(table, table.map { (error, _) -> error to classifyExit(error, ExecutorKind.Unknown) })
    }

    @Test
    fun theDisabledMessageNamesTheRunnerOrTheCliSession() {
        assertEquals(
            ExitVerdict.Final(
                TerminalFailure.Disabled(
                    "Terminal access is off on the runner android-spike. If the Amp app runs this runner, turn on Allow Terminal Access " +
                        "in the app's Settings > Runner. If you started it yourself, restart it with amp --no-tui --remote-control-terminal. " +
                        "Then tap Retry. You can continue using this thread.",
                ),
            ),
            classifyExit("Remote control terminal is disabled", runner),
        )
        assertEquals(
            ExitVerdict.Final(
                TerminalFailure.Disabled(
                    "Terminal access is off for this thread. The thread runs in the amp you started in a terminal window, so the Amp app's " +
                        "Allow Terminal Access setting does not affect it. Quit amp in that window, start it again with " +
                        "amp --remote-control-terminal, and tap Retry. You can continue using this thread.",
                ),
            ),
            classifyExit("Remote control terminal is disabled", ExecutorKind.CliSession),
        )
    }

    @Test
    fun readsTheExecutorKindFromTheS08ExecutionEnvironment() {
        val s08 = AmpJson.parseToJsonElement(
            """{"environmentId":"runner:android-spike","environmentResource":null,"stage":"headless_ready","provider":"runner","runnerAssociation":"explicit"}""",
        ) as JsonObject

        assertEquals(runner, ExecutorKind.of(s08))
        assertEquals(ExecutorKind.CliSession, ExecutorKind.of(AmpJson.parseToJsonElement("""{"environmentId":"local:abc"}""") as JsonObject))
        assertEquals(ExecutorKind.Unknown, ExecutorKind.of(AmpJson.parseToJsonElement("""{"provider":"orb"}""") as JsonObject))
    }

    @Test
    fun cleansFileNamesAsTheWebDoes() {
        assertEquals(
            listOf("my-photo-1-.png", "notes.md", "image.png", "file.pdf", "image", "a".repeat(64)),
            listOf(
                cleanFileName("my photo (1).png", "image/png"),
                cleanFileName("..-notes.md", "text/markdown"),
                cleanFileName("", "image/png"),
                cleanFileName("...", "application/pdf"),
                cleanFileName("☃", "image/x-unknown"),
                cleanFileName("a".repeat(80), "text/plain"),
            ),
        )
    }
}
