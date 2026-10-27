package com.agentifiers.amp.sync.internal.catalog

import com.agentifiers.amp.model.AgentMode
import com.agentifiers.amp.model.GitHost
import com.agentifiers.amp.model.ModeSource
import com.agentifiers.amp.model.OrbSize
import com.agentifiers.amp.model.ProjectId
import com.agentifiers.amp.model.ProjectInfo
import com.agentifiers.amp.model.RepoRef
import com.agentifiers.amp.model.RepoRole
import com.agentifiers.amp.model.RunnerId
import com.agentifiers.amp.model.RunnerInfo
import com.agentifiers.amp.model.ShipBehavior
import com.agentifiers.amp.sync.api.Catalog
import com.agentifiers.amp.sync.api.CreditsGlance
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Outcome
import com.agentifiers.amp.sync.internal.wire.array
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * What the New Thread pickers offer. Each list is fetched again whenever a screen starts collecting it, so the
 * runner list reflects which runners are online now (offline runners leave `listRunners`, S3).
 */
internal class CatalogEngine(
    private val http: AmpHttp,
    private val scope: CoroutineScope,
    private val onUnauthorized: () -> Unit,
) : Catalog {
    private val mutableRunners = MutableStateFlow<PersistentList<RunnerInfo>>(persistentListOf())
    private val mutableProjects = MutableStateFlow<PersistentList<ProjectInfo>>(persistentListOf())
    private val mutableModes = MutableStateFlow(DIAL)
    private val mutableCredits = MutableStateFlow<CreditsGlance>(CreditsGlance.Loading)
    private val fetching = Mutex()

    override val runners: StateFlow<PersistentList<RunnerInfo>> = mutableRunners.asStateFlow()
    override val projects: StateFlow<PersistentList<ProjectInfo>> = mutableProjects.asStateFlow()
    override val modes: StateFlow<PersistentList<AgentMode>> = mutableModes.asStateFlow()
    override val orbSizes: StateFlow<PersistentList<OrbSize>> = MutableStateFlow(ORB_SIZES).asStateFlow()
    override val credits: StateFlow<CreditsGlance> = mutableCredits.asStateFlow()

    init {
        merge(mutableRunners.subscriptionCount, mutableProjects.subscriptionCount, mutableModes.subscriptionCount, mutableCredits.subscriptionCount)
            .map { it > 0 }
            .distinctUntilChanged()
            .filter { it }
            .onEach { scope.launch { refresh() } }
            .launchIn(scope)
    }

    suspend fun refresh() = fetching.withLock {
        mutableRunners.value = fetchRunners() ?: mutableRunners.value
        mutableProjects.value = fetchProjects() ?: mutableProjects.value
        mutableModes.value = fetchModes() ?: DIAL
        fetchCredits()?.let { mutableCredits.value = it }
    }

    /** The user actor's `listRunners` carries each runner's `agentModes`; internal `listRunners` is the same list without them (S3). */
    private suspend fun fetchRunners(): PersistentList<RunnerInfo>? {
        val grant = when (val result = http.userGrant()) {
            is Outcome.Ok -> result.value
            Outcome.Unauthorized -> return null.also { onUnauthorized() }
            is Outcome.Failed -> null
        }
        val fromActor = grant?.let { (http.userAction(it, "listRunners", JsonArray(emptyList())) as? Outcome.Ok)?.value as? JsonArray }
        val rows = fromActor ?: ((http.internal("listRunners", JsonObject(emptyMap())) as? Outcome.Ok)?.value as? JsonObject)?.array("runners")
            ?: return null
        return rows.mapNotNull { (it as? JsonObject)?.let { row -> runnerOf(row, grant?.userId) } }.toPersistentList()
    }

    private suspend fun fetchProjects(): PersistentList<ProjectInfo>? {
        val result = (http.internal("listAccessibleProjects", JsonObject(emptyMap())) as? Outcome.Ok)?.value ?: return null
        return listOf(result, (result as? JsonObject)?.get("projects")).firstNotNullOfOrNull { it as? JsonArray }
            ?.mapNotNull { (it as? JsonObject)?.let(::projectOf) }?.toPersistentList()
    }

    /** `listAgentModes` is read from the CLI, not observed, so its rows are read tolerantly and the Dial is the fallback. */
    private suspend fun fetchModes(): PersistentList<AgentMode>? {
        val result = (http.internal("listAgentModes", JsonObject(emptyMap())) as? Outcome.Ok)?.value ?: return null
        val rows = listOf(result, (result as? JsonObject)?.get("modes"), (result as? JsonObject)?.get("agentModes"))
            .firstNotNullOfOrNull { it as? JsonArray } ?: return null
        return rows.mapNotNull { (it as? JsonObject)?.let(::modeOf) }.takeIf { it.isNotEmpty() }?.toPersistentList()
    }

    /** `userDisplayBalanceInfo` is read from the CLI, not observed; a balance the app cannot find shows as unavailable. */
    private suspend fun fetchCredits(): CreditsGlance? = when (val result = http.internal("userDisplayBalanceInfo", JsonObject(emptyMap()))) {
        is Outcome.Ok -> {
            val info = result.value as? JsonObject
            val remaining = listOf("remainingUsd", "balanceUsd", "balance", "remaining").firstNotNullOfOrNull { info?.number(it) }
            if (remaining == null) CreditsGlance.Unavailable else CreditsGlance.Known(remaining, info?.obj("workspace")?.string("quota"))
        }
        Outcome.Unauthorized -> null
        is Outcome.Failed -> CreditsGlance.Unavailable
    }

    companion object {
        /** The default Dial (core.md:32), what the picker offers when `listAgentModes` gives nothing. */
        val DIAL: PersistentList<AgentMode> = persistentListOf(
            AgentMode("low", "Low", locked = false, description = "Amp Low"),
            AgentMode("medium", "Medium", locked = false, description = "Amp Medium"),
            AgentMode("high", "High", locked = false, description = "Amp High"),
            AgentMode("ultra", "Ultra", locked = false, description = "Amp Ultra"),
        )

        /** Static because the live list is web-only (COVERAGE.md, orb-sizes-and-costs); the server validates the size on spawn. */
        val ORB_SIZES: PersistentList<OrbSize> =
            listOf("a1.tiny", "a1.small", "a1.medium", "a1.large", "a1.xxlarge", "a1.3xlarge").map(::OrbSize).toPersistentList()

        /** One runner row of `listRunners` (`captures/s03-runner.live.jsonl:8`, `:10`). */
        fun runnerOf(row: JsonObject, me: String?): RunnerInfo? {
            val id = row.string("runnerId") ?: return null
            val capabilities = row.array("capabilities")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            val owner = row.string("ownerUserId")
            return RunnerInfo(
                id = RunnerId(id),
                hostname = row.string("hostname") ?: row.string("name") ?: id,
                directories = row.array("directories")?.mapNotNull { (it as? JsonObject)?.string("path") }?.toPersistentList()
                    ?: listOfNotNull(row.string("workingDirectory")).toPersistentList(),
                shared = owner != null && owner != me,
                online = true,
                modes = row.array("agentModes")?.mapNotNull { (it as? JsonObject)?.let(::modeOf) }.orEmpty().toPersistentList(),
                canCreateWorktree = "create-worktree" in capabilities,
            )
        }

        /** One mode row as `listRunners.agentModes` carries it (`key`, `label`, `description`, `model`, `pluginName`). */
        fun modeOf(row: JsonObject): AgentMode? {
            val key = row.string("key") ?: return null
            val source = when {
                key in DIAL.map { it.key } -> ModeSource.Dial
                row.string("pluginName") != null -> ModeSource.Plugin
                row.bool("raw") == true -> ModeSource.RawModel
                else -> ModeSource.BuiltIn
            }
            return AgentMode(key, row.string("label") ?: row.string("name") ?: key, locked = false, row.string("description"), row.string("model"), source)
        }

        fun projectOf(row: JsonObject): ProjectInfo? {
            val id = row.string("id") ?: row.string("projectID") ?: return null
            val repo = row.string("repositoryURL")
            return ProjectInfo(
                id = ProjectId(id),
                name = row.string("name") ?: id,
                repos = listOfNotNull(repo?.let { RepoRef(it, RepoRole.Primary) }).toPersistentList(),
                shipBehavior = ShipBehavior.Ship,
                gitHost = when {
                    repo == null -> GitHost.AmpHosted
                    "github.com" in repo -> GitHost.GitHub
                    "gitlab" in repo -> GitHost.GitLab
                    "bitbucket" in repo -> GitHost.Bitbucket
                    else -> GitHost.OtherHttps
                },
            )
        }

        private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    }
}
