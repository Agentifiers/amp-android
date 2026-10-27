package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.SocketExchange
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.runnerListing
import com.agentifiers.amp.fake.runnerStatus
import kotlinx.serialization.json.JsonObject

/**
 * The S7 runner thread with a Changes snapshot whose one Unstaged file, `src/Huge.kt`, carries old and new contents
 * of [LINES] lines each, so the snapshot row the app would cache is over Android's 2 MB CursorWindow.
 */
internal object ChangesOversizeSnapshot : ScenarioSpec {
    override val name = "changes-oversize-snapshot"

    const val LINES = 60_000

    override fun build(): Scenario {
        val old = (1..LINES).joinToString("") { "    val value$it = $it\n" }
        val new = (1..LINES).joinToString("") { if (it % 4 == 0) "    val value$it = ${it * 2}\n" else "    val value$it = $it\n" }
        return oversizeChanges(file("src/Huge.kt", old, new, changed = LINES / 4), orderedPaths = listOf("src/Huge.kt"))
    }
}

/**
 * The S7 runner thread with one small Unstaged file whose intelligent order lists [PADDING_PATHS] more paths than the
 * diff has, so the file order row the app would cache is over Android's 2 MB CursorWindow.
 */
internal object ChangesOversizeOrder : ScenarioSpec {
    override val name = "changes-oversize-order"

    const val PADDING_PATHS = 70_000

    override fun build(): Scenario {
        val padding = (1..PADDING_PATHS).map { "generated/padding/file-${it.toString().padStart(6, '0')}.kt" }
        return oversizeChanges(file("src/Small.kt", "val a = 1\n", "val a = 2\n", changed = 1), orderedPaths = listOf("src/Small.kt") + padding)
    }
}

private fun file(path: String, old: String, new: String, changed: Int): JsonObject = json(
    "path" to path, "changeType" to "modified", "created" to false, "diffToken" to "fake-$path",
    "oldContent" to old, "newContent" to new, "isBinary" to false,
    "diffStat" to json("added" to changed, "deleted" to changed, "changed" to changed),
)

private fun oversizeChanges(file: JsonObject, orderedPaths: List<String>): Scenario {
    val captured = ThreadScript.from(Capture.load("s07-changes"))
    val exchanges: List<SocketExchange> = captured.exchanges + ChangesScript.meta() + listOf(
        ChangesScript.snapshot("index", "worktree", true, "fake-oversize-unstaged", listOf(file)),
        ChangesScript.snapshot(S7_HEAD, "index", false, "fake-oversize-staged", emptyList()),
        ChangesScript.order(
            listOf(ChangesScript.OrderSection("unstaged", "fake-oversize-unstaged", "index", "worktree", true, listOf(file))),
            listOf(json("sectionID" to "unstaged", "orderedPaths" to orderedPaths)),
        ),
    )
    val script = ThreadScript(captured.version, captured.history, captured.greeting, captured.burst, captured.turns, exchanges)
    return Scenario(listOf(FakeThread(RUNNER_THREAD, runnerListing(), runnerStatus(), script)))
}
