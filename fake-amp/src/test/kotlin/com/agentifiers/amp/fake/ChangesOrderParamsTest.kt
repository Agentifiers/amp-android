package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The S20 probe against the fake: the order request without `includeUntracked` is refused as Amp refuses it. */
class ChangesOrderParamsTest {

    @Test
    fun `file order without includeUntracked is invalid params, and with it is ordered`() = FakeAmp.start("changes").use { server ->
        val socket = Wire(server).threadSocket(RUNNER_THREAD)
        val resume = socket.request("client_resume", json("version" to 0))
        socket.readUntil { it.string("id") == resume }

        val snapshotParams = json("requestId" to "snap-1", "baseRevision" to "index", "headRevision" to "worktree", "includeUntracked" to true, "content" to "renderable")
        socket.request("client_git_diff_snapshot", snapshotParams)
        val snapshot = socket.readUntil { it.isEvent("client_git_diff_snapshot_result") }.last().params().obj("snapshot")!!
        val files = snapshot.array("files")!!.map { it as JsonObject }.map { json("path" to it.string("path"), "changeType" to it.string("changeType"), "diff" to (it.string("diff") ?: "")) }
        val section = json("sectionID" to "unstaged", "diffHash" to snapshot.string("diffHash"), "baseRevision" to "index", "headRevision" to "worktree", "files" to files)

        val withoutFlag = socket.request("client_changes_file_order", json("requestId" to "order-1", "sections" to listOf(section)))
        val rejection = socket.readUntil { it.string("id") == withoutFlag }.last()
        assertEquals(json("code" to -32602, "message" to "Invalid method params"), rejection.obj("error"))

        val withFlag = socket.request("client_changes_file_order", json("requestId" to "order-2", "sections" to listOf(section + mapOf("includeUntracked" to element(true)))))
        val frames = socket.readUntil { it.isEvent("client_changes_file_order_result") }
        assertEquals(json("ok" to true), frames.first { it.string("id") == withFlag }.obj("result"))
        val result = frames.last().params()
        assertEquals("order-2", result.string("requestId"))
        assertEquals(
            json(
                "status" to "success",
                "sections" to listOf(
                    json(
                        "sectionID" to "unstaged",
                        "orderedPaths" to listOf("notes/plan.md", "calc.py", "legacy.py"),
                        "summary" to "Renames add to add_numbers, fixes its operator, and drops the legacy helper.",
                        "codeTour" to "Start with notes/plan.md for the intent, then calc.py for the rename, then legacy.py, which is removed.",
                    ),
                ),
            ),
            result.obj("result"),
        )
        socket.close()
    }
}
