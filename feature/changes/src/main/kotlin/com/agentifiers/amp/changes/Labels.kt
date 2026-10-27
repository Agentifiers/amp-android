package com.agentifiers.amp.changes

import com.agentifiers.amp.model.ChangeScope
import com.agentifiers.amp.model.ChangeSource
import com.agentifiers.amp.model.ChangesView
import com.agentifiers.amp.model.DiffComment
import com.agentifiers.amp.model.FileOrder
import com.agentifiers.amp.model.RepoChanges
import com.agentifiers.amp.model.SectionKind
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Outcome
import kotlin.time.Instant

internal fun sourceLabel(source: ChangeSource, stale: Boolean, time: (Instant) -> String): String {
    val base = when (source) {
        is ChangeSource.LiveGit -> "Live"
        is ChangeSource.Capture -> "Recorded ${time(source.capturedAt)}"
        is ChangeSource.Cached -> "As of ${time(source.capturedAt)}"
    }
    return if (stale) "$base · Refreshing…" else base
}

internal fun orderLabel(order: FileOrder): String = when (order) {
    is FileOrder.Intelligent -> if (order.updateAvailable) "Apply Updated File Order" else "Order Files Alphabetically"
    FileOrder.Alphabetical, FileOrder.Ordering -> "Order Files Intelligently"
    is FileOrder.Unavailable -> "Intelligent File Order Unavailable"
}

/** The `setOrder(intelligent)` argument the order button sends, or null when the button is disabled. */
internal fun orderRequest(order: FileOrder): Boolean? = when (order) {
    is FileOrder.Intelligent -> order.updateAvailable
    FileOrder.Alphabetical, FileOrder.Ordering -> true
    is FileOrder.Unavailable -> null
}

internal fun commitsAhead(count: Int): String = "$count ${if (count == 1) "commit" else "commits"} ahead"

internal fun repoHeading(repo: RepoChanges): String =
    listOfNotNull(repo.name, repo.branch, repo.ahead.takeIf { it > 0 }?.let(::commitsAhead)).joinToString(" · ")

internal fun currentScope(view: ChangesView.Ready): ChangeScope =
    view.scopes.firstOrNull { it.id == view.scope } ?: ChangeScope.Uncommitted

/** The closed scope picker: "<label> · N ahead", or just the label. [aheadOfBase] is the full wording. */
internal fun scopeSummary(view: ChangesView.Ready): String {
    val label = currentScope(view).label
    return if (aheadOfBase(view) == null) label else "$label · ${view.facts.ahead} ahead"
}

/** "N commits ahead of <base>" for the scope menu and the picker's content description, or null with no base or none ahead. */
internal fun aheadOfBase(view: ChangesView.Ready): String? {
    val base = view.facts.baseBranch?.takeIf { it.isNotBlank() } ?: return null
    val ahead = view.facts.ahead
    return if (ahead > 0) "${commitsAhead(ahead)} of $base" else null
}

internal fun emptyText(view: ChangesView.Ready): String =
    if (view.scope == ChangeScope.Uncommitted.id && view.facts.ahead > 0) "No uncommitted changes" else "No Changes"

/** The line under a section header with no files, so an empty STAGED does not read as a header missing its rows. */
internal fun emptySectionText(kind: SectionKind): String = when (kind) {
    SectionKind.Unstaged -> "Nothing unstaged"
    SectionKind.Staged -> "Nothing staged"
    SectionKind.Range -> "No changes"
}

/** A pending comment pill, as on the web: base name, line part, and the first 36 characters of the text. */
internal fun pillLabel(comment: DiffComment): String {
    val text = comment.text.trim()
    val shown = if (text.length > PILL_CHARS) text.take(PILL_CHARS) + "…" else text
    return "${comment.selection.path.substringAfterLast('/')}:${lineRef(comment.selection)} $shown"
}

private const val PILL_CHARS = 36

internal fun binaryText(oldSize: Long?, newSize: Long?): String =
    (listOf("Binary file") + listOfNotNull(oldSize?.let { "old $it bytes" }, newSize?.let { "new $it bytes" })).joinToString(" · ")

/** The parent directory a Reveal in File Tree opens, or null for a file at the repository root. */
internal fun parentDir(path: String): String? = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }

/** The text a failed engine call shows. Refused carries the server's own words. */
internal fun failureText(outcome: Outcome<*>): String? = when (outcome) {
    is Outcome.Ok -> null
    is Outcome.Refused -> outcome.why
    Outcome.Unknown -> "No answer from the executor. Refresh to see the current state."
    is Outcome.Failed -> when (val error = outcome.error) {
        EngineError.Offline -> "Offline."
        EngineError.Unauthorized -> "This account is signed out."
        EngineError.StepUpRequired -> "This account needs a passkey to act from the app"
        EngineError.PaymentRequired -> "This account is out of credits."
        is EngineError.NotFound -> "Not found: ${error.what}"
        is EngineError.Server -> error.message
        is EngineError.Drift -> "Unexpected answer from ${error.where}."
    }
}
