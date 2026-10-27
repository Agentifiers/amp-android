// Synthesized design (base: arena candidate A, grafts from B and C, interrogation fixes): Gradle module map.
//
// Dependency direction (arrows = "may depend on", all `implementation` unless marked api):
//
//   :app ──► :feature:* ──► :core:design ──► :core:model
//     │          └────────► :sync (public api package only; wire is Kotlin-internal)
//     ├──► :notify ──► :sync, :store (ledger only), :core:model
//     ├──► :layout ──► :core:model
//     ├──► :store (DataStore files only)
//     └──► :sync ──► :store, :vt, :core:model ; :store ──► :core:model
//   :feature:terminal ──► :vt          :feature:desktop ──► :waymote ──► :sync (RelayChannel only)
//   :fake-amp is debugImplementation in :app and testImplementation in :sync
//
// Composition: Hilt. :store's StoreModule provides AccountDatabases and AppPreferences, :app's
// EngineModule provides AmpEngines and BackgroundAccessProvider, and :notify reaches the engine only
// through NotifyEntryPoint. LeaseIssuer is never a Hilt binding (app/AppShell.kt AmpApplication).
//
// Enforced by: Gradle `implementation` (no transitive leaks), Kotlin `internal` for every wire type,
// and build-logic/ModuleGraphRules.kt, the same table as data: `./gradlew checkModuleGraph` fails on
// any edge not in it, and lint `AmpSourceBans` fails on the literal "/_app/remote/" outside
// :feature:web and on webkit / Custom Tabs imports outside :feature:web.

rootProject.name = "amp-android"

pluginManagement { includeBuild("build-logic"); repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }

// ── :core:model ───────────────────────────────────────────────────────────────
// Owns: every domain type the app reasons about. Ids, ThreadState and its sum types, Fleet and
// sidebar types, HumanGate (with UnsupportedGate), ToolCall/ToolRun, ChangeSet/FileDiff, FileNode,
// PaneDestination, MachineTab, Command (ThreadIntent, AccountCommand, Guard; Durable and Ephemeral
// classes), AccountResource keys, ThreadQuery, Account. Pure Kotlin (JVM), no Android, no coroutines
// beyond kotlinx.coroutines-core types in signatures, kotlinx.collections.immutable for lists.
// Must never import: anything Android, OkHttp, kotlinx.serialization annotations (domain types are
// not wire types), or any other module.
include(":core:model")

// ── :core:design ──────────────────────────────────────────────────────────────
// Owns: Material 3 theme, AmpTag (the single registry of test tags), semantics helpers
// (RealButton, Heading, SelectableTab), Markdown renderer, monospace code/diff line primitives,
// AuthedImage (renders through the account's AuthenticatedImageLoader from :sync, passed in by the
// caller), haptics.
// Must never import: :sync, :store, :layout internals, android.webkit. It renders domain values only.
include(":core:design")

// ── :sync ─────────────────────────────────────────────────────────────────────
// Owns: the engine. Public package `amp.sync.api`: AmpEngines and EngineParts, LeaseIssuer,
// AccountScope (read(AccountResource), dispatch(AccountCommand), images), ThreadHandle, Fleet, Machine
// (Changes, Files, TerminalSession, RelayChannelFactory, Portals), Catalog, PuckDirectory,
// BackgroundAccessProvider and BackgroundAccess, PushEvent, GateTokens, SignIn, Loadable.
// Internal: OkHttp transports for internal RPC, thread actor socket, user actor socket + stateless
// actions, stateless /request/rpc (client_append_user_msg only), RequestCorrelator, RivetNumber,
// relay frame codec, wire DTOs (@Serializable, all `internal`), WireDecoders → ThreadFact classes →
// ThreadReducer, LinkSupervisor, outbox + OutboxCodec + routeOf, EphemeralSender, TokenVault (tokens
// and push keys, the only owner), push payload decoding, push-subscription upkeep, terminal sessions
// with their VtEngine, network logger (debug).
// Must never import: Compose, android.webkit, :feature:*, :notify, :layout. Must never expose a
// type from `amp.sync.internal` in a public signature (Kotlin enforces it; `explicitApi()` on).
include(":sync")

// ── :store ────────────────────────────────────────────────────────────────────
// Owns: one Room database per account (`amp-<accountId>.db`) and one DataStore file per proto type
// (`roster.pb`, `workspaces.pb`), each opened once by StoreModule.
// Tables: thread_summary, message_row, sequenced_row, snapshot_row (one per fact class; raw wire
// JSON), resume_cursor, change_snapshot, blob, file_order (the Changes cache), outbox (Durable commands
// only), draft, comment_draft, watch_list, push_subscription, notification_ledger, snooze_ledger,
// label_cache. DataStore: account roster (no secrets), workspace and account per window.
// Must never import: :sync (it is used by :sync, not the reverse), Compose, OkHttp.
// Its public API is consumed only by :sync, :notify (ledger) and :app (DataStore), checked by
// checkModuleGraph. Features never touch it.
include(":store")

// ── :layout ───────────────────────────────────────────────────────────────────
// Owns: WindowGeometry (size class, creases, posture, display kind, input profile), crease
// sources (WindowInfoTracker FoldingFeatures, thirds fallback, none), Arrangement (Triage, Review,
// Terminal, Read, Puck, Focus, per layout.md), SPANNABLE, Workspace (per-window pane stacks +
// arrangement + divider offsets), the pure `planLayout()` function with avoidBands, divider snapping,
// unfold, and the Compose host `AdaptiveWorkspace` with the window bar.
// Must never import: :sync, any :feature:*. :app passes each slot's content in as a lambda.
include(":layout")

// ── :vt ───────────────────────────────────────────────────────────────────────
// Owns: a VT/xterm parser and screen model (cells, attributes, scrollback ring, alt screen,
// cursor, bracketed paste, mouse reporting modes, OSC 52 clipboard requests). Pure Kotlin.
// Base: a Kotlin VT core written for this app. Termux's emulator is GPLv3, so it is not a base
// (docs/design/terminal.md). Fallback core: libghostty-vt (MIT) through JNI if the Kotlin core falls short.
// Used by :sync, whose TerminalSession owns one VtEngine per session, and by :feature:terminal for types.
// Must never import: Android, Compose, :sync. Bytes in, ScreenSnapshot out.
include(":vt")

// ── :waymote ──────────────────────────────────────────────────────────────────
// Owns: the amp-desktop-v1 client: stream/audio/control channel framing, config messages,
// MediaCodec video decode to a Surface, Opus decode via MediaCodec to AudioTrack, feedback/ping,
// display selection, key/text/pointer encoding, clipboard sync. Besides :sync, the one module that
// speaks a wire format, by design: amp-desktop-v1 control JSON.
// Must never import: Compose (it renders into a Surface it is handed), :feature:*, wire DTOs of
// :sync (it receives an opaque RelayChannel of bytes).
include(":waymote")

// ── :feature:fleet ────────────────────────────────────────────────────────────
// Owns: sidebar (Pinned, active by project, Inactive 72h, Snoozed, Archived), rail, Activity feed
// with query syntax chips, search, thread row actions (pin, color, labels, rename, snooze,
// archive, delete, mark unread) as real buttons, new-thread sheet (executor picker: orb + size,
// runner + directory + worktree, project, mode dial), "Running" monitor (amp top).
// Must never import: :sync internals (impossible), :layout, android.webkit.
include(":feature:fleet")

// ── :feature:thread ───────────────────────────────────────────────────────────
// Owns: title bar + thread menu, transcript list (turns, step groups, Markdown, images, recap,
// compaction markers, errors/retry), ToolCards (one list, duplicate names fail ToolCardsTest),
// GenericToolCard, GateDock (approvals, plugin dialogs, ask_user_choice, MCP elicitation, secret
// entry), Composer (steer/queue/interrupt, attachments, dictation, mode chip), queue strip, share and
// multiplayer sheet, comments, presence and drafts of others, report-bug sheet.
// Must never import: android.webkit (amp-view and MCP Apps are WebSurface destinations).
include(":feature:thread")

// ── :feature:changes ──────────────────────────────────────────────────────────
// Owns: the Changes machine tab: sections, intelligent order, muted generated files, per-repo
// grouping, virtualized diff viewer (unified + split on wide slots), hunk selection →
// explain/comment, stage/unstage, Ship / Push / Review / Restack / Rebase as prompt sends.
// The tab strip is :app's MachinePane; this module renders only its own tab.
// Must never import: :feature:thread (talks via ThreadIntent only), android.webkit.
include(":feature:changes")

// ── :feature:files ────────────────────────────────────────────────────────────
// Owns: the Files machine tab: file tree, file viewer (code, image, markdown), upload from SAF/camera/drag-and-drop,
// download/share-out via SAF CreateDocument, attachment gallery for the thread.
// Must never import: android.webkit.
include(":feature:files")

// ── :feature:terminal ─────────────────────────────────────────────────────────
// Owns: the Terminal machine tab: Canvas renderer over TerminalSession.screen, extra-keys row,
// hardware keyboard + IME bridge, selection/copy, paste/drop-to-stage, row mirror semantics for
// agent-device. The session and its VtEngine live in :sync, shared by every window.
// Must never import: android.webkit, :waymote.
include(":feature:terminal")

// ── :feature:desktop ──────────────────────────────────────────────────────────
// Owns: the Desktop machine tab (SurfaceView via AndroidExternalSurface), touch-to-pointer modes
// (trackpad, direct), pinch zoom, keyboard bar, display picker, take-over/hand-back, PiP.
// Must never import: android.webkit, :vt.
include(":feature:desktop")

// ── :feature:space ────────────────────────────────────────────────────────────
// Owns: the Space machine tab on the LiveKit Android SDK (audio, video, screen share via
// MediaProjection), call foreground service (microphone|camera|mediaProjection), PiP.
// Must never import: android.webkit (the degraded fallback opens AccountWebPage.ThreadOnWeb by value).
include(":feature:space")

// ── :feature:puck ─────────────────────────────────────────────────────────────
// Owns: Puck panel (a ThreadHandle with Puck chrome), conversation list, Puck voice call over
// client_puck_voice_* with Core-Telecom self-managed calls, share-to-Puck target, App Shortcuts
// and Quick Settings tile for Dictate to Puck / Call Puck, media presentation viewer,
// puck_platform_navigation → PaneDestination.
// Must never import: android.webkit.
include(":feature:puck")

// ── :feature:settings ─────────────────────────────────────────────────────────
// Owns: accounts (device-flow sign-in, switch, sign out through :app's SignOutCoordinator), native
// settings pages that read AccountResource keys and write AccountCommands (runner sharing, env
// secrets, dictation vocabulary, automations, project ship behavior, custom portal hostnames, MCP
// server approvals, External API OAuth clients), credits glance, app settings (notification
// channels, layout, sounds), and links that open AccountPage web surfaces for web-remote-only
// settings (billing, workspace admin, model routing, integrations).
// Must never import: android.webkit (links are PaneDestination.Web values).
include(":feature:settings")

// ── :feature:web ──────────────────────────────────────────────────────────────
// Owns: the only WebView and Custom Tabs code: the Portal machine tab (mintPortalLoginURL), McpApp
// (sandboxed WebView, postMessage bridge to mcpAppCallTool/mcpAppReadResource), AmpView (sandboxed,
// network-restricted), AccountPageView (the account's WebView profile, side pane on Expanded and
// wider), WebProfiles (one androidx.webkit Profile per account, deleted at sign-out). The only module
// where the literal "/_app/remote/" may appear, to block it; never calls it.
// Must never import: :sync internals (impossible), :feature:* (other features navigate to it by value).
include(":feature:web")

// ── :notify ───────────────────────────────────────────────────────────────────
// Owns: notification channels (needs_you, finished, watching, other), PushChannelSelector,
// RelayMessagingService (FCM data messages from the push relay, docs/design/push-relay.md),
// UnifiedPushReceiver (fallback without Google Play services, one instance per account),
// KeepAlivePolicy (pure), WatchService (specialUse foreground service with a
// Play declaration, user-opted per thread, bounded by the user's watch limit), PollWorker
// (WorkManager), GateFetchWorker, quick reply and approve/deny receivers, NotificationLedger dedupe,
// launcher shortcuts, AccountTeardown, NotifyEntryPoint.
// Must never import: Compose UI of features, :layout, android.webkit. Gets only BackgroundAccess
// from :sync, so it cannot open a viewer lease on an orb thread. Parses no wire format: push
// decryption, payload parsing and gate tokens are :sync's.
include(":notify")

// ── :fake-amp ─────────────────────────────────────────────────────────────────
// Owns: an in-process fake Amp server (OkHttp MockWebServer, HTTP + WebSocket) that replays
// spikes/protocol/captures/*.jsonl and scripted scenarios: /api/internal, /api/thread-actors,
// /actors/gateway/threadActor|userActor, /request/rpc, relay frames, attachments, Web Push
// deliveries. Used by :sync unit tests and by debug builds driven by agent-device.
// Must never ship in release (debugImplementation only).
include(":fake-amp")

// ── :app ──────────────────────────────────────────────────────────────────────
// Owns: AmpApplication (@HiltAndroidApp; builds EngineParts and keeps LeaseIssuer), EngineModule,
// MainActivity (multi-instance, edge-to-edge, root semantics testTagsAsResourceId = true), WindowState
// (one account per window), PaneHost (exhaustive `when` over PaneDestination), MachinePane (tab strip,
// exhaustive `when` over MachineTab), VisibleThreadLeases (lifecycle-bound), SignOutCoordinator, App
// Links (ampcode.com/threads/*, workspace custom domains resolved per account), EntryRouter, share
// target, debug app-event deep link (amp-debug://event/<name>?payload=<json>), in-app updates,
// keyboard shortcut groups (the table in screens.md), drag-and-drop targets.
// Must never import: wire types (impossible), android.webkit (goes through :feature:web).
include(":app")
