package com.agentifiers.amp.fleet.newthread

import com.agentifiers.amp.model.AgentMode
import com.agentifiers.amp.model.ExecutorChoice
import com.agentifiers.amp.model.GitHost
import com.agentifiers.amp.model.ModeSource
import com.agentifiers.amp.model.OrbProvider
import com.agentifiers.amp.model.OrbSize
import com.agentifiers.amp.model.ProjectId
import com.agentifiers.amp.model.ProjectInfo
import com.agentifiers.amp.model.RunnerId
import com.agentifiers.amp.model.RunnerInfo
import com.agentifiers.amp.model.ShipBehavior
import com.agentifiers.amp.model.WorktreeChoice
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Outcome
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewThreadTest {
    private fun mode(key: String, source: ModeSource, description: String? = null) =
        AgentMode(key, key.replaceFirstChar { it.uppercase() }, locked = false, description = description, source = source)

    private val catalog = listOf(
        mode("low", ModeSource.Dial),
        mode("plugin-review", ModeSource.Plugin, description = "Reviews the diff"),
        mode("medium", ModeSource.Dial),
        mode("gpt-5", ModeSource.RawModel),
        mode("smart", ModeSource.BuiltIn),
        mode("high", ModeSource.Dial),
    )
    private val runnerModes = listOf(mode("medium", ModeSource.Plugin), mode("runner-docs", ModeSource.Plugin), mode("opus", ModeSource.RawModel))

    private val runner = RunnerInfo(
        RunnerId("r1"), "devbox-amp-app", persistentListOf("/Users/dev/Code/amp-android/"), shared = false, online = true,
        modes = persistentListOf(), canCreateWorktree = true,
    )
    private val project = ProjectInfo(ProjectId("p1"), "amp-android", persistentListOf(), ShipBehavior.Ship, GitHost.GitHub)

    @Test
    fun `amp modes merge the runner's, keep the first of a key, and order Dial, BuiltIn, Plugin`() {
        assertEquals(
            listOf("low", "medium", "high", "smart", "plugin-review", "runner-docs"),
            pickerModes(catalog, runnerModes, query = "", raw = false).map { it.key },
        )
    }

    @Test
    fun `raw models sit on their own tab`() {
        assertEquals(listOf("gpt-5", "opus"), pickerModes(catalog, runnerModes, query = "", raw = true).map { it.key })
        assertEquals(emptyList<String>(), pickerModes(catalog.filter { it.source != ModeSource.RawModel }, emptyList(), query = "", raw = true).map { it.key })
    }

    @Test
    fun `search matches display, key, or description, ignoring case`() {
        assertEquals(listOf("plugin-review"), pickerModes(catalog, runnerModes, query = "DIFF", raw = false).map { it.key })
        assertEquals(listOf("high"), pickerModes(catalog, runnerModes, query = " Hig ", raw = false).map { it.key })
        assertEquals(listOf("opus"), pickerModes(catalog, runnerModes, query = "opu", raw = true).map { it.key })
    }

    @Test
    fun `default mode is medium, else the first`() {
        assertEquals("medium", defaultMode(catalog)?.key)
        assertEquals("low", defaultMode(catalog.filter { it.key != "medium" })?.key)
        assertNull(defaultMode(emptyList()))
    }

    @Test
    fun `executor chip names the choice, leaving the project to its own picker`() {
        val runners = listOf(runner)
        assertEquals("Choose", executorLabel(null, runners))
        assertEquals(
            "devbox-amp-app · amp-android",
            executorLabel(ExecutorChoice.Runner(RunnerId("r1"), "/Users/dev/Code/amp-android/", WorktreeChoice.InPlace, null), runners),
        )
        assertEquals(
            "devbox-amp-app · amp-android · new worktree fix-login",
            executorLabel(ExecutorChoice.Runner(RunnerId("r1"), "/Users/dev/Code/amp-android", WorktreeChoice.NewWorktree("fix-login"), null), runners),
        )
        assertEquals("Orb · a1.large", executorLabel(ExecutorChoice.Orb(ProjectId("p1"), OrbSize("a1.large"), OrbProvider.Amp), runners))
    }

    @Test
    fun `project chip names the choice's project, or why there is none to pick`() {
        val projects = listOf(project)
        assertEquals("Project: pick an executor first", projectLabel(projectPick(null), projects))
        assertEquals("Project: needs a machine", projectLabel(projectPick(ExecutorChoice.NoMachine), projects))
        assertEquals("Project: No Project", projectLabel(projectPick(ExecutorChoice.Orb(null, OrbSize("a1.medium"), OrbProvider.Amp)), projects))
        assertEquals("Project: amp-android", projectLabel(projectPick(ExecutorChoice.Orb(ProjectId("p1"), OrbSize("a1.medium"), OrbProvider.Amp)), projects))
        assertEquals(
            "Project: gone",
            projectLabel(projectPick(ExecutorChoice.Runner(RunnerId("r1"), "/a", WorktreeChoice.InPlace, ProjectId("gone"))), projects),
        )
    }

    @Test
    fun `picking a project sets it on the orb or runner and leaves No machine alone`() {
        assertEquals(
            ExecutorChoice.Orb(ProjectId("p1"), OrbSize("a1.small"), OrbProvider.Amp),
            ExecutorChoice.Orb(null, OrbSize("a1.small"), OrbProvider.Amp).withProject(ProjectId("p1")),
        )
        assertEquals(
            ExecutorChoice.Runner(RunnerId("r1"), "/a", WorktreeChoice.InPlace, null),
            ExecutorChoice.Runner(RunnerId("r1"), "/a", WorktreeChoice.InPlace, ProjectId("p1")).withProject(null),
        )
        assertEquals(ExecutorChoice.NoMachine, ExecutorChoice.NoMachine.withProject(ProjectId("p1")))
    }

    @Test
    fun `worktree names follow Amp's rule and are suggested from the prompt`() {
        assertTrue(isValidWorktreeName("fix-login_2"))
        assertFalse(isValidWorktreeName("-fix"))
        assertFalse(isValidWorktreeName("fix login"))
        assertFalse(isValidWorktreeName(""))
        assertEquals("fix-the-login-crash", worktreeNameSuggestion("Fix the login crash, then add a test"))
        assertEquals("", worktreeNameSuggestion("  !!  "))
    }

    @Test
    fun `runner rows count directories and spot the Mac app`() {
        assertEquals("1 directory · Mac app", runnerSummary(runner))
        assertEquals("2 directories", runnerSummary(runner.copy(hostname = "build-box", directories = persistentListOf("/a", "/b"))))
    }

    @Test
    fun `start failures read as one notice`() {
        assertNull(startFailure(Outcome.Ok(Unit)))
        assertEquals("Starting a thread needs a connection.", startFailure(Outcome.Failed(EngineError.Offline)))
        assertEquals("Couldn't start the thread: runner busy", startFailure(Outcome.Failed(EngineError.Server("409", "runner busy"))))
        assertEquals("Couldn't start the thread: Amp did not answer", startFailure(Outcome.Unknown))
        assertEquals("Couldn't start the thread: policy", startFailure(Outcome.Refused("policy")))
    }
}
