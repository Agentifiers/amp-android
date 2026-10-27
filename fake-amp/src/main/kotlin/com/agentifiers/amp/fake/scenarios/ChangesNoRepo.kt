package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.GATE_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.gateStatus
import com.agentifiers.amp.fake.listingFor

/**
 * The `files` thread and workspace, where the executor's folder is not a git repository: every git command the
 * Changes pane sends exits 128 with git's own stderr, as `git rev-parse HEAD` does outside a repository. Files keep
 * working, so the pane and the Files tab can be checked side by side.
 */
internal object ChangesNoRepo : ScenarioSpec {
    override val name = "changes-no-repo"

    override fun build(): Scenario {
        val captured = ThreadScript.from(Capture.load("s19-files"))
        val git = listOf("head", "status", "branch", "comparison_base_ref", "comparison_base_head", "ahead_commits")
            .map { ChangesScript.command(it, 128, "", "fatal: not a git repository (or any of the parent directories): .git\n") }
        val script = captured.plus(exchanges = git)
        return Scenario(listOf(FakeThread(GATE_THREAD, listingFor(GATE_THREAD, null, script.version), gateStatus(), script, workspace = s19Workspace())))
    }
}
