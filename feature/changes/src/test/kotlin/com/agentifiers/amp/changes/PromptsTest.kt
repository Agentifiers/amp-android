package com.agentifiers.amp.changes

import com.agentifiers.amp.model.ChangeScope
import com.agentifiers.amp.model.ChangesAction
import com.agentifiers.amp.model.ChangesFacts
import com.agentifiers.amp.model.CommitContext
import com.agentifiers.amp.model.DiffComment
import com.agentifiers.amp.model.DiffSelection
import com.agentifiers.amp.model.DiffSide
import com.agentifiers.amp.model.ShipBehavior
import com.agentifiers.amp.model.ShipTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptsTest {
    private fun facts(
        target: ShipTarget = ShipTarget.Known("origin/main"),
        behavior: ShipBehavior = ShipBehavior.Ship,
        github: Boolean = false,
        branch: String? = "main",
        hasUncommitted: Boolean = false,
        ahead: Int = 0,
        shipInFlight: Boolean = false,
        threadUrl: String? = null,
    ) = ChangesFacts(target, behavior, github, branch, "main", hasUncommitted, ahead, shipInFlight, threadUrl, null)

    private val quant = DiffComment("c1", DiffSelection("src/quant.rs", DiffSide.New, 42, 42), "Use 0..64 here and drop the + 1.", null)
    private val idct = DiffComment(
        "c2",
        DiffSelection("src/dct/idct.rs", DiffSide.New, 10, 14),
        "Why clamp before rounding?",
        CommitContext("4bd66138fb8e9a0c1d2e3f405162738495a6b7c8", "Fix zigzag walk\n\nWalk the table in order.", "dct"),
    )
    private val blank = DiffComment("c3", DiffSelection("src/lib.rs", DiffSide.Old, 3, 3), "   ", null)

    private val block = "Diff review comments:\n" +
        "1. src/quant.rs:42 - Use 0..64 here and drop the + 1.\n" +
        "2. src/dct/idct.rs:10-14 [commit 4bd66138fb8e in dct: Fix zigzag walk] - Why clamp before rounding?"

    private val shipText = "Ship all committed and uncommitted changes to origin/main. Commit any uncommitted changes before pushing. " +
        "If push fails because origin/main is ahead, rebase and resolve merge conflicts, checking with me before proceeding if there " +
        "are any substantive conflicts, then push again. If rebasing floods add/add conflicts in unrelated files, run `git rebase " +
        "--abort`, `git fetch --quiet --unshallow origin`, then rebase again. Fetch origin and rebase onto latest origin/main, then " +
        "run the full test suite before pushing. Do not repeat it after a later fetch, rebase, or rejected push unless conflict " +
        "resolution or other local edits changed files after the successful test run. Ignore a failing check from the test suite " +
        "only after verifying that the same check also fails on origin/main without this thread's changes."

    private val pushText = "Push the changes to a feature branch on the remote. If the current branch is origin/main, first make a new " +
        "feature branch with a short descriptive name following this repository's conventions; otherwise, reuse the existing " +
        "feature branch we're on. If there are uncommitted changes, commit them on the feature branch before pushing. Do not " +
        "merge directly into origin/main."

    private fun confirm(button: ChangesButton, facts: ChangesFacts, scope: ChangeScope = ChangeScope.Uncommitted, comments: List<DiffComment> = emptyList()) =
        (availability(button, facts, scope, comments) as ActionAvailability.Available).confirm

    @Test
    fun commentBlockNumbersCommentsAndNamesTheCommitOnlyWhenPresent() {
        assertEquals(block, commentBlock(listOf(quant, blank, idct)))
        assertEquals(
            "Diff review comments:\n1. src/dct/idct.rs:10-14 [commit 4bd66138fb8e: Fix zigzag walk] - Why clamp before rounding?",
            commentBlock(listOf(idct.copy(commit = idct.commit!!.copy(repositoryName = null)))),
        )
        assertEquals("", commentBlock(listOf(blank)))
    }

    @Test
    fun defaultShipConfirmsWithTheTargetLineAndTheWebText() {
        assertEquals(
            ConfirmRequest(ChangesAction.Ship, "Ship Changes?", "Push to origin/main", shipText, "Ship", emptyList()),
            confirm(ChangesButton.Ship, facts()),
        )
    }

    @Test
    fun shipTargetLineCountsCommitsAndUncommittedChanges() {
        val lines = listOf(true to 2, true to 0, false to 1, false to 3, false to 0).map { (uncommitted, ahead) ->
            confirm(ChangesButton.Ship, facts(hasUncommitted = uncommitted, ahead = ahead)).target
        }
        assertEquals(
            listOf(
                "Commit changes and push 3 commits to origin/main",
                "Commit changes and push to origin/main",
                "Push 1 commit to origin/main",
                "Push 3 commits to origin/main",
                "Push to origin/main",
            ),
            lines,
        )
    }

    @Test
    fun pendingCommentsPrefixShipAndAreRemovedWithIt() {
        val request = confirm(ChangesButton.Ship, facts(), comments = listOf(quant, blank, idct))
        assertEquals("$block\n\n$shipText", request.prompt)
        assertEquals(listOf("c1", "c2"), request.commentIds)
    }

    @Test
    fun pushOnTheBaseBranchOfAGitHubProjectOpensAPullRequest() {
        assertEquals(
            ConfirmRequest(
                ChangesAction.PushBranch,
                "Push to Branch?",
                "Push to new branch & open GitHub PR",
                "$pushText Include https://ampcode.com/threads/T-1 in the GitHub pull request description and report its URL.",
                "Push to Branch",
                emptyList(),
            ),
            confirm(ChangesButton.Ship, facts(behavior = ShipBehavior.PushBranch, github = true, threadUrl = "https://ampcode.com/threads/T-1")),
        )
    }

    @Test
    fun pushTargetLineNamesTheBranchAndOmitsGitHubElsewhere() {
        val onFeature = confirm(ChangesButton.Ship, facts(behavior = ShipBehavior.PushBranch, github = true, branch = "feat/zigzag"))
        assertEquals("Push to GitHub PR branch feat/zigzag", onFeature.target)
        assertEquals("$pushText Get a pull request URL from GitHub after pushing and report it back to me.", onFeature.prompt)

        val otherHost = confirm(ChangesButton.Ship, facts(behavior = ShipBehavior.PushBranch, branch = "feat/zigzag"))
        assertEquals("Push to feat/zigzag", otherHost.target)
        assertEquals(pushText, otherHost.prompt)

        assertEquals("Push to branch", confirm(ChangesButton.Ship, facts(behavior = ShipBehavior.PushBranch, branch = null)).target)
    }

    @Test
    fun customShipSendsTheSavedPromptAndABlankOneFallsBackToTheDefault() {
        assertEquals(
            ConfirmRequest(ChangesAction.Ship, "Ship?", "Uses this project's custom Ship prompt", "Merge to trunk and deploy.\n", "Ship", emptyList()),
            confirm(ChangesButton.Ship, facts(behavior = ShipBehavior.Custom("Merge to trunk and deploy.\n"))),
        )
        assertEquals("Ship Changes?", confirm(ChangesButton.Ship, facts(behavior = ShipBehavior.Custom("  "))).title)
    }

    @Test
    fun restackNeverCarriesPendingComments() {
        assertEquals(
            ConfirmRequest(
                ChangesAction.Restack,
                "Restack Changes?",
                "Amp will organize all your committed and uncommitted changes into a cleaner set of commits. The final file contents stay the same.",
                "Refactor all committed and uncommitted changes since the merge base into logical, easy-to-review commits. Inspect the " +
                    "complete diff first, then reorder, split, squash, and edit commits as needed. Preserve the final tree and behavior. Do not push.",
                "Restack",
                emptyList(),
            ),
            confirm(ChangesButton.Restack, facts(), comments = listOf(quant)),
        )
    }

    @Test
    fun rebaseTargetsTheBaseRefWithCommentsFirst() {
        assertEquals(
            ConfirmRequest(
                ChangesAction.Rebase,
                "Rebase?",
                "Fetch origin and rebase onto origin/main",
                "Diff review comments:\n1. src/quant.rs:42 - Use 0..64 here and drop the + 1.\n\n" +
                    "Fetch origin and rebase the current work onto latest origin/main. If rebasing floods add/add conflicts in unrelated " +
                    "files, run `git rebase --abort`, `git fetch --quiet --unshallow origin`, then rebase again. Preserve all uncommitted " +
                    "changes and restore them after rebasing.",
                "Rebase",
                listOf("c1"),
            ),
            confirm(ChangesButton.Rebase, facts(), comments = listOf(quant)),
        )
    }

    @Test
    fun reviewTextFollowsTheScope() {
        val uncommitted = confirm(ChangesButton.Review, facts())
        val all = confirm(ChangesButton.Review, facts(), ChangeScope.AllChanges("9f8e7d6c"))
        val commit = confirm(ChangesButton.Review, facts(), ChangeScope.Commit("b2c3", "b2c3", "Fix zigzag", "a1b2", isHead = true))
        assertEquals(
            listOf(
                "Review Changes?" to "Request a code review",
                "Review Changes?" to "Request a code review for all changes",
                "Review Changes?" to "Request a code review for a1b2..b2c3",
            ),
            listOf(uncommitted, all, commit).map { it.title to it.target },
        )
        assertEquals(
            listOf(
                "Ask the oracle to review the current changes.",
                "Ask the oracle to review all changes since merge base 9f8e7d6c, including untracked files.",
                "Ask the oracle to review a1b2..b2c3.",
            ),
            listOf(uncommitted, all, commit).map { it.prompt },
        )
    }

    @Test
    fun unknownTargetBlocksShipAndRebaseButNotReview() {
        val unknown = facts(target = ShipTarget.Unknown)
        assertEquals(
            listOf(
                ActionAvailability.Blocked("Needs live status from the executor."),
                ActionAvailability.Blocked("Needs live status from the executor."),
            ),
            listOf(ChangesButton.Ship, ChangesButton.Rebase).map { availability(it, unknown, ChangeScope.Uncommitted, emptyList()) },
        )
        assertEquals("Request a code review", confirm(ChangesButton.Review, unknown).target)
        assertEquals(listOf("Push", "Rebase"), listOf(ChangesButton.Ship, ChangesButton.Rebase).map {
            buttonLabel(it, unknown.copy(behavior = ShipBehavior.PushBranch))
        })
    }

    @Test
    fun aShipInFlightBlocksOnlyShip() {
        val inFlight = facts(shipInFlight = true)
        assertEquals(
            ActionAvailability.Blocked("Already asked the agent to ship or push these changes."),
            availability(ChangesButton.Ship, inFlight, ChangeScope.Uncommitted, emptyList()),
        )
        assertEquals("Fetch origin and rebase onto origin/main", confirm(ChangesButton.Rebase, inFlight).target)
        assertEquals("Rebase onto origin/main", buttonLabel(ChangesButton.Rebase, inFlight))
    }
}
