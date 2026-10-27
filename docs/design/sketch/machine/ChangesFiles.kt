// :feature:changes  package amp.changes   +  :feature:files  package amp.files
// Changes and Files panes. Data comes from Machine.changes / Machine.files (:sync), which hide:
//  - runner/any connected executor: client_git_command status|head|diff_range_snapshot|file_diff|
//    stage_file|unstage_file and client_git_diff_snapshot (spike S7 VERIFIED); refresh on
//    executor_workspace_maybe_changed.
//  - orb threads, executor asleep: GET diff-captures/latest (ETag) + blob/<sha>/raw (cli, NOT
//    VERIFIED: spike could not start an orb) → client-side diff; on failure ChangesView.Unavailable
//    with [Wake orb to load live changes] (explicit, because waking bills).
//  - intelligent order: client_changes_file_order → sections (≤ 42), blue dot when a new order is ready.
package amp.changes

import amp.model.*
import androidx.compose.runtime.Composable

/** The Changes tab. The tab strip around it is :app's MachinePane; this file renders only its own tab. */
@Composable
fun ChangesTab(dest: PaneDestination.Machine, tab: MachineTab.Changes, scope: amp.sync.api.AccountScope): Unit = TODO(
    "scope.thread(dest.thread).machine.changes.view → ChangesPane; focusPath scrolls to the file"
)

/**
 * Wide slot (≥ 600dp, e.g. V3's 714dp): file list left, diff right, split view optional.
 * Narrow slot (360dp third, cover): file list → tap → diff (stack).
 * Diff body: LazyColumn over FileDiffView.Ready lines; each line is ONE merged semantics node
 * (line numbers + text), so a 3,000-line diff composes ~60 nodes on screen.
 * Hunk header row has real buttons: [Explain] [Comment] [Copy]. Selecting lines → DiffSelection.
 * Action bar: [Ship] (label from ShipBehavior; confirm sheet), [Review], [Restack], [Rebase];
 * each dispatches ThreadIntent.RunChangesAction (a queued prompt, as on the web:
 * "Ship, Review, and Amp-written prompts always queue", core.md:225). No custom buttons: their
 * source, getChangesPrompts, is web-remote. Staging is Changes.stage, a direct call that needs a live socket.
 * Drag a file path or hunk out as text/plain (DRAG_FLAG_GLOBAL) for another app or slot.
 */
@Composable
fun ChangesPane(view: ChangesView, onIntent: (ThreadIntent) -> Unit, onOpenFile: (String) -> Unit): Unit = TODO()

@Composable
fun DiffView(diff: FileDiffView, onSelect: (DiffSelection) -> Unit): Unit = TODO()

// ── :feature:files (its own file and package amp.files in the build) ─────────

@Composable
fun FilesTab(dest: PaneDestination.Machine, tab: MachineTab.Files, scope: amp.sync.api.AccountScope): Unit = TODO(
    "scope.thread(dest.thread).machine.files.tree(tab.path ?: \"/\") → FilesPane"
)

/**
 * Files over client_filesystem_read_directory_v2 / read_file_v2 / write_file_v3, VERIFIED in spike S19
 * (s19-files.jsonl:36-73): paths may be relative to the workspace root; directory entries are
 * positional tuples [name, kind, {mtimeMs}|null], so tree rows show name, kind and modified time and no
 * size; reads return contentBase64 with no size; results settle by requestId on the
 * client_filesystem_*_result notification (RequestCorrelator). A v3 write with overwrite:false onto an
 * existing file returns ALREADY_EXISTS (:73), which the upload sheet turns into [Replace] / [Keep both].
 * Uploads are direct calls that need a live socket; a lost ack shows Unknown.
 * Reads: `range` reads are rejected (-32602, :65), so every read fetches the whole file, one read at a
 * time per thread, and a decoded result over 4 MiB is refused with a message. A file whose name or
 * kind suggests a large binary gets a warning before it opens. getThreadFileURLs answered
 * permission-denied on a runner thread (:76-78) and is not used: download and share-out read the file
 * over the socket and write it through SAF CreateDocument or ACTION_SEND via FileProvider. Upload
 * sources: SAF picker, camera, share-in, drag-and-drop from other windows (requestDragAndDropPermissions).
 */
@Composable
fun FilesPane(root: FileNode, onOpen: (FileNode.File) -> Unit, onUpload: () -> Unit): Unit = TODO()
