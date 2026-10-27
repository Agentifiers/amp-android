package com.agentifiers.amp.thread.toolcards

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.code.CodeBlock
import com.agentifiers.amp.design.code.UnifiedDiff
import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolRun

/** [path] is the file the Changes tab scrolls to; for a patch, the first file it names. */
data class EditCall(val path: String, val change: EditChange)

sealed interface EditChange {
    /** `edit_file {path, old_str, new_str}`; [diff] is the result's fenced unified diff once the edit is done. */
    data class Replace(val oldText: String, val newText: String, val diff: String?) : EditChange

    /** `create_file` and `write_file {path, content}`. */
    data class Create(val content: String) : EditChange

    /** `apply_patch {patchText}` (older calls: `patch`), which may touch several [files]. */
    data class Patch(val patch: String, val files: List<String>) : EditChange

    data object Delete : EditChange
    data object Undo : EditChange
}

object EditCard : ToolCard<EditCall> {
    override val names = listOf("edit_file", "apply_patch", "create_file", "write_file", "delete_file", "undo_edit")

    private val PATCH_FILE = Regex("^\\*\\*\\* (?:Add|Delete|Update) File: (.+)$", RegexOption.MULTILINE)

    override fun decode(call: ToolCall): EditCall? {
        val input = call.input.value
        val path = input.text("path")
        return when (call.name.raw) {
            "edit_file" -> EditCall(
                path ?: return null,
                EditChange.Replace(
                    input["old_str"].string ?: return null,
                    input["new_str"].string ?: return null,
                    call.run.result["diff"].string,
                ),
            )
            "create_file", "write_file" -> EditCall(path ?: return null, EditChange.Create(input["content"].string ?: return null))
            "apply_patch" -> {
                val patch = input["patchText"].string ?: input["patch"].string ?: return null
                val files = PATCH_FILE.findAll(patch).map { it.groupValues[1].trim() }.distinct().toList()
                EditCall(path ?: files.firstOrNull() ?: return null, EditChange.Patch(patch, files))
            }
            "delete_file" -> EditCall(path ?: return null, EditChange.Delete)
            "undo_edit" -> EditCall(path ?: return null, EditChange.Undo)
            else -> null
        }
    }

    override fun summary(call: EditCall, run: ToolRun): String = when (val change = call.change) {
        is EditChange.Replace -> {
            val (added, removed) = change.diff?.let(::diffCounts) ?: (change.newText.lines().size to change.oldText.lines().size)
            "${call.path} · +$added -$removed"
        }
        is EditChange.Create -> "${call.path} · ${count(change.content.trimEnd('\n').lines().size, "line")}"
        is EditChange.Patch -> if (change.files.size > 1) "${call.path} and ${change.files.size - 1} more" else call.path
        EditChange.Delete, EditChange.Undo -> call.path
    }

    @Composable
    override fun Body(call: EditCall, run: ToolRun, ctx: ToolCardContext) {
        FindText(call.path, ctx, mono = true)
        when (val change = call.change) {
            is EditChange.Replace -> UnifiedDiff(change.diff ?: replacementDiff(call.path, change), Modifier.fillMaxWidth(), ctx.highlight)
            is EditChange.Create -> CodeBlock(change.content, languageOf(call.path), highlight = ctx.highlight, collapseAfterLines = 40)
            is EditChange.Patch -> UnifiedDiff(change.patch, Modifier.fillMaxWidth(), ctx.highlight)
            EditChange.Delete, EditChange.Undo -> Unit
        }
        AmpButton("Open in Changes", { ctx.navigate(PaneDestination.Machine(ctx.thread, MachineTab.Changes(call.path))) }, ThreadTags.toolOpenChanges)
    }
}

/** Added and removed lines in a unified diff, headers excluded. */
internal fun diffCounts(diff: String): Pair<Int, Int> {
    val body = diff.lineSequence().dropWhile { !it.startsWith("@@") }
    return body.count { it.startsWith("+") } to body.count { it.startsWith("-") }
}

/** The edit as a diff before the result's diff arrives. */
private fun replacementDiff(path: String, change: EditChange.Replace): String = buildString {
    append("--- ").append(path).append("\n+++ ").append(path).append("\n@@ @@\n")
    change.oldText.lines().forEach { append('-').append(it).append('\n') }
    change.newText.lines().forEach { append('+').append(it).append('\n') }
}
