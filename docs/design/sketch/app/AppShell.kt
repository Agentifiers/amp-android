// :app  package amp.app
// Composition root, window shell, lease ownership, pane dispatch, sign-out, deep links, and
// agent-device hooks.
package amp.app

import amp.layout.*
import amp.model.*
import amp.sync.api.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * @HiltAndroidApp. Builds the engine once in onCreate with AmpEngines.create(…) and splits the result:
 * [parts].engines and [parts].background go into Hilt through EngineModule, and [leases] stays here.
 * `internal`, so no other module can name it, and only VisibleThreadLeases reads it.
 */
abstract class AmpApplication /* : Application() */ {
    internal abstract val parts: EngineParts
    internal val leases: LeaseIssuer get() = parts.leases
}

/**
 * @Module @InstallIn(SingletonComponent). Provides AmpEngines and BackgroundAccessProvider from
 * AmpApplication.parts. :store's StoreModule provides AccountDatabases and AppPreferences. LeaseIssuer
 * is never bound, so injection cannot reach it from any module.
 */
interface EngineModule

/** Each window carries its own account. Two windows can show two accounts (auth-and-security.md). */
data class WindowState(val windowKey: String, val account: AccountId, val workspace: Workspace)

/**
 * MainActivity: documentLaunchMode="intoExisting" + PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI, so
 * DeX and split-screen can host several windows; every window gets a windowKey (taskId + display).
 * Edge-to-edge, caption-bar insets for desktop windowing, predictive back per focused slot.
 * Root: Modifier.semantics { testTagsAsResourceId = true }.testTag(AmpTag.Shell.root).
 * The keyboard shortcuts are the table in docs/design/screens.md "Keyboard shortcuts", published as
 * KeyboardShortcutGroups; this file defines no chords of its own.
 */
@Composable
fun AmpWindow(window: WindowState, engines: AmpEngines, leases: VisibleThreadLeases, geometry: GeometrySource): Unit = TODO(
    "scope = engines.scope(window.account); planLayout(); " +
    "AdaptiveWorkspace(plan, pane = { d, m -> PaneHost(d, scope, m) }, …); " +
    "leases.show(scope, threads referenced by the plan's visible slots); KeyboardShortcuts(); DropTargets()"
)

/**
 * Holds one viewer lease per (window, thread) for every thread referenced by a visible slot's top
 * destination (Thread, Machine, Puck). Bound to the window's Lifecycle, not to the composition:
 * leases are held while the window is STARTED and all released on ON_STOP, then re-taken on ON_START.
 * Two windows on the same thread hold two leases on one ThreadEngine: one socket, one StateFlow.
 * LeaseLifecycleTest (JVM, TestLifecycleOwner) moves a window to CREATED and asserts every lease closed.
 */
class VisibleThreadLeases(windowKey: String, lifecycle: androidx.lifecycle.Lifecycle, issuer: LeaseIssuer) /* : DefaultLifecycleObserver */ {
    fun show(scope: AccountScope, threads: Set<ThreadId>): Unit = TODO()
}

/** Every pane, one exhaustive `when`. A new PaneDestination without a branch fails to compile. */
@Composable
fun PaneHost(dest: PaneDestination, scope: AccountScope, modifier: Modifier): Unit = TODO(
    """
    when (dest) {
      PaneDestination.Sidebar         -> amp.fleet.SidebarPane(dest, scope)
      is PaneDestination.Activity     -> amp.fleet.ActivityPane(dest, scope)
      PaneDestination.Running         -> amp.fleet.RunningPane(dest, scope)
      is PaneDestination.NewThread    -> amp.fleet.NewThreadPane(dest, scope)
      is PaneDestination.Thread       -> amp.thread.ThreadPane(dest, scope)
      is PaneDestination.Machine      -> MachinePane(dest, scope)
      is PaneDestination.Puck         -> amp.puck.PuckPane(dest, scope)
      is PaneDestination.Settings     -> amp.settings.SettingsPane(dest, scope)
      is PaneDestination.Web          -> amp.web.WebPane(dest, scope)
    }
    """
)

/**
 * The machine pane: a strip of real Tabs (selected semantics, tags `tab-<key>`) and an exhaustive
 * `when` over MachineTab. It lives in :app because :app is the only module that depends on every
 * feature module. A tab whose capability is Availability.No shows the reason instead of hiding (e.g.
 * runner started without --remote-control-terminal), except Desktop, which appears only when
 * MachineCaps.desktop is Yes. [Expand] / [Collapse] / [Focus] call layout's expand/collapse.
 */
@Composable
fun MachinePane(dest: PaneDestination.Machine, scope: AccountScope): Unit = TODO(
    """
    when (val tab = dest.tab) {
      is MachineTab.Changes  -> amp.changes.ChangesTab(dest, tab, scope)
      is MachineTab.Files    -> amp.files.FilesTab(dest, tab, scope)
      is MachineTab.Terminal -> amp.terminal.TerminalTab(dest, tab, scope)
      is MachineTab.Portal   -> amp.web.PortalTab(dest, tab, scope)
      is MachineTab.Desktop  -> amp.desktop.DesktopTab(dest, tab, scope)
      MachineTab.Space       -> amp.space.SpaceTab(dest, tab, scope)
    }
    """
)

/**
 * One router for every external entry: App Links (https://ampcode.com/threads/T-…, per-account custom
 * domains matched against the roster), amp:// notification intents, share intents, App Shortcuts,
 * Puck navigation, and the debug app-event hook. Each resolves to an EntryTarget. The target goes to
 * the most recently focused window on that account, through layout.place(). When no window shows that
 * account, it opens a new window for it on Medium and wider; on Compact, the single window switches to
 * that account and restores the account's saved workspace for this windowKey.
 */
interface EntryRouter { fun route(intent: android.content.Intent): EntryTarget? }
data class EntryTarget(val account: AccountId, val dest: PaneDestination)

/**
 * The only sign-out path (auth-and-security.md "Sign out"). In order: close every window's panes for
 * the account; :notify's AccountTeardown cancels its notifications and WorkManager tag, unregisters a
 * UnifiedPush instance if the device uses one and removes its launcher shortcuts; :feature:web's WebProfiles
 * deletes the `amp-<accountId>` WebView profile; AmpEngines.signOut deletes the relay registration, revokes
 * the token and deletes the vault entry, push keys, Room file and image cache. Each step is idempotent, so
 * a crash mid-way is retried from the start.
 */
class SignOutCoordinator(engines: AmpEngines, notify: amp.notify.AccountTeardown, web: amp.web.WebProfiles) {
    suspend fun signOut(account: AccountId): Unit = TODO()
}

/**
 * Debug-only agent-device hook. Debug builds register amp-debug://event/<name>?payload=<json> and set
 * AGENT_DEVICE_ANDROID_APP_EVENT_URL_TEMPLATE to match. This sealed type is the single list of events;
 * the table in docs/design/verification.md mirrors it. Absent from release builds.
 */
sealed interface AppEvent {
    data class UseServer(val url: String) : AppEvent                                  // use-server
    data class SignInToken(val token: String) : AppEvent                              // sign-in-token
    data class OpenThread(val thread: ThreadId, val pane: MachineTab?) : AppEvent     // open-thread
    data class SetArrangement(val arrangement: Arrangement) : AppEvent                // set-arrangement
    data class InjectPush(val payloadJson: String) : AppEvent                          // inject-push, the only push-injection path
    data class DropConnection(val target: SocketTarget) : AppEvent                    // drop-connection
    data class GeometryOverride(val widthDp: Float, val creasesDp: List<Float>) : AppEvent
    data class SeedOutbox(val commands: Int, val delivery: String) : AppEvent          // "replayable" | "at-most-once" | "guarded"
    data class TerminalType(val thread: ThreadId, val tab: Int, val text: String) : AppEvent   // terminal-type
    data class TerminalPaste(val thread: ThreadId, val tab: Int, val text: String) : AppEvent  // terminal-paste
}
enum class SocketTarget { Thread, User }

/**
 * Opened from the window bar (`window-palette`) on touch and from the shortcut in screens.md.
 * Entries are derived, not listed by hand: thread search (Fleet.search), every PaneDestination
 * reachable from the focused slot, every ThreadIntent the focused thread's state allows (rename,
 * snooze, share, ship…), and the thread's plugin commands (ThreadState.pluginCommands from
 * command.list, run via ThreadIntent.RunPluginCommand; S15b).
 */
interface CommandPalette { fun entries(query: String, focus: PaneDestination?): List<PaletteEntry> }
sealed interface PaletteEntry {
    data class Go(val label: String, val dest: PaneDestination) : PaletteEntry
    data class Do(val label: String, val thread: ThreadId, val intent: ThreadIntent) : PaletteEntry
}

// ─────────────────────────────────────────────────────────────────────────────
// :fake-amp  package amp.fake   (debugImplementation + testImplementation only)
// In-process fake Amp server on 127.0.0.1 (MockWebServer with WebSocket upgrade). Speaks the real
// wire, so :sync runs unmodified against it (no transport seam in production code).
//
// Scenario = ordered capture lines {n, t, ch, dir, data} from spikes/protocol/captures/*.jsonl
// (format measured): for ch=thread/user/http, `send` lines are expectations (matched by method and
// shape, ids rewritten), `recv` lines are emitted after the matching send (or on connect), with
// time scaled. Scripted scenarios add: approval gate, plugin confirm, 2,000-message thread (node
// budget), reconnect mid-turn (resume cursor), drift (unknown types and seq-less Sequenced types),
// a request whose client_*_result arrives seconds after its ok response, a lost ack for one command
// of each delivery class, 401 → refresh, and Web Push delivery (encrypted with the registered keys) both as
// a relay-shaped FCM data map {v, rid, body | wake} and as a UnifiedPush message.
// ─────────────────────────────────────────────────────────────────────────────
interface FakeAmpServer {
    fun start(scenario: String): String          // returns baseUrl
    fun expectNoUnmatchedSends()
    fun stop()
}
