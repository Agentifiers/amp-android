package com.agentifiers.amp.sync.internal.changes

import com.agentifiers.amp.model.ChangeKind
import com.agentifiers.amp.model.DiffStat
import com.agentifiers.amp.sync.internal.wire.AmpJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChangesWireTest {

    @Test
    fun `git's not a git repository answer is NOT_A_REPOSITORY in either branch of the result schema`() {
        fun answer(raw: String) = gitAnswerOf(AmpJson.parseToJsonElement(raw) as JsonObject)
        val stderr = "fatal: not a git repository (or any of the parent directories): .git"
        assertEquals(
            GitAnswer.Failed("NOT_A_REPOSITORY", stderr),
            answer("""{"requestId":"r","ok":true,"exitCode":128,"stdout":"","stderr":"$stderr\n"}"""),
        )
        assertEquals(
            GitAnswer.Failed("NOT_A_REPOSITORY", stderr),
            answer("""{"requestId":"r","ok":false,"error":{"code":"GIT_OPERATION_FAILED","message":"$stderr"}}"""),
        )
        assertEquals(
            GitAnswer.Failed("GIT_OPERATION_FAILED", "fatal: ambiguous argument 'HEAD': unknown revision or path not in the working tree."),
            answer("""{"requestId":"r","ok":true,"exitCode":128,"stdout":"","stderr":"fatal: ambiguous argument 'HEAD': unknown revision or path not in the working tree.\n"}"""),
        )
        assertEquals(
            GitAnswer.Failed("NOT_RECORDED", "fake-amp has no recording of client_git_command with these params"),
            answer("""{"requestId":"r","ok":false,"error":{"code":"NOT_RECORDED","message":"fake-amp has no recording of client_git_command with these params"}}"""),
        )
    }

    @Test
    fun `the order request carries each section's includeUntracked, which Amp requires (S20)`() {
        val request = orderRequest(
            listOf(
                OrderSection("unstaged", "h-unstaged", Revisions("index", "worktree", includeUntracked = true), listOf(file("bun.lock", "@@ -1 +1 @@\n-a\n+b\n"))),
                OrderSection("staged", "h-staged", Revisions("62f98fb", "index", includeUntracked = false), listOf(file("README.md", ""))),
            ),
        )
        assertEquals(
            """{"sections":[""" +
                """{"sectionID":"unstaged","diffHash":"h-unstaged","baseRevision":"index","headRevision":"worktree","includeUntracked":true,""" +
                """"files":[{"path":"bun.lock","changeType":"modified","diff":"@@ -1 +1 @@\n-a\n+b\n"}]},""" +
                """{"sectionID":"staged","diffHash":"h-staged","baseRevision":"62f98fb","headRevision":"index","includeUntracked":false,""" +
                """"files":[{"path":"README.md","changeType":"modified","diff":""}]}]}""",
            request.toString(),
        )
    }

    @Test
    fun `a file diff over 20,000 characters is cut to 20,000, the most Amp accepts per file`() {
        val request = orderRequest(listOf(OrderSection("unstaged", "h", Revisions("index", "worktree", includeUntracked = true), listOf(file("big.txt", "x".repeat(25_000))))))
        assertEquals(
            """{"sections":[{"sectionID":"unstaged","diffHash":"h","baseRevision":"index","headRevision":"worktree","includeUntracked":true,""" +
                """"files":[{"path":"big.txt","changeType":"modified","diff":"${"x".repeat(20_000)}"}]}]}""",
            request.toString(),
        )
    }

    @Test
    fun `the order result is read from result status, as recorded at s20 line 1020`() {
        val recorded = AmpJson.parseToJsonElement(
            """{"requestId":"6aae8cb0-cf0f-43f5-88c9-7f3a7d307c2a","result":{"status":"success","sections":[""" +
                """{"sectionID":"unstaged","diffHash":"0000000000000000000000000000000000000000000000000000000000000001","orderedPaths":["apps/web/package.json"]}]}}""",
        ) as JsonObject
        assertEquals(OrderAnswer.Ordered(listOf(OrderResult("unstaged", listOf("apps/web/package.json"), null, null))), orderAnswerOf(recorded))

        val failed = AmpJson.parseToJsonElement("""{"requestId":"r","result":{"status":"failure","error":"model unavailable"}}""") as JsonObject
        assertEquals(OrderAnswer.Failed("model unavailable"), orderAnswerOf(failed))
    }

    private fun file(path: String, diff: String) = SnapshotFile(
        path = path, change = ChangeKind.Modified, binary = false, diff = diff, oldContent = null, newContent = null,
        oldSize = null, newSize = null, omittedReason = null, stat = DiffStat(1, 1, 1), diffToken = null,
    )
}
