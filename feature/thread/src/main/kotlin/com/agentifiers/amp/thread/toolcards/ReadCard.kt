package com.agentifiers.amp.thread.toolcards

import androidx.compose.runtime.Composable
import com.agentifiers.amp.design.code.CodeBlock
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolRun

/** [result] is what the tool returned as text: file content, matches, or paths. */
data class ReadCall(val target: ReadTarget, val result: String?)

sealed interface ReadTarget {
    /** `Read`/`read_file {path, read_range?: [start, end]}` and `get_diagnostics {path}`. */
    data class File(val path: String, val lines: IntRange?) : ReadTarget

    /** `finder {query}`, `Grep`/`ripgrep {pattern, path?}`, `glob`/`Glob {filePattern}`. */
    data class Search(val query: String, val path: String?) : ReadTarget
}

object ReadCard : ToolCard<ReadCall> {
    override val names = listOf("read_file", "Read", "finder", "ripgrep", "Grep", "glob", "Glob", "get_diagnostics")

    override fun decode(call: ToolCall): ReadCall? {
        val input = call.input.value
        val target = when (call.name.raw) {
            "read_file", "Read", "get_diagnostics" -> ReadTarget.File(input.text("path") ?: return null, lineRange(input["read_range"].list))
            "finder" -> ReadTarget.Search(input.text("query") ?: return null, null)
            "ripgrep", "Grep" -> ReadTarget.Search(input.text("pattern") ?: return null, input.text("path"))
            "glob", "Glob" -> ReadTarget.Search(input.text("filePattern") ?: input.text("pattern") ?: return null, input.text("path"))
            else -> return null
        }
        val result = call.run.result
        return ReadCall(target, result.prose() ?: result["content"].string)
    }

    private fun lineRange(range: List<JsonValue>): IntRange? {
        if (range.size != 2) return null
        val start = range[0].int ?: return null
        val end = range[1].int ?: return null
        return if (start >= 1 && end >= start) start..end else null
    }

    override fun summary(call: ReadCall, run: ToolRun): String = when (val target = call.target) {
        is ReadTarget.File -> when (val lines = target.lines) {
            null -> target.path
            else -> if (lines.first == lines.last) "${target.path} L${lines.first}" else "${target.path} L${lines.first}-${lines.last}"
        }
        is ReadTarget.Search -> target.path?.let { "\"${target.query}\" in $it" } ?: "\"${target.query}\""
    }

    @Composable
    override fun Body(call: ReadCall, run: ToolRun, ctx: ToolCardContext) {
        FindText(summary(call, run), ctx, mono = true)
        val language = (call.target as? ReadTarget.File)?.path?.let(::languageOf)
        call.result?.let { CodeBlock(it, language, highlight = ctx.highlight, collapseAfterLines = 40) }
    }
}
