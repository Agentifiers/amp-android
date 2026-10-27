package com.agentifiers.amp.sync.internal.settings

import com.agentifiers.amp.model.AccountCommand
import com.agentifiers.amp.model.AccountResource
import com.agentifiers.amp.model.AutomationInfo
import com.agentifiers.amp.model.ExtensionsGlance
import com.agentifiers.amp.model.IntentId
import com.agentifiers.amp.model.McpApprovalInfo
import com.agentifiers.amp.model.McpApprovalOwner
import com.agentifiers.amp.model.OAuthClientInfo
import com.agentifiers.amp.model.OAuthClientType
import com.agentifiers.amp.model.ProjectId
import com.agentifiers.amp.model.RunnerId
import com.agentifiers.amp.model.RunnerShare
import com.agentifiers.amp.model.SecretEntry
import com.agentifiers.amp.model.SecretTarget
import com.agentifiers.amp.model.ShipBehavior
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.api.AccountCommandResult
import com.agentifiers.amp.sync.api.Loadable
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant
import com.agentifiers.amp.sync.api.Outcome as ApiOutcome

/** One account's settings reads and writes over cli-tier internal RPC. */
internal class AccountSettings(http: AmpHttp, private val scope: CoroutineScope) {
    private val rpc = SettingsRpc(http)
    private val results = MutableSharedFlow<AccountCommandResult>(extraBufferCapacity = 16)
    val commandResults: SharedFlow<AccountCommandResult> = results.asSharedFlow()
    private val cells = ConcurrentHashMap<AccountResource<*>, Cell<*>>()

    /** The same flow for the same key. The first read fetches, and a read while the key is Failed fetches again. */
    fun <T> read(resource: AccountResource<T>): StateFlow<Loadable<T>> {
        @Suppress("UNCHECKED_CAST")
        val cell = cells.computeIfAbsent(resource) { Cell(resource) } as Cell<T>
        cell.fetchIfIdle()
        return cell.flow
    }

    fun dispatch(command: AccountCommand): IntentId {
        val intent = IntentId.mint()
        val write = writeOf(command)
        scope.launch {
            val outcome = write.run()
            write.affects?.let { cells[it]?.refetch()?.join() }
            results.emit(AccountCommandResult(intent, command, outcome))
        }
        return intent
    }

    private inner class Cell<T>(private val resource: AccountResource<T>) {
        private val state = MutableStateFlow<Loadable<T>>(Loadable.Loading)
        val flow: StateFlow<Loadable<T>> = state.asStateFlow()
        private var job: Job? = null
        private var generation = 0

        @Synchronized
        fun fetchIfIdle() {
            val idle = job.let { it == null || (it.isCompleted && state.value is Loadable.Failed) }
            if (idle) refetch()
        }

        /** A newer fetch supersedes an older one, so a refetch after a write never shows the pre-write answer. */
        @Synchronized
        fun refetch(): Job {
            job?.cancel()
            val mine = ++generation
            if (state.value is Loadable.Failed) state.value = Loadable.Loading
            return scope.launch { settle(mine, load(resource)) }.also { job = it }
        }

        @Synchronized
        private fun settle(mine: Int, outcome: ApiOutcome<T>) {
            if (mine != generation) return
            when (outcome) {
                is ApiOutcome.Ok -> state.value = Loadable.Ready(outcome.value, Clock.System.now())
                is ApiOutcome.Failed -> if (state.value !is Loadable.Ready) state.value = Loadable.Failed(outcome.error)
                ApiOutcome.Unknown, is ApiOutcome.Refused -> Unit
            }
        }
    }

    // ── Reads: each key and the method(s) behind it ──

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> load(resource: AccountResource<T>): ApiOutcome<T> = when (resource) {
        AccountResource.RunnerShares -> runnerShares()
        is AccountResource.Secrets -> rpc.read("listEnvVarSecrets", params { put("target", resource.target.wire()) }) {
            objects("items").map { SecretEntry(it.need("name"), it.secretKind()) }.toPersistentList()
        }
        AccountResource.Automations -> rpc.read("listAutomations") { objects("automations").map { it.automation() }.toPersistentList() }
        AccountResource.DictationVocabulary -> vocabulary()
        AccountResource.McpApprovals -> rpc.read("listMCPServerApprovals") {
            objects("approvals").map { McpApprovalInfo(it.need("serverName"), it.need("specHash"), it.owner()) }.toPersistentList()
        }
        AccountResource.OAuthClients -> rpc.team().then { team ->
            if (team == null) {
                ApiOutcome.Ok(persistentListOf())
            } else {
                rpc.read("listExternalAPIOAuthClients", params { put("workspaceName", team.name) }) {
                    objects("clients").map { OAuthClientInfo(it.need("clientID"), it.need("name"), it.strings("scopes").toPersistentList()) }.toPersistentList()
                }
            }
        }
        AccountResource.Extensions -> extensions()
        AccountResource.CustomDomains -> rpc.team().then { team ->
            domains("user").then { mine -> if (team == null) ApiOutcome.Ok(mine) else domains("workspace").then { ApiOutcome.Ok((mine + it).distinct().toPersistentList()) } }
        }
    } as ApiOutcome<T>

    private suspend fun runnerShares() = rpc.read("listRunnerShares") {
        objects("shares").map { RunnerShare(RunnerId(it.need("runnerId")), it.need("shareID")) }.toPersistentList()
    }

    private suspend fun vocabulary() = rpc.read("getDictationVocabulary") { vocabularyTerms(need("vocabulary")) }

    private suspend fun domains(target: String) = rpc.read("listCustomDomains", params { put("target", target) }) {
        objects("domains").map { it.need("hostname") }.toPersistentList()
    }

    private suspend fun extensions(): ApiOutcome<ExtensionsGlance> = coroutineScope {
        val servers = async { rpc.read("mcpListAccountServers") { objects("servers").map { it.need("name") }.toPersistentList() } }
        val plugins = async { rpc.read("listGlobalPluginRepositories") { repositories() } }
        val skills = async { rpc.read("listGlobalSkillRepositories") { repositories() } }
        servers.await().then { s -> plugins.await().then { p -> skills.await().then { k -> ApiOutcome.Ok(ExtensionsGlance(s, p, k)) } } }
    }

    // ── Writes: each command, its method(s), and the key it changes ──

    private class Write(val affects: AccountResource<*>?, val run: suspend () -> ApiOutcome<Unit>)

    private fun writeOf(command: AccountCommand): Write = when (command) {
        is AccountCommand.ShareRunner -> Write(AccountResource.RunnerShares) { if (command.share) share(command.runner) else unshare(command.runner) }
        is AccountCommand.SetSecret -> Write(AccountResource.Secrets(command.target)) {
            rpc.write("setEnvVarSecret", params {
                put("target", command.target.wire())
                put("name", command.name)
                put("value", command.value)
                put("kind", if (command.secret) "secret" else "env")
            })
        }
        is AccountCommand.SetAutomationEnabled -> Write(AccountResource.Automations) {
            rpc.write("updateAutomation", params {
                put("automationID", command.id)
                put("enabled", command.enabled)
            })
        }
        is AccountCommand.DeleteAutomation -> Write(AccountResource.Automations) { rpc.write("deleteAutomation", params { put("automationID", command.id) }) }
        is AccountCommand.SetShipBehavior -> Write(null) { rpc.write("updateProject", shipParams(command.project, command.behavior)) }
        is AccountCommand.EditVocabulary -> Write(AccountResource.DictationVocabulary) {
            vocabulary().then { current ->
                val next = (current - command.remove.toSet() + command.add).map(String::trim).filter(String::isNotEmpty).distinct()
                rpc.write("setDictationVocabulary", params {
                    put("operation", "set")
                    put("vocabulary", next.joinToString(", "))
                })
            }
        }
        is AccountCommand.CreateOAuthClient -> Write(AccountResource.OAuthClients) { createOAuthClient(command) }
        is AccountCommand.ClearOrbSnapshots -> Write(null) { rpc.write("deleteProjectSandboxSnapshots", params { put("projectID", command.project.raw) }) }
    }

    private suspend fun share(runner: RunnerId): ApiOutcome<Unit> = rpc.team().then { team ->
        if (team == null) {
            ApiOutcome.Refused("Join a workspace before sharing a runner.")
        } else {
            rpc.write("shareRunner", params {
                put("runnerId", runner.raw)
                put("workspaceID", team.id)
            })
        }
    }

    /** The CLI's `amp runners share --off`: revoke every share of this runner. */
    private suspend fun unshare(runner: RunnerId): ApiOutcome<Unit> = runnerShares().then { shares ->
        var outcome: ApiOutcome<Unit> = ApiOutcome.Ok(Unit)
        for (share in shares.filter { it.runner.raw.equals(runner.raw, ignoreCase = true) }) {
            outcome = rpc.write("revokeRunnerShare", params { put("shareID", share.shareId) })
            if (outcome !is ApiOutcome.Ok) break
        }
        outcome
    }

    private suspend fun createOAuthClient(command: AccountCommand.CreateOAuthClient): ApiOutcome<Unit> = when (command.type) {
        OAuthClientType.ServerSide -> ApiOutcome.Refused("Create server-side clients on the web, where the secret is shown once.")
        OAuthClientType.ClientSide -> rpc.team().then { team ->
            if (team == null) {
                ApiOutcome.Refused("OAuth clients belong to a workspace. Join a workspace first.")
            } else {
                rpc.write("createExternalAPIOAuthClient", params {
                    put("workspaceName", team.name)
                    put("type", "client-side")
                    put("name", command.name)
                    putJsonArray("scopes") { command.scopes.forEach { add(it) } }
                    putJsonArray("redirectURIs") { command.redirectUris.forEach { add(it) } }
                })
            }
        }
    }
}

private inline fun <A, B> ApiOutcome<A>.then(next: (A) -> ApiOutcome<B>): ApiOutcome<B> = when (this) {
    is ApiOutcome.Ok -> next(value)
    is ApiOutcome.Failed -> this
    is ApiOutcome.Refused -> this
    ApiOutcome.Unknown -> ApiOutcome.Unknown
}

private inline fun params(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

private fun SecretTarget.wire(): JsonObject = buildJsonObject {
    when (this@wire) {
        SecretTarget.Personal -> put("type", "user")
        SecretTarget.Workspace -> put("type", "workspace")
        is SecretTarget.Project -> {
            put("type", "project")
            put("projectID", id.raw)
        }
    }
}

private fun shipParams(project: ProjectId, behavior: ShipBehavior): JsonObject = params {
    put("projectID", project.raw)
    when (behavior) {
        ShipBehavior.Ship -> put("changesWorkflow", "merge-to-main")
        ShipBehavior.PushBranch -> put("changesWorkflow", "push-to-branch")
        is ShipBehavior.Custom -> {
            put("changesWorkflow", "custom-ship")
            put("customShipPrompt", behavior.prompt)
        }
    }
}

private fun JsonObject.secretKind(): Boolean = when (need("kind")) {
    "secret" -> true
    "env" -> false
    else -> shape()
}

private fun JsonObject.owner(): McpApprovalOwner = when (need("scope")) {
    "user" -> McpApprovalOwner.User
    "workspace" -> McpApprovalOwner.Workspace
    else -> shape()
}

private fun JsonObject.automation(): AutomationInfo = AutomationInfo(
    id = need("id"),
    thread = ThreadId.parse(need("targetThreadID")) ?: shape(),
    title = need("title"),
    nextRun = string("nextRunAt")?.let { runCatching { Instant.parse(it) }.getOrNull() ?: shape() },
    enabled = flag("enabled"),
)

/** Only repositories that exist are listed; each is its clone URL (`/git/@<owner>/-/plugins` or `/-/skills`). */
private fun JsonObject.repositories(): PersistentList<String> = objects("repositories").filter { it.flag("exists") }.map { it.need("cloneURL") }.toPersistentList()

/**
 * The server keeps vocabulary as one free-text string (`getDictationVocabulary{vocabulary}`); the CLI documents terms
 * as separated by commas or spaces. Terms are split on commas and newlines so multi-word terms survive, and written
 * back comma-separated.
 */
private fun vocabularyTerms(vocabulary: String): PersistentList<String> =
    vocabulary.split(',', '\n').map(String::trim).filter(String::isNotEmpty).toPersistentList()
