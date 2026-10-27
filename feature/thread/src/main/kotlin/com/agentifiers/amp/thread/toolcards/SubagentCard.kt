package com.agentifiers.amp.thread.toolcards

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.markdown.Markdown
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolRun

enum class SubagentKind { Task, Oracle, Librarian, Stop }

/** [task] is what the agent asked for; [answer] is the subagent's Markdown reply once it finishes. */
data class SubagentCall(val kind: SubagentKind, val title: String?, val task: String?, val answer: String?)

object SubagentCard : ToolCard<SubagentCall> {
    /** Librarian answers run long (Amp's docs), so the card opens on their first lines. */
    const val LIBRARIAN_PREVIEW_LINES = 12

    override val names = listOf("Task", "oracle", "librarian", "subagent_stop")

    override fun decode(call: ToolCall): SubagentCall? {
        val input = call.input.value
        val result = call.run.result
        val answer = result.prose()?.trim() ?: result.text("message")
        return when (call.name.raw) {
            "Task" -> {
                val description = input.text("description")
                val prompt = input.text("prompt")
                if (description == null && prompt == null) return null
                SubagentCall(SubagentKind.Task, description, prompt ?: description, answer)
            }
            "oracle" -> SubagentCall(SubagentKind.Oracle, null, input.text("task") ?: return null, answer)
            "librarian" -> {
                val query = input.text("query")
                val context = input.text("context")
                val task = if (query != null && context != null) "Context: $context\n\nQuery: $query" else query ?: context ?: return null
                SubagentCall(SubagentKind.Librarian, query, task, answer)
            }
            "subagent_stop" -> SubagentCall(SubagentKind.Stop, null, null, answer)
            else -> null
        }
    }

    override fun summary(call: SubagentCall, run: ToolRun): String =
        (call.title ?: call.task ?: call.answer)?.let(::firstLine).orEmpty()

    @Composable
    override fun Body(call: SubagentCall, run: ToolRun, ctx: ToolCardContext) {
        call.task?.let { Markdown(it, highlight = ctx.highlight, onLink = ctx.openUrl) }
        val answer = call.answer ?: return
        val lines = answer.lines()
        var showAll by rememberSaveable(answer) { mutableStateOf(false) }
        val collapsed = call.kind == SubagentKind.Librarian && lines.size > LIBRARIAN_PREVIEW_LINES && !showAll
        Text("Answer", color = AmpTheme.colors.text.inkMuted)
        Markdown(if (collapsed) lines.take(LIBRARIAN_PREVIEW_LINES).joinToString("\n") else answer, highlight = ctx.highlight, onLink = ctx.openUrl)
        if (collapsed) AmpButton("Show All", { showAll = true }, ThreadTags.toolShowAll, rank = AmpButtonRank.Link, trailingIcon = AmpIcons.ChevronDown)
    }
}
