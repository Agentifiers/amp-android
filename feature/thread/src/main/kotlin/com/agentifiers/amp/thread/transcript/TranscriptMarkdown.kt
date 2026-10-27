package com.agentifiers.amp.thread.transcript

import com.agentifiers.amp.model.ImageSource
import com.agentifiers.amp.model.MarkdownPart
import com.agentifiers.amp.model.Step
import com.agentifiers.amp.model.TranscriptView
import com.agentifiers.amp.model.TurnEnd
import com.agentifiers.amp.model.UserBlock
import com.agentifiers.amp.thread.toolcards.toolLabel
import com.agentifiers.amp.thread.toolcards.toolSummary

/** Copy as Markdown and Export: the loaded transcript rendered on the device, prompts and replies with one line per tool call. */
fun transcriptMarkdown(title: String?, view: TranscriptView.Ready): String = buildString {
    append("# ").append(title ?: "Untitled thread").append("\n")
    for (turn in view.turns) {
        turn.prompt?.let { prompt ->
            append("\n## User\n\n")
            for (block in prompt.blocks) when (block) {
                is UserBlock.Text -> append(block.markdown.trim()).append("\n")
                is UserBlock.Image -> append("![").append(block.sourcePath ?: "image").append("](")
                    .append((block.source as? ImageSource.Url)?.url ?: "inline image").append(")\n")
            }
        }
        if (turn.steps.isNotEmpty()) append("\n## Assistant\n\n")
        for (step in turn.steps) when (step) {
            is Step.Text -> append(MarkdownPart.withoutFences(step.markdown).trim()).append("\n\n")
            is Step.Thinking -> Unit
            is Step.Tools -> {
                for (call in step.calls) {
                    val summary = toolSummary(call)
                    append("- **").append(toolLabel(call)).append("**")
                    if (summary.isNotEmpty()) append(" `").append(summary.replace('`', '\'')).append("`")
                    append(" (").append(call.run.kind.word).append(")\n")
                }
                append("\n")
            }
            is Step.Manual -> append("```\n$ ").append(step.command).append("\n").append(manualOutput(step.run)).append("```\n\n")
        }
        if (turn.end == TurnEnd.Interrupted) append("_Interrupted_\n")
    }
}.trimEnd() + "\n"
