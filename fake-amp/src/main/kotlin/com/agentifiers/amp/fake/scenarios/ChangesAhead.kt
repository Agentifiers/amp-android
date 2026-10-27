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
 * The S7 runner thread on branch `feature/zigzag-walk`, three commits ahead of `origin/release/2026-10`, with one
 * uncommitted edit to `zigzag.py`. Real branches are usually ahead of their base, which widens the closed scope picker
 * beside Ship in the action bar. The oldest commit has a long subject and its own scripted snapshot, so its scope widens
 * the picker further.
 */
internal object ChangesAhead : ScenarioSpec {
    override val name = "changes-ahead"

    const val MERGE_BASE = "9f1c2e7a4b3d5f60718293a4b5c6d7e8f9012345"
    const val SKETCH = "3a9b8c7d6e5f40312a2b3c4d5e6f708192a3b4c5"

    override fun build(): Scenario {
        val captured = ThreadScript.from(Capture.load("s07-changes"))
        val zigzag = json(
            "path" to "zigzag.py", "changeType" to "modified", "created" to false, "diffToken" to "fake-zigzag",
            "oldContent" to "def walk(n):\n    return range(n)\n", "newContent" to "def walk(n):\n    return range(n - 1, -1, -1)\n",
            "isBinary" to false, "diffStat" to json("added" to 1, "deleted" to 1, "changed" to 1),
        )
        val sketch = json(
            "path" to "zigzag.py", "changeType" to "added", "created" to true, "diffToken" to "fake-zigzag-sketch",
            "oldContent" to "", "newContent" to "def walk(n):\n    return range(n)\n",
            "isBinary" to false, "diffStat" to json("added" to 2, "deleted" to 0, "changed" to 0),
        )
        val ahead = listOf(
            commit(S7_HEAD, "Walk the zigzag both ways", "7c4e2a19d0b3f5e6a7b8c9d0e1f2a3b4c5d6e7f8"),
            commit("7c4e2a19d0b3f5e6a7b8c9d0e1f2a3b4c5d6e7f8", "Add the zigzag walk", SKETCH),
            commit(SKETCH, "Sketch the zigzag module with a walk in both directions and a shared step helper", MERGE_BASE),
        )
        val sketchSection = "commit-$SKETCH"
        val exchanges = captured.exchanges +
            ChangesScript.meta("feature/zigzag-walk", "refs/remotes/origin/release/2026-10", MERGE_BASE, ahead) +
            listOf(
                ChangesScript.snapshot("index", "worktree", true, "fake-zigzag-unstaged", listOf(zigzag)),
                ChangesScript.snapshot(S7_HEAD, "index", false, "fake-zigzag-staged", emptyList()),
                ChangesScript.snapshot(MERGE_BASE, SKETCH, false, "fake-zigzag-sketch", listOf(sketch)),
                ChangesScript.order(
                    listOf(ChangesScript.OrderSection("unstaged", "fake-zigzag-unstaged", "index", "worktree", true, listOf(zigzag))),
                    listOf(json("sectionID" to "unstaged", "orderedPaths" to listOf("zigzag.py"))),
                ),
                ChangesScript.order(
                    listOf(ChangesScript.OrderSection(sketchSection, "fake-zigzag-sketch", MERGE_BASE, SKETCH, false, listOf(sketch))),
                    listOf(json("sectionID" to sketchSection, "orderedPaths" to listOf("zigzag.py"))),
                ),
            )
        val script = ThreadScript(captured.version, captured.history, captured.greeting, captured.burst, captured.turns, exchanges)
        return Scenario(listOf(FakeThread(RUNNER_THREAD, runnerListing(), runnerStatus(), script)))
    }

    private fun commit(hash: String, subject: String, parent: String) =
        json("hash" to hash, "shortHash" to hash.take(7), "subject" to subject, "parentHash" to parent)
}
