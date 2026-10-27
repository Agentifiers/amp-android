// :core:model  package amp.model
// Domain types for the machine panes: Changes, Files, Portals, Desktop displays.
package amp.model

import kotlinx.collections.immutable.PersistentList
import kotlin.time.Instant

// ── Changes ───────────────────────────────────────────────────────────────────

/** Where a change set came from. Live git for any connected executor (S7 verified); captures for orbs. */
sealed interface ChangeSource {
    data class LiveGit(val head: String, val base: String) : ChangeSource
    data class Capture(val capturedAt: Instant, val etag: String) : ChangeSource
}

sealed interface ChangesView {
    data object Loading : ChangesView
    data class Unavailable(val reason: UnavailableReason) : ChangesView
    data class Ready(
        val source: ChangeSource,
        val repos: PersistentList<RepoChanges>,          // multi-repo projects group by repo (projects-orbs.md:65)
        val order: FileOrder,
        val actions: PersistentList<ChangesAction>,      // Ship label/confirm from project ShipBehavior
        val stale: Boolean,                              // executor_workspace_maybe_changed since load
    ) : ChangesView
}
data class RepoChanges(val repo: RepoRef, val branch: String, val ahead: Int, val behind: Int, val sections: PersistentList<ChangeSection>)
data class ChangeSection(val title: String, val kind: SectionKind, val files: PersistentList<FileChange>, val tour: String?)
enum class SectionKind { Uncommitted, Committed, Ordered }

data class FileChange(
    val id: String,
    val path: String,
    val change: ChangeKind,
    val stat: DiffStat,
    val binary: Boolean,
    val muted: Boolean,           // generated code muted by intelligent order
    val staged: StageState,
)
sealed interface ChangeKind {
    data object Added : ChangeKind
    data object Modified : ChangeKind
    data object Deleted : ChangeKind
    data class Renamed(val from: String) : ChangeKind
}
data class DiffStat(val added: Int, val deleted: Int)
enum class StageState { Unstaged, Staged, Partial, NotApplicable }

sealed interface FileOrder {
    data object Alphabetical : FileOrder
    data class Intelligent(val sectionsReady: Boolean, val updateAvailable: Boolean) : FileOrder  // blue dot
    data class Disabled(val reason: String) : FileOrder   // > 500 files or read-only (core.md:218)
}

/** A diff, line-virtualized. Hunks hold line ranges into a shared line store, not strings per hunk. */
sealed interface FileDiffView {
    data object Loading : FileDiffView
    data class TooLarge(val bytes: Long) : FileDiffView
    data object Binary : FileDiffView
    data class Ready(val path: String, val hunks: PersistentList<Hunk>, val lineCount: Int) : FileDiffView {
        fun line(index: Int): DiffLine = TODO("index into the shared line store")
    }
}
data class Hunk(val header: String, val firstLine: Int, val lineCount: Int, val oldStart: Int, val newStart: Int)
data class DiffLine(val kind: DiffLineKind, val oldNo: Int?, val newNo: Int?, val text: String)
enum class DiffLineKind { Context, Added, Removed, NoNewline }

data class DiffSelection(val path: String, val side: DiffSide, val startLine: Int, val endLine: Int)
enum class DiffSide { Old, New }

// ── Files ─────────────────────────────────────────────────────────────────────

sealed interface FileNode {
    val path: String
    data class Dir(override val path: String, val children: Children) : FileNode
    /** Directory entries carry only mtimeMs; reads return no size (s19-files.jsonl:36, :48, measured). */
    data class File(override val path: String, val modifiedAt: Instant?) : FileNode
}
sealed interface Children {
    data object NotLoaded : Children
    data object Loading : Children
    data class Loaded(val nodes: PersistentList<FileNode>) : Children
    data class Failed(val reason: String) : Children
}
sealed interface FileContent {
    data class Text(val path: String, val text: String, val language: String) : FileContent
    data class Image(val path: String, val bytes: ByteArray) : FileContent
    data class Binary(val path: String, val sizeBytes: Long) : FileContent
}

// ── Portals ───────────────────────────────────────────────────────────────────

data class Portal(val port: Int, val url: String, val name: String, val public: Boolean)

// ── Desktop ───────────────────────────────────────────────────────────────────

data class DesktopDisplay(val id: String, val name: String, val widthPx: Int, val heightPx: Int)
enum class DesktopTarget { Orb, Mac }
