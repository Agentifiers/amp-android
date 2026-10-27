package com.agentifiers.amp.sync.internal.changes

import com.agentifiers.amp.model.AgentActivity
import com.agentifiers.amp.model.ChangeKind
import com.agentifiers.amp.model.ChangeScope
import com.agentifiers.amp.model.ChangeScopeId
import com.agentifiers.amp.model.ChangeSection
import com.agentifiers.amp.model.ChangeSource
import com.agentifiers.amp.model.ChangesFacts
import com.agentifiers.amp.model.ChangesView
import com.agentifiers.amp.model.CommitContext
import com.agentifiers.amp.model.DiffComment
import com.agentifiers.amp.model.DiffSelection
import com.agentifiers.amp.model.DiffSide
import com.agentifiers.amp.model.ExecutorStatus
import com.agentifiers.amp.model.FileChange
import com.agentifiers.amp.model.FileDiffView
import com.agentifiers.amp.model.FileOrder
import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.RepoChanges
import com.agentifiers.amp.model.SectionId
import com.agentifiers.amp.model.SectionKind
import com.agentifiers.amp.model.ShipBehavior
import com.agentifiers.amp.model.ShipTarget
import com.agentifiers.amp.model.StageState
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.UnavailableReason
import com.agentifiers.amp.model.diff.DiffEngine
import com.agentifiers.amp.store.ChangeSnapshotRow
import com.agentifiers.amp.store.CommentDraftRow
import com.agentifiers.amp.store.FileOrderRow
import com.agentifiers.amp.sync.api.Changes
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.internal.net.Outcome as HttpOutcome
import com.agentifiers.amp.sync.internal.readOrAbsent
import com.agentifiers.amp.sync.internal.thread.Call
import com.agentifiers.amp.sync.internal.thread.ThreadDeps
import com.agentifiers.amp.sync.internal.thread.ThreadEngine
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.array
import com.agentifiers.amp.sync.internal.wire.long
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The Changes pane's engine for one thread (git-and-diffs.md, "How the app picks a source"). It loads only while
 * someone collects [view]: the cached snapshot first, then live git whenever the thread socket is live and an
 * executor is attached, and again after `executor_workspace_maybe_changed`. All state is confined to one thread.
 */
internal class ChangesEngine(
    private val thread: ThreadEngine,
    private val deps: ThreadDeps,
    private val scope: CoroutineScope,
) : Changes {
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val threadId = thread.id.raw
    private val cache = deps.database.changes
    private val drafts = deps.database.commentDrafts

    private val mutableView = MutableStateFlow<ChangesView>(ChangesView.Loading)
    override val view: StateFlow<ChangesView> = mutableView.asStateFlow()

    override val comments: StateFlow<PersistentList<DiffComment>> = drafts.observe(threadId)
        .map { rows -> rows.filter { it.anchor.startsWith(COMMENT_ANCHOR) }.sortedBy { it.updatedAtMs }.mapNotNull(::commentOf).toPersistentList() }
        .stateIn(scope, SharingStarted.Eagerly, persistentListOf())

    private var selected: ChangeScopeId = ChangeScope.Uncommitted.id
    private var meta: GitMeta? = null
    private var loaded: Loaded? = null
    private var failure: GitAnswer.Failed? = null
    private var offline = false
    private var refreshFailure: String? = null
    private var stale = false
    private var intelligent = true
    private var applied: Ranking? = null
    private var proposed: Ranking? = null
    private var orderFailed = false
    private var ordering: Job? = null
    private var project = ProjectFacts()
    private var executorKind: String? = null
    private var shipWatch = ShipWatch()
    private var busy = false
    private var loading: Job? = null
    private val diffs = HashMap<String, DiffSlot>()

    init {
        scope.launch(confined) {
            mutableView.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { visible -> if (visible) whileVisible() }
        }
    }

    override fun diff(fileId: String): StateFlow<FileDiffView> {
        val slot = synchronized(diffs) { diffs.getOrPut(fileId) { DiffSlot(MutableStateFlow(FileDiffView.Loading), null) } }
        scope.launch(confined) { fill(fileId, slot) }
        return slot.flow.asStateFlow()
    }

    override fun refresh() {
        scope.launch(confined) { reload() }
    }

    override fun setOrder(intelligent: Boolean) {
        scope.launch(confined) {
            this@ChangesEngine.intelligent = intelligent
            if (intelligent) {
                proposed?.let { applied = it }
                proposed = null
                if (applied?.matches(currentHashes()) != true) requestOrder()
            }
            publish()
        }
    }

    override fun selectScope(scope: ChangeScopeId) {
        this.scope.launch(confined) {
            if (selected == scope) return@launch
            selected = scope
            loaded = null
            applied = null
            proposed = null
            publish()
            paintCache()
            reload()
        }
    }

    override suspend fun addComment(selection: DiffSelection, text: String): Outcome<DiffComment> {
        val body = text.trim()
        if (body.isEmpty()) return Outcome.Refused("Write a comment first.")
        val existing = comments.value
        if (existing.size >= MAX_COMMENTS) return Outcome.Refused("You can stage up to 20 comments.")
        if ((existing.sumOf { it.text.toByteArray().size } + body.toByteArray().size) > MAX_COMMENT_BYTES) {
            return Outcome.Refused("Pending comments can contain up to 10 KiB of text.")
        }
        val commit = withContext(confined) {
            (scopes().firstOrNull { it.id == selected } as? ChangeScope.Commit)?.let { c ->
                CommitContext(c.hash, c.subject, repositoryName = null)
            }
        }
        val comment = DiffComment(UUID.randomUUID().toString(), selection, body, commit)
        drafts.save(CommentDraftRow(threadId, COMMENT_ANCHOR + comment.id, encodeComment(comment), System.currentTimeMillis()))
        return Outcome.Ok(comment)
    }

    override fun removeComments(ids: Collection<String>) {
        val anchors = ids.map { COMMENT_ANCHOR + it }
        scope.launch { anchors.forEach { drafts.delete(threadId, it) } }
    }

    override suspend fun stage(path: String, staged: Boolean): Outcome<Unit> {
        val type = if (staged) "stage_file" else "unstage_file"
        return when (val answer = git(gitCommand(type, "path" to path))) {
            is GitAnswer.Ok -> {
                withContext(confined) { reload() }
                Outcome.Ok(Unit)
            }
            is GitAnswer.Failed -> Outcome.Failed(EngineError.Server(answer.code, answer.message))
        }
    }

    override suspend fun explain(selection: DiffSelection): Outcome<String> {
        val request = withContext(confined) { explainRequest(selection) } ?: return Outcome.Failed(EngineError.NotFound(selection.path))
        return when (val call = thread.call(EXPLAIN_SELECTION, request)) {
            is Call.Done -> when (val answer = gitAnswerOf(call.body)) {
                is GitAnswer.Ok -> call.body.string("explanation")?.let { Outcome.Ok(it) } ?: Outcome.Failed(EngineError.Drift(EXPLAIN_SELECTION))
                is GitAnswer.Failed -> Outcome.Failed(EngineError.Server(answer.code, answer.message))
            }
            is Call.Rejected -> Outcome.Failed(EngineError.Server(call.error.code?.toString() ?: "rejected", call.error.message))
            Call.NotLive -> Outcome.Failed(EngineError.Offline)
            Call.Lost -> Outcome.Unknown
        }
    }

    // ── loading ─────────────────────────────────────────────────────────────

    private suspend fun whileVisible() = coroutineScope {
        if (loaded == null) paintCache()
        launch { loadProject() }
        launch {
            thread.state.map(::connected).distinctUntilChanged().collect { live ->
                offline = !live
                if (live) reload() else publish()
            }
        }
        launch {
            thread.state.collect { state ->
                executorKind = (state.executor as? ExecutorStatus.Attached)?.kind
                val wasBusy = busy
                busy = state.activity is AgentActivity.Busy
                shipWatch = shipWatch.observe(state, thread.changesWorkflowIds(), System.currentTimeMillis())
                if (wasBusy && !busy && intelligent && loaded != null && applied?.matches(currentHashes()) != true) requestOrder()
                publish()
            }
        }
        thread.workspaceChanged.collectLatest {
            stale = true
            publish()
            delay(WORKSPACE_DEBOUNCE_MS)
            reload()
        }
    }

    private fun connected(state: ThreadState): Boolean = state.link == Link.Live && state.executor is ExecutorStatus.Attached

    /** Runs one live load at a time; a newer request replaces a running one. */
    private suspend fun reload() {
        loading?.cancel()
        val job = scope.launch(confined) { loadLive() }
        loading = job
        job.join()
    }

    private suspend fun loadLive() {
        if (!connected(thread.state.value)) {
            offline = true
            publish()
            return
        }
        val gitMeta = when (val result = loadMeta()) {
            is MetaResult.Ok -> result.meta
            is MetaResult.Failed -> return fail(result.answer)
        }
        meta = gitMeta
        val wanted = scopes().firstOrNull { it.id == selected } ?: ChangeScope.Uncommitted.also { selected = it.id }
        val plans = sectionPlans(wanted, gitMeta)
        val answers = coroutineScope { plans.map { plan -> async { plan to git(GIT_DIFF_SNAPSHOT, plan.revisions.snapshotParams()) } }.awaitAll() }
        val sections = ArrayList<LoadedSection>()
        for ((plan, answer) in answers) {
            when (answer) {
                is GitAnswer.Failed -> return fail(answer)
                is GitAnswer.Ok -> {
                    val raw = answer.body.obj("snapshot") ?: answer.body
                    val snapshot = snapshotOf(raw) ?: return fail(GitAnswer.Failed("INTERNAL_ERROR", "The snapshot had no files."))
                    sections += LoadedSection(plan.id, plan.kind, plan.revisions, snapshot, raw)
                }
            }
        }
        val now = Clock.System.now()
        loaded = Loaded(ChangeSource.LiveGit(gitMeta.head), selected, sections)
        failure = null
        refreshFailure = null
        stale = false
        offline = false
        cache.saveSnapshot(ChangeSnapshotRow(threadId, selected.raw, sections.joinToString(",") { it.snapshot.diffHash }, encodeLoaded(sections), now.toEpochMilliseconds()))
        refreshDiffs()
        if (intelligent) {
            if (applied?.matches(currentHashes()) != true) cachedRanking()?.let { applied = it; proposed = null }
            if (applied?.matches(currentHashes()) != true && (applied == null || !busy)) requestOrder()
        }
        publish()
    }

    private suspend fun loadMeta(): MetaResult = coroutineScope {
        val head = async { git(gitCommand("head")) }
        val status = async { git(gitCommand("status")) }
        val branch = async { git(gitCommand("branch")) }
        val base = async { git(gitCommand("comparison_base_ref")) }
        val baseHead = async { git(gitCommand("comparison_base_head")) }
        val ahead = async { git(gitCommand("ahead_commits")) }
        val headSha = when (val answer = head.await()) {
            is GitAnswer.Ok -> answer.stdout.trim()
            is GitAnswer.Failed -> return@coroutineScope MetaResult.Failed(answer)
        }
        fun GitAnswer.text(): String? = (this as? GitAnswer.Ok)?.stdout?.trim()?.takeIf { it.isNotEmpty() }
        val target = base.await().text()?.let(::normalizeTarget)
        MetaResult.Ok(
            GitMeta(
                head = headSha,
                branch = branch.await().text(),
                target = target,
                mergeBase = baseHead.await().text(),
                ahead = ahead.await().text()?.let(::aheadCommitsOf).orEmpty(),
                hasUncommitted = status.await().let { it is GitAnswer.Ok && it.stdout.isNotBlank() },
            ),
        )
    }

    private fun fail(answer: GitAnswer.Failed) {
        when {
            answer.code == "EXECUTOR_NOT_CONNECTED" -> offline = true
            answer.code == NOT_A_REPOSITORY -> {
                loaded = null
                failure = answer
            }
            loaded == null -> failure = answer
            else -> refreshFailure = answer.message
        }
        stale = false
        publish()
    }

    private suspend fun git(params: JsonObject): GitAnswer = git(GIT_COMMAND, params)

    private suspend fun git(method: String, params: JsonObject): GitAnswer = when (val call = thread.call(method, params)) {
        is Call.Done -> gitAnswerOf(call.body)
        is Call.Rejected -> GitAnswer.Failed("INVALID_ARGS", call.error.message)
        Call.NotLive -> GitAnswer.Failed("EXECUTOR_NOT_CONNECTED", "Runner offline")
        Call.Lost -> GitAnswer.Failed("INTERNAL_ERROR", "The executor did not answer.")
    }

    private fun scopes(): List<ChangeScope> {
        val gitMeta = meta ?: return listOf(ChangeScope.Uncommitted)
        val result = ArrayList<ChangeScope>()
        if (gitMeta.ahead.isNotEmpty() && gitMeta.mergeBase != null) result += ChangeScope.AllChanges(gitMeta.mergeBase)
        result += ChangeScope.Uncommitted
        gitMeta.ahead.forEachIndexed { index, commit ->
            val parent = commit.parent ?: gitMeta.ahead.getOrNull(index + 1)?.hash ?: gitMeta.mergeBase ?: return@forEachIndexed
            result += ChangeScope.Commit(commit.hash, commit.shortHash, commit.subject, parent, isHead = commit.hash == gitMeta.head)
        }
        return result
    }

    private fun sectionPlans(scope: ChangeScope, gitMeta: GitMeta): List<SectionPlan> = when (scope) {
        ChangeScope.Uncommitted -> listOf(
            SectionPlan(SectionId("unstaged"), SectionKind.Unstaged, Revisions("index", "worktree", includeUntracked = true)),
            SectionPlan(SectionId("staged"), SectionKind.Staged, Revisions(gitMeta.head, "index", includeUntracked = false)),
        )
        is ChangeScope.AllChanges -> listOf(SectionPlan(SectionId("all"), SectionKind.Range, Revisions(scope.mergeBase, "worktree", includeUntracked = true)))
        is ChangeScope.Commit -> listOf(SectionPlan(SectionId(scope.id.raw), SectionKind.Range, Revisions(scope.parent, scope.hash, includeUntracked = false)))
    }

    private suspend fun paintCache() {
        val row = readOrAbsent("Changes snapshot") { cache.latestSnapshot(threadId, selected.raw) } ?: return publish()
        val sections = decodeLoaded(row.json) ?: return publish()
        if (loaded?.source is ChangeSource.LiveGit) return
        loaded = Loaded(ChangeSource.Cached(Instant.fromEpochMilliseconds(row.capturedAtMs)), selected, sections)
        cachedRanking()?.let { applied = it }
        refreshDiffs()
        publish()
    }

    private suspend fun loadProject() {
        val link = deps.http.internal("getThreadProject", buildJsonObject { put("thread", threadId) })
        val linked = (link as? HttpOutcome.Ok)?.value as? JsonObject ?: return
        val projectId = linked.string("projectID") ?: linked.string("id") ?: linked.obj("project")?.string("id") ?: return
        val info = (deps.http.internal("getProject", buildJsonObject { put("projectID", projectId) }) as? HttpOutcome.Ok)?.value as? JsonObject
        val fields = info?.obj("project") ?: info ?: linked
        val custom = fields.string("customShipPrompt")
        project = ProjectFacts(
            behavior = when (fields.string("changesWorkflow")) {
                "push-to-branch" -> ShipBehavior.PushBranch
                "custom-ship" -> if (custom.isNullOrBlank()) ShipBehavior.Ship else ShipBehavior.Custom(custom)
                else -> ShipBehavior.Ship
            },
            github = fields.string("repositoryMode") == "github",
        )
        publish()
    }

    // ── intelligent order ───────────────────────────────────────────────────

    private fun currentHashes(): Map<SectionId, String> = loaded?.sections?.associate { it.id to it.snapshot.diffHash }.orEmpty()

    private fun orderUnavailable(): String? {
        val sections = loaded?.sections ?: return null
        if (sections.sumOf { it.snapshot.files.size } > ORDER_MAX_FILES || sections.size > ORDER_MAX_SECTIONS) {
            return "This diff is too large to order intelligently."
        }
        return null
    }

    private suspend fun cachedRanking(): Ranking? {
        val sections = loaded?.sections ?: return null
        val rows = sections.map { readOrAbsent("Changes file order") { cache.fileOrder(threadId, it.id.raw, it.snapshot.diffHash) } ?: return null }
        val results = rows.mapNotNull { row -> orderResultOf(AmpJson.parseToJsonElement(row.json) as JsonObject)?.firstOrNull() }
        return Ranking(sections.associate { it.id to it.snapshot.diffHash }, results.associateBy { SectionId(it.sectionId) })
    }

    private fun requestOrder() {
        val sections = loaded?.sections?.filter { it.snapshot.files.isNotEmpty() } ?: return
        if (sections.isEmpty() || orderUnavailable() != null || ordering?.isActive == true) return
        val request = orderRequest(sections.map { OrderSection(it.id.raw, it.snapshot.diffHash, it.revisions, it.snapshot.files) }) ?: return
        val hashes = currentHashes()
        ordering = scope.launch(confined) {
            publish()
            val answer = when (val call = thread.call(FILE_ORDER, request)) {
                is Call.Done -> orderAnswerOf(call.body)
                is Call.Rejected -> OrderAnswer.Failed("${call.error.code} ${call.error.message}")
                Call.NotLive -> OrderAnswer.Failed("runner offline")
                Call.Lost -> OrderAnswer.Failed("no answer")
            }
            val results = (answer as? OrderAnswer.Ordered)?.results
            val shown = sections.flatMap { s -> s.snapshot.files.map { it.path } }.toSet()
            val matched = results.orEmpty().flatMap { it.orderedPaths }.count { it in shown }
            if (results == null || matched < shown.size * MIN_MATCH_FRACTION) {
                deps.log.frame('<', FILE_ORDER, "unavailable: ${(answer as? OrderAnswer.Failed)?.reason ?: "$matched of ${shown.size} paths matched"}")
                if (applied == null) orderFailed = true
            } else {
                orderFailed = false
                val ranking = Ranking(hashes, results.associateBy { SectionId(it.sectionId) })
                results.forEach { result ->
                    val hash = hashes[SectionId(result.sectionId)] ?: return@forEach
                    cache.saveFileOrder(FileOrderRow(threadId, result.sectionId, hash, orderRowJson(result)))
                }
                val previous = applied
                if (previous == null || !intelligent || orderedPaths(previous) == orderedPaths(ranking)) {
                    applied = ranking
                    proposed = null
                } else {
                    proposed = ranking
                }
            }
            publish()
        }
    }

    private fun orderedPaths(ranking: Ranking): List<String> =
        loaded?.sections?.flatMap { section -> order(section, ranking).map { it.first.path } }.orEmpty()

    /** A section's files in [ranking]'s order with their muted flag, or alphabetically when there is no ranking. */
    private fun order(section: LoadedSection, ranking: Ranking?): List<Pair<SnapshotFile, Boolean>> {
        val alphabetical = section.snapshot.files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })
        if (ranking == null) return alphabetical.map { it to false }
        val exact = ranking.sections[section.id]?.takeIf { ranking.hashes[section.id] == section.snapshot.diffHash }
        val ranks = (exact?.orderedPaths ?: ranking.sections.values.flatMap { it.orderedPaths }).withIndex().associate { it.value to it.index }
        return alphabetical.sortedBy { ranks[it.path] ?: Int.MAX_VALUE }.map { it to (exact != null && it.path !in ranks) }
    }

    // ── projection ──────────────────────────────────────────────────────────

    private fun publish() {
        val current = loaded
        val failed = failure
        mutableView.value = when {
            current != null -> ready(current)
            failed?.code == NOT_A_REPOSITORY -> ChangesView.NoRepository
            failed != null -> ChangesView.Failed(failed.message)
            offline -> ChangesView.Unavailable(UnavailableReason.NoExecutor, "Runner offline")
            else -> ChangesView.Loading
        }
    }

    private fun ready(current: Loaded): ChangesView.Ready {
        val unavailable = orderUnavailable()
        val ranking = applied.takeIf { intelligent && unavailable == null }
        val order = when {
            unavailable != null -> FileOrder.Unavailable(unavailable)
            !intelligent -> FileOrder.Alphabetical
            orderFailed && applied == null -> FileOrder.Unavailable(ORDER_UNAVAILABLE)
            applied == null -> FileOrder.Ordering
            else -> FileOrder.Intelligent(updateAvailable = proposed != null)
        }
        val paths = current.sections.associate { it.kind to it.snapshot.files.map(SnapshotFile::path).toSet() }
        val sections = current.sections.map { section ->
            val result = ranking?.sections?.get(section.id)?.takeIf { ranking.hashes[section.id] == section.snapshot.diffHash }
            ChangeSection(
                id = section.id,
                kind = section.kind,
                diffHash = section.snapshot.diffHash,
                files = order(section, ranking).map { (file, muted) -> fileChange(section, file, muted, paths) }.toPersistentList(),
                summary = result?.summary,
                codeTour = result?.codeTour,
            )
        }
        val first = current.sections.firstOrNull()?.snapshot
        val gitMeta = meta
        val live = current.source is ChangeSource.LiveGit && !offline
        val repo = RepoChanges(first?.repositoryName ?: "Repository", gitMeta?.branch ?: first?.branch, gitMeta?.ahead?.size ?: 0, sections.toPersistentList())
        return ChangesView.Ready(
            source = current.source,
            scopes = scopes().toPersistentList(),
            scope = current.scope,
            repos = persistentListOf(repo),
            order = order,
            facts = facts(gitMeta.takeIf { live }, current),
            canStage = live && current.scope == ChangeScope.Uncommitted.id,
            stale = stale,
            notice = when {
                refreshFailure != null -> REFRESH_FAILED
                offline && current.source is ChangeSource.Cached -> "Runner offline"
                else -> null
            },
            noticeDetail = refreshFailure,
        )
    }

    private fun fileChange(section: LoadedSection, file: SnapshotFile, muted: Boolean, paths: Map<SectionKind, Set<String>>): FileChange {
        val inUnstaged = file.path in paths[SectionKind.Unstaged].orEmpty()
        val inStaged = file.path in paths[SectionKind.Staged].orEmpty()
        val stage = when (section.kind) {
            SectionKind.Range -> StageState.NotApplicable
            SectionKind.Unstaged, SectionKind.Staged -> when {
                inUnstaged && inStaged -> StageState.Partial
                section.kind == SectionKind.Unstaged -> StageState.Unstaged
                else -> StageState.Staged
            }
        }
        return FileChange(
            id = fileId(section.id, file.path),
            path = file.path,
            change = file.change,
            stat = file.stat,
            binary = file.binary,
            muted = muted,
            stage = stage,
            oldSize = file.oldSize,
            newSize = file.newSize,
            omittedReason = file.omittedReason,
        )
    }

    private fun facts(gitMeta: GitMeta?, current: Loaded): ChangesFacts {
        val url = deps.endpoint.base.newBuilder().addPathSegment("threads").addPathSegment(threadId).build().toString()
        val host = deps.endpoint.base.host
        val uncommitted = gitMeta?.hasUncommitted
            ?: (current.scope == ChangeScope.Uncommitted.id && current.sections.any { it.snapshot.files.isNotEmpty() })
        return ChangesFacts(
            target = gitMeta?.target?.let(ShipTarget::Known) ?: ShipTarget.Unknown,
            behavior = project.behavior,
            github = project.github,
            branch = gitMeta?.branch,
            baseBranch = gitMeta?.target,
            hasUncommitted = uncommitted,
            ahead = gitMeta?.ahead?.size ?: 0,
            shipInFlight = shipWatch.inFlight,
            threadUrl = url,
            syncCommand = if (executorKind == ORB_EXECUTOR) listOfNotNull(if (host == "ampcode.com") null else "AMP_URL=${deps.endpoint.base.toString().trimEnd('/')}", "amp", "sync", url).joinToString(" ") else null,
        )
    }

    // ── diffs ───────────────────────────────────────────────────────────────

    private fun findFile(fileId: String): Pair<LoadedSection, SnapshotFile>? {
        val sections = loaded?.sections ?: return null
        for (section in sections) section.snapshot.files.firstOrNull { fileId(section.id, it.path) == fileId }?.let { return section to it }
        return null
    }

    private fun refreshDiffs() {
        val slots = synchronized(diffs) { diffs.toMap() }
        slots.forEach { (id, slot) -> scope.launch(confined) { fill(id, slot) } }
    }

    private suspend fun fill(fileId: String, slot: DiffSlot) {
        val (_, file) = findFile(fileId) ?: return
        val key = file.diffToken ?: (file.path + file.stat)
        if (slot.key == key && slot.flow.value != FileDiffView.Loading) return
        slot.key = key
        slot.flow.value = withContext(Dispatchers.Default) { diffOf(file) }
    }

    /** git-and-diffs.md, "Renames, binary files, and omitted files" and "Computing a diff from file contents". */
    private fun diffOf(file: SnapshotFile): FileDiffView {
        if (file.binary) return FileDiffView.Binary(file.oldSize, file.newSize)
        file.omittedReason?.let { return FileDiffView.Omitted(it) }
        val isNew = file.change == ChangeKind.Added || file.change == ChangeKind.Untracked
        val old = if (isNew) null else file.oldContent
        val new = if (file.change == ChangeKind.Deleted) null else file.newContent
        val hasContents = (isNew || old != null) && (file.change == ChangeKind.Deleted || new != null)
        if (hasContents) return DiffEngine.fromContents(file.path, old, new)
        file.diff?.let { return DiffEngine.fromPatch(file.path, it) }
        return FileDiffView.Omitted("The file content is not available.")
    }

    private fun explainRequest(selection: DiffSelection): JsonObject? {
        val match = loaded?.sections?.firstNotNullOfOrNull { section -> section.snapshot.files.firstOrNull { it.path == selection.path } } ?: return null
        val content = (if (selection.side == DiffSide.New) match.newContent else match.oldContent).orEmpty().replace("\r\n", "\n")
        val lines = content.split('\n')
        val start = (selection.startLine - 1).coerceIn(0, lines.size)
        val end = selection.endLine.coerceIn(start, lines.size)
        return buildJsonObject {
            put("path", selection.path)
            put("side", selection.side.wire)
            put("startLine", selection.startLine)
            put("endLine", selection.endLine)
            put("fileStatus", wireChangeType(match.change))
            put("selectedCode", lines.subList(start, end).joinToString("\n").take(MAX_EXPLAIN_CHARS))
            put("contextScope", "file")
            put("fileHead", meta?.head ?: "")
            put("contextBefore", lines.subList(0, start).joinToString("\n").takeLast(MAX_CONTEXT_CHARS))
            put("contextAfter", lines.subList(end, lines.size).joinToString("\n").take(MAX_CONTEXT_CHARS))
        }
    }

    private class DiffSlot(val flow: MutableStateFlow<FileDiffView>, var key: String?)

    private companion object {
        const val COMMENT_ANCHOR = "diff:"
        const val MAX_COMMENTS = 20
        const val MAX_COMMENT_BYTES = 10_240
        const val MAX_EXPLAIN_CHARS = 20_000
        const val MAX_CONTEXT_CHARS = 640_000
        const val MIN_MATCH_FRACTION = 0.1
        const val ORDER_UNAVAILABLE = "Intelligent order isn't available for this thread."
        const val REFRESH_FAILED = "Couldn't refresh changes. These may be out of date."
        const val WORKSPACE_DEBOUNCE_MS = 750L
        const val ORB_EXECUTOR = "sandbox"

        fun fileId(section: SectionId, path: String) = "${section.raw}:$path"

        fun orderRowJson(result: OrderResult): String = buildJsonObject {
            put("sections", JsonArray(listOf(buildJsonObject {
                put("sectionID", result.sectionId)
                put("orderedPaths", JsonArray(result.orderedPaths.map(::JsonPrimitive)))
                result.summary?.let { put("summary", it) }
                result.codeTour?.let { put("codeTour", it) }
            })))
        }.toString()

        fun encodeLoaded(sections: List<LoadedSection>): String = JsonArray(sections.map { section ->
            buildJsonObject {
                put("id", section.id.raw)
                put("kind", section.kind.name)
                put("base", section.revisions.base)
                put("head", section.revisions.head)
                put("includeUntracked", section.revisions.includeUntracked)
                put("snapshot", section.raw)
            }
        }).toString()

        fun decodeLoaded(json: String): List<LoadedSection>? = runCatching {
            (AmpJson.parseToJsonElement(json) as JsonArray).map { item ->
                val section = item as JsonObject
                val raw = section.obj("snapshot")!!
                LoadedSection(
                    id = SectionId(section.string("id")!!),
                    kind = SectionKind.valueOf(section.string("kind")!!),
                    revisions = Revisions(section.string("base")!!, section.string("head")!!, section["includeUntracked"].toString() == "true"),
                    snapshot = snapshotOf(raw)!!,
                    raw = raw,
                )
            }
        }.getOrNull()

        fun encodeComment(comment: DiffComment): String = buildJsonObject {
            put("id", comment.id)
            put("path", comment.selection.path)
            put("side", comment.selection.side.name)
            put("startLine", comment.selection.startLine)
            put("endLine", comment.selection.endLine)
            put("text", comment.text)
            comment.commit?.let { commit ->
                put("commitHash", commit.hash)
                put("commitSubject", commit.subject)
                commit.repositoryName?.let { put("repositoryName", it) }
            }
        }.toString()

        fun commentOf(row: CommentDraftRow): DiffComment? = runCatching {
            val json = AmpJson.parseToJsonElement(row.text) as JsonObject
            DiffComment(
                id = json.string("id")!!,
                selection = DiffSelection(
                    json.string("path")!!, DiffSide.valueOf(json.string("side")!!),
                    json.long("startLine")!!.toInt(), json.long("endLine")!!.toInt(),
                ),
                text = json.string("text")!!,
                commit = json.string("commitHash")?.let { CommitContext(it, json.string("commitSubject").orEmpty(), json.string("repositoryName")) },
            )
        }.getOrNull()
    }
}

private data class GitMeta(
    val head: String,
    val branch: String?,
    val target: String?,
    val mergeBase: String?,
    val ahead: List<AheadCommit>,
    val hasUncommitted: Boolean,
)

private sealed interface MetaResult {
    data class Ok(val meta: GitMeta) : MetaResult
    data class Failed(val answer: GitAnswer.Failed) : MetaResult
}

private data class SectionPlan(val id: SectionId, val kind: SectionKind, val revisions: Revisions)

private data class LoadedSection(val id: SectionId, val kind: SectionKind, val revisions: Revisions, val snapshot: Snapshot, val raw: JsonObject)

private data class Loaded(val source: ChangeSource, val scope: ChangeScopeId, val sections: List<LoadedSection>)

/** An order result and the section hashes it was computed for. */
private data class Ranking(val hashes: Map<SectionId, String>, val sections: Map<SectionId, OrderResult>) {
    fun matches(current: Map<SectionId, String>): Boolean = current.isNotEmpty() && current.all { (id, hash) -> hashes[id] == hash }
}

private data class ProjectFacts(val behavior: ShipBehavior = ShipBehavior.Ship, val github: Boolean = false)

/**
 * Whether a Ship or Push this device sent is still waiting or running (git-and-diffs.md, "Guarding against a second
 * Ship"). Waiting while it is pending or queued; running from when it leaves the queue until the agent stops working.
 * A turn that never reports busy within [START_GRACE_MS] of leaving the queue counts as finished.
 */
internal data class ShipWatch(val waiting: Boolean = false, val runningSinceMs: Long? = null, val sawBusy: Boolean = false) {
    val inFlight: Boolean get() = waiting || runningSinceMs != null

    fun observe(state: ThreadState, workflowIds: Set<MessageId>, nowMs: Long): ShipWatch {
        if (workflowIds.isEmpty()) return ShipWatch()
        val nowWaiting = state.pending.any { it.id in workflowIds } || state.queue.any { MessageId(it.id.raw) in workflowIds }
        val isBusy = state.activity is AgentActivity.Busy
        val since = runningSinceMs
        return when {
            nowWaiting -> ShipWatch(waiting = true)
            waiting -> ShipWatch(runningSinceMs = nowMs, sawBusy = isBusy)
            since == null -> this
            isBusy -> copy(sawBusy = true)
            sawBusy || nowMs - since > START_GRACE_MS -> ShipWatch()
            else -> this
        }
    }

    private companion object {
        const val START_GRACE_MS = 10_000L
    }
}
