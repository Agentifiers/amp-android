package com.agentifiers.amp.model

import kotlinx.collections.immutable.PersistentList
import kotlin.time.Instant

/**
 * Account-level settings reads: `AccountScope.read(key)` with a typed key below. Writes go through
 * `AccountScope.dispatch(AccountCommand)`. Settings that exist only on web-remote are AccountWebPage values.
 */
sealed interface AccountResource<out T> {
    data object RunnerShares : AccountResource<PersistentList<RunnerShare>>
    data class Secrets(val target: SecretTarget) : AccountResource<PersistentList<SecretEntry>>
    data object Automations : AccountResource<PersistentList<AutomationInfo>>
    data object DictationVocabulary : AccountResource<PersistentList<String>>
    data object McpApprovals : AccountResource<PersistentList<McpApprovalInfo>>
    data object OAuthClients : AccountResource<PersistentList<OAuthClientInfo>>
    /** Read-only listings: MCP servers, global plugin repositories, global skill repositories. */
    data object Extensions : AccountResource<ExtensionsGlance>
    data object CustomDomains : AccountResource<PersistentList<String>>
}

data class RunnerShare(val runner: RunnerId, val shareId: String)
data class OAuthClientInfo(val clientId: String, val name: String, val scopes: PersistentList<String>)

/** Server-side clients get a secret shown once at creation, so the app creates only client-side ones. */
enum class OAuthClientType { ServerSide, ClientSide }
data class ExtensionsGlance(val mcpServers: PersistentList<String>, val pluginRepos: PersistentList<String>, val skillRepos: PersistentList<String>)

sealed interface SecretTarget {
    data object Personal : SecretTarget
    data class Project(val id: ProjectId) : SecretTarget
    data object Workspace : SecretTarget
}

data class SecretEntry(val name: String, val isSecret: Boolean)
data class AutomationInfo(val id: String, val thread: ThreadId, val title: String, val nextRun: Instant?, val enabled: Boolean)
/** One `listMCPServerApprovals` row. Approvals are held per user or per workspace; the list carries no thread or project scope. */
data class McpApprovalInfo(val serverName: String, val specHash: String, val owner: McpApprovalOwner)

enum class McpApprovalOwner { User, Workspace }

/** App lock guards the interface, never the keys (auth-and-security.md, "App lock"). Off by default. */
sealed interface AppLock {
    data object Off : AppLock
    data class On(val after: LockAfter) : AppLock
}

enum class LockAfter(val seconds: Long, val label: String) {
    Immediately(0, "Immediately"),
    OneMinute(60, "1 minute"),
    FiveMinutes(300, "5 minutes"),
    ThirtyMinutes(1800, "30 minutes"),
}
