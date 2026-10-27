// :core:model  package amp.model
// Account-level settings data. One read path: `AccountScope.read(key)` with a typed key below. One
// write path: `AccountScope.dispatch(AccountCommand)`. :sync maps each key to its cli-tier list method
// in one exhaustive `when` (`sourceOf`, sync/internal/Routing.kt) and refetches a key when the user
// actor reports `resourceRevisionUpdated` for it (S14 measured 8 such events). Settings that exist only
// on web-remote are AccountWebPage values (Panes.kt) and open in a web surface.
package amp.model

import kotlinx.collections.immutable.PersistentList
import kotlin.time.Instant

sealed interface AccountResource<out T> {
    data object RunnerShares : AccountResource<PersistentList<RunnerShare>>              // listRunnerShares✓
    data class Secrets(val target: SecretTarget) : AccountResource<PersistentList<SecretEntry>> // listEnvVarSecrets✓
    data object Automations : AccountResource<PersistentList<AutomationInfo>>            // listAutomations✓
    data object DictationVocabulary : AccountResource<PersistentList<String>>            // getDictationVocabulary✓
    data object McpApprovals : AccountResource<PersistentList<McpApprovalInfo>>          // listMCPServerApprovals✓
    data object OAuthClients : AccountResource<PersistentList<OAuthClientInfo>>          // listExternalAPIOAuthClients✓
    /** Read-only listings: mcpListAccountServers✓, listGlobalPluginRepositories✓, listGlobalSkillRepositories✓. */
    data object Extensions : AccountResource<ExtensionsGlance>
    data object CustomDomains : AccountResource<PersistentList<String>>                  // listCustomDomains✓
}

data class RunnerShare(val runner: RunnerId, val shareId: String)
data class OAuthClientInfo(val clientId: String, val name: String, val scopes: PersistentList<String>)
data class ExtensionsGlance(val mcpServers: PersistentList<String>, val pluginRepos: PersistentList<String>, val skillRepos: PersistentList<String>)
sealed interface SecretTarget { data object Personal : SecretTarget; data class Project(val id: ProjectId) : SecretTarget; data object Workspace : SecretTarget }
data class SecretEntry(val name: String, val isSecret: Boolean)
data class AutomationInfo(val id: String, val thread: ThreadId, val title: String, val nextRun: Instant?, val enabled: Boolean)
data class McpApprovalInfo(val serverName: String, val scope: McpApprovalScope)
