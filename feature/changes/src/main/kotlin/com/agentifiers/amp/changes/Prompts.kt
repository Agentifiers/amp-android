package com.agentifiers.amp.changes

import com.agentifiers.amp.model.ChangeScope
import com.agentifiers.amp.model.ChangesAction
import com.agentifiers.amp.model.ChangesFacts
import com.agentifiers.amp.model.CommitContext
import com.agentifiers.amp.model.DiffComment
import com.agentifiers.amp.model.DiffSelection
import com.agentifiers.amp.model.ShipBehavior
import com.agentifiers.amp.model.ShipTarget

// Texts follow docs/design/git-and-diffs.md, "Ship and the other Changes actions" and "How comments reach the agent".

internal const val NEEDS_LIVE_STATUS = "Needs live status from the executor."
internal const val ALREADY_SHIPPING = "Already asked the agent to ship or push these changes."

/** The four action buttons the pane shows. Ship resolves to [ChangesAction.PushBranch] under Push to Branch. */
internal enum class ChangesButton { Ship, Review, Restack, Rebase }

/** What the confirm sheet shows and sends. [commentIds] are the pending comments folded into [prompt]. */
internal data class ConfirmRequest(
    val action: ChangesAction,
    val title: String,
    val target: String,
    val prompt: String,
    val sendLabel: String,
    val commentIds: List<String>,
)

internal sealed interface ActionAvailability {
    data class Available(val confirm: ConfirmRequest) : ActionAvailability
    data class Blocked(val reason: String) : ActionAvailability
}

internal fun buttonLabel(button: ChangesButton, facts: ChangesFacts): String = when (button) {
    ChangesButton.Ship -> if (facts.behavior is ShipBehavior.PushBranch) "Push" else "Ship"
    ChangesButton.Review -> "Review"
    ChangesButton.Restack -> "Restack"
    ChangesButton.Rebase -> (facts.target as? ShipTarget.Known)?.let { "Rebase onto ${it.ref}" } ?: "Rebase"
}

internal fun availability(button: ChangesButton, facts: ChangesFacts, scope: ChangeScope, comments: List<DiffComment>): ActionAvailability {
    val pending = comments.filter { it.text.isNotBlank() }
    val included = PendingComments(commentBlock(pending), pending.map { it.id })
    val target = facts.target
    return when (button) {
        ChangesButton.Review -> ActionAvailability.Available(
            ConfirmRequest(ChangesAction.Review, "Review Changes?", reviewTargetLine(scope), included.prefix(reviewPrompt(scope)), "Review", included.ids),
        )
        ChangesButton.Restack -> ActionAvailability.Available(
            ConfirmRequest(ChangesAction.Restack, "Restack Changes?", RESTACK_TARGET_LINE, RESTACK_PROMPT, "Restack", emptyList()),
        )
        ChangesButton.Ship -> when {
            target !is ShipTarget.Known -> ActionAvailability.Blocked(NEEDS_LIVE_STATUS)
            facts.shipInFlight -> ActionAvailability.Blocked(ALREADY_SHIPPING)
            else -> ActionAvailability.Available(shipConfirm(facts, target.ref, included))
        }
        ChangesButton.Rebase -> when (target) {
            is ShipTarget.Known -> ActionAvailability.Available(
                ConfirmRequest(
                    ChangesAction.Rebase, "Rebase?", "Fetch origin and rebase onto ${target.ref}",
                    included.prefix(rebasePrompt(target.ref)), "Rebase", included.ids,
                ),
            )
            ShipTarget.Unknown -> ActionAvailability.Blocked(NEEDS_LIVE_STATUS)
        }
    }
}

/** The web's `Diff review comments:` block (801.dfc6ad0a.js:79, `function SK`). Empty when no comment has text. */
internal fun commentBlock(comments: List<DiffComment>): String {
    val kept = comments.filter { it.text.isNotBlank() }
    if (kept.isEmpty()) return ""
    return "Diff review comments:\n" + kept.withIndex().joinToString("\n") { (index, comment) ->
        "${index + 1}. ${comment.selection.path}:${lineRef(comment.selection)}${commitRef(comment.commit)} - ${comment.text}"
    }
}

internal fun lineRef(selection: DiffSelection): String =
    if (selection.startLine == selection.endLine) "${selection.startLine}" else "${selection.startLine}-${selection.endLine}"

private fun commitRef(commit: CommitContext?): String {
    if (commit == null) return ""
    val repository = commit.repositoryName?.let { " in $it" }.orEmpty()
    val subject = commit.subject.lineSequence().firstOrNull()?.trim().orEmpty()
    return " [commit ${commit.hash.take(12)}$repository${if (subject.isEmpty()) "" else ": $subject"}]"
}

private class PendingComments(val block: String, val ids: List<String>) {
    fun prefix(text: String): String = if (block.isEmpty()) text else "$block\n\n$text"
}

private fun shipConfirm(facts: ChangesFacts, target: String, included: PendingComments): ConfirmRequest {
    val behavior = facts.behavior
    return when {
        behavior is ShipBehavior.PushBranch -> ConfirmRequest(
            ChangesAction.PushBranch, "Push to Branch?", pushTargetLine(facts, target),
            included.prefix(pushPrompt(facts, target)), "Push to Branch", included.ids,
        )
        behavior is ShipBehavior.Custom && behavior.prompt.isNotBlank() -> ConfirmRequest(
            ChangesAction.Ship, "Ship?", "Uses this project's custom Ship prompt", included.prefix(behavior.prompt), "Ship", included.ids,
        )
        else -> ConfirmRequest(
            ChangesAction.Ship, "Ship Changes?", shipTargetLine(facts, target), included.prefix(shipPrompt(target)), "Ship", included.ids,
        )
    }
}

/** The web's Ship tooltip (801.dfc6ad0a.js:26, `function MA`). */
private fun shipTargetLine(facts: ChangesFacts, target: String): String {
    val ahead = facts.ahead
    val action = when {
        facts.hasUncommitted && ahead > 0 -> "Commit changes and push ${ahead + 1} commits to"
        facts.hasUncommitted -> "Commit changes and push to"
        ahead > 0 -> "Push $ahead ${if (ahead == 1) "commit" else "commits"} to"
        else -> "Push to"
    }
    return "$action $target"
}

/** The web's Push to Branch tooltip (801.dfc6ad0a.js:26, `function MA`). */
private fun pushTargetLine(facts: ChangesFacts, target: String): String {
    val branch = facts.branch?.trim()?.takeIf { it.isNotEmpty() }
    val onBase = facts.github && branch == target.removePrefix("origin/")
    val action = when {
        facts.github && onBase -> "Push to new branch & open GitHub PR"
        facts.github -> "Push to GitHub PR branch"
        branch != null -> "Push to"
        else -> "Push to branch"
    }
    return if (branch != null && !onBase) "$action $branch" else action
}

private fun shipPrompt(target: String): String =
    "Ship all committed and uncommitted changes to $target. Commit any uncommitted changes before pushing. " +
        "If push fails because $target is ahead, rebase and resolve merge conflicts, checking with me before proceeding " +
        "if there are any substantive conflicts, then push again. If rebasing floods add/add conflicts in unrelated files, " +
        "run `git rebase --abort`, `git fetch --quiet --unshallow origin`, then rebase again. Fetch origin and rebase onto " +
        "latest $target, then run the full test suite before pushing. Do not repeat it after a later fetch, rebase, or " +
        "rejected push unless conflict resolution or other local edits changed files after the successful test run. " +
        "Ignore a failing check from the test suite only after verifying that the same check also fails on $target " +
        "without this thread's changes."

private fun pushPrompt(facts: ChangesFacts, target: String): String = buildList {
    add("Push the changes to a feature branch on the remote.")
    add(
        "If the current branch is $target, first make a new feature branch with a short descriptive name following this " +
            "repository's conventions; otherwise, reuse the existing feature branch we're on.",
    )
    add("If there are uncommitted changes, commit them on the feature branch before pushing.")
    add("Do not merge directly into $target.")
    if (facts.github) {
        add(
            facts.threadUrl?.let { "Include $it in the GitHub pull request description and report its URL." }
                ?: "Get a pull request URL from GitHub after pushing and report it back to me.",
        )
    }
}.joinToString(" ")

private fun rebasePrompt(target: String): String =
    "Fetch origin and rebase the current work onto latest $target. If rebasing floods add/add conflicts in unrelated " +
        "files, run `git rebase --abort`, `git fetch --quiet --unshallow origin`, then rebase again. Preserve all " +
        "uncommitted changes and restore them after rebasing."

private fun reviewPrompt(scope: ChangeScope): String = when (scope) {
    ChangeScope.Uncommitted -> "Ask the oracle to review the current changes."
    is ChangeScope.AllChanges -> "Ask the oracle to review all changes since merge base ${scope.mergeBase}, including untracked files."
    is ChangeScope.Commit -> "Ask the oracle to review ${scope.parent}..${scope.hash}."
}

private fun reviewTargetLine(scope: ChangeScope): String = when (scope) {
    ChangeScope.Uncommitted -> "Request a code review"
    is ChangeScope.AllChanges -> "Request a code review for all changes"
    is ChangeScope.Commit -> "Request a code review for ${scope.parent}..${scope.hash}"
}

private const val RESTACK_TARGET_LINE =
    "Amp will organize all your committed and uncommitted changes into a cleaner set of commits. The final file contents stay the same."

private const val RESTACK_PROMPT =
    "Refactor all committed and uncommitted changes since the merge base into logical, easy-to-review commits. Inspect " +
        "the complete diff first, then reorder, split, squash, and edit commits as needed. Preserve the final tree and " +
        "behavior. Do not push."
