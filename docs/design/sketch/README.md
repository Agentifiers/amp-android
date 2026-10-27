# Architecture sketch

This directory is the synthesized architecture for Amp on Android. It is arena candidate A with six grafts from candidates B and C, five fixes from the cross-judge, the measured results of protocol spike 2 (`spikes/protocol/RESULTS.md`, rows S15 to S20), and the fixes from the adversarial review (rows F1 to F42). The Kotlin files are sketches: real types and signatures with `TODO()` bodies. Nothing here builds yet.

- `RATIONALE.md` explains the design and the synthesis decision.
- `COVERAGE.md` maps every `must` and `should` capability to a module and type, and lists every unverified path with its fallback.
- `settings.gradle.kts` is the module map with ownership notes. `build-logic/ModuleGraphRules.kt` is the same map as enforceable data.
- `check-sketch.py` checks this README's line citations, brace balance in every `.kt` file, and names the review removed. Run `python3 docs/design/sketch/check-sketch.py` after an edit. `--fix` rewrites stale citation line numbers.

Where these files and the prose docs in `docs/design/` disagree, the prose docs win, and the sketch changes to match.

## Module map

**`:core:model`.** Owns every domain type: ids, `ThreadState` and its sum types, `HumanGate` with `UnsupportedGate`, `Command` (`ThreadIntent`, `AccountCommand`, `Guard`, the `Durable` and `Ephemeral` classes), `AccountResource` keys, `PaneDestination` and `MachineTab`, fleet and transcript types. Exposes plain Kotlin values. Must never import Android, OkHttp, kotlinx.serialization, or any other module.

**`:core:design`.** Owns the Material theme, `AmpTag` (every test tag, including `pane-<kind>`, `arrangement-<name>` and the window bar), semantics helpers, Markdown and code rendering, and `AuthedImage`. Exposes composables that render domain values. Must never import `:sync`, `:store`, `:layout` internals, or webkit.

**`:sync`.** Owns the engine: transports, `WireDecoders` and the pure `ThreadReducer`, `LinkSupervisor`, the outbox with `OutboxCodec` and `routeOf`, `RequestCorrelator`, `TokenVault` (tokens and push keys, nowhere else), push decryption and upkeep, terminal sessions with their VT engine, and the `AgentNet` log. Exposes only `amp.sync.api`: `AmpEngines` and `EngineParts`, `LeaseIssuer`, `AccountScope`, `ThreadHandle`, `Fleet`, `Machine`, `TerminalSession`, `Catalog`, `PuckDirectory`, `BackgroundAccessProvider`, `BackgroundAccess`, `PushEvent`, `GateTokens`. Must never import Compose, webkit, `:feature:*`, `:notify` or `:layout`, and never puts an internal type in a public signature.

**`:store`.** Owns one Room database per account (`message_row`, `sequenced_row`, `snapshot_row`, `resume_cursor`, `outbox`, `watch_list`, ledgers) and one DataStore file per proto type (roster, workspaces with their account). Exposes `AccountDatabases` and `AppPreferences` through Hilt, each opening a file once. Must never import `:sync`, Compose or OkHttp. Only `:sync`, `:notify` and `:app` may depend on it.

**`:layout`.** Owns window geometry, crease sources, the six arrangements, `SPANNABLE`, the pure `planLayout`, divider snapping, unfold, and the Compose host with the window bar. Exposes `LayoutPlan` and `Workspace`. Must never import `:sync` or any feature.

**`:vt`.** Owns the terminal parser and screen model. Exposes bytes in, `ScreenSnapshot` out. Must never import Android, Compose or `:sync`.

**`:waymote`.** Owns the desktop stream client and MediaCodec decode. Exposes `WaymoteSession` over opaque `RelayChannel`s. Besides `:sync`, it is the one module that speaks a wire format, by design. Must never import Compose, `:feature:*`, or `:sync` wire types.

**`:feature:fleet`.** Owns the sidebar, Activity, the Running monitor and the new-thread sheet. Exposes pane composables. Must never import `:layout` or webkit.

**`:feature:thread`.** Owns the thread pane: title bar and menu (with Watch), transcript, tool cards (the `ToolCards` list), gate dock, composer, queue strip. Exposes pane composables. Must never import webkit.

**`:feature:changes`, `:feature:files`, `:feature:terminal`, `:feature:desktop`, `:feature:space`.** Each owns one machine tab and exposes only that composable. None imports another feature or webkit; `:feature:terminal` adds `:vt`, `:feature:desktop` adds `:waymote`.

**`:feature:puck`.** Owns the Puck pane, voice, share-to-Puck and its entry points. Exposes pane composables. Must never import webkit.

**`:feature:settings`.** Owns accounts and native settings pages that read `AccountResource` keys and write `AccountCommand`s, plus links to web-only settings. Exposes pane composables. Must never import webkit.

**`:feature:web`.** Owns every WebView and Custom Tab: the Portal machine tab, MCP Apps, `amp-view`, account pages in the account's WebView profile, and `WebProfiles`. Exposes pane and tab composables. Must never import other features. It is the only module where webkit imports and the literal `/_app/remote/` are allowed, and it uses that literal only to block navigation.

**`:notify`.** Owns channels, `PushChannelSelector`, `RelayMessagingService` (FCM from the push relay) and `UnifiedPushReceiver` (fallback without Google Play services), `KeepAlivePolicy`, `WatchService` (`specialUse`), `PollWorker`, `GateFetchWorker`, action receivers, the ledger, `AccountTeardown` and `NotifyEntryPoint`. Exposes Android components only. Must never import feature UI, `:layout` or webkit, gets only `BackgroundAccess` from `:sync`, and parses no wire format.

**`:fake-amp`.** Owns an in-process fake server that replays spike captures and scripted scenarios. Exposes `FakeAmpServer`. Must never ship in release.

**`:app`.** Owns `AmpApplication` (Hilt root, the only holder of `LeaseIssuer`), `EngineModule`, windows with one account each, `VisibleThreadLeases`, `PaneHost` and `MachinePane` (the two exhaustive `when`s that render every pane and tab), `SignOutCoordinator`, entry routing, debug app events, and the keyboard shortcut groups from `screens.md`. Exposes the APK. Must never import wire types or webkit.

**`build-logic`.** Owns `ModuleGraphRules`: allowed edges, test and debug edges, import bans, the `/_app/remote/` literal ban. Exposes `checkModuleGraph` and the `AmpSourceBans` lint. Must never be a runtime dependency.

## Data flow

1. A socket frame becomes an `InboundFrame`. `client_*_result` frames settle requests in `RequestCorrelator`, and events go to the decoder that `WireDecoders` maps their type to.
2. The decoder returns a classed `ThreadFact` (Sequenced, Snapshot, Keyed, Volatile, or drift).
3. `ThreadReducer.plan` folds a frame's facts purely; `ThreadCache.commit` writes rows and cursor in one transaction.
4. `project(durable, volatile, connection, local)` builds `ThreadState`, which Compose collects.
5. `dispatch(Command)` writes a `Durable` command to the outbox and keeps an `Ephemeral` one in memory. The drainer routes each command with `routeOf`, checks guards after the socket's replay, and settles the command by its delivery class.

## Where each graft landed

| Graft or fix | Source | Location |
|---|---|---|
| 1. Fact classes, measured classification | C | `sync/internal/Facts.kt:45` `ThreadFact`, `:53` `Keyed` (added for spike 2 plugin dialogs) |
| 1. Invariants I1 to I8 | C | `sync/internal/Facts.kt:16` `I1` |
| 1. Pure reducer | C | `sync/internal/Facts.kt:158` `ThreadReducer` |
| 1. Cache by fact class (replaces seq-string keys) | C | `store/Persistence.kt:27` `MessageRow`, `:35` `SequencedRow`, `:38` `SnapshotRow`, `:47` `CacheWrite` |
| 1. Replay property test | C | `sync/test/ThreadReducerReplayTest.kt:13` `feeding a capture twice`, `:26` `a resume after any prefix` |
| 2. Delivery classes | C | `core-model/Intents.kt:24` `Command`, `:35` `Guard`, `:53` `Send` (Replayable per S16) |
| 2. Exhaustive `routeOf` | C | `sync/internal/Routing.kt:34` `routeOf` |
| 2. `/request/rpc` for `client_append_user_msg` only | S6, S18 | `sync/internal/Routing.kt:28` `StatelessRpc`, `sync/internal/Transport.kt:183` `StatelessThreadRpc` |
| 2. Outbox drain rules | C | `sync/internal/Routing.kt:108` `OutboxDrainer` |
| 3. `KeepAlivePolicy` | C | `notify/KeepAlive.kt:38` `keepAlivePolicy` |
| 4. `UnsupportedGate` (B's `OnlyInWeb`) | B | `core-model/Gates.kt:100` `UnsupportedGate`; dock `feature-thread/ThreadPane.kt:46` `GateDock`; notification `notify/Notify.kt:106` `postGate` |
| 4. Not-spiked fallback rows | B | `COVERAGE.md:246` `## Unverified paths` |
| 5. Module rules as data | B | `build-logic/ModuleGraphRules.kt:10` `ModuleGraphRules` |
| 5. `/_app/remote/` literal ban | B | `build-logic/ModuleGraphRules.kt:47` `BanKind.Literal` |
| 6. Arrangements named as in layout.md | C, layout.md | `layout/Layout.kt:74` `Arrangement` |
| 6. `SPANNABLE` | C | `layout/Layout.kt:92` `SPANNABLE` |
| 6. `avoidBands` | C | `layout/Layout.kt:122` `avoidBands` |
| Fix: settings facade removed | judge | `sync/Api.kt:83` `AccountResource<T>`, `:85` `dispatch(command: AccountCommand)`; `core-model/Settings.kt:12` `AccountResource`; `core-model/Intents.kt:133` `AccountCommand` |
| Fix: link and viewer out of the fold | judge | `sync/internal/ThreadEngine.kt:94` `Connection`, `:100` `DurableThread`, `:133` `project`; `core-model/ThreadState.kt:277` `Link`, `:294` `Degradation` |
| Fix: tool card example | judge | `RATIONALE.md:96` `DiagnosticsCard` |
| Spike 2: resume cursor `thread.data.v` | S17 | `sync/internal/ThreadEngine.kt:65` `thread.data.v` |
| Spike 2: one socket per thread for background answers | S18 | `sync/internal/ThreadEngine.kt:90` `onSocket`, `sync/Api.kt:338` `answer(thread: ThreadId, response: GateResponse)` |
| Spike 2: plugin dialog wire, indicator | S15b, S15c | `core-model/Gates.kt:73` `PluginGate`, `core-model/Fleet.kt:40` `NeedsHuman`, `notify/Notify.kt:22` `FleetStatus.NeedsHuman` |
| Spike 2: files | S19 | `machine/ChangesFiles.kt:47` `client_filesystem_read_directory_v2` |
| Spike 2: `AgentNet` log line | S20 | `sync/internal/Transport.kt:249` `NetworkLog` |

## Where each review fix landed

The ids are the findings of the adversarial review. Rows owned by the prose docs alone are not listed.

| Fix | Location |
|---|---|
| F1. A request with a `requestId` settles only on its `*_result`, an error, or the 120 s timeout | `sync/internal/Transport.kt:146` `RequestCorrelator` |
| F3. A paused orb opens from the cache with no socket until **Connect live** | `core-model/ThreadState.kt:285` `OrbPaused`; `sync/Api.kt:125` `connectLive` |
| F4. Notification **Allow** only when the whole input is visible | `notify/Notify.kt:106` `postGate` |
| F5. Leases follow the window lifecycle | `app/AppShell.kt:54` `VisibleThreadLeases`; `sync/Api.kt:123` `attach(token: ViewerToken)` |
| F6. `Ephemeral` commands never reach Room | `core-model/Intents.kt:31` `Ephemeral`, `:77` `AnswerSecret`, `:136` `SetSecret`; `sync/internal/Routing.kt:84` `OutboxCodec` |
| F7. Only `:app` holds `LeaseIssuer`; `:notify` gets `BackgroundAccess` only | `sync/Api.kt:28` `EngineParts`, `:140` `LeaseIssuer`; `app/AppShell.kt:17` `AmpApplication`; `notify/Notify.kt:44` `NotifyEntryPoint` |
| F8. `:sync` owns tokens, push keys and the image loader | `sync/internal/Transport.kt:42` `TokenVault`; `sync/Api.kt:111` `AuthenticatedImageLoader` |
| F9. Hilt, one Room and DataStore instance per file, `SignOutCoordinator` | `app/AppShell.kt:27` `EngineModule`, `:116` `SignOutCoordinator`; `store/Persistence.kt:139` `AccountDatabases`, `:150` `AppPreferences` |
| F10. One account per window | `app/AppShell.kt:30` `WindowState`, `:105` `EntryRouter`; `store/Persistence.kt:134` `WorkspaceEntry` |
| F11. Exhaustive `when`s and two maps replace code generation | `app/AppShell.kt:60` `PaneHost`, `:84` `MachinePane`; `feature-thread/ToolCards.kt:39` `ToolCards`; `sync/internal/Facts.kt:128` `WireDecoders`; `build-logic/ModuleGraphRules.kt:38` `allowedTestAndDebug` |
| F12. Send acknowledged by its first echo, `Interrupt` on its own, `editId`, interactive calls stay direct | `core-model/Intents.kt:66` `Interrupt`, `:55` `EditMessage`; `sync/internal/Facts.kt:59` `QueuedAdded`; `core-model/ThreadState.kt:217` `ComposerAction`; `sync/Api.kt:97` `startThread` |
| F13. Guards checked after the resume; one socket per thread | `sync/internal/Routing.kt:108` `OutboxDrainer`; `sync/internal/ThreadEngine.kt:90` `onSocket` |
| F14. Wire parsing stays in `:sync` | `sync/internal/Routing.kt:84` `OutboxCodec`; `sync/Api.kt:360` `decodePush`, `:385` `GateTokens` |
| F15. One terminal session per key, shared by every window | `sync/Api.kt:188` `TerminalSession`; `core-model/Panes.kt:33` `Terminal(val session` |
| F16. Terminal keys and scrollback, account pages in a WebView profile | `machine/Terminal.kt:48` `TerminalTab`; `core-model/Panes.kt:50` `AccountPage(`; `feature-other/WebSurfaces.kt:54` `AccountPageView` |
| F18. One-hour snooze | `core-model/ThreadState.kt:92` `OneHour` |
| F19. Background sockets resume from `threadVersion` | `sync/internal/Transport.kt:31` `WsGrant`; `sync/Api.kt:327` `BackgroundAccess` |
| F21. Replay test order; `PluginUi` never cached | `sync/test/ThreadReducerReplayTest.kt:76` `resumeBurst`, `:43` `plugin dialogs never reach the cache`; `sync/internal/Facts.kt:43` `Aspect` |
| F22. `plugin.ui.request_removed` | `sync/internal/Facts.kt:105` `PluginRequestRemoved` |
| F23. One integer rule | `sync/internal/Transport.kt:89` `RivetNumber` |
| F24. `specialUse` watch service | `notify/KeepAlive.kt:51` `WatchService` |
| F26. Shortcuts live in `screens.md` only | `app/AppShell.kt:37` `Keyboard shortcuts` |
| F27, F28, F29, F36. Window bar, portrait, crease-only snapping, unfold | `layout/Layout.kt:134` `WindowBar`, `:177` `planLayout`, `:185` `snap`, `:199` `unfold` |
| F32. Respawn on the same thread | `sync/Api.kt:103` `respawn` |
| F41. No custom Changes buttons | `core-model/Intents.kt:159` `ChangesAction` |
| Engine eviction (C nit 14) | `sync/Api.kt:90` `fun thread(id: ThreadId)` |
| `MachineTabKind` removed (C nit 17) | `layout/Layout.kt:67` `ColumnSpec` |
