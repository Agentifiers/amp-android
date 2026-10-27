package com.agentifiers.amp.sync.internal.fleet

import com.agentifiers.amp.model.ActivityItem
import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.FleetView
import com.agentifiers.amp.model.Freshness
import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.IntentId
import com.agentifiers.amp.model.LabelName
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.ProjectId
import com.agentifiers.amp.model.Sidebar
import com.agentifiers.amp.model.SnoozeSource
import com.agentifiers.amp.model.SnoozeState
import com.agentifiers.amp.model.ThreadColor
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadProject
import com.agentifiers.amp.model.ThreadQuery
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.model.UserId
import com.agentifiers.amp.model.Visibility
import com.agentifiers.amp.model.arrangeSidebar
import com.agentifiers.amp.model.fleetStatus
import com.agentifiers.amp.model.matches
import com.agentifiers.amp.sync.api.ActivityPage
import com.agentifiers.amp.sync.api.Fleet
import com.agentifiers.amp.sync.api.FleetNotice
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Outcome
import com.agentifiers.amp.sync.internal.push.WatchedThreads
import com.agentifiers.amp.sync.internal.wire.RivetNumber
import com.agentifiers.amp.sync.internal.wire.array
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.long
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant

/** One `listThreads` row, parsed. */
private data class Listed(
    val id: ThreadId,
    val version: Long,
    val title: String?,
    val pinned: Boolean,
    val archived: Boolean,
    val visibility: Visibility?,
    val lastActivity: Instant?,
    val parent: ThreadId?,
    val isPuck: Boolean,
    val creator: UserId?,
    val executor: String?,
)

/** One `getRecentThreads` summary, parsed. [latestReply] is `latestAssistantMessageID`, which a read marks up to (S10). */
private data class Recent(val status: FleetStatus, val unread: Boolean, val latestReply: MessageId?)

private data class CachedLabels(val version: Long, val labels: PersistentList<LabelName>)

/** A write this device made, shown over the server's rows until a read that started after its ack. */
private data class Expectation(val thread: ThreadId, val intent: ThreadIntent, val ackedBeforeLoad: Int?)

/** What every output is derived from. [summaries] is null until the first load answers. */
private data class Snapshot(val summaries: List<ThreadSummary>?, val failure: String?, val asOf: Instant?)

/**
 * The account's threads: `listThreads` rows (archived ones included, for the Archived section), the user actor's
 * `getRecentThreads` for live state (S2), `getThreadProject` and `getThreadLabels` per thread, and the device
 * [ledger] for snooze and color, which have no read path (S10). A [viewed] thread's own socket overrides its status,
 * gates first, so a gate open in a window puts the row in Needs you at once. Organize writes show at once through
 * [expect] and reconcile after [settled]. A thread in [viewed] is marked read. State is confined to one dispatcher.
 */
internal class FleetEngine(
    private val http: AmpHttp,
    private val scope: CoroutineScope,
    private val onUnauthorized: () -> Unit,
    private val watchedThreads: WatchedThreads,
    private val ledger: OrganizeLedger,
    /** Threads a started window shows, from the viewer leases. */
    private val viewed: StateFlow<Set<ThreadId>>,
    private val dispatch: (ThreadId, ThreadIntent) -> IntentId,
    /** The thread's socket state; read only for [viewed] threads, whose leases keep their sockets open. */
    private val threadState: (ThreadId) -> StateFlow<ThreadState>,
    private val clock: () -> Instant = { Clock.System.now() },
) : Fleet {
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private var listed: List<Listed>? = null
    private var recent: Map<ThreadId, Recent>? = null
    private val labelCache = HashMap<ThreadId, CachedLabels>()
    private val projectCache = HashMap<ThreadId, ThreadProject>()
    private var snoozes: Map<ThreadId, DeviceSnooze> = emptyMap()
    private var colors: Map<ThreadId, ThreadColor> = emptyMap()
    private val expected = LinkedHashMap<IntentId, Expectation>()
    private val bulkArchive = HashSet<ThreadId>()
    private var loadsStarted = 0
    private var failure: String? = null
    private var asOf: Instant? = null
    private var loading: Job? = null
    private var reloadAfter = false
    private var viewing: Set<ThreadId> = emptySet()
    /** The reply each viewed thread was marked read up to while it stays shown, so Mark as Unread during the view sticks. */
    private val readWhileViewing = HashMap<ThreadId, MessageId>()
    /** The socket state of each viewed thread, refreshed only when its gates, activity, or link change. */
    private var live: Map<ThreadId, ThreadState> = emptyMap()

    @Volatile var self: UserId? = null
        private set

    private val snapshot = MutableStateFlow(Snapshot(null, null, null))
    private val mutableNotices = MutableSharedFlow<FleetNotice>(extraBufferCapacity = 16)
    override val notices: Flow<FleetNotice> = mutableNotices.asSharedFlow()

    override val threads: StateFlow<FleetView> = snapshot.map { snap ->
        when {
            snap.summaries != null -> FleetView.Ready(snap.summaries.filter { !it.archived }.toPersistentList(), snap.failure)
            snap.failure != null -> FleetView.Failed(snap.failure)
            else -> FleetView.Loading
        }
    }.stateIn(scope, SharingStarted.Eagerly, FleetView.Loading)

    override val sidebar: StateFlow<Sidebar> = snapshot.map { snap ->
        val freshness = when {
            snap.asOf == null -> Freshness.NotLoaded
            snap.failure == null -> Freshness.Live
            else -> Freshness.Cached(snap.asOf)
        }
        arrangeSidebar(snap.summaries.orEmpty(), clock(), freshness)
    }.stateIn(scope, SharingStarted.Eagerly, arrangeSidebar(emptyList(), clock(), Freshness.NotLoaded))

    override val running: StateFlow<PersistentList<ThreadSummary>> = summaries { list ->
        list.filter { !it.archived && (it.status == FleetStatus.Working || it.status == FleetStatus.Compacting) }
    }

    private val usedLabels: StateFlow<PersistentList<LabelName>> = summaries { list ->
        list.flatMap { it.labels }.distinct().sortedBy { it.raw }
    }

    init {
        scope.launch { ledger.snoozes.collect { rows -> withContext(confined) { snoozes = rows; publish() } } }
        scope.launch { ledger.colors.collect { rows -> withContext(confined) { colors = rows; publish() } } }
        scope.launch {
            viewed.collect { threads ->
                withContext(confined) {
                    viewing = threads
                    readWhileViewing.keys.retainAll(threads)
                    markViewedRead()
                }
            }
        }
        scope.launch { liveStates().collect { states -> withContext(confined) { live = states; publish() } } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun liveStates(): Flow<Map<ThreadId, ThreadState>> = viewed.flatMapLatest { ids ->
        if (ids.isEmpty()) return@flatMapLatest flowOf(emptyMap())
        val states = ids.map { id ->
            threadState(id).distinctUntilChanged { a, b -> a.gates == b.gates && a.activity == b.activity && a.link == b.link }
        }
        combine(states) { all -> all.associateBy { it.id } }
    }

    override fun activity(query: ThreadQuery): StateFlow<ActivityPage> = snapshot.map { snap ->
        val list = snap.summaries ?: return@map ActivityPage.Loading
        val items = list.filter { query.matches(it, clock(), self) }
            .mapNotNull { summary -> summary.lastActivity?.let { ActivityItem.Updated(it, summary.id) } }
            .sortedByDescending { it.at }
        ActivityPage.Ready(items.toPersistentList(), more = false, serverFiltered = false)
    }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_SHARING_MS), ActivityPage.Loading)

    override fun search(query: ThreadQuery): Flow<PersistentList<ThreadSummary>> =
        snapshot.map { snap -> snap.summaries.orEmpty().filter { query.matches(it, clock(), self) }.newestFirst() }

    override fun labels(): StateFlow<PersistentList<LabelName>> = usedLabels

    override fun children(parent: ThreadId): StateFlow<PersistentList<ThreadSummary>> = summaries { list -> list.filter { it.parent == parent }.newestFirst() }

    override fun archiveAll(ids: PersistentList<ThreadId>): PersistentList<IntentId> {
        synchronized(bulkArchive) { bulkArchive += ids }
        val intents = ids.map { dispatch(it, ThreadIntent.Archive(true)) }.toPersistentList()
        mutableNotices.tryEmit(FleetNotice.Archived(ids))
        return intents
    }

    override val watched: StateFlow<PersistentSet<ThreadId>> get() = watchedThreads.threads
    override fun setWatched(thread: ThreadId, watching: Boolean): Unit = watchedThreads.set(thread, watching)

    override fun refresh() {
        scope.launch(confined) {
            if (loading?.isActive == true) {
                reloadAfter = true
                return@launch
            }
            loading = scope.launch { load() }
        }
    }

    /** Shows [intent]'s effect on [thread] until a read that starts after it settles. */
    fun expect(thread: ThreadId, id: IntentId, intent: ThreadIntent) {
        val bulk = synchronized(bulkArchive) { bulkArchive.remove(thread) }
        if (intent == ThreadIntent.Archive(true) && !bulk) mutableNotices.tryEmit(FleetNotice.Archived(persistentListOf(thread)))
        scope.launch(confined) {
            expected[id] = Expectation(thread, intent, ackedBeforeLoad = null)
            publish()
        }
    }

    /** [failure] is null when the server took the write. A refused write drops its expectation and says why. */
    fun settled(thread: ThreadId, id: IntentId, intent: ThreadIntent, failure: String?) {
        scope.launch(confined) {
            if (failure == null) {
                expected[id]?.let { expected[id] = it.copy(ackedBeforeLoad = loadsStarted + 1) }
                when (intent) {
                    is ThreadIntent.Color -> ledger.color(thread, intent.color)
                    is ThreadIntent.Snooze -> ledger.snooze(thread, intent.until, clock())
                    is ThreadIntent.SetLabels -> labelCache[thread] = CachedLabels(versionOf(thread), intent.labels)
                    else -> Unit
                }
            } else {
                expected.remove(id)
                mutableNotices.tryEmit(FleetNotice.Failed(thread, actionName(intent), failure))
            }
            publish()
            refresh()
        }
    }

    private suspend fun load() {
        val started = withContext(confined) { ++loadsStarted }
        val result = fetch()
        withContext(confined) {
            when (result) {
                is Fetched.Rows -> {
                    listed = result.listed
                    recent = result.recent
                    failure = null
                    asOf = clock()
                    expected.values.removeAll { it.ackedBeforeLoad != null && it.ackedBeforeLoad <= started }
                }
                is Fetched.Failed -> failure = result.reason
                Fetched.Unauthorized -> Unit
            }
            publish()
            markViewedRead()
            loading = null
            if (reloadAfter) {
                reloadAfter = false
                loading = scope.launch { load() }
            }
        }
        if (result == Fetched.Unauthorized) onUnauthorized()
    }

    private sealed interface Fetched {
        data class Rows(val listed: List<Listed>, val recent: Map<ThreadId, Recent>?) : Fetched
        data class Failed(val reason: String) : Fetched
        data object Unauthorized : Fetched
    }

    private suspend fun fetch(): Fetched {
        val params = buildJsonObject {
            put("limit", LIMIT)
            put("includeArchived", true)
        }
        val rows = when (val result = http.internal("listThreads", params)) {
            is Outcome.Ok -> (result.value as? JsonObject)?.array("threads")
            Outcome.Unauthorized -> return Fetched.Unauthorized
            is Outcome.Failed -> return Fetched.Failed(result.reason)
        } ?: return Fetched.Failed("Amp sent a thread list this version cannot read.")
        val listed = rows.mapNotNull { (it as? JsonObject)?.let(::parseListed) }
        val recent = recentStatuses()
        fillPerThread(listed)
        return Fetched.Rows(listed, recent)
    }

    /** Projects never change once known; labels are fetched again when the thread's version moves. */
    private suspend fun fillPerThread(listed: List<Listed>) {
        val (needProject, needLabels) = withContext(confined) {
            listed.filter { it.id !in projectCache }.map { it.id } to
                listed.filter { labelCache[it.id]?.version != it.version }.map { it.id to it.version }
        }
        val permits = Semaphore(PARALLEL_READS)
        coroutineScope {
            val projects = needProject.map { id -> async { permits.withPermit { id to projectOf(id) } } }
            val labels = needLabels.map { (id, version) -> async { permits.withPermit { Triple(id, version, labelsOf(id)) } } }
            val gotProjects = projects.awaitAll()
            val gotLabels = labels.awaitAll()
            withContext(confined) {
                gotProjects.forEach { (id, project) -> if (project != null) projectCache[id] = project }
                gotLabels.forEach { (id, version, names) -> if (names != null) labelCache[id] = CachedLabels(version, names) }
            }
        }
    }

    private suspend fun projectOf(thread: ThreadId): ThreadProject? {
        val result = http.internal("getThreadProject", buildJsonObject { put("thread", thread.raw) }) as? Outcome.Ok ?: return null
        val body = result.value as? JsonObject ?: return ThreadProject.None
        val project = body.obj("project") ?: body
        val id = project.string("id") ?: return ThreadProject.None
        return ThreadProject.Linked(ProjectId(id), project.string("name") ?: id)
    }

    private suspend fun labelsOf(thread: ThreadId): PersistentList<LabelName>? {
        val result = http.internal("getThreadLabels", buildJsonObject { put("thread", thread.raw) }) as? Outcome.Ok ?: return null
        val rows = result.value as? JsonArray ?: return null
        return rows.mapNotNull { (it as? JsonObject)?.string("name")?.let(::LabelName) }.toPersistentList()
    }

    /** Null when the user actor could not be reached; the rows still show, with an unknown status. */
    private suspend fun recentStatuses(): Map<ThreadId, Recent>? {
        val grant = (http.userGrant() as? Outcome.Ok)?.value ?: return null
        self = UserId(grant.userId)
        val args = buildJsonArray {
            addJsonObject {
                put("limit", LIMIT)
                put("sinceMs", RivetNumber.encode(System.currentTimeMillis() - RECENT_WINDOW_MS))
            }
        }
        val output = (http.userAction(grant, "getRecentThreads", args) as? Outcome.Ok)?.value as? JsonArray ?: return null
        return output.mapNotNull { element ->
            val summary = element as? JsonObject ?: return@mapNotNull null
            val id = summary.string("threadId")?.let(ThreadId::parse) ?: return@mapNotNull null
            val unread = summary.bool("hasUnreadMessages") == true
            id to Recent(statusOf(summary.string("state"), unread, summary.obj("indicator")), unread, summary.string("latestAssistantMessageID")?.let(::MessageId))
        }.toMap()
    }

    private fun publish() {
        val rows = listed ?: return run { snapshot.value = Snapshot(null, failure, asOf) }
        val now = clock()
        val summaries = rows.mapNotNull { row -> merge(row, now) }
        snapshot.value = Snapshot(summaries, failure, asOf)
    }

    /** The user is looking at a viewed thread's replies, so it leaves "done" for "idle" (screens.md, "Thread rows"). */
    private fun markViewedRead() {
        for (thread in viewing) {
            val live = recent?.get(thread) ?: continue
            val reply = live.latestReply ?: continue
            if (!live.unread || readWhileViewing[thread] == reply) continue
            readWhileViewing[thread] = reply
            dispatch(thread, ThreadIntent.MarkRead(reply))
        }
    }

    private fun merge(row: Listed, now: Instant): ThreadSummary? {
        val reported = recent?.get(row.id)
        val unread = reported?.unread == true
        val socket = live[row.id]?.takeIf { it.link == Link.Live }?.fleetStatus(unread)
        var summary = ThreadSummary(
            id = row.id,
            title = row.title,
            status = socket ?: reported?.status ?: if (recent == null) FleetStatus.Reported("unknown") else FleetStatus.Inactive,
            executor = row.executor,
            pinned = row.pinned,
            archived = row.archived,
            snooze = snoozes[row.id]?.state(now, unread) ?: SnoozeState.NotSnoozed,
            color = colors[row.id] ?: ThreadColor.None,
            labels = labelCache[row.id]?.labels ?: persistentListOf(),
            project = projectCache[row.id] ?: ThreadProject.None,
            visibility = row.visibility,
            lastActivity = row.lastActivity,
            parent = row.parent,
            unread = unread,
            isPuck = row.isPuck,
            creator = row.creator,
        )
        for (expectation in expected.values) {
            if (expectation.thread != row.id) continue
            summary = summary.apply(expectation.intent) ?: return null
        }
        return summary
    }

    private fun versionOf(thread: ThreadId): Long = listed?.firstOrNull { it.id == thread }?.version ?: -1

    private fun <T> summaries(select: (List<ThreadSummary>) -> List<T>): StateFlow<PersistentList<T>> =
        snapshot.map { it.summaries }.filterNotNull().map { select(it).toPersistentList() }
            .stateIn(scope, SharingStarted.Eagerly, persistentListOf())

    private companion object {
        const val LIMIT = 100
        const val PARALLEL_READS = 6
        const val STOP_SHARING_MS = 5_000L
        const val RECENT_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

        fun parseListed(thread: JsonObject): Listed? {
            val id = thread.string("id")?.let(ThreadId::parse) ?: return null
            val lastActivityMs = thread.long("userLastInteractedAt") ?: thread.long("created")
            val parent = (thread["relationships"] as? JsonArray)?.firstNotNullOfOrNull { relation ->
                (relation as? JsonObject)?.takeIf { it.string("role") == "parent" }?.string("threadID")?.let(ThreadId::parse)
            }
            return Listed(
                id = id,
                version = thread.long("v") ?: 0,
                title = thread.string("title"),
                pinned = thread.bool("pinned") == true,
                archived = thread.bool("archived") == true,
                visibility = visibilityOf(thread.obj("meta")?.string("visibility")),
                lastActivity = lastActivityMs?.let(Instant::fromEpochMilliseconds),
                parent = parent,
                isPuck = thread.string("agentMode") == PUCK_AGENT_MODE,
                creator = thread.string("creatorUserID")?.let(::UserId),
                executor = thread.obj("env")?.obj("initial")?.string("hostname") ?: thread.string("executorType"),
            )
        }

        /** [INFERENCE] The web client tells Puck threads apart by agentMode; the constant's value is unobserved. */
        const val PUCK_AGENT_MODE = "puck"

        fun statusOf(state: String?, unread: Boolean, indicator: JsonObject?): FleetStatus {
            if (indicator?.string("kind") == "action-required") return FleetStatus.NeedsInput(indicator.string("description") ?: "Needs your input")
            return when (state) {
                null -> FleetStatus.Inactive
                "idle" -> if (unread) FleetStatus.Done else FleetStatus.Idle
                "working", "streaming", "tool_use", "running_tools" -> FleetStatus.Working
                "compacting" -> FleetStatus.Compacting
                "error" -> FleetStatus.Error
                else -> FleetStatus.Reported(state)
            }
        }

        fun actionName(intent: ThreadIntent): String = when (intent) {
            is ThreadIntent.Rename -> "Rename"
            is ThreadIntent.Pin -> if (intent.pinned) "Pin" else "Unpin"
            is ThreadIntent.Color -> "Color"
            is ThreadIntent.SetLabels -> "Labels"
            is ThreadIntent.Snooze -> if (intent.until == null) "Unsnooze" else "Snooze"
            is ThreadIntent.Archive -> if (intent.archived) "Archive" else "Unarchive"
            ThreadIntent.Delete -> "Delete"
            ThreadIntent.MarkUnread -> "Mark as Unread"
            is ThreadIntent.MarkRead -> "Mark Read"
            is ThreadIntent.SetVisibility -> "Visibility"
            is ThreadIntent.OpenToCollaborators -> "Multiplayer"
            else -> intent::class.simpleName.orEmpty()
        }

        /** Null when the write removes the thread. */
        fun ThreadSummary.apply(intent: ThreadIntent): ThreadSummary? = when (intent) {
            is ThreadIntent.Rename -> copy(title = intent.title)
            is ThreadIntent.Pin -> copy(pinned = intent.pinned)
            is ThreadIntent.Color -> copy(color = intent.color)
            is ThreadIntent.SetLabels -> copy(labels = intent.labels)
            is ThreadIntent.Snooze -> copy(snooze = intent.until?.let { SnoozeState.Snoozed(it, SnoozeSource.ThisDevice) } ?: SnoozeState.NotSnoozed)
            is ThreadIntent.Archive -> copy(archived = intent.archived)
            ThreadIntent.Delete -> null
            ThreadIntent.MarkUnread -> copy(unread = true, status = if (status == FleetStatus.Idle) FleetStatus.Done else status)
            is ThreadIntent.MarkRead -> copy(unread = false, status = if (status == FleetStatus.Done) FleetStatus.Idle else status)
            is ThreadIntent.SetVisibility -> copy(visibility = intent.visibility)
            else -> this
        }

        fun List<ThreadSummary>.newestFirst(): PersistentList<ThreadSummary> =
            sortedByDescending { it.lastActivity }.toPersistentList()
    }
}
