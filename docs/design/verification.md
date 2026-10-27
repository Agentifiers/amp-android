# Verification with agent-device

The app is built and verified with `agent-device` (version 0.21.16 on this machine). This document sets the rules the app follows so that agent-device can drive and check every flow, and the test layers that run on every change.

## The app is drivable by design

agent-device reads the Android accessibility tree. Its `id=` selector matches the Android `resource-id`, and Compose only exposes `testTag` as a `resource-id` when a parent sets `testTagsAsResourceId` (the TriFold's published specs section 1.4). The app follows these rules:

- The root composable sets `Modifier.semantics { testTagsAsResourceId = true }`.
- Every pane, tab, button, field, list row, and status indicator a test needs has a stable `testTag`. Tags are kebab-case and describe the thing, not its position: `pane-thread`, `composer-input`, `composer-send`, `thread-row-<threadId>`, `tool-card-<toolUseId>`, `approval-allow`, `diff-file-<path>`.
- Secondary actions are real buttons with their own tags. agent-device does not see custom accessibility actions, long-click, or `stateDescription`, so a swipe-to-archive row also has an **Archive** button in its overflow menu.
- Tabs, toggles, and arrangement items report `selected` or `checked`. Section titles set `heading()`.
- Status is text, not color alone. A thread row shows "working", "needs approval", or "done" as text next to the dot, so `is text` can check it.
- Lazy lists keep the tree small. agent-device stops at 5000 nodes, and a long transcript or diff must stay under that. Transcript and diff use `LazyColumn`, so only visible rows are in the tree.

The layout prototype confirmed the first two rules: every tag passed `agent-device is exists` as an `id=` selector.

## Debug builds accept app events

agent-device `trigger-app-event` sends an app-defined event through a deep link. Debug builds register `amp-debug://event/<name>?json=<urlencoded json>` (`app/src/debug/kotlin/com/agentifiers/amp/debug/AppEvents.kt:16`), and `scripts/flows.sh` sets `AGENT_DEVICE_ANDROID_APP_EVENT_URL_TEMPLATE='amp-debug://event/{event}?json={payload}'` to match. Release builds do not register the scheme. `AppEvents.handlers` in the debug source set is the one list of events, and this table mirrors it. Each handler is one file in `app/src/debug/kotlin/com/agentifiers/amp/debug/`.

| Event | Payload | Effect |
|---|---|---|
| `use-server` | `{url}` or `{scenario}` | Points the next sign-in at a server. A scenario name starts that `:fake-amp` scenario inside the app. Signs out every account on another server. |
| `sign-in-token` | `{token}` | Signs in with a test token and skips the sign-in flow. |
| `open-thread` | `{threadId, pane?}` | Opens a thread, optionally on a machine tab: `changes`, `files`, `terminal`, `portal`, `space`, or `desktop`. |
| `set-arrangement` | `{name}` | Applies one of the six arrangements to the window in front. |
| `geometry-override` | `{widthDp, creasesDp}`, or `{}` to clear | Lays the window out as if it had this width and these creases, so one emulator covers the whole layout matrix. |
| `drop-connection` | `{target}`, where `target` is `thread` or `user` | Closes thread sockets to test reconnect and resume. `user` closes nothing, because the only user actor socket belongs to the Watch service. |
| `files-stored` | `{threadId, path}` | Logs the size and SHA-256 of the file the running `:fake-amp` scenario stored, so a flow checks an upload byte for byte. |
| `run-plugin-command` | `{threadId, plugin, commandId}` | Runs a plugin command as the command palette does, so a flow reaches a plugin dialog without the palette. |
| `lock-now` | `{}` | Locks every window when app lock is on, so a flow tests the lock without backgrounding the app. |
| `inject-push` | the push payload JSON, with `kind`, `threadId`, and `tag` required | Encrypts the payload to the signed-in account's push keys and runs decryption and the notification display on it. This is the only way a flow injects a push. agent-device's `push` command sends a broadcast (the TriFold's published specs), and the app has no receiver for it. |
| `seed-outbox` | `{threadId, delivery, commands, inFlight?}`, where `delivery` is `replayable`, `at-most-once`, or `guarded` | Queues commands of one delivery class while offline, to test how each class drains or gives up. `Ephemeral` commands never reach the outbox table, so they cannot be seeded. |
| `pin-widget` | `{}` | Asks the launcher to pin the fleet widget, so a flow puts it on the home screen with one confirm tap. |
| `terminal-type` | `{threadId, tab?, text}` | Sends `text` to the tab's PTY as typed input. `tab` is 1 to 5 and defaults to 1. |
| `terminal-paste` | `{threadId, tab?, text}` | Runs `text` through the paste path, including bracketed paste and the multi-line dialog. agent-device has no Android clipboard command, so this is the only way to test paste. |
| `open-catalog` | `{width?, section?}` | Opens the `:core:design` component catalog in a pane `width` dp wide, so a flow sees every icon and control at a real pane width. |

## Network evidence comes from app logs

`agent-device network dump` parses HTTP traffic from the app log, not from a proxy. It reads `adb logcat -v time --pid <pid>` and expects one line per finished HTTP exchange: a JSON object with `method`, `url`, `status`, one of `headers`, `requestHeaders`, or `responseHeaders`, and optional `requestBody` and `responseBody`, followed by the text `duration=<ms>` outside the JSON. A `durationMs` key inside the JSON is ignored. The format was read from the installed agent-device parser and checked by running copies of its parser functions on sample lines (`spikes/protocol/RESULTS.md` row S20). Debug builds write this line under the tag `AgentNet`:

```
10-22 14:03:07.123 I/AgentNet( 4321): {"method":"POST","url":"https://ampcode.com/api/internal?listThreads","status":200,"headers":{"content-type":"application/json"}} duration=87
```

WebSocket frames are not HTTP exchanges. The app logs them under a separate `AmpWs` tag as plain text with no JSON object, so they cannot match the `network dump` line shape [INFERENCE: the parser keys on the JSON object, per row S20], and flow scripts read them with `agent-device logs`. Bodies are off by default. The `Authorization` header, `wsToken` values, and the `x-rivet-conn-params` header are always redacted before logging.

## The TriFold AVD

`scripts/create-trifold-avd.sh [avd-name]` creates the AVD that CI and every flow use. The default name is `TriFold_Inner_API36`. It installs the Android 16 (API 36) `google_apis` arm64 image and sets a 2160 x 1584 screen at 320 dpi, which is 1080 x 792 dp, the TriFold's inner display in landscape. It also sets landscape as the initial orientation and enables a hardware keyboard (`scripts/create-trifold-avd.sh:2-26`). The script prints the emulator command line when it finishes.

The emulator reports no folding features for this AVD (`scripts/create-trifold-avd.sh:3`). Flows set creases with the `geometry-override` app event. The real crease positions were measured on the physical TriFold (`layout.md`).

## Notifications are checked in the shade

A notification is system UI, not part of the app's window. A flow that checks one does this:

1. Produce the notification, with the `inject-push` app event or a live turn.
2. Open the shade with `adb shell cmd statusbar expand-notifications`.
3. Take an `agent-device snapshot` and assert on the notification's title, text, and action labels, such as **Allow**, **Review**, or **Reply**. Notification views carry no app resource ids, so these assertions match text [INFERENCE].
4. Press an action by its label, or close the shade with `adb shell cmd statusbar collapse`.

## Test layers

1. **JVM tests (every change).** These need no device:
   - Protocol contract tests. The spike captures in `spikes/protocol/captures/*.jsonl` replay through the Kotlin protocol parser and reducer. A frame type the parser does not know must produce an `Unknown` event, never an exception.
   - The replay property test, which feeds history replay first and then the current-state burst, the order the server sends them (`architecture.md:76-91`).
   - A correlator test: a bare `{ok:true}` response never settles a request that carries a `requestId` (`transport.md:203-220`).
   - A lease test: driving a window's lifecycle to `ON_STOP` releases every lease it held (`architecture.md:93-103`).
   - `ToolCardsTest` and `WireDecodersTest`, which fail on a duplicate key (`architecture.md:118-126`).
   - An outbox test that no `Ephemeral` command reaches Room, on top of the compile-time rule (`architecture.md:105-116`).
2. **Fake server flows (emulator, every change).** A small fake Amp server replays recorded sessions: thread list, a live turn with tool calls, a plugin dialog, a terminal session, and a diff. The app talks to it through the `use-server` event. agent-device `.ad` scripts drive each flow and assert on tagged elements. `agent-device test 'flows/*.ad' --reporter junit:<path>` runs the suite on the TriFold AVD.
3. **Form-factor sweep (emulator, every change that touches UI).** The same flows run on the TriFold AVD in four window states: inner landscape, inner portrait (`adb shell settings put system user_rotation 1`), cover (`wm size 1080x2520` and `wm density 420`), and a 304dp freeform width. `agent-device diff screenshot --baseline` flags visual changes per arrangement.
4. **Live drift check (nightly).** A runner starts on a Mac, and a short `.ad` flow creates a thread on it with the low mode, sends one message, opens the terminal, reads the diff, and archives the thread. The same run waits for that turn's push to arrive through the deployed push relay and FCM, so a broken push path fails the night it breaks. A failure here means Amp changed the protocol or push delivery. The spike cost $0.05 for a comparable run (`spikes/protocol/RESULTS.md`).
5. **Device sweep (before release).** `agent-device test --shard-all 2 --device <trifold>,<pixel>` runs the fake-server flows on the physical TriFold and the Pixel 8.
6. **Push relay tests (every change to `relay/`).** Vitest runs the Worker in workerd with FCM mocked at the `fetch` boundary. The cases are listed in `push-relay.md`, under "Tests".

## Performance budgets

The transcript must stay smooth while deltas stream in. The spike saw 31 `delta` events in a 13-second turn (`RESULTS.md` row S4), but long turns with streaming code are denser. The target is under 1 percent janky frames on the TriFold while a recorded fast stream replays, measured with `agent-device perf frames --json`. Terminal output at 1 MB per second must not drop input echo. These are targets to measure in the first build, not measured values.

## Open questions

- Which agent-device version does the build standardize on? 0.21.16 has no Android `fold` command. Newer versions add it only for Pixel and 7.6-inch emulator profiles.
- Can the physical TriFold be reserved for this project's device sweep, given another session uses it today?
