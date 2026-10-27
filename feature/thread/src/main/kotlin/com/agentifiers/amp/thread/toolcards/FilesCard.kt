package com.agentifiers.amp.thread.toolcards

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolRun

enum class FileDirection { Upload, Download }

/**
 * A file moved between the machine ([local], a path the Files tab opens) and the thread's file storage ([remote]).
 * [source] is the other thread a download reads from.
 */
data class FilesCall(val direction: FileDirection, val local: String?, val remote: String?, val source: ThreadId?)

object FilesCard : ToolCard<FilesCall> {
    override val names = listOf("upload_thread_file", "download_thread_file", "download_thread_changes")

    /**
     * Field names from the web's renderers (ampcode.com's web bundle, `function Al` and `function Fl`):
     * upload `{path, destination?}` returns `{remotePath}`, download `{path, destination?, thread?}` returns `{localPath}`.
     */
    override fun decode(call: ToolCall): FilesCall? {
        val input = call.input.value
        val result = call.run.result
        val source = input.text("thread")?.let(ThreadId::parse)
        val files = when (call.name.raw) {
            "upload_thread_file" -> FilesCall(FileDirection.Upload, input.text("path"), result.text("remotePath") ?: input.text("destination"), null)
            "download_thread_file" -> FilesCall(FileDirection.Download, result.text("localPath") ?: input.text("destination"), input.text("path"), source)
            "download_thread_changes" -> FilesCall(FileDirection.Download, result.text("localPath") ?: input.text("destination"), null, source)
            else -> return null
        }
        return files.takeIf { it.local != null || it.remote != null }
    }

    override fun summary(call: FilesCall, run: ToolRun): String {
        val (from, to) = when (call.direction) {
            FileDirection.Upload -> call.local to call.remote
            FileDirection.Download -> call.remote to call.local
        }
        return if (from != null && to != null && from != to) "$from → $to" else (from ?: to).orEmpty()
    }

    @Composable
    override fun Body(call: FilesCall, run: ToolRun, ctx: ToolCardContext) {
        call.local?.let { local ->
            Row(horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                FindText(local, ctx, Modifier.weight(1f), mono = true)
                AmpButton("Open", { ctx.navigate(PaneDestination.Machine(ctx.thread, MachineTab.Files(local))) }, ThreadTags.toolOpenFile)
            }
        }
        call.remote?.let { FindText("Thread file: $it", ctx, mono = true, color = AmpTheme.colors.text.inkMuted) }
        call.source?.let { source ->
            AssistChip(
                onClick = { ctx.navigate(PaneDestination.Thread(source)) },
                label = { Text(source.raw) },
                modifier = Modifier.testTag(ThreadTags.toolThread(source)),
            )
        }
    }
}
