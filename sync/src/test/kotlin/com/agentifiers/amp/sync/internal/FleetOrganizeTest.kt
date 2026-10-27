package com.agentifiers.amp.sync.internal

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.fake.FakeAmpServer
import com.agentifiers.amp.fake.ReadMark
import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.FleetView
import com.agentifiers.amp.model.Freshness
import com.agentifiers.amp.model.LabelName
import com.agentifiers.amp.model.Multiplayer
import com.agentifiers.amp.model.ProjectId
import com.agentifiers.amp.model.Sidebar
import com.agentifiers.amp.model.SnoozeUntil
import com.agentifiers.amp.model.ThreadColor
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadProject
import com.agentifiers.amp.model.ThreadQuery
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.model.Viewer
import com.agentifiers.amp.model.UserId
import com.agentifiers.amp.model.Visibility
import com.agentifiers.amp.sync.api.ViewerToken
import com.agentifiers.amp.sync.internal.fleet.MemoryLedger
import com.agentifiers.amp.sync.internal.fleet.MemoryWatchList
import com.agentifiers.amp.sync.internal.push.LeaseLedger
import com.agentifiers.amp.sync.internal.push.LeasedThreadHandle
import com.agentifiers.amp.sync.internal.push.WatchedThreads
import com.agentifiers.amp.sync.internal.attachments.AttachmentUploader
import com.agentifiers.amp.sync.internal.fleet.FleetEngine
import com.agentifiers.amp.sync.internal.fleet.OrganizeLedger
import com.agentifiers.amp.sync.internal.fleet.Organizer
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.FixedBearer
import com.agentifiers.amp.sync.internal.net.NetLog
import com.agentifiers.amp.sync.internal.thread.ThreadDeps
import com.agentifiers.amp.sync.internal.thread.ThreadEngine
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

/** The 40-thread `fleet-organize` fleet of local://U11-contract.md, driven through the engines over real HTTP and sockets. */
class FleetOrganizeTest {
    private val client = OkHttpClient()
    private val servers = ArrayList<FakeAmpServer>()
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun stop() {
        scopes.forEach { it.cancel() }
        servers.forEach { it.close() }
    }

    @Test
    fun sidebarSectionsMatchTheScriptedFleet() = runBlocking {
        val sidebar = account(start("fleet-organize")).loaded()

        assertEquals(listOf(3, 4, 5), sidebar.needsYou.numbers())
        assertEquals(listOf(7, 6), sidebar.pinned.numbers())
        assertEquals(
            listOf(
                "amp-android" to listOf(1, 39, 8, 9, 10, 11, 12, 13, 14, 15),
                "relay" to listOf(2, 16, 17, 18, 19, 20),
                "No Project" to listOf(21, 22, 40, 23),
            ),
            sidebar.active.map { group -> ((group.project as? ThreadProject.Linked)?.name ?: "No Project") to group.threads.numbers() },
        )
        assertEquals(ThreadProject.Linked(ProjectId("proj-android"), "amp-android"), sidebar.active.first().project)
        assertEquals(listOf(24, 25, 26, 27, 28, 29, 30, 31), sidebar.inactive72h.numbers())
        assertEquals(emptyList<Int>(), sidebar.snoozed.numbers())
        assertEquals(listOf(32, 33, 34), sidebar.archived.numbers())
        assertEquals(FleetStatus.NeedsInput("Bash: ./gradlew migrate"), sidebar.needsYou.first().status)
        assertEquals(listOf(LabelName("docs"), LabelName("planning")), sidebar.pinned.last().labels)
    }

    @Test
    fun querySyntaxReturnsTheExpectedThreads() = runBlocking {
        val account = account(start("fleet-organize"))
        account.loaded()
        val expected = mapOf(
            "label:bug" to listOf(9, 17, 10, 24),
            "label:ui -label:bug" to listOf(1, 8, 25),
            "project:relay pinned:true" to listOf(7),
            "archived:true" to listOf(32, 33, 34),
            "crease" to listOf(1),
            "parent:${id(1).raw}" to listOf(35, 36),
            "visibility:unlisted" to listOf(40),
            "puck:true" to listOf(37, 38),
        )
        val actual = expected.keys.associateWith { query -> account.fleet.search(ThreadQuery.parse(query)).first().numbers() }
        assertEquals(expected, actual)
        assertEquals(listOf(35, 36), account.fleet.children(id(1)).first { it.isNotEmpty() }.numbers())
    }

    @Test
    fun everyRowActionSurvivesAReload() = runBlocking {
        val server = start("fleet-organize")
        val ledger = MemoryLedger()
        val first = account(server, ledger)
        first.loaded()

        first.act(21, ThreadIntent.Pin(true))
        first.act(22, ThreadIntent.Color(ThreadColor.Purple))
        first.act(8, ThreadIntent.SetLabels(persistentListOf(LabelName("ui"), LabelName("release"))))
        first.act(12, ThreadIntent.Rename("Keyboard shortcuts v2"))
        first.act(13, ThreadIntent.Snooze(SnoozeUntil.Indefinitely))
        first.act(14, ThreadIntent.Archive(true))
        first.act(15, ThreadIntent.Delete)
        first.act(11, ThreadIntent.MarkUnread)
        first.act(40, ThreadIntent.SetVisibility(Visibility.Private))
        first.act(7, ThreadIntent.Pin(false))

        val expected = listOf(
            "pinned" to listOf(21, 6),
            "color 22" to ThreadColor.Purple,
            "labels 8" to listOf(LabelName("ui"), LabelName("release")),
            "title 12" to "Keyboard shortcuts v2",
            "snoozed" to listOf(13),
            "archived" to listOf(32, 14, 33, 34),
            "row 15" to null,
            "needs you" to listOf(3, 4, 5, 11),
            "visibility 40" to Visibility.Private,
            "relay" to listOf(2, 7, 16, 17, 18, 19, 20),
        )
        val reloaded = reloadUntil(server, ledger) { organized(it) == expected }
        assertEquals(expected, organized(reloaded))
    }

    /** The facts [everyRowActionSurvivesAReload] checks. The socket rename can settle after the HTTP writes, so the reload waits for all of them. */
    private fun organized(sidebar: Sidebar): List<Pair<String, Any?>> {
        val rows = sidebar.all().associateBy { it.id }
        return listOf(
            "pinned" to sidebar.pinned.numbers(),
            "color 22" to rows[id(22)]?.color,
            "labels 8" to rows[id(8)]?.labels,
            "title 12" to rows[id(12)]?.title,
            "snoozed" to sidebar.snoozed.numbers(),
            "archived" to sidebar.archived.numbers(),
            "row 15" to rows[id(15)],
            "needs you" to sidebar.needsYou.numbers(),
            "visibility 40" to rows[id(40)]?.visibility,
            "relay" to sidebar.active.firstOrNull { (it.project as? ThreadProject.Linked)?.name == "relay" }?.threads?.numbers()?.sorted(),
        )
    }

    @Test
    fun archiveAllArchivesTheInactiveSection() = runBlocking {
        val server = start("fleet-organize")
        val account = account(server)
        val inactive = account.loaded().inactive72h.map { it.id }
        account.fleet.archiveAll(inactive.let { persistentListOf(*it.toTypedArray()) })
        withTimeout(TIMEOUT_MS) { account.fleet.sidebar.first { it.inactive72h.isEmpty() && it.archived.size == 11 } }

        val reloaded = reloadUntil(server, MemoryLedger()) { it.archived.size == 11 }
        assertEquals(listOf(24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34).sorted(), reloaded.archived.numbers().sorted())
    }

    @Test
    fun aSecondUserShowsAsPresenceAndTyping() = runBlocking {
        val account = account(start("share-presence"))
        account.loaded()
        val thread = account.thread(ThreadId.parse("T-01a16702-3202-73fb-8f52-e4498ccd3721")!!)
        val lease = thread.attach(ViewerToken("test"))
        val ada = Viewer(UserId("user_01JTEAMMATE00000000000000"), "Ada Lovelace")
        val state = withTimeout(TIMEOUT_MS) { thread.state.first { it.presence.viewers.isNotEmpty() && it.presence.typing.isNotEmpty() } }
        assertEquals(listOf(ada), state.presence.viewers)
        assertEquals(listOf(ada), state.presence.typing)

        thread.dispatch(ThreadIntent.OpenToCollaborators(3600))
        val open = withTimeout(TIMEOUT_MS) { thread.state.first { it.multiplayer is Multiplayer.Open } }
        assertEquals(Multiplayer.Open::class, open.multiplayer::class)
        lease.close()
    }

    @Test
    fun puckOpensAFreshConversationAndAnswers() = runBlocking {
        val account = account(start("puck-chat"))
        account.loaded()
        assertEquals(listOf(37, 38), account.puck.conversations.first { it.size == 2 }.numbers())

        val fresh = (account.puck.open(startFresh = true) as com.agentifiers.amp.sync.api.Outcome.Ok).value
        val listed = account.puck.conversations.first { list -> list.any { it.id == fresh.thread } }
        assertEquals(listOf("New conversation", "Puck: label cleanup"), listed.map { it.title })

        val thread = account.thread(fresh.thread)
        val lease = thread.attach(ViewerToken("test"))
        withTimeout(TIMEOUT_MS) { thread.state.first { it.link == com.agentifiers.amp.model.Link.Live } }
        thread.dispatch(ThreadIntent.Send(com.agentifiers.amp.model.Draft.text("Start a thread to fix the rail"), com.agentifiers.amp.model.SendMode.Auto))
        val replied = withTimeout(TIMEOUT_MS) {
            thread.state.first { state -> state.assistantText().contains("I can start an agent for that. Which project?") }
        }
        assertEquals("I can start an agent for that. Which project?", replied.assistantText().last())
        lease.close()
    }

    @Test
    fun showingAnUnreadThreadMarksItReadAndMarkAsUnreadSticks() = runBlocking {
        val server = start("fleet-basic")
        val account = account(server)
        val runner = ThreadId.parse("T-01a16702-3202-73fb-8f52-e4498ccd3721")!!
        assertEquals(listOf(FleetStatus.Done), account.loaded().needsYou.filter { it.id == runner }.map { it.status })

        val shown = account.show(runner)
        val read = withTimeout(TIMEOUT_MS) { account.fleet.sidebar.first { sidebar -> sidebar.all().any { it.id == runner && it.status == FleetStatus.Idle } } }
        assertEquals(emptyList<ThreadSummary>(), read.needsYou.filter { it.id == runner })
        withTimeout(TIMEOUT_MS) { while (server.readMarks.isEmpty()) kotlinx.coroutines.delay(50) }
        assertEquals(listOf(ReadMark("T-01a16702-3202-73fb-8f52-e4498ccd3721", "M-034kiPr07BmkiGcd3C8Pek")), server.readMarks)

        account.thread(runner).dispatch(ThreadIntent.MarkUnread)
        withTimeout(TIMEOUT_MS) { account.fleet.sidebar.first { sidebar -> sidebar.needsYou.any { it.id == runner } } }
        kotlinx.coroutines.delay(RELOAD_GAP_MS)
        account.fleet.refresh()
        kotlinx.coroutines.delay(RELOAD_GAP_MS)
        assertEquals(listOf(FleetStatus.Done), account.fleet.sidebar.value.needsYou.filter { it.id == runner }.map { it.status })
        assertEquals(1, server.readMarks.size)
        shown.close()
    }

    @Test
    fun freshnessHasNoTimeUntilAReadAnswers() = runBlocking {
        val server = start("fleet-organize")
        val account = account(server)
        val before = Clock.System.now()
        val loaded = account.loaded()
        server.close()
        account.fleet.refresh()
        val cached = withTimeout(TIMEOUT_MS) { account.fleet.sidebar.first { it.freshness is Freshness.Cached } }
        assertEquals(loaded.all().map { it.id }, cached.all().map { it.id })
        assertTrue((cached.freshness as Freshness.Cached).asOf in before..Clock.System.now())

        val never = account(server)
        never.fleet.refresh()
        withTimeout(TIMEOUT_MS) { never.fleet.threads.first { it is FleetView.Failed } }
        assertEquals(Freshness.NotLoaded, never.fleet.sidebar.value.freshness)
    }

    private fun start(scenario: String) = FakeAmp.start(scenario, frameGapCapMs = 20).also { servers += it }

    private fun account(server: FakeAmpServer, ledger: OrganizeLedger = MemoryLedger()): Account {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return Account(server, ledger, scope)
    }

    /** A fresh engine reads only the server and the ledger, so it sees what a restart would; retried while writes land. */
    private suspend fun reloadUntil(server: FakeAmpServer, ledger: OrganizeLedger, done: (Sidebar) -> Boolean): Sidebar {
        var last: Sidebar? = null
        repeat(RELOADS) {
            val sidebar = account(server, ledger).loaded()
            if (done(sidebar)) return sidebar
            last = sidebar
            kotlinx.coroutines.delay(RELOAD_GAP_MS)
        }
        return last!!
    }

    private inner class Account(server: FakeAmpServer, ledger: OrganizeLedger, private val scope: CoroutineScope) {
        private val endpoint = Endpoint(server.ampBaseUrl.toHttpUrl(), server.actorsBaseUrl.toHttpUrl(), "fake-rivet")
        private val http = AmpHttp(client, endpoint, FixedBearer("fake-token"))
        val threads = ConcurrentHashMap<ThreadId, ThreadEngine>()
        private val leases = LeaseLedger()
        val fleet = FleetEngine(http, scope, {}, WatchedThreads(MemoryWatchList(), scope), ledger, leases.threads, dispatch = { thread, intent -> thread(thread).dispatch(intent) }, threadState = { thread -> thread(thread).state })
        private val deps = ThreadDeps(client, endpoint, http, "test", NetLog(false), {}, com.agentifiers.amp.sync.internal.changes.MemoryDatabase(), AttachmentUploader(client, endpoint, FixedBearer("fake-token")), Organizer(http, fleet, scope) {}, fleet::self)
        val puck = com.agentifiers.amp.sync.internal.puck.PuckEngine(http, fleet, scope, {}, ::thread)

        fun thread(id: ThreadId): ThreadEngine = threads.computeIfAbsent(id) { ThreadEngine(it, deps, scope) }

        /** A window showing [id], as :app's VisibleThreadLeases attaches it. */
        fun show(id: ThreadId): AutoCloseable = LeasedThreadHandle(thread(id), leases) {}.attach(ViewerToken("test"))

        suspend fun loaded(): Sidebar {
            fleet.refresh()
            return withTimeout(TIMEOUT_MS) { fleet.sidebar.first { it.all().isNotEmpty() } }
        }

        fun act(number: Int, intent: ThreadIntent) {
            thread(id(number)).dispatch(intent)
        }
    }


    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val RELOADS = 30
        const val RELOAD_GAP_MS = 500L

        fun id(number: Int): ThreadId = ThreadId.parse("T-f1ee7000-0000-4000-8000-0000000000%02d".format(number))!!

        fun List<ThreadSummary>.numbers(): List<Int> = map { it.id.raw.takeLast(2).toInt() }

        fun Sidebar.all(): List<ThreadSummary> = needsYou + pinned + active.flatMap { it.threads } + inactive72h + snoozed + archived

        fun com.agentifiers.amp.model.ThreadState.assistantText(): List<String> =
            (transcript as? com.agentifiers.amp.model.TranscriptView.Ready)?.turns.orEmpty()
                .flatMap { turn -> turn.steps.filterIsInstance<com.agentifiers.amp.model.Step.Text>().map { it.markdown } }
    }
}
