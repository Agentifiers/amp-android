package com.agentifiers.amp.thread.toolcards

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.runtime.Composable
import com.agentifiers.amp.design.code.TerminalOutput
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolRun

data class ShellCall(val target: ShellTarget, val output: String?, val exitCode: Int?)

sealed interface ShellTarget {
    /** `shell_command {command, workdir}`; `run_terminal_command` names them `cmd` and `cwd`. */
    data class Command(val command: String, val workdir: String?) : ShellTarget

    /** `shell_command_status {pid}` and `shell_command_kill {pid}` address a background command. */
    data class Process(val pid: Int) : ShellTarget
}

object ShellCard : ToolCard<ShellCall> {
    override val names = listOf("shell_command", "async_shell_command", "run_terminal_command", "Bash", "shell_command_status", "shell_command_kill")

    override fun decode(call: ToolCall): ShellCall? {
        val input = call.input.value
        val command = input["command"].string ?: input["cmd"].string
        val target = if (command != null) {
            ShellTarget.Command(command, input.text("workdir") ?: input.text("cwd"))
        } else {
            ShellTarget.Process(input["pid"].int ?: return null)
        }
        val payload = call.run.payload
        return ShellCall(target, payload["output"].string ?: payload.string, call.run.result["exitCode"].int)
    }

    override fun summary(call: ShellCall, run: ToolRun): String {
        val subject = when (val target = call.target) {
            is ShellTarget.Command -> firstLine(target.command)
            is ShellTarget.Process -> "PID ${target.pid}"
        }
        return call.exitCode?.let { "$subject · exit $it" } ?: subject
    }

    @Composable
    override fun Body(call: ShellCall, run: ToolRun, ctx: ToolCardContext) {
        when (val target = call.target) {
            is ShellTarget.Command -> {
                FindText("$ ${target.command}", ctx, mono = true)
                target.workdir?.let { FindText(it, ctx, mono = true, color = AmpTheme.colors.text.inkMuted) }
            }
            is ShellTarget.Process -> FindText("PID ${target.pid}", ctx, mono = true)
        }
        call.output?.takeIf { it.isNotBlank() }?.let { TerminalOutput(it, highlight = ctx.highlight) }
        call.exitCode?.let { FindText("exit $it", ctx, mono = true, color = AmpTheme.colors.text.inkMuted) }
    }
}
