package com.agentifiers.amp.changes

import android.content.ClipData
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import com.agentifiers.amp.model.ChangeScopeId
import com.agentifiers.amp.model.DiffComment
import com.agentifiers.amp.model.DiffSide
import com.agentifiers.amp.model.Draft
import com.agentifiers.amp.model.FileChange
import com.agentifiers.amp.model.FileDiffView
import com.agentifiers.amp.model.FileOrder
import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.SectionKind
import com.agentifiers.amp.model.SendMode
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.diff.DiffEngine
import com.agentifiers.amp.sync.api.Changes
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.ThreadHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val EXPLAIN_FAILED = "Could not explain this selection."

/** The comment being written. [selection] is fixed when the field opens. */
internal data class CommentDraft(val selection: LineSelection, val text: String = "", val error: String? = null, val adding: Boolean = false)

internal sealed interface StageOp {
    data object Running : StageOp
    data class Failed(val message: String) : StageOp
}

/** The pane's local state and every action its controls take. Composables read it and call it; nothing else mutates it. */
@Stable
internal class PaneController(
    val changes: Changes,
    private val thread: ThreadHandle,
    private val threadId: ThreadId,
    private val scope: CoroutineScope,
    private val clipboard: Clipboard,
    private val navigate: (PaneDestination) -> Unit,
) {
    var selection by mutableStateOf<LineSelection?>(null)
        private set
    var draft by mutableStateOf<CommentDraft?>(null)
        private set

    /** Explanations and explain failures, keyed by [rowKey] of file id and hunk index. */
    val explanations = mutableStateMapOf<String, String>()

    /** After one failed Explain the buttons stay hidden for the rest of the session. */
    var explainBroken by mutableStateOf(false)
        private set
    var explaining by mutableStateOf(false)
        private set

    /** Revealed line counts per gap, keyed by [rowKey] of file id and gap index. */
    val revealed = mutableStateMapOf<String, Int>()
    private val expandedOverrides = mutableStateMapOf<String, Boolean>()
    val staging = mutableStateMapOf<String, StageOp>()

    var confirm by mutableStateOf<ConfirmRequest?>(null)
    var openFileId by mutableStateOf<String?>(null)

    /** The path a stage or unstage just moved, so the list scrolls it into view in its new section. */
    var moved by mutableStateOf<Pair<String, SectionKind>?>(null)
    var fullLine by mutableStateOf<String?>(null)
    var fileListOpen by mutableStateOf(false)
    var syncOpen by mutableStateOf(false)

    /** The diffs on screen, for Copy and Explain on a gutter selection. */
    var visibleDiffs: Map<String, FileDiffView> = emptyMap()

    private val scrolls = HashMap<String, ScrollState>()

    /** One horizontal scroll per file and side, shared by every line row of that file so the lines stay aligned. */
    fun horizontalScroll(fileId: String, side: DiffSide?): ScrollState = scrolls.getOrPut("$fileId:$side") { ScrollState(0) }

    fun isExpanded(file: FileChange): Boolean = expandedOverrides[file.id] ?: !file.startsCollapsed

    fun setExpanded(file: FileChange, expanded: Boolean) {
        expandedOverrides[file.id] = expanded
    }

    fun setAllExpanded(files: List<FileChange>, expanded: Boolean) {
        files.forEach { expandedOverrides[it.id] = expanded }
    }

    fun setOrder(order: FileOrder) {
        orderRequest(order)?.let(changes::setOrder)
    }

    /** A new scope shows other files, so a selection or an open comment on the old one no longer points anywhere. */
    fun selectScope(id: ChangeScopeId) {
        selection = null
        draft = null
        changes.selectScope(id)
    }

    fun tapLine(fileId: String, path: String, side: DiffSide, line: Int) {
        selection = selection.tap(fileId, path, side, line)
    }

    fun commentOnHunk(fd: FileDiff, hunk: Int) {
        val hunkLines = hunkSelection(fd.file.id, fd.file.path, fd.diff.hunks[hunk])
        selection = hunkLines
        draft = CommentDraft(hunkLines)
    }

    fun startComment() {
        selection?.let { draft = CommentDraft(it) }
    }

    fun editComment(text: String) {
        draft = draft?.copy(text = text, error = null)
    }

    fun cancelComment() {
        draft = null
    }

    fun addComment() {
        val current = draft ?: return
        if (current.adding || current.text.isBlank()) return
        draft = current.copy(adding = true)
        scope.launch {
            val outcome = changes.addComment(current.selection.toDiffSelection(), current.text.trim())
            val failure = failureText(outcome)
            if (failure == null) {
                draft = null
                selection = null
            } else {
                draft = draft?.copy(adding = false, error = failure)
            }
        }
    }

    fun removeComment(id: String) {
        changes.removeComments(listOf(id))
    }

    fun sendComments(comments: List<DiffComment>) {
        val kept = comments.filter { it.text.isNotBlank() }
        if (kept.isEmpty()) return
        thread.dispatch(ThreadIntent.Send(Draft.text(commentBlock(kept)), SendMode.Auto))
        changes.removeComments(kept.map { it.id })
    }

    fun explainHunk(fd: FileDiff, hunk: Int) {
        explain(hunkSelection(fd.file.id, fd.file.path, fd.diff.hunks[hunk]), hunk)
    }

    fun explainSelection() {
        val current = selection ?: return
        val diff = visibleDiffs[current.fileId] as? FileDiffView.Ready ?: return
        explain(current, hunkIndexOf(diff, current.side, current.start))
    }

    private fun explain(target: LineSelection, hunk: Int) {
        if (explaining || explainBroken) return
        explaining = true
        scope.launch {
            val outcome = changes.explain(target.toDiffSelection())
            explaining = false
            if (outcome is Outcome.Ok) {
                explanations[rowKey(target.fileId, hunk)] = outcome.value
            } else {
                explainBroken = true
                explanations[rowKey(target.fileId, hunk)] = EXPLAIN_FAILED
            }
        }
    }

    fun copy(text: String) {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Amp", text))) }
    }

    fun copyHunk(fd: FileDiff, hunk: Int) = copy(DiffEngine.hunkText(fd.diff, hunk))

    fun copySelection() {
        val current = selection ?: return
        val diff = visibleDiffs[current.fileId] as? FileDiffView.Ready ?: return
        copy(selectedText(diff, current))
    }

    fun revealMore(fileId: String, gap: Int) {
        val key = rowKey(fileId, gap)
        revealed[key] = nextReveal(revealed[key] ?: 0)
    }

    fun revealAll(fileId: String, gap: Int) {
        revealed[rowKey(fileId, gap)] = Int.MAX_VALUE
    }

    /** [staged] is the state the file should end in: true stages it, false unstages it. */
    fun stage(file: FileChange, staged: Boolean) {
        if (staging[file.id] == StageOp.Running) return
        staging[file.id] = StageOp.Running
        scope.launch {
            when (val failure = failureText(changes.stage(file.path, staged))) {
                null -> {
                    staging.remove(file.id)
                    moved = file.path to if (staged) SectionKind.Staged else SectionKind.Unstaged
                }
                else -> staging[file.id] = StageOp.Failed(failure)
            }
        }
    }

    fun sendConfirm(request: ConfirmRequest, prompt: String) {
        thread.dispatch(ThreadIntent.RunChangesAction(request.action, prompt))
        if (request.commentIds.isNotEmpty()) changes.removeComments(request.commentIds)
        confirm = null
    }

    fun openInFiles(path: String) = navigate(PaneDestination.Machine(threadId, MachineTab.Files(path)))

    fun revealInFileTree(path: String) = navigate(PaneDestination.Machine(threadId, MachineTab.Files(parentDir(path))))
}
