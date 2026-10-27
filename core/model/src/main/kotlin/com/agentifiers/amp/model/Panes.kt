package com.agentifiers.amp.model

/**
 * Every place the user can look. Layout assigns destinations to slots, and :app renders each one through
 * an exhaustive `when` over this sealed type, so a new destination without a pane fails to compile.
 * Deep links, Puck navigation, notifications and app events all resolve to a destination.
 */
sealed interface PaneDestination {
    /** Stable string used in saved workspaces, deep links and test tags. */
    val route: String

    data object Sidebar : PaneDestination { override val route = "sidebar" }
    data class Activity(val query: ThreadQuery) : PaneDestination { override val route = "activity" }
    data object Running : PaneDestination { override val route = "running" }
    data class Thread(val id: ThreadId) : PaneDestination { override val route = "thread/${id.raw}" }
    data class Machine(val thread: ThreadId, val tab: MachineTab) : PaneDestination { override val route = "machine/${thread.raw}/${tab.key}" }
    data class Puck(val conversation: PuckConversationId?) : PaneDestination { override val route = "puck" }
    data class NewThread(val prefill: Draft?) : PaneDestination { override val route = "new" }
    data class Settings(val page: SettingsPage) : PaneDestination { override val route = "settings/${page.name}" }
    /** The only way to reach web content. Built from the values below, never from free-form URLs. */
    data class Web(val surface: WebSurface) : PaneDestination { override val route = "web/${surface.kind}" }
}

/** The machine panes of layout.md. They exist only for threads with an executor. */
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
 * Inherently-web content that is not a machine tab. McpApp and AmpView render in a sandboxed WebView.
 * AccountPage renders in the account's own WebView profile `amp-<accountId>`.
 */
sealed interface WebSurface {
    val kind: String

    data class McpApp(val thread: ThreadId, val serverId: String, val resourceUri: String, val toolName: String) : WebSurface { override val kind = "mcp-app" }
    data class AmpView(val block: AmpViewBlock) : WebSurface { override val kind = "amp-view" }
    data class AccountPage(val page: AccountWebPage, val account: AccountId, val suffix: String?) : WebSurface { override val kind = "account" }
}

/**
 * Plain ampcode.com page routes (not remote-function ids), each backed by calls that are web-remote only.
 * Paths resolve against the account's base URL. [browserOnly] pages open in a Custom Tab because passkeys
 * do not work in the app's WebView. The paths are read from docs, not verified.
 */
enum class AccountWebPage(val path: String, val browserOnly: Boolean = false) {
    Billing("/settings/billing"),
    NotificationSettings("/settings/notifications"),
    WorkspaceAdmin("/workspace"),
    WorkspaceMembers("/workspace/members"),
    WorkspaceGroups("/workspace/groups"),
    ProjectSettings("/projects"),
    CreateProject("/projects/new"),
    Integrations("/settings/integrations"),
    PuckSettings("/settings/puck"),
    SavedPrompts("/settings/prompts"),
    GitIdentity("/settings/git"),
    PortalPolicy("/settings/portals"),
    ThreadOnWeb("/threads"),
    ModelRouting("/settings/models"),
    Passkeys("/settings/security", browserOnly = true),

    // Unverified, like the paths above: read from Amp's docs, never fetched.
    Account("/settings"),
    Usage("/settings/usage"),
    ModeDial("/settings/dial"),
    Composer("/settings/composer"),
    Plugins("/settings/plugins"),
    Skills("/settings/skills"),
    Domains("/settings/domains"),
    SecretsAndEnvVars("/settings/environment-variables"),

    /** The web's sign-in route, `/(minimal)/auth/sign-in`, which takes `?returnTo=<path>` (web chunk `1ba99a58.js`). */
    SignIn("/auth/sign-in"),
}

/** Native settings pages. Each reads AccountResource keys and writes AccountCommands. */
enum class SettingsPage { Accounts, Notifications, Layout, Runners, Secrets, Automations, Projects, Dictation, McpServers, OAuthClients, PortalHostnames, Diagnostics, About, Usage, App }
