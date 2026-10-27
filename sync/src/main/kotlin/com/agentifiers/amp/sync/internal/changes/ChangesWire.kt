package com.agentifiers.amp.sync.internal.changes

import com.agentifiers.amp.model.ChangeKind
import com.agentifiers.amp.model.ChangesAction
import com.agentifiers.amp.model.DiffStat
import com.agentifiers.amp.sync.internal.wire.array
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.long
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal const val WORKSPACE_MAYBE_CHANGED = "executor_workspace_maybe_changed"
internal const val GIT_COMMAND = "client_git_command"
internal const val GIT_DIFF_SNAPSHOT = "client_git_diff_snapshot"
internal const val FILE_ORDER = "client_changes_file_order"
internal const val EXPLAIN_SELECTION = "client_explain_diff_selection"

/** `meta.changesWorkflow` for the two actions the web marks (`changesWorkflow:d([`ship`,`push_branch`])`). */
internal fun changesWorkflowOf(action: ChangesAction): String? = when (action) {
    ChangesAction.Ship -> "ship"
    ChangesAction.PushBranch -> "push_branch"
    ChangesAction.Review, ChangesAction.Restack, ChangesAction.Rebase, ChangesAction.FixCi -> null
}

internal fun gitCommand(type: String, vararg extra: Pair<String, String>): JsonObject = buildJsonObject {
    putJsonObject("operation") {
        put("type", type)
        extra.forEach { (key, value) -> put(key, value) }
    }
}

/** The two revisions of one section and what the snapshot request carries. */
internal data class Revisions(val base: String, val head: String, val includeUntracked: Boolean) {
    fun snapshotParams(): JsonObject = buildJsonObject {
        put("baseRevision", base)
        put("headRevision", head)
        put("includeUntracked", includeUntracked)
        put("content", "renderable")
    }
}

/** How a git request settled, from `client_*_result{ok, exitCode?, stdout?, stderr?, error?}`. */
internal sealed interface GitAnswer {
    data class Ok(val body: JsonObject) : GitAnswer {
        val stdout: String get() = body.string("stdout").orEmpty()
    }

    data class Failed(val code: String, val message: String) : GitAnswer
}

/** [GitAnswer.Failed.code] when the executor's folder is not a git repository, which is not an executor failure. */
internal const val NOT_A_REPOSITORY = "NOT_A_REPOSITORY"

internal fun gitAnswerOf(body: JsonObject): GitAnswer {
    val error = body.obj("error")
    val exit = body.long("exitCode")
    val failed = when {
        body.bool("ok") != true || error != null ->
            GitAnswer.Failed(error?.string("code") ?: "INTERNAL_ERROR", error?.string("message") ?: "The executor did not answer.")
        exit != null && exit != 0L ->
            GitAnswer.Failed("GIT_OPERATION_FAILED", body.string("stderr").orEmpty().lineSequence().firstOrNull { it.isNotBlank() } ?: "git exited with $exit")
        else -> return GitAnswer.Ok(body)
    }
    // git's own wording, which it has kept for years; the result schema lets the executor report it in either branch.
    return if ("not a git repository" in failed.message) failed.copy(code = NOT_A_REPOSITORY) else failed
}

/** One file of a `client_git_diff_snapshot` or `diff_range_snapshot` payload (s07-changes.jsonl:65, :71). */
internal data class SnapshotFile(
    val path: String,
    val change: ChangeKind,
    val binary: Boolean,
    val diff: String?,
    val oldContent: String?,
    val newContent: String?,
    val oldSize: Long?,
    val newSize: Long?,
    val omittedReason: String?,
    val stat: DiffStat,
    val diffToken: String?,
)

internal data class Snapshot(
    val repositoryName: String,
    val branch: String?,
    val head: String?,
    val diffHash: String,
    val capturedAtMs: Long?,
    val files: List<SnapshotFile>,
)

internal fun snapshotOf(body: JsonObject): Snapshot? {
    val files = body.array("files") ?: return null
    return Snapshot(
        repositoryName = body.string("repositoryName") ?: body.string("repositoryRoot")?.substringAfterLast('/') ?: "Repository",
        branch = body.string("branch"),
        head = body.string("head"),
        diffHash = body.string("diffHash") ?: files.hashCode().toString(),
        capturedAtMs = body.long("capturedAt"),
        files = files.mapNotNull { (it as? JsonObject)?.let(::snapshotFileOf) },
    )
}

private fun snapshotFileOf(file: JsonObject): SnapshotFile? {
    val path = file.string("path") ?: return null
    val previous = file.string("previousPath") ?: file.string("oldPath")
    val stat = file.obj("diffStat")
    return SnapshotFile(
        path = path,
        change = changeKindOf(file.string("changeType"), previous),
        binary = file.bool("isBinary") == true,
        diff = file.string("diff"),
        oldContent = file.string("oldContent"),
        newContent = file.string("newContent"),
        oldSize = file.long("oldSizeBytes"),
        newSize = file.long("newSizeBytes"),
        omittedReason = file.string("contentOmittedReason"),
        stat = DiffStat(stat?.long("added")?.toInt() ?: 0, stat?.long("deleted")?.toInt() ?: 0, stat?.long("changed")?.toInt() ?: 0),
        diffToken = file.string("diffToken"),
    )
}

/** The eight `changeType` values (`type_changed` and friends); an unknown value reads as modified. */
internal fun changeKindOf(type: String?, previous: String?): ChangeKind = when (type) {
    "added" -> ChangeKind.Added
    "deleted" -> ChangeKind.Deleted
    "renamed" -> ChangeKind.Renamed(previous.orEmpty())
    "copied" -> ChangeKind.Copied(previous.orEmpty())
    "type_changed" -> ChangeKind.TypeChanged
    "unmerged" -> ChangeKind.Unmerged
    "untracked" -> ChangeKind.Untracked
    else -> ChangeKind.Modified
}

internal fun wireChangeType(kind: ChangeKind): String = when (kind) {
    ChangeKind.Added -> "added"
    ChangeKind.Modified -> "modified"
    ChangeKind.Deleted -> "deleted"
    is ChangeKind.Renamed -> "renamed"
    is ChangeKind.Copied -> "copied"
    ChangeKind.TypeChanged -> "type_changed"
    ChangeKind.Unmerged -> "unmerged"
    ChangeKind.Untracked -> "untracked"
}

/** An ahead commit: `hash`, `shortHash`, `subject`, `message?`, `parentHash?` (ampcode.com's web bundle, `isMergeBase`). */
internal data class AheadCommit(val hash: String, val shortHash: String, val subject: String, val parent: String?)

internal fun aheadCommitsOf(stdout: String): List<AheadCommit>? {
    val element = runCatching { com.agentifiers.amp.sync.internal.wire.AmpJson.parseToJsonElement(stdout) }.getOrNull()
    val items = (element as? JsonArray) ?: (element as? JsonObject)?.array("commits") ?: return null
    return items.mapNotNull { item ->
        val commit = item as? JsonObject ?: return@mapNotNull null
        if (commit.obj("metadata")?.bool("isMergeBase") == true) return@mapNotNull null
        val hash = commit.string("hash") ?: return@mapNotNull null
        AheadCommit(hash, commit.string("shortHash") ?: hash.take(7), commit.string("subject").orEmpty(), commit.string("parentHash"))
    }
}

/** Strips `refs/remotes/` and `refs/heads/` and adds `origin/` when missing (ampcode.com's web bundle, `function jA`). */
internal fun normalizeTarget(ref: String): String? {
    val trimmed = ref.trim().removePrefix("refs/remotes/").removePrefix("refs/heads/")
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith("origin/")) trimmed else "origin/$trimmed"
}

/** One section of an order request: at most 500 files of `{path, changeType, diff}` (ampcode.com's web bundle, `.max(42)`). */
internal data class OrderSection(val sectionId: String, val diffHash: String, val revisions: Revisions, val files: List<SnapshotFile>)

internal const val ORDER_MAX_FILES = 500
internal const val ORDER_MAX_SECTIONS = 42
internal const val ORDER_MAX_CHARS = 200_000
internal const val ORDER_MAX_FILE_DIFF_CHARS = 20_000

/**
 * The `client_changes_file_order` params. Amp refuses a section without `includeUntracked` with `-32602`
 * (s20-changes-params.jsonl:1016-1019), and its schema caps each file diff at 20,000 characters (RESULTS.md S20b).
 * Each diff is cut to 20,000 characters, or to an equal share of the 200,000 character budget the web applies when
 * that is smaller. Null when even empty diffs do not fit.
 */
internal fun orderRequest(sections: List<OrderSection>): JsonObject? {
    fun build(perFile: Int): JsonObject = buildJsonObject {
        put("sections", JsonArray(sections.map { section ->
            buildJsonObject {
                put("sectionID", section.sectionId)
                put("diffHash", section.diffHash)
                put("baseRevision", section.revisions.base)
                put("headRevision", section.revisions.head)
                put("includeUntracked", section.revisions.includeUntracked)
                put("files", JsonArray(section.files.map { file ->
                    buildJsonObject {
                        put("path", file.path)
                        put("changeType", wireChangeType(file.change))
                        put("diff", file.diff.orEmpty().take(perFile))
                    }
                }))
            }
        }))
    }
    val full = build(ORDER_MAX_FILE_DIFF_CHARS)
    if (full.toString().length <= ORDER_MAX_CHARS) return full
    val fileCount = sections.sumOf { it.files.size }.coerceAtLeast(1)
    val overhead = build(0).toString().length
    if (overhead > ORDER_MAX_CHARS) return null
    return build(minOf(ORDER_MAX_FILE_DIFF_CHARS, (ORDER_MAX_CHARS - overhead) / fileCount))
}

internal data class OrderResult(val sectionId: String, val orderedPaths: List<String>, val summary: String?, val codeTour: String?)

/** How an order request settled. [Failed.reason] is the server's own words, for the log only. */
internal sealed interface OrderAnswer {
    data class Ordered(val results: List<OrderResult>) : OrderAnswer

    data class Failed(val reason: String) : OrderAnswer
}

/** `client_changes_file_order_result{requestId, result:{status:"success", sections} | {status:"failure", error}}` (s20-changes-params.jsonl:1020). */
internal fun orderAnswerOf(body: JsonObject): OrderAnswer {
    val result = body.obj("result")
    return when (result?.string("status")) {
        "success" -> orderResultOf(result)?.let(OrderAnswer::Ordered) ?: OrderAnswer.Failed("success without sections")
        "failure" -> OrderAnswer.Failed(result.string("error") ?: "failure without an error")
        else -> OrderAnswer.Failed("unknown result: ${result?.string("status")}")
    }
}

/** The `sections` of a success result, or of a cached [FileOrderRow][com.agentifiers.amp.store.FileOrderRow]. */
internal fun orderResultOf(body: JsonObject): List<OrderResult>? = body.array("sections")?.mapNotNull { item ->
    val section = item as? JsonObject ?: return@mapNotNull null
    OrderResult(
        sectionId = section.string("sectionID") ?: return@mapNotNull null,
        orderedPaths = section.array("orderedPaths")?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.orEmpty(),
        summary = section.string("summary"),
        codeTour = section.string("codeTour"),
    )
}
