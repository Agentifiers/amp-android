package com.agentifiers.amp.sync.internal.changes

import com.agentifiers.amp.sync.internal.fleet.testThreadDeps
import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.model.ChangeSource
import com.agentifiers.amp.model.ChangesAction
import com.agentifiers.amp.model.ChangesView
import com.agentifiers.amp.model.DiffSelection
import com.agentifiers.amp.model.DiffSide
import com.agentifiers.amp.model.FileDiffView
import com.agentifiers.amp.model.FileOrder
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.SectionKind
import com.agentifiers.amp.model.ShipTarget
import com.agentifiers.amp.model.StageState
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.TranscriptView
import com.agentifiers.amp.model.UserBlock
import com.agentifiers.amp.model.diff.DiffEngine
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.ViewerToken
import com.agentifiers.amp.sync.internal.attachments.AttachmentUploader
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.FixedBearer
import com.agentifiers.amp.sync.internal.net.NetLog
import com.agentifiers.amp.sync.internal.thread.ThreadDeps
import com.agentifiers.amp.sync.internal.thread.ThreadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Changes engine against the fake `changes` scenario, over real sockets, as the pane uses it. */
class ChangesEngineTest {
    private val runner = ThreadId.parse("T-01a16702-3202-73fb-8f52-e4498ccd3721")!!
    private val filesThread = ThreadId.parse("T-01a16738-2ec2-7354-98e9-688bc413f079")!!

    @Test
    fun `live git loads the uncommitted sections in intelligent order, stages a file, explains a hunk, and queues Ship`() = withEngine { engine ->
        val changes = engine.machine.changes
        val collector = CoroutineScope(Dispatchers.Default).launch { changes.view.collect {} }
        try {
            val first = changes.view.ready { it.order is FileOrder.Intelligent }
            assertEquals(ChangeSource.LiveGit("4bd66138fb8e40058af10a28e7512761f31a0de3"), first.source)
            val unstaged = first.repos.single().sections.first { it.kind == SectionKind.Unstaged }
            assertEquals(
                listOf(
                    "notes/plan.md" to false, "calc.py" to false, "legacy.py" to false, "__pycache__/calc.cpython-312.pyc" to true,
                ),
                unstaged.files.map { it.path to it.muted },
            )
            assertEquals("A notes/plan.md added +3", unstaged.files[0].rowText)
            assertEquals("Renames add to add_numbers, fixes its operator, and drops the legacy helper.", unstaged.summary)
            assertEquals(ShipTarget.Known("origin/main"), first.facts.target)
            assertEquals("main", first.facts.branch)
            assertEquals(true, first.facts.hasUncommitted)
            assertEquals(true, first.canStage)

            val calc = changes.diff("unstaged:calc.py").first { it is FileDiffView.Ready } as FileDiffView.Ready
            assertEquals(
                "@@ -1,2 +1,2 @@\n-def add(a, b):\n-    return a - b\n+def add_numbers(a, b):\n+    return a + b\n",
                DiffEngine.toUnified(calc),
            )
            val binary = changes.diff("unstaged:__pycache__/calc.cpython-312.pyc").first { it !is FileDiffView.Loading }
            assertEquals(FileDiffView.Binary(null, 251), binary)

            assertEquals(Outcome.Ok(Unit), changes.stage("calc.py", staged = true))
            val staged = changes.view.ready { view -> view.repos.single().sections.any { s -> s.kind == SectionKind.Staged && s.files.isNotEmpty() } }
            val sections = staged.repos.single().sections.associate { it.kind to it.files.map { file -> file.path to file.stage } }
            assertEquals(listOf("calc.py" to StageState.Staged), sections[SectionKind.Staged])
            assertEquals(
                listOf("notes/plan.md", "legacy.py", "__pycache__/calc.cpython-312.pyc"),
                sections[SectionKind.Unstaged]!!.map { it.first },
            )

            assertEquals(
                Outcome.Ok("Renames add to add_numbers and fixes the operator, so it now returns a + b instead of a - b."),
                changes.explain(DiffSelection("calc.py", DiffSide.New, 1, 2)),
            )

            val comment = "Diff review comments:\n1. calc.py:1-2 - Keep add as an alias."
            engine.dispatch(ThreadIntent.Send(com.agentifiers.amp.model.Draft.text(comment), com.agentifiers.amp.model.SendMode.Auto))
            engine.state.first { state -> state.transcript.userTexts().contains(comment) }

            val ship = MessageId("M-androidChangesShip000001")
            engine.dispatch(ThreadIntent.RunChangesAction(ChangesAction.Ship, "Ship all committed and uncommitted changes to origin/main.", ship))
            val queued = engine.state.first { state -> state.queue.isNotEmpty() }.queue.single()
            assertEquals("Ship all committed and uncommitted changes to origin/main." to false, queued.text to queued.steer)
            assertTrue(changes.view.ready { it.facts.shipInFlight }.facts.shipInFlight)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `a failed intelligent order leaves the files alphabetical with a plain note, not the server's words`() = withEngine("changes-order-fails") { engine ->
        val changes = engine.machine.changes
        val collector = CoroutineScope(Dispatchers.Default).launch { changes.view.collect {} }
        try {
            val view = changes.view.ready { it.order is FileOrder.Unavailable }
            assertEquals(FileOrder.Unavailable("Intelligent order isn't available for this thread."), view.order)
            val unstaged = view.repos.single().sections.first { it.kind == SectionKind.Unstaged }
            assertEquals(
                listOf("__pycache__/calc.cpython-312.pyc" to false, "calc.py" to false, "legacy.py" to false, "notes/plan.md" to false),
                unstaged.files.map { it.path to it.muted },
            )
            assertEquals(null, unstaged.summary)
            assertEquals(null, view.notice)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `a workspace with no git repository shows NoRepository, not a failure`() = withEngine("changes-no-repo", filesThread) { engine ->
        val changes = engine.machine.changes
        val collector = CoroutineScope(Dispatchers.Default).launch { changes.view.collect {} }
        try {
            assertEquals(ChangesView.NoRepository, changes.view.answered())
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `any other git failure with nothing loaded is still Failed, carrying the executor's message`() = withEngine("files", filesThread) { engine ->
        val changes = engine.machine.changes
        val collector = CoroutineScope(Dispatchers.Default).launch { changes.view.collect {} }
        try {
            assertEquals(
                ChangesView.Failed("fake-amp has no recording of client_git_command with these params"),
                changes.view.answered(),
            )
        } finally {
            collector.cancel()
        }
    }

    /** The first view that reflects git's answer, past Loading and the "Runner offline" shown before the socket is live. */
    private suspend fun kotlinx.coroutines.flow.StateFlow<ChangesView>.answered(): ChangesView =
        first { it is ChangesView.NoRepository || it is ChangesView.Failed || it is ChangesView.Ready }

    private fun TranscriptView.userTexts(): List<String> =
        (this as? TranscriptView.Ready)?.turns?.mapNotNull { it.prompt }?.flatMap { it.blocks }?.filterIsInstance<UserBlock.Text>()?.map { it.markdown }.orEmpty()

    private suspend fun kotlinx.coroutines.flow.StateFlow<ChangesView>.ready(predicate: (ChangesView.Ready) -> Boolean): ChangesView.Ready =
        first { it is ChangesView.Ready && predicate(it) } as ChangesView.Ready

    private fun withEngine(scenario: String = "changes", thread: ThreadId = runner, block: suspend (ThreadEngine) -> Unit) = FakeAmp.start(scenario, frameGapCapMs = 20).use { server ->
        val client = OkHttpClient()
        val endpoint = Endpoint(server.ampBaseUrl.toHttpUrl(), server.actorsBaseUrl.toHttpUrl(), "pk_fake")
        val http = AmpHttp(client, endpoint, FixedBearer("fake-amp-accepts-any-bearer"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val deps = testThreadDeps(client, endpoint, http, AttachmentUploader(client, endpoint, FixedBearer("fake-amp-accepts-any-bearer")), scope)
        val engine = ThreadEngine(thread, deps, scope)
        val lease = engine.attach(ViewerToken("test-window"))
        try {
            runBlocking { withTimeout(30_000) { block(engine) } }
        } finally {
            lease.close()
            scope.cancel()
        }
    }
}
