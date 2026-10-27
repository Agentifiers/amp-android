// :core:model  package amp.model
// PaneDestination: every place the user can look. Layout assigns destinations to slots, and :app
// renders each one through an exhaustive `when` over this sealed type (app/AppShell.kt PaneHost), so a
// new destination without a pane fails to compile. Deep links, Puck navigation events, notifications
// and app-events all resolve to a destination, so there is one router.
package amp.model

sealed interface PaneDestination {
    /** Stable string used in saved workspaces, deep links and test tags. */
    val route: String

    data object Sidebar : PaneDestination { override val route = "sidebar" }
    data class Activity(val query: ThreadQuery) : PaneDestination { override val route = "activity" }
    data object Running : PaneDestination { override val route = "running" }                    // amp top
    data class Thread(val id: ThreadId) : PaneDestination { override val route = "thread/${id.raw}" }
    data class Machine(val thread: ThreadId, val tab: MachineTab) : PaneDestination { override val route = "machine/${thread.raw}/${tab.key}" }
    data class Puck(val conversation: PuckConversationId?) : PaneDestination { override val route = "puck" }
    data class NewThread(val prefill: Draft?) : PaneDestination { override val route = "new" }
    data class Settings(val page: SettingsPage) : PaneDestination { override val route = "settings/${page.name}" }
    /** The only way to reach web content. Built from values below, never from free-form URLs. */
    data class Web(val surface: WebSurface) : PaneDestination { override val route = "web/${surface.kind}" }
}

/**
 * The machine panes of layout.md: Changes, Files, Terminal, Portal, Desktop, Space. They exist only for
 * threads with an executor. :app's MachinePane renders them through an exhaustive `when`.
 */
sealed interface MachineTab {
    val key: String
    data class Changes(val focusPath: String?) : MachineTab { override val key = "changes" }
    data class Files(val path: String?) : MachineTab { override val key = "files" }
    /** session = null is tab 1, which opens with no sessionKey; tabs 2 to 5 use tab-2 to tab-5 (terminal.md). */
    data class Terminal(val session: TerminalSessionKey?) : MachineTab { override val key = "terminal" }
    data class Portal(val port: Int?) : MachineTab { override val key = "portal" }
    data class Desktop(val target: DesktopTarget) : MachineTab { override val key = "desktop" }
    data object Space : MachineTab { override val key = "space" }
}

/**
 * Inherently-web content that is not a machine tab (direction constraint). McpApp and AmpView render
 * in an in-app sandboxed WebView. AccountPage renders in the account's own WebView profile
 * `amp-<accountId>` (auth-and-security.md), in a side pane on Expanded and wider with the account in
 * the pane header, and as a full pane on Compact. Portals are MachineTab.Portal, rendered by :feature:web.
 */
sealed interface WebSurface {
    val kind: String
    data class McpApp(val thread: ThreadId, val serverId: String, val resourceUri: String, val toolName: String) : WebSurface { override val kind = "mcp-app" }
    data class AmpView(val block: AmpViewBlock) : WebSurface { override val kind = "amp-view" }
    /** [account] is the window's account; the pane header shows it, and the WebView uses its profile. */
    data class AccountPage(val page: AccountWebPage, val account: AccountId, val suffix: String?) : WebSurface { override val kind = "account" }
}

/**
 * Plain ampcode.com page routes (not remote-function ids). Each exists because its backing calls
 * are web-remote only. Paths resolve against the account's base URL (custom domains).
 * browserOnly pages open in a Custom Tab, because passkeys do not work in the app's WebView
 * (auth-and-security.md, step-up).
 */
enum class AccountWebPage(val path: String, val browserOnly: Boolean = false) {
    Billing("/settings/billing"),
    NotificationSettings("/settings/notifications"),
    WorkspaceAdmin("/workspace"),
    WorkspaceMembers("/workspace/members"),
    WorkspaceGroups("/workspace/groups"),
    ProjectSettings("/projects"),           // + "/<id>/settings" as the suffix
    CreateProject("/projects/new"),
    Integrations("/settings/integrations"),  // GitHub, GitLab, Slack, Linear, MCP OAuth connect
    PuckSettings("/settings/puck"),
    SavedPrompts("/settings/prompts"),
    GitIdentity("/settings/git"),
    PortalPolicy("/settings/portals"),
    ThreadOnWeb("/threads"),                 // + "/<id>": [Answer on web] and [Open on web]
    ModelRouting("/settings/models"),        // product.md: model routing is shown in a web view
    Passkeys("/settings/security", browserOnly = true),
}
// Route paths are [guess] from docs; verified list is an open question. Wrong paths degrade to the
// ampcode.com home page in the same WebView, never to native code calling web-remote.

/** Native settings pages. Each reads AccountResource keys and writes AccountCommands; see Settings.kt. */
enum class SettingsPage { Accounts, Notifications, Layout, Runners, Secrets, Automations, Projects, Dictation, McpServers, OAuthClients, PortalHostnames, Diagnostics, About }

// ── Accounts ──────────────────────────────────────────────────────────────────

data class Account(
    val id: AccountId,
    val email: String,
    val baseUrl: String,                 // ampcode.com or workspace custom domain
    val workspace: WorkspaceRef,
    val auth: AuthHealth,
)
sealed interface WorkspaceRef { data object Personal : WorkspaceRef; data class Team(val id: String, val name: String) : WorkspaceRef }
sealed interface AuthHealth {
    data object Ok : AuthHealth
    data object Refreshing : AuthHealth
    data object NeedsSignIn : AuthHealth      // invalid_grant
    data object NeedsStepUp : AuthHealth      // passkey sudo required for remote control
}
/** [lastUsed] picks the account for a new window. Each open window carries its own account (WindowState, app/AppShell.kt). */
data class AccountRoster(val accounts: kotlinx.collections.immutable.PersistentList<Account>, val lastUsed: AccountId?)
