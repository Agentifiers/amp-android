package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.runnerListing
import com.agentifiers.amp.fake.runnerStatus

/**
 * The S7 runner thread with one scripted Unstaged file whose diff is 3,750 lines long: `src/Table.kt` has 3,000
 * lines and every fourth one changes, so the hunks merge into one. It exercises the line-virtualized diff and its
 * accessibility node budget (git-and-diffs.md, "Driving the pane with agent-device").
 */
internal object ChangesLarge : ScenarioSpec {
    override val name = "changes-large"

    const val LINES = 3_000

    override fun build(): Scenario {
        val captured = ThreadScript.from(Capture.load("s07-changes"))
        val old = (1..LINES).joinToString("") { "    val v$it = $it\n" }
        val new = (1..LINES).joinToString("") { if (it % 4 == 0) "    val v$it = ${it * 2}\n" else "    val v$it = $it\n" }
        val table = json(
            "path" to "src/Table.kt", "changeType" to "modified", "created" to false, "diffToken" to "fake-table",
            "oldContent" to old, "newContent" to new, "isBinary" to false,
            "diffStat" to json("added" to LINES / 4, "deleted" to LINES / 4, "changed" to LINES / 4),
        )
        val exchanges = captured.exchanges + ChangesScript.meta() + listOf(
            ChangesScript.snapshot("index", "worktree", true, "fake-table-unstaged", listOf(table)),
            ChangesScript.snapshot(S7_HEAD, "index", false, "fake-table-staged", emptyList()),
            ChangesScript.order(
                listOf(ChangesScript.OrderSection("unstaged", "fake-table-unstaged", "index", "worktree", true, listOf(table))),
                listOf(json("sectionID" to "unstaged", "orderedPaths" to listOf("src/Table.kt"))),
            ),
        )
        val script = ThreadScript(captured.version, captured.history, captured.greeting, captured.burst, captured.turns, exchanges)
        return Scenario(listOf(FakeThread(RUNNER_THREAD, runnerListing(), runnerStatus(), script)))
    }
}
