package com.agentifiers.amp.changes

import com.agentifiers.amp.model.ChangeScope
import com.agentifiers.amp.model.ChangeSource
import com.agentifiers.amp.model.ChangesFacts
import com.agentifiers.amp.model.ChangesView
import com.agentifiers.amp.model.DiffComment
import com.agentifiers.amp.model.DiffSelection
import com.agentifiers.amp.model.DiffSide
import com.agentifiers.amp.model.FileOrder
import com.agentifiers.amp.model.RepoChanges
import com.agentifiers.amp.model.SectionKind
import com.agentifiers.amp.model.ShipBehavior
import com.agentifiers.amp.model.ShipTarget
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Outcome
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

class LabelsTest {
    private val commit = ChangeScope.Commit("b2c3d4", "b2c3d4", "Fix zigzag walk\nBody", "a1b2c3", isHead = true)

    private fun view(scope: ChangeScope, ahead: Int, baseBranch: String?) = ChangesView.Ready(
        source = ChangeSource.LiveGit("abc"),
        scopes = persistentListOf(ChangeScope.Uncommitted, ChangeScope.AllChanges("a1"), commit),
        scope = scope.id,
        repos = persistentListOf(),
        order = FileOrder.Alphabetical,
        facts = ChangesFacts(ShipTarget.Unknown, ShipBehavior.Ship, false, "feat", baseBranch, false, ahead, false, null, null),
        canStage = true,
        stale = false,
        notice = null,
        noticeDetail = null,
    )

    @Test
    fun orderButtonFollowsTheOrderState() {
        val orders = listOf(
            FileOrder.Intelligent(updateAvailable = false),
            FileOrder.Intelligent(updateAvailable = true),
            FileOrder.Alphabetical,
            FileOrder.Ordering,
            FileOrder.Unavailable("This diff is too large to order intelligently."),
        )
        assertEquals(
            listOf(
                "Order Files Alphabetically" to false,
                "Apply Updated File Order" to true,
                "Order Files Intelligently" to true,
                "Order Files Intelligently" to true,
                "Intelligent File Order Unavailable" to null,
            ),
            orders.map { orderLabel(it) to orderRequest(it) },
        )
    }

    @Test
    fun scopeSummaryAddsTheAheadCountOnlyWithABase() {
        val views = listOf(
            view(ChangeScope.Uncommitted, 3, "origin/release/2026-10"),
            view(commit, 1, "main"),
            view(ChangeScope.AllChanges("a1"), 2, null),
            view(ChangeScope.Uncommitted, 0, "main"),
        )
        assertEquals(
            listOf(
                "Uncommitted · 3 ahead" to "3 commits ahead of origin/release/2026-10",
                "Fix zigzag walk · 1 ahead" to "1 commit ahead of main",
                "All Changes" to null,
                "Uncommitted" to null,
            ),
            views.map { scopeSummary(it) to aheadOfBase(it) },
        )
    }

    @Test
    fun emptyStateSaysWhetherCommitsExist() {
        assertEquals(
            listOf("No uncommitted changes", "No Changes", "No Changes"),
            listOf(view(ChangeScope.Uncommitted, 2, "main"), view(ChangeScope.Uncommitted, 0, "main"), view(commit, 2, "main")).map(::emptyText),
        )
    }

    @Test
    fun sourceLabelNamesTheSourceAndARunningRefresh() {
        val at = Instant.fromEpochSeconds(1_700_000_000)
        val time: (Instant) -> String = { "@${it.epochSeconds}" }
        assertEquals(
            listOf("Live", "Recorded @1700000000 · Refreshing…", "As of @1700000000"),
            listOf(
                sourceLabel(ChangeSource.LiveGit("abc"), false, time),
                sourceLabel(ChangeSource.Capture(at, "etag"), true, time),
                sourceLabel(ChangeSource.Cached(at), false, time),
            ),
        )
    }

    @Test
    fun headingsAndPills() {
        assertEquals("amp · feat/zigzag · 2 commits ahead", repoHeading(RepoChanges("amp", "feat/zigzag", 2, persistentListOf())))
        assertEquals("dct", repoHeading(RepoChanges("dct", null, 0, persistentListOf())))
        assertEquals(
            listOf("quant.rs:42 Use 0..64 here and drop the + 1.", "idct.rs:10-14 Why clamp before rounding? The table…"),
            listOf(
                DiffComment("c1", DiffSelection("src/quant.rs", DiffSide.New, 42, 42), "Use 0..64 here and drop the + 1.", null),
                DiffComment("c2", DiffSelection("src/dct/idct.rs", DiffSide.Old, 10, 14), "Why clamp before rounding? The table is signed.", null),
            ).map(::pillLabel),
        )
        assertEquals(listOf("src/dct", null), listOf(parentDir("src/dct/idct.rs"), parentDir("README.md")))
    }

    @Test
    fun failuresShowTheEnginesWords() {
        assertEquals(
            listOf(null, "You can stage up to 20 comments.", "pathspec 'x' did not match"),
            listOf(
                Outcome.Ok(Unit),
                Outcome.Refused("You can stage up to 20 comments."),
                Outcome.Failed(EngineError.Server("GIT_OPERATION_FAILED", "pathspec 'x' did not match")),
            ).map(::failureText),
        )
    }

    @Test
    fun anEmptySectionSaysWhatIsMissing() {
        assertEquals(
            listOf("Nothing unstaged", "Nothing staged", "No changes"),
            listOf(SectionKind.Unstaged, SectionKind.Staged, SectionKind.Range).map(::emptySectionText),
        )
    }
}
