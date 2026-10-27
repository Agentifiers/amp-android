package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.array
import com.agentifiers.amp.fake.obj
import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.Event
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Reply
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.SocketExchange
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.plus
import com.agentifiers.amp.fake.runnerListing
import com.agentifiers.amp.fake.runnerStatus
import com.agentifiers.amp.fake.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * S7 live git on the runner thread (status, head, range snapshot, file diff, `INVALID_ARGS` capture), with the
 * `client_git_diff_snapshot` result widened by two scripted files: an added `notes/plan.md` and a deleted `legacy.py`.
 *
 * For the Changes pane it also scripts, in the S7 shapes, the Uncommitted sections before and after `calc.py` is
 * staged, intelligent order (the `.pyc` is left out, so it is muted), Explain on `calc.py` lines 1-2, and a first
 * turn that keeps working, so a Ship sent after it queues.
 */
internal object Changes : ScenarioSpec {
    override val name = "changes"

    override fun build(): Scenario = scenario(orderFails = false)

    /** With [orderFails], both order requests are answered `{status:"failure"}` and everything else stays the same. */
    fun scenario(orderFails: Boolean): Scenario {
        val captured = ThreadScript.from(Capture.load("s07-changes"))
        val exchanges = captured.exchanges.map { if (it.method == "client_git_diff_snapshot") widened(it) else it }
        val recorded = captured.exchanges.first { it.method == "client_git_diff_snapshot" }.replies
            .firstNotNullOf { (it as? Reply.Notify)?.event?.body?.obj("snapshot") }.array("files").orEmpty().map { it as JsonObject }
        val script = ThreadScript(captured.version, captured.history, captured.greeting, captured.burst, listOf(ChangesScript.workingTurn()), exchanges + review(recorded, orderFails))
        return Scenario(listOf(FakeThread(RUNNER_THREAD, runnerListing(), runnerStatus(), script)))
    }

    /** [recorded] is the S7 snapshot's `.pyc` and `calc.py`. */
    private fun review(recorded: List<JsonObject>, orderFails: Boolean): List<SocketExchange> {
        val calc = recorded.first { it.string("path") == "calc.py" }
        val unstagedBefore = recorded + SCRIPTED_FILES
        val unstagedAfter = unstagedBefore - calc
        fun unstaged(hash: String, files: List<JsonObject>) = ChangesScript.OrderSection("unstaged", hash, "index", "worktree", true, files)
        fun staged(hash: String, files: List<JsonObject>) = ChangesScript.OrderSection("staged", hash, S7_HEAD, "index", false, files)
        fun order(sections: List<ChangesScript.OrderSection>, results: List<JsonObject>) =
            if (orderFails) ChangesScript.orderFails(sections, ORDER_ERROR) else ChangesScript.order(sections, results)
        val important = listOf("notes/plan.md", "calc.py", "legacy.py")
        return ChangesScript.meta() + listOf(
            ChangesScript.snapshot("index", "worktree", true, "fake-unstaged-before", unstagedBefore),
            ChangesScript.snapshot("index", "worktree", true, "fake-unstaged-after", unstagedAfter),
            ChangesScript.snapshot(S7_HEAD, "index", false, "fake-staged-before", emptyList()),
            ChangesScript.snapshot(S7_HEAD, "index", false, "fake-staged-after", listOf(calc)),
            ChangesScript.command("stage_file", "", "path" to "calc.py"),
            ChangesScript.command("unstage_file", "", "path" to "calc.py"),
            order(
                listOf(unstaged("fake-unstaged-before", unstagedBefore)),
                listOf(json("sectionID" to "unstaged", "orderedPaths" to important, "summary" to SUMMARY, "codeTour" to TOUR)),
            ),
            order(
                listOf(unstaged("fake-unstaged-after", unstagedAfter), staged("fake-staged-after", listOf(calc))),
                listOf(
                    json("sectionID" to "unstaged", "orderedPaths" to important - "calc.py", "summary" to SUMMARY),
                    json("sectionID" to "staged", "orderedPaths" to listOf("calc.py")),
                ),
            ),
            ChangesScript.explain(
                json(
                    "path" to "calc.py", "side" to "additions", "startLine" to 1, "endLine" to 2, "fileStatus" to "modified",
                    "selectedCode" to "def add_numbers(a, b):\n    return a + b", "contextScope" to "file", "fileHead" to S7_HEAD,
                    "contextBefore" to "", "contextAfter" to "",
                ),
                "Renames add to add_numbers and fixes the operator, so it now returns a + b instead of a - b.",
            ),
        )
    }

    private const val SUMMARY = "Renames add to add_numbers, fixes its operator, and drops the legacy helper."
    private const val TOUR = "Start with notes/plan.md for the intent, then calc.py for the rename, then legacy.py, which is removed."
    private const val ORDER_ERROR = "Changes file order failed: model returned no sections"

    private fun widened(exchange: SocketExchange): SocketExchange = SocketExchange(
        exchange.method,
        exchange.params,
        exchange.replies.map { reply ->
            val event = (reply as? Reply.Notify)?.event ?: return@map reply
            val snapshot = event.body.obj("snapshot") ?: return@map reply
            val files = JsonArray(snapshot.array("files").orEmpty() + SCRIPTED_FILES)
            Reply.Notify(Event(event.type, event.body + mapOf("snapshot" to (snapshot + mapOf("files" to files)))))
        },
    )

    private val SCRIPTED_FILES: List<JsonObject> = listOf(
        file(
            path = "notes/plan.md", changeType = "added", created = true,
            diff = "diff --git a/notes/plan.md b/notes/plan.md\nnew file mode 100644\nindex 0000000..5d1f2a7\n--- /dev/null\n+++ b/notes/plan.md\n@@ -0,0 +1,3 @@\n+# Plan\n+\n+Rename add to add_numbers.",
            old = "", new = "# Plan\n\nRename add to add_numbers.\n", added = 3, deleted = 0,
        ),
        file(
            path = "legacy.py", changeType = "deleted", created = false,
            diff = "diff --git a/legacy.py b/legacy.py\ndeleted file mode 100644\nindex 8c3e1b0..0000000\n--- a/legacy.py\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-def old_add(a, b):\n-    return a + b",
            old = "def old_add(a, b):\n    return a + b\n", new = "", added = 0, deleted = 2,
        ),
    )

    private fun file(path: String, changeType: String, created: Boolean, diff: String, old: String, new: String, added: Int, deleted: Int) = json(
        "path" to path,
        "changeType" to changeType,
        "created" to created,
        "diffToken" to "fake-$path",
        "diff" to diff,
        "fullFileDiff" to diff,
        "oldContent" to old,
        "newContent" to new,
        "isBinary" to false,
        "diffStat" to json("added" to added, "deleted" to deleted, "changed" to maxOf(added, deleted)),
    )
}
