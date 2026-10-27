package com.agentifiers.amp.model

import kotlinx.collections.immutable.PersistentList
import kotlin.time.Instant

// Changes (docs/design/git-and-diffs.md, "The diff model")

/** Where a change set came from, shown as "Live", "Recorded" plus the time, or "As of" plus the time. */
sealed interface ChangeSource {
    data class LiveGit(val head: String) : ChangeSource
    data class Capture(val capturedAt: Instant, val etag: String) : ChangeSource
    data class Cached(val capturedAt: Instant) : ChangeSource
}

sealed interface ChangesView {
    data object Loading : ChangesView

    /** No source answered and nothing is cached: "Runner offline", "Orb paused", "No recorded changes yet". */
    data class Unavailable(val reason: UnavailableReason, val explanation: String) : ChangesView

    /** The executor answered and its folder is not a git repository, so there are no changes to show; not an outage. */
    data object NoRepository : ChangesView

    /** INVALID_ARGS or INTERNAL_ERROR with nothing to show: "Could not load changes." plus [message] and Refresh. */
    data class Failed(val message: String) : ChangesView

    data class Ready(
        val source: ChangeSource,
        val scopes: PersistentList<ChangeScope>,
        val scope: ChangeScopeId,
        val repos: PersistentList<RepoChanges>,
        val order: FileOrder,
        val facts: ChangesFacts,
        /** Staging needs a connected executor, so it is false on a capture or the cache. */
        val canStage: Boolean,
        /** executor_workspace_maybe_changed arrived and the refresh has not landed. */
        val stale: Boolean,
        /** Why a cached or older answer is shown, in plain words, such as "Runner offline". */
        val notice: String?,
        /** The failed refresh's own message for the notice's Details, such as the first line of a git error. */
        val noticeDetail: String?,
    ) : ChangesView {
        val files: List<FileChange> get() = repos.flatMap { repo -> repo.sections.flatMap { it.files } }
    }
}

@JvmInline
value class ChangeScopeId(val raw: String)

/** A scope picker option (git-and-diffs.md, "Scope picker"). */
sealed interface ChangeScope {
    val id: ChangeScopeId
    val label: String

    data object Uncommitted : ChangeScope {
        override val id = ChangeScopeId("uncommitted")
        override val label = "Uncommitted"
    }

    data class AllChanges(val mergeBase: String) : ChangeScope {
        override val id = ChangeScopeId("all")
        override val label = "All Changes"
    }

    data class Commit(val hash: String, val shortHash: String, val subject: String, val parent: String, val isHead: Boolean) : ChangeScope {
        override val id = ChangeScopeId("commit-$hash")
        override val label = subject.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: shortHash
    }
}

data class RepoChanges(val name: String, val branch: String?, val ahead: Int, val sections: PersistentList<ChangeSection>)

@JvmInline
value class SectionId(val raw: String)

data class ChangeSection(
    val id: SectionId,
    val kind: SectionKind,
    val diffHash: String,
    val files: PersistentList<FileChange>,
    val summary: String?,
    val codeTour: String?,
) {
    val title: String get() = kind.title
}

enum class SectionKind(val title: String) { Unstaged("Unstaged"), Staged("Staged"), Range("Changes") }

/** One changed file in one section. The same path can sit in Unstaged and Staged, so [id] names the section too. */
data class FileChange(
    val id: String,
    val path: String,
    val change: ChangeKind,
    val stat: DiffStat,
    val binary: Boolean,
    /** Intelligent order did not list it. Muted files start collapsed and dimmed. */
    val muted: Boolean,
    val stage: StageState,
    val oldSize: Long?,
    val newSize: Long?,
    val omittedReason: String?,
) {
    /** Binary files and files over 2,000 changed lines start collapsed (git-and-diffs.md, "File order"). */
    val startsCollapsed: Boolean get() = muted || binary || stat.added + stat.deleted > COLLAPSE_LINES

    /** The row text: the change letter and word, the path, the counts a row shows, and "muted". Status is text, never color alone. */
    val rowText: String
        get() = buildString {
            append(change.letter).append(' ').append(path).append(' ').append(change.word)
            listOfNotNull(stat.addedLabel, stat.deletedLabel).forEach { append(' ').append(it) }
            if (muted) append(" muted")
        }

    companion object {
        const val COLLAPSE_LINES = 2_000
    }
}

/** The eight protocol `changeType` values, each with its letter and word. */
sealed interface ChangeKind {
    val letter: Char
    val word: String

    data object Added : ChangeKind { override val letter = 'A'; override val word = "added" }
    data object Modified : ChangeKind { override val letter = 'M'; override val word = "modified" }
    data object Deleted : ChangeKind { override val letter = 'D'; override val word = "deleted" }
    data class Renamed(val from: String) : ChangeKind { override val letter = 'R'; override val word get() = "renamed from $from" }
    data class Copied(val from: String) : ChangeKind { override val letter = 'C'; override val word get() = "copied from $from" }
    data object TypeChanged : ChangeKind { override val letter = 'T'; override val word = "type changed" }
    data object Unmerged : ChangeKind { override val letter = 'U'; override val word = "conflict" }
    data object Untracked : ChangeKind { override val letter = '?'; override val word = "untracked" }
}

data class DiffStat(val added: Int, val deleted: Int, val changed: Int) {
    /** "+3", or null when nothing was added: a row shows and speaks only the side that changed. */
    val addedLabel: String? get() = "+$added".takeIf { added > 0 }

    /** "−2", or null when nothing was deleted. */
    val deletedLabel: String? get() = "\u2212$deleted".takeIf { deleted > 0 }
}
enum class StageState { Unstaged, Staged, Partial, NotApplicable }

sealed interface FileOrder {
    /** The user picked alphabetical order. No file is muted. */
    data object Alphabetical : FileOrder

    /** Intelligent order is on and its first result has not arrived, so files are alphabetical meanwhile. */
    data object Ordering : FileOrder

    /** [updateAvailable] is the web's blue dot, stated as text by the Apply Updated File Order label. */
    data class Intelligent(val updateAvailable: Boolean) : FileOrder

    /** Over 500 files, a viewer without access, or a failed request. Files are alphabetical. */
    data class Unavailable(val reason: String) : FileOrder
}

/** The target of Ship, Push and Rebase. Unknown until live git reports a base ref; the app never guesses one. */
sealed interface ShipTarget {
    data class Known(val ref: String) : ShipTarget
    data object Unknown : ShipTarget
}

/** What the action buttons and their prompts read about the thread. */
data class ChangesFacts(
    val target: ShipTarget,
    val behavior: ShipBehavior,
    val github: Boolean,
    val branch: String?,
    val baseBranch: String?,
    val hasUncommitted: Boolean,
    val ahead: Int,
    /** A Ship or Push this device sent is queued, pending, or its turn is running. */
    val shipInFlight: Boolean,
    val threadUrl: String?,
    /** `amp sync <thread URL>` for orb threads, null otherwise. */
    val syncCommand: String?,
)

/**
 * A diff, line-virtualized. [lines] is the one line store for the file; hunks are ranges into it. When
 * [expandable], the store holds every line of both sides in order, so the lines between hunks can be revealed.
 */
sealed interface FileDiffView {
    data object Loading : FileDiffView
    data class Binary(val oldSize: Long?, val newSize: Long?) : FileDiffView
    data class TooLarge(val reason: String) : FileDiffView
    data class Omitted(val reason: String) : FileDiffView
    data class Failed(val message: String) : FileDiffView

    data class Ready(
        val path: String,
        val hunks: PersistentList<Hunk>,
        val lines: PersistentList<DiffLine>,
        val expandable: Boolean,
        val language: Language,
    ) : FileDiffView {
        val lineCount: Int get() = lines.size

        fun line(index: Int): DiffLine = lines[index]
    }
}

/** [firstLine] and [lineCount] index [FileDiffView.Ready.lines]. [header] is the text after the second `@@`. */
data class Hunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val header: String,
    val firstLine: Int,
    val lineCount: Int,
) {
    val range: String get() = "@@ -$oldStart,$oldCount +$newStart,$newCount @@" + if (header.isEmpty()) "" else " $header"
}

/** One diff line. [intraline] are changed character ranges, end exclusive; [tokens] are syntax spans. */
data class DiffLine(
    val kind: DiffLineKind,
    val oldNo: Int?,
    val newNo: Int?,
    val text: String,
    val intraline: PersistentList<TextSpan>,
    val tokens: PersistentList<SyntaxSpan>,
)

enum class DiffLineKind(val marker: Char) { Context(' '), Added('+'), Removed('-'), NoNewline('\\') }

data class TextSpan(val start: Int, val end: Int)
data class SyntaxSpan(val start: Int, val end: Int, val kind: SyntaxKind)
enum class SyntaxKind { Keyword, Literal, Comment, Number, Type }

/** The language a file name picks for highlighting. [Plain] is never tokenized. */
enum class Language { Kotlin, Java, Python, JavaScript, TypeScript, Rust, Go, Swift, C, Cpp, Shell, Ruby, Json, Yaml, Toml, Markdown, Html, Css, Sql, Dockerfile, Makefile, Plain }

data class DiffSelection(val path: String, val side: DiffSide, val startLine: Int, val endLine: Int)

/** [wire] is the side name that pending comments and Explain send. */
enum class DiffSide(val wire: String) { Old("deletions"), New("additions") }

/** A pending review comment, sent with the next message (git-and-diffs.md, "Select and comment"). */
data class DiffComment(
    val id: String,
    val selection: DiffSelection,
    val text: String,
    val commit: CommitContext?,
)

data class CommitContext(val hash: String, val subject: String, val repositoryName: String?)

// Files

sealed interface FileNode {
    val path: String

    data class Dir(override val path: String, val children: Children) : FileNode
    /** Directory entries carry only mtimeMs; reads return no size (s19-files.jsonl:36, :48). */
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
    class Image(val path: String, val bytes: ByteArray) : FileContent
    class Binary(val path: String, val bytes: ByteArray) : FileContent {
        val sizeBytes: Long get() = bytes.size.toLong()
    }
}

// Portals

/**
 * One link from the executor's JSON manifests in `.amp/portals/` (Amp's docs).
 * [port] comes from a `-p<port>` host label and is null for custom hostnames. Whether a portal is public is
 * a web-only setting, so the app does not model it.
 */
data class Portal(val url: String, val label: String, val folder: String?, val note: String?, val port: Int?)

// Desktop

data class DesktopDisplay(val id: String, val name: String, val widthPx: Int, val heightPx: Int)
enum class DesktopTarget { Orb, Mac }
