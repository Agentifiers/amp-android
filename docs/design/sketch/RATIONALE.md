# Amp for Android: synthesized architecture

This is arena candidate A (native-first) with six grafts from candidates B and C, five fixes from the cross-judge, the measured results of protocol spike 2, and the fixes from the adversarial review. `README.md` in this directory maps every graft and review fix to a file and line.

Every Amp surface is Compose on `cli`-tier contracts. Web content appears only where the content is web by nature. Portal pages, MCP App UIs and `amp-view` prototypes run in sandboxed in-app WebViews. Account, billing, workspace-admin and model-routing pages run in a WebView that uses the account's own profile, `amp-<accountId>`, so a page never shows another account's session.

Claim labels: **measured** (seen in a spike capture or the layout prototype), **inferred** (read from code or docs, not exercised), **guess**.

## Problem

Amp has no Android client, no FCM path, and no public write API. The stable contract a third-party client can use is the private protocol the CLI ships: internal RPC, the thread actor socket, the user actor, and the relay (`docs/protocol/private-protocol.md:12-22`). Spike 1 verified the core loop over it (`spikes/protocol/RESULTS.md` S1-S14). Spike 2 (S15-S20) measured the paths every candidate had assumed. The device is a 1080x792dp inner display with two creases the emulator does not report (measured, `logcat-LayoutProto.txt:2`), a 411dp cover, and DeX windows down to 304dp.

Constraints:

- The core loop runs on `cli`-tier contracts. `web-remote` appears only inside a web surface.
- No wire type on a public module surface.
- agent-device drives everything by `resource-id`, ignores long-click and custom actions, and caps snapshots at 5000 nodes (the TriFold's published specs).
- Opening an orb thread wakes and bills the orb (`collab-apps.md:388`). Background code must never do that by accident.

## Usage (caller's view)

### README for a new contributor

> The app has one engine per signed-in account, `AccountScope`, in `:sync`. It owns every socket, cache, token and push key for that account. Each window shows one account.
>
> ```kotlin
> val thread: ThreadHandle = scope.thread(threadId)                       // cheap, never connects
> val state: ThreadState by thread.state.collectAsStateWithLifecycle()     // cached first, then live
> thread.dispatch(ThreadIntent.Send(draft, SendMode.Steer))               // outbox first, then the wire
> ```
>
> You never open sockets. `:app`'s `VisibleThreadLeases` holds a viewer lease for every thread a pane shows in a started window, and drops them all when the window stops. Only `:app` holds the `LeaseIssuer` that mints those leases. Background code gets `BackgroundAccess`, which cannot hold a lease and refuses to open a socket on an asleep orb.
>
> `ThreadState` is built at read time from four sources with one writer each: the durable fold, the volatile tier, the connection, and this device's unsent work. You never see a wire type.
>
> Every write is a `Command` whose type names its delivery class: `Replayable`, `AtMostOnce`, `Guarded`, or `Ephemeral` for secrets that must never be stored. `routeOf` picks its channel in one exhaustive `when`.
>
> Where things go:
> - A screen region is a `PaneDestination` and one branch in `:app`'s `PaneHost`. A machine tab is a `MachineTab` subclass and one branch in `:app`'s `MachinePane`. Both are exhaustive `when`s, so a missing branch fails to compile.
> - A tool card is one file in `:feature:thread` plus one entry in the `ToolCards` list. Unknown tools get `GenericToolCard`.
> - A protocol message is one decoder that returns a classed `ThreadFact`, plus its type names in the `WireDecoders` list in `:sync`.
> - An account setting is an `AccountResource` key to read and an `AccountCommand` to write.
> - Keyboard shortcuts are the table in `docs/design/screens.md`. The sketch defines none.
> - `android.webkit`, Custom Tabs, and the literal `/_app/remote/` are allowed only in `:feature:web`. `build-logic/ModuleGraphRules.kt` enforces it.

### Call site 1: a Compose screen that consumes live thread state

```kotlin
// :feature:thread  ThreadPane.kt, called from :app's PaneHost
@Composable
fun ThreadPane(dest: PaneDestination.Thread, scope: AccountScope) {
	val thread = remember(dest.id) { scope.thread(dest.id) }
	val state by thread.state.collectAsStateWithLifecycle()
	Column(Modifier.testTag(AmpTag.Thread.pane(dest.id))) {
		ThreadTitleBar(state.header, state.link, state.viewer, thread::dispatch)
		LinkBanner(state.link, state.drift, onConnectLive = thread::connectLive)  // Link.OrbPaused offers Connect live
		TranscriptOrSkeleton(state.transcript, state.activity, thread::dispatch)
		GateDock(state.gates, thread::dispatch, onOpen = navigate)  // UnsupportedGate → [Answer on web]
		Composer(state.composer, thread::dispatch)
	}
}
```

### Call site 2: the notification path when a thread needs a human

```kotlin
// :notify  RelayMessagingService.onMessageReceived (FCM from our push relay; UnifiedPushReceiver does the same)
val delivery = entryPoint.background().routeRelayMessage(data) ?: return   // data["rid"] → account
val bg = entryPoint.background().forAccount(delivery.account)
val body = delivery.body ?: return PollWorker.runOnce(ctx, delivery.account)  // a wake message: sync instead
val event = bg.decodePush(body) ?: return             // :sync decrypts and parses; :notify reads no wire format
if (event is PushEvent.ApprovalRequired) GateFetchWorker.enqueueExpedited(ctx, delivery.account, GateFetchTrigger.Push(event))
// The watch and poll paths enqueue GateFetchTrigger.Status when the user actor reports
// indicator {kind:"action-required"} (measured for plugin dialogs, S15b :65, S15c :66).

// :notify  GateFetchWorker.doWork
val gates = bg.peekGates(trigger.thread)       // one-shot socket: connect, resume, read, close
val gate = (gates as? Outcome.Ok)?.value?.firstOrNull { it.answerability == Answerability.Answerable }
if (gate != null) notifier.postGate(account, trigger.thread, title, tag, gate)   // Allow only if the whole input is visible
else notifier.postOpenOnly(account, trigger.thread)

// :notify  GateActionReceiver (PendingIntent carries GateTokens.encode(response))
when (bg.answer(action.thread, action.response)) {   // one-shot socket; never /request/rpc (S18: -32601)
	is Outcome.Ok -> notifier.resolve(action.tag, ResolvedAs.Approved)
	is Outcome.Refused -> notifier.resolve(action.tag, ResolvedAs.AnsweredElsewhere)  // guard no longer held
	else -> notifier.postOpenOnly(action.account, action.thread)
}
```

`KeepAlivePolicy` decides which delivery paths run on top of the 15-minute `PollWorker`: Web Push when the account has a push registration (our relay over FCM by default, UnifiedPush on devices without Google Play services), or a `specialUse` `WatchService` for threads the user chose to Watch, until the user's watch limit runs out. `NotificationLedger` dedupes every path by tag.

### Call site 3: adding a renderer for a new tool type

```kotlin
// :feature:thread  renderers/DiagnosticsCard.kt, plus one entry in ToolCards.all
object DiagnosticsCard : ToolCard<DiagnosticsCall> {
	override val names = listOf("get_diagnostics")
	override fun decode(call: ToolCall): DiagnosticsCall? =      // null → GenericToolCard, never a crash
		call.input.string("path")?.let(::DiagnosticsCall)

	@Composable
	override fun Content(call: DiagnosticsCall, run: ToolRun, ctx: ToolCardContext) {
		ToolCardFrame(ctx, title = "Diagnostics", status = run) {
			Text(call.path, style = AmpType.code)
			if (run is ToolRun.Done) run.result["diagnostics"].items().forEach { DiagnosticRow(it, ctx::navigate) }
			else RunOutcomeRow(run)
		}
	}
	override fun summary(call: DiagnosticsCall, run: ToolRun) = "Diagnostics · ${call.path}"
}
```

`get_diagnostics` is in the web's tool enum (`core.md`, tool registry) and no existing card claims it. `ToolCardsTest` fails on a duplicate name. A's original example claimed a name `MediaCard` already handles, which would have failed the same check (judge red flag, measured).

## Shape

### Data first

- **`ThreadState`** (`core-model/ThreadState.kt`) is the only thing screens read. Its fields are sum types: `AgentActivity` (adds `LastKnown` for when no socket epoch is live), `ExecutorState`, `ToolRun`, `HumanGate`, `Link` (adds `OrbPaused` for a paused executor with no socket), `Viewer`, `Degradation`.
- **Four sources, one writer each** (`sync/internal/ThreadEngine.kt`). `DurableThread` is written only by the reducer from Sequenced, Snapshot and Keyed facts. `VolatileThread` holds Volatile facts for one socket epoch. `Connection` (`Link` + `Viewer`) is written only by `LinkSupervisor`. `LocalThread` (draft, outbox, unsent ephemeral commands) is written only by the outbox, the ephemeral sender and the draft store. A replay can move the fold, never the link. A reconnect can move the link, never the fold.
- **Fact classes** (`sync/internal/Facts.kt`). Across all 895 typed recv frames in spikes 1 and 2, exactly six types carry `seq`: `message_added`, `error_set`, `error_cleared`, `queued_message_added`, `queued_message_dequeued`, `cancelled` (measured by a script over `captures/*.jsonl`). These are `Sequenced`. Whole-value frames are `Snapshot`. `plugin.ui.request_added`, `request_settled` and `request_removed` are `Keyed`. Streaming and presence frames are `Volatile`. Invariants I1-I8 state what each class may do, and `sync/test/ThreadReducerReplayTest.kt` checks them by feeding every capture once, twice, and split at every frame with a simulated restart that replays history and then the snapshot burst, in the order S17 measured.
- **Cache by fact class** (`store/Persistence.kt`). `message_row` is keyed by protocol message id. `sequenced_row` is keyed by the `seq` the frame carries. `snapshot_row` holds the latest frame per aspect, except `PluginUi`, which is never cached. Volatile and Keyed facts are never cached. Rows and cursor commit in one transaction.
- **Commands with delivery classes** (`core-model/Intents.kt`, `sync/internal/Routing.kt`). `Replayable` resends after a lost ack. `AtMostOnce` becomes `OutboxState.Unknown` and waits for the user. `Guarded` re-checks a predicate over the caught-up fold before every attempt and drops with a reason when it fails, which is how "answered on another client" settles. `Ephemeral` carries a secret, stays in memory, and is sent once; `OutboxCodec` accepts only `Command.Durable`, so a secret cannot reach Room. `Send` is `Replayable` because the server dedupes by client `messageId` (S16, measured), and its first echo, `queued_message_added` or `message_added`, acknowledges it.
- **Human gates are one queue** (`core-model/Gates.kt`). Each gate builds its own `GateResponse`. A secret plugin input builds a `SecretPluginAnswer` instead, which no stored command can carry. A gate the app cannot decode or answer natively is `UnsupportedGate` with no responder, rendered as [Answer on web].
- **Settings are keys, not methods** (`core-model/Settings.kt`). `AccountScope.read(AccountResource<T>)` and `AccountScope.dispatch(AccountCommand)` replace a facade of about twenty one-line pass-throughs (judge red flag).
- **Transcript is windowed and turn-grouped.** A streaming `delta` replaces one overlay entry. Older history pages backward (S9). Diff and terminal views are line-virtualized, which keeps screens under 5000 nodes.

### Flow

`OkHttp socket → InboundFrame (Transport.kt) → WireDecoders → ThreadFact → ThreadReducer.plan (pure) → ThreadCache.commit (one transaction) → DurableThread / VolatileThread → project(durable, volatile, connection, local) → StateFlow<ThreadState> → Compose`.

Writes go the other way: `dispatch(Command) → outbox row (Durable) or memory (Ephemeral) → drainer → routeOf → the thread's one socket | /request/rpc (append only) | internal RPC | user action → settle by delivery class`. A request that carries a `requestId` settles in `RequestCorrelator` only on its `client_*_result` notification, an error response, or a 120 s timeout. Its `{ok:true}` response arrives first and carries no data (S7, S19: the result came 100 to 160 ms later, measured), so it never settles the request.

### Module map

Detail in `settings.gradle.kts`, enforced by `build-logic/ModuleGraphRules.kt`, summarized per module in `README.md`. Hilt composes the modules: `:store` provides one Room instance per account file and one DataStore per proto type, `:app` provides `AmpEngines` and `BackgroundAccessProvider`, and `:notify` reaches the engine only through `NotifyEntryPoint`.

### Load-bearing decisions

1. **The engine is the API.** `AccountScope`, `ThreadHandle`, `Fleet`, `Machine`, `TerminalSession` and `BackgroundAccess` are the whole public surface of `:sync`.
2. **Wire stays `internal` to `:sync`.** Unknown event types and malformed frames raise `ThreadState.drift`, a visible banner, never an exception. Push payloads, gate tokens and outbox rows are encoded in `:sync` too. `:waymote` is the one other module that speaks a wire format, by design.
3. **Background code cannot wake orbs, by type.** `AmpEngines.create` returns `EngineParts`, and only `:app` holds its `LeaseIssuer`, in an `internal` field that is never a Hilt binding. A `ViewerToken` has an `internal` constructor, so nothing else can make one. `:notify` gets `BackgroundAccess` through `NotifyEntryPoint`, and its one-shot sockets carry no viewer lease. Only a viewer lease wakes a sleeping orb: a thread shown in a started window's pane sends one `client_spawn_executor{executorDemand:"optional"}`, as the web's open thread page does (`screens.md`, "Wake an orb only for a shown thread").
4. **`/request/rpc` is append-only, by type.** `StatelessThreadRpc` has one method, `appendUserMessage`. S18 probed 18 methods and the gateway returned `-32601` for all 17 others, including `client_tool_approval_response`, `client_cancel`, `client_set_thread_title` and `client_upsert_notification_subscription` (measured, `s18b-rpc-allowlist.jsonl`). Gate answers, mark-read and push upkeep without a lease use a one-shot socket, which joins the thread's socket when one is open.
5. **Layout is data.** `planLayout(WindowGeometry, Workspace) -> LayoutPlan` is pure. The six arrangements are the ones `docs/design/layout.md` names, and each maps to every window class. `SPANNABLE` and `avoidBands` make the crease rules checkable.
6. **Panes and machine tabs are one exhaustive `when` each, in `:app`.** `:app` is the only module that depends on every feature, so `PaneHost` and `MachinePane` live there, and a new destination or tab without a branch fails to compile.
7. **Notifications run on one pure policy.** `keepAlivePolicy` picks among UI, watch, push and polling. The watch service is `specialUse` with a Play declaration for live agent monitoring, and the user's watch limit bounds it.
8. **Web is a quarantined leaf.** Only `:feature:web` may import webkit or Custom Tabs, or contain the literal `/_app/remote/`, which it names only to block navigation. Account pages use the account's WebView profile.
9. **One list per extension point, checked by a test.** Tool cards live in `ToolCards` and wire decoders in `WireDecoders`. A unit test fails on a duplicate key in each.
10. **Verification is designed in.** Every actionable node carries an `AmpTag`, including `pane-<kind>`, `arrangement-<name>` and the window bar from layout.md. Debug builds accept the app events verification.md names, and `inject-push` is the only push-injection path. Network lines use the `AgentNet` one-line JSON shape that agent-device's parser reads (S20, parser run on sample lines).

### Form factors (`layout/Layout.kt`)

| Window | Width class | Layout |
|---|---|---|
| TriFold cover 411x960 | Compact | One stack. Bottom bar: Threads, Thread, Changes, Terminal (layout.md) |
| DeX freeform ≥304, app splits | Compact | One stack. Top tabs when the height is Compact |
| TriFold inner portrait 792 | Medium, 720 and wider | Rail 72, Thread 360, Machine 360. Horizontal creases become `avoidBands` |
| Medium narrower than 720 | Medium | Rail and Thread. Machine panes as title-bar tabs that replace the transcript |
| TriFold inner landscape 1080 | Expanded | The workspace's arrangement over three panels, slot edges on creases |
| Expanded without creases | Expanded | Rail, Thread, one machine pane at 380 |
| DeX monitor ≥1200 | Large, ExtraLarge | Sidebar 300, Thread, two machine panes |

Arrangements on the inner display: **Triage** (default; Sidebar, Thread, Machine, one panel each), **Review** (Rail and Thread, Changes over two panels), **Terminal** (Rail and Thread, Terminal over two panels), **Read** (Sidebar, Thread over two panels), **Puck** (Sidebar, Thread, Puck), **Focus** (one pane over three panels). The window bar, outside every pane, picks the arrangement and exits Focus. With creases, a divider snaps to the nearest crease. Without creases, it snaps to the midpoint within 48dp (prototype 684 → 720, measured). Cover and inner keep separate saved workspaces; unfolding restores the inner one with the cover's thread.

### Resilience

- **Reconnect and resume.** Backoff runs from 3 s to 30 s with jitter and never gives up while a viewer is present. A cold thread seeds its cursor from `thread.data.v` of `getThreadInitialTranscriptPage`. Resuming at `v` replays 0 messages, and resuming at 0 replays the history (S17, measured). A one-shot socket on a thread with no cache resumes from the `threadVersion` that `POST /api/thread-actors` returns, so it replays nothing.
- **Outbox.** Every `Durable` command persists before any I/O and drains after the resume replay and snapshot burst. Delivery classes decide what a lost ack means. `OutboxCodec` is versioned; a row from an unknown version becomes `Rejected` so the user can resend it.
- **Tokens and push keys.** `:sync` is the only owner. Tokens and push keys are separate vault entries with separate compare-and-set, so a refresh cannot overwrite new push keys. Refresh happens 2 min before expiry and once on 401, for images too.
- **Drift.** Unknown events and malformed frames raise `Degradation.Seen`. Unknown tools get `GenericToolCard`. Undecodable gates become `UnsupportedGate`. Unknown response shapes become `EngineError.Drift`.

### What it deliberately does not do

It does not call SvelteKit remote functions, scrape hashes, or embed the ampcode.com thread page. It does not run an executor on Android. It does not store a secret value, and it does not show custom Changes buttons, whose only source is `web-remote`.

### Why native beats a web shell for this brief

The constraint already decides the core loop: a shell's core loop is ampcode.com's client, which runs on `web-remote` calls (`private-protocol.md:293-306`). ampcode.com emits native hooks only for `ios-v1` and `macos-v1` (`GROUNDING.md:33`). agent-device selects by `resource-id`, which web content lacks. One engine with N panes means one socket per thread on the TriFold, not three web apps. Terminal and desktop need keyboard, IME and pointer fidelity that native gives. The cost is about 60 tool renderers and a private protocol to track, softened by `GenericToolCard`.

## Synthesis decision

Base A won on enforcement: wire types are `internal`, background code cannot hold a lease, and each extension point has one place to register. The grafts and fixes below changed the base. Each was folded under one rule: every source of state has one writer, every write names its delivery class, and every unmeasured path has a typed fallback.

| Change | From | What changed in the base |
|---|---|---|
| Fact classes and I1-I8 | C | A keyed non-message events by a seq they do not carry. Facts are now classed from the captures, cached by class, and property-tested. Re-measuring with spike 2 moved `error_set` and `error_cleared` to Sequenced (39 and 7 frames carry `seq`) and added the Keyed class for plugin dialogs. |
| Delivery classes and `routeOf` | C | A's replay boolean is gone. Three classes, one exhaustive route function, and an append-only stateless path. S16 made `Send` Replayable. S18 closed `/request/rpc` to everything else. |
| `KeepAlivePolicy` | C | A pure function in `:notify`. The watch service is opt-in per thread, as notifications.md says, and the review moved it to `specialUse`. |
| `UnsupportedGate` | B | B's `OnlyInWeb` arm, generalized: any gate kind that fails to decode, plus kinds with no answer path. B's not-spiked rows became the fallback table in `COVERAGE.md`. |
| Module rules as data | B | `ModuleGraphRules` holds the edges, the test and debug edges, the import bans, and the `/_app/remote/` literal ban. One lint detector reads it. |
| `avoidBands`, `SPANNABLE`, arrangement names | C, layout.md | Presets became layout.md's six arrangements as data. Only machine panes span a crease automatically. Portrait creases become chrome-avoid bands. |
| Machine tabs per feature | fix | Each tab lives in its own feature. The strip moved to `:app`. Space became a machine tab, as layout.md lists it. Portal became a tab in `:feature:web`. |
| Settings facade removed | fix | About twenty pass-throughs became `AccountResource` keys and `AccountCommand`s on the shared outbox. Model routing moved to a web page per product.md. |
| Link and viewer out of the fold | fix | `Connection` is owned by `LinkSupervisor`. `drift` moved out of `Link.Live` into its own field. |
| Tool card example | fix | Uses `get_diagnostics`, which no card claims. |
| Duplicate results | fix, then review F1 | The judge's fix held an ok response for a 2-second grace. The review showed the response carries no data and the result can come later, so a request with a `requestId` now settles only on its result, an error, or the timeout. |

Rejected, as in SYNTHESIS: B's web transcript and `ios-v1` impersonation, C's Room-only read path, and C's first-result-wins rule.

### Changes from the adversarial review

Three reviewers attacked the design. These decisions changed the sketch. The README maps each to a file and line.

| Id | Decision | Why |
|---|---|---|
| F1 | Settle a `requestId` request only on its result, an error, or 120 s | The ok response came 100 to 160 ms before the result in S7 and S19 (measured). A 2 s grace would settle a slow read as empty [inferred]. |
| F5 | Leases follow the window lifecycle, not the composition | A composition outlives `onStop`, so sockets stayed open in the background [inferred]. `LeaseLifecycleTest` covers release on stop. |
| F6 | `Ephemeral` delivery class | `SetSecret` and secret plugin inputs were persisted in the outbox. |
| F7 | `EngineParts` splits `LeaseIssuer` from the engine | `ViewerLeases.issue` was public and guarded only by lint. |
| F8 | `:sync` owns tokens, push keys and the image loader | Three modules read the vault, and one combined compare-and-set could write back stale push keys [inferred]. |
| F9 | Hilt, one Room and DataStore instance per file, `SignOutCoordinator` | Android-created components had no way to reach the engine, and sign-out could not reach `:notify`, `:feature:web` or the image cache. |
| F10 | One account per window | The sketch had one global active account while the docs allow two windows on two accounts. |
| F11 | Exhaustive `when`s in `:app`, one list each for tool cards and decoders | The KSP module needed a cross-module aggregation nobody had designed, and `checkModuleGraph` would have rejected its own edges. |
| F12 | First echo acknowledges a send; `Interrupt` is its own `AtMostOnce` command; `editId`; interactive calls stay direct | A resent "interrupt and send" would cancel the turn it started [inferred], and a steer stayed unacknowledged for a whole tool run (S5 :56, :64, measured). |
| F13 | Guards run after the resume; one socket per thread | Plugin dialogs are never cached, so a guard checked before the burst dropped valid answers. |
| F14 | Push decoding, gate tokens and `OutboxCodec` in `:sync` | `:notify` and `:core:model` were parsing JSON where kotlinx.serialization is banned. |
| F15 | `TerminalSession` with its VT engine in `:sync`, one per key, shared by windows | A second window would have joined a raw byte stream mid-escape [inferred]. |
| F16 | The sketch follows the prose docs | Terminal keys and scrollback, nullable session key, account pages in the WebView profile, push keys in `:sync`. |
| F18 | One-hour snooze; other values read, not observed | S10 sent only `one_hour` and `off`. |
| F19 | Background sockets resume from `threadVersion` | A never-opened thread would replay its whole history in a background worker. |
| F21 | The replay test feeds history before the snapshot burst; `PluginUi` is never cached | That is the order S17 measured, and I3 already said plugin dialogs are not cached. |
| F22 | `plugin.ui.request_removed` is a Keyed fact that also resolves the notification | Removed dialogs would have stayed visible until the next reconnect [inferred]. |
| F23 | One integer rule: at or above 2^31, a `.0` literal | The sketch said 2^32 and doubles; transport.md said 2^31 and `.0`. |
| F24 | `specialUse` watch service with a Play declaration | The data-sync service type needs a declaration too, and Android defines it for data transfer, which fits a status socket poorly. |
| F26 | Shortcuts live only in `screens.md` | The sketch's own list collided with it (Ctrl+Shift+N). |

## Tradeoffs accepted

- Renderer lag behind the web for new tools, in exchange for native fidelity. `GenericToolCard` keeps unknown tools legible.
- A local snooze ledger reconciled with `find?q=snoozed:true`, because `listThreads` has no snooze field (S10, measured).
- Ship as a plain prompt send. `startThreadShipping` is `web-remote`.
- A Cloudflare Worker push relay to operate (`docs/design/push-relay.md`), in exchange for push on every device with Google Play services and no second app to install. A 15-minute floor remains only for devices with neither Play services nor a UnifiedPush distributor, when the user does not Watch.
- One-shot sockets for background answers and push upkeep. S18 left no stateless alternative. Their cost per thread is unmeasured.
- Plugin dialog state is not cached. A cold open shows pending dialogs only after the reconnect snapshot, which re-lists them (S15c :101, measured).
- Whole-file reads in Files, one at a time per thread, because `range` reads are rejected (S19, measured).
- A secret entered while the app dies is lost, because `Ephemeral` commands are never stored. The user enters it again.
- A Play Console declaration for the `specialUse` watch service.
- Hand-written lists for tool cards and wire decoders, each guarded by a duplicate-key test, and destructive cache migrations, as in A.

## Alternatives considered

- **Hybrid shell with native islands.** Lost on the `cli`-tier constraint and verifiability. Two state owners per thread.
- **Per-feature repositories.** Every screen would merge flows from one event sequence.
- **Room as the only read path (C).** Same behavior with more code; its per-table writer rule was already broken by its own cursor table.
- **A guard instead of server dedupe for `Send`.** Considered before S16; S16 made it unnecessary, so `Send` is plain Replayable.
- **Keeping native model-key listings.** product.md lists model routing as web-only, so it opens a web page.
- **Code generation for panes, tabs, cards and decoders.** Removed by the review (F11). Sealed types already give exhaustive `when`s, and the other two lists each live in one module.

## Open questions and risks

1. **Tool approvals are still unrecorded** (S15 INCONCLUSIVE: the turn failed before any tool call). Payload, push tag, and whether the user-actor indicator fires for approvals are unknown. `UnsupportedGate` keeps the loop usable. A rerun with a working model is the next spike.
2. **No push arrived for a detached plugin dialog** within 45 s (S15c). If Amp never pushes for plugin dialogs, users without Watch learn of them at the 15-minute poll. Whether `getRecentThreads` carries the indicator is [INFERENCE].
3. **What the TriFold reports through `WindowInfoTracker`.** Thirds are the fallback for a device with no table entry and no reported folds.
4. **Read and Focus put a transcript across a crease.** layout.md lists them; SYNTHESIS says only machine panes span. The sketch allows it only in user-chosen arrangements (`userWide`). Needs a product call.
5. **Two app events beyond verification.md's table** (`geometry-override`, `seed-outbox`). The doc should add them or the sketch should drop them.
6. **One-shot socket cost** for push upkeep. Resuming from `threadVersion` removes the replay; the socket's own cost is unmeasured.
7. **Queued sends, other snooze values, `plugin.ui.request_removed`, `ui.select`, dedupe by `editId`, and `amp-exec-v1` staging** are read from the web client and not observed. `COVERAGE.md` lists them.

## Next implementation step

Build `:sync` against `:fake-amp` replaying `s04-live-thread.jsonl`, `s05-steer-interrupt.jsonl`, `s15b-plugin-dialog.jsonl` and `s16-dedupe.jsonl`, until `ThreadReducerReplayTest` passes on all four.
