# Terminal

The Terminal pane is a shell on the thread's executor, shown next to the transcript. The shell runs in a tmux session that you, the agent, and every other client share, so a command the agent starts there is visible to you and the other way around (Amp's docs). The app draws the terminal natively in Compose and reaches it over the thread actor relay, which is the one terminal path the protocol spike verified (`spikes/protocol/RESULTS.md:18`).

This document covers the transport, the wire format, the session lifecycle, the choice of VT emulator, rendering, input, file upload, the states the pane can show, the live terminal in a reply, accessibility, security, and performance targets. Pane placement and arrangements are defined in `layout.md`. Test rules are defined in `verification.md`.

Citations into the minified web client in ampcode.com's web bundle use `file:offset`, where the offset is a character offset into the single-line bundle. Those facts come from reading Amp's shipped web code. The spike did not exercise them on the wire unless a `RESULTS.md` row says so.

## Evidence status

Only the first row was observed live. Every other row ships with the fallback named in the last column.

| Capability | Status | Evidence | What the app does |
|---|---|---|---|
| Terminal over the actor relay on a runner | VERIFIED | `spikes/protocol/RESULTS.md:18`, `spikes/protocol/captures/s08-terminal.jsonl:57-106` | Primary and only transport |
| Terminal over the actor relay on an orb | [INFERENCE] same path as runners | `docs/design/sketch/COVERAGE.md`, row `orb-terminal-and-the-retired-editor-terminal`, lists it as inferred | Ships. If `open` never reaches `ready`, the pane offers **Open on web** |
| Direct portal socket `/.amp/terminal` | Not reachable | Needs `directTerminalURL` from SvelteKit `__data`, tier `web-plain` (`docs/protocol/private-protocol.md:200-202`, `:214`, `:330`). `directTerminalAvailable` was `false` (`s08-terminal.jsonl:51`) | Not used |
| WebRTC data channel | Not reachable | ICE servers come from `getWebRTCIceServers`, tier `web-remote` (`docs/protocol/private-protocol.md:199`) | Not used |
| Several sessions per thread through `sessionKey` | Read from the web client | ampcode.com's web bundle | Ships. A keyed tab that fails shows its error and leaves tab 1 working |
| Runner terminal disabled messages | Read from the web client | ampcode.com's web bundle | Ships |
| File staging through relay service `amp-exec-v1` | NOT VERIFIED | ampcode.com's web bundle | Ships. Falls back to inserting the attachment URL |
| Attachment upload | VERIFIED for an 80-byte PNG | `spikes/protocol/RESULTS.md:22` | Used for file staging |
| Waking a sleeping orb | VERIFIED live once (`spikes/protocol/RESULTS.md`, row S21b) | `spikes/protocol/captures/s21-orb-wake.jsonl` | The machine pane shows the wake; **Retry** and **Open on Web** after a failure |
| Live terminal in a reply (`amp-terminal:`) | Read from the web client | ampcode.com's web bundle | Ships |

**Open on web** opens `https://ampcode.com/threads/<threadId>/terminal` in a Custom Tab. The web route is `/(app)/threads/[thread=threadID]/(active)/terminal` (ampcode.com's web bundle). [INFERENCE] The parenthesized segments are SvelteKit route groups and do not appear in the URL.

## The app uses the actor relay and nothing else

The web client tries three transports in order: a WebRTC data channel, a direct WebSocket to the executor's portal at `/.amp/terminal`, and the actor relay over the thread socket (`docs/protocol/private-protocol.md:197-204`). The runner docs describe the same order from the other side. Terminal and Desktop "connect to the runner over WebRTC", and if that fails within 5 seconds "they fall back to Amp's relay" (Amp's docs).

The Android app reverses this and uses only the relay.

| Order | Transport | Needs | Tier | Android |
|---|---|---|---|---|
| 1 | Actor relay on the thread socket | The thread `wsToken` the app already holds | `cli` | Used. VERIFIED on a runner |
| 2 | Direct portal WebSocket, subprotocols `["amp-terminal-v1", <wsToken>]` | `directTerminalURL` from `threadActorConfig` in SvelteKit `__data` | `web-plain`, cookie-authenticated, devalue-encoded | Not used |
| 3 | WebRTC data channel with relay framing | ICE servers from `getWebRTCIceServers` | `web-remote`, ids change per deploy | Not used |

The relay is the right first choice for three reasons. It is the only verified path. It needs nothing beyond the thread socket that the transcript already uses, so a terminal adds no connection. The CLI ships the same relay, which keeps it stable for as long as old CLIs must keep working. The cost is one extra hop through Amp's servers. The spike measured 155 ms from `client_executor_relay_open` to `ready` (`spikes/protocol/RESULTS.md:18`) and 136 ms from sending `pwd && git status --short\r` to the first echoed byte (`s08-terminal.jsonl:64-65`), both from a Mac to a runner on the same machine.

The direct socket becomes worth adding if a `cli`-tier source for `directTerminalURL` appears and orb threads report `directTerminalAvailable: true`. Both transports carry the same `amp-terminal-v1` payloads, so only the byte channel in `:sync` would change.

## Relay wire format

### Messages on the thread socket

The relay rides the thread actor WebSocket that `:sync` already holds (`spikes/protocol/RESULTS.md:14`). Relay control messages are JSON-RPC notifications with no `id`.

| Direction | Message | Fields | Evidence |
|---|---|---|---|
| App to actor | `client_executor_relay_open` | `channelId`, `service: "amp-terminal-v1"`, `binaryFrames: true` | `s08-terminal.jsonl:57` |
| App to actor | `client_executor_relay_data` | `channelId`, `dataBase64`, `binary` | `s08-terminal.jsonl:58` |
| App to actor | `client_executor_relay_close` | `channelId` | `s08-terminal.jsonl:106` |
| Actor to app | `client_executor_relay_output` | `channelId`, `dataBase64`, `binary` | `spikes/protocol/s08-terminal.ts:34` |
| Actor to app | `client_executor_relay_exit` | `channelId` | `s08-terminal.jsonl:105` |

The app names each channel `relay-<uuid>` (`spikes/protocol/s08-terminal.ts:11`). It sends the first control frame as JSON `client_executor_relay_data`. After the first binary relay frame arrives from the actor, it sends everything as binary relay frames, as the web client does (`spikes/protocol/RESULTS.md:18`, `spikes/protocol/s08-terminal.ts:38-41`). It accepts output in either form for the life of the channel.

### Binary relay frame

Binary relay frames share the thread socket with JSON text frames (`docs/protocol/private-protocol.md:108`). The codec is `encodeRelayFrame` and `decodeRelayFrame` in `spikes/protocol/lib.ts:126-145`.

| Byte offset | Size | Field | Values |
|---|---|---|---|
| 0 | 1 | magic | `0xAE` |
| 1 | 1 | version | `0x01` |
| 2 | 1 | kind | `1` data to the executor, `2` output from the executor |
| 3 | 1 | flags | bit 0 set means the payload is binary, bit 1 set means `fin` |
| 4 | 1 | channel id length | 1 to 255 |
| 5 | n | channel id | UTF-8 |
| 5 + n | rest | payload | control JSON when bit 0 is clear, raw bytes when set |

A payload is split into chunks of at most 1,000,000 bytes and reassembled up to 16 MiB (`docs/protocol/private-protocol.md:108`). Only the last chunk has `fin` set. Every frame in the spike had `fin` set (`s08-terminal.jsonl:59-104`). The decoder appends chunks per channel until `fin` and drops a message that grows past 16 MiB.

As an example, the `ready` frame in `s08-terminal.jsonl:59` starts with `AE 01 02 02 2A`. Kind 2 is executor output, flags `0x02` mean a text payload with `fin`, and `0x2A` is the 42-byte channel id `relay-d4435c1d-ee77-4032-9bb7-c38d27a64587`. The 16-byte payload `{"type":"ready"}` follows.

### `amp-terminal-v1` payloads

The terminal service uses the same payloads on every transport (`docs/protocol/private-protocol.md:206-211`).

| Direction | Flag bit 0 | Payload |
|---|---|---|
| App to executor | clear | `{"type":"open","cols":<int>,"rows":<int>,"sessionKey"?:<string>,"initialCommand"?:<string>}` |
| App to executor | clear | `{"type":"resize","cols":<int>,"rows":<int>}` |
| App to executor | set | Raw input bytes in UTF-8 |
| Executor to app | clear | `{"type":"ready"}` or `{"type":"exit","error"?:<string>}` |
| Executor to app | set | Raw PTY output |

`sessionKey` matches `^[A-Za-z0-9_-]{1,128}$` and `initialCommand` holds at most 8,192 characters (ampcode.com's web bundle). The app never sends `initialCommand`.

### Limits

| Limit | Value | Evidence |
|---|---|---|
| Input chunk | 49,152 bytes | ampcode.com's web bundle (`Gr`) |
| Input buffered before `ready` | 196,608 bytes | `docs/protocol/private-protocol.md:211` |
| Time to `ready` | 10 s | `docs/protocol/private-protocol.md:211` |
| Time to first output after `ready` | 15 s, then the web writes a notice | ampcode.com's web bundle |
| Relay chunk | 1,000,000 bytes | `docs/protocol/private-protocol.md:108` |
| Relay reassembly | 16 MiB | `docs/protocol/private-protocol.md:108` |
| OSC sequence | 1,048,576 characters in the web's OSC 52 scanner | ampcode.com's web bundle |

### The verified session, step by step

The spike opened a terminal on runner `android-spike` and recorded this sequence (`s08-terminal.jsonl`):

1. Line 57. The app sends `client_executor_relay_open` with `binaryFrames: true`.
2. Line 58. The app sends `{"type":"open","cols":80,"rows":24}` as `client_executor_relay_data` with `binary: false`.
3. Line 59. The executor answers `{"type":"ready"}` in a binary relay frame, 153 ms after line 57.
4. Line 60. The app sends `{"type":"resize","cols":80,"rows":24}` as a binary relay frame of kind 1 with bit 0 clear.
5. Lines 61-63. Output arrives as kind 2 frames with bit 0 set. It clears the screen and draws an Orb banner and a zsh prompt.
6. Line 64. The app sends `pwd && git status --short\r` as a kind 1 frame with bit 0 set.
7. Lines 65-95. The echo and the command output arrive, many as 1-byte frames.
8. Line 97. The app sends `exit\r`.
9. Line 104. The executor sends `{"type":"exit","error":"tmux client exited with status 1"}`.
10. Line 105. The actor sends `client_executor_relay_exit`.
11. Line 106. The app sends `client_executor_relay_close`.

## Session lifecycle

### One session per key, shared by every window

`:sync`'s `Machine` owns one `TerminalSession` per thread and session key, and every window shares it (`docs/design/architecture.md`, "One socket per visible thread"). The session holds the relay channel and the VT engine. A Terminal pane or a live terminal card attaches to it as a viewer through `TerminalSession.attachViewer`. A second window that opens the same tab reads the session's current screen instead of joining the byte stream in the middle of an escape sequence. Tab 1 has no session key (see [Several terminals per thread](#several-terminals-per-thread)).

### Open

1. The app waits until the pane's size is stable for two consecutive layout passes with at least 2 columns and 1 row. The web waits the same way, for up to 10 animation frames (ampcode.com's web bundle).
2. The app creates a channel id and sends `client_executor_relay_open`.
3. The app sends the `open` frame with the measured `cols`, `rows`, and the tab's `sessionKey` (see [Sessions and tabs](#several-terminals-per-thread)).
4. Until `ready`, the app queues input up to 196,608 bytes. Input past that limit is dropped, and a snackbar says "The terminal is still connecting."
5. On `ready`, the status becomes `ready`, the queue flushes in order, and the app sends `resize` if the size changed since step 3.
6. If `ready` does not arrive within 10 s, the app closes the channel and shows the timed-out state.
7. If no output arrives within 15 s of `ready`, the app writes the web's notice into the terminal in dim text: `[No terminal output received. The terminal connection may be unavailable.]` (ampcode.com's web bundle).

### Resize

The app sends `resize` only after `ready`, only when `cols` or `rows` changed, and at most once per 100 ms. Arrangement changes, divider drags, fold and unfold, the soft keyboard showing or hiding, pinch zoom, and DeX window resizes all change the size. The debounce matters because a divider drag in the layout prototype produced a new column count on almost every frame.

When two windows show the same session, the window that most recently had keyboard focus in the terminal decides the size. The other window draws the same screen model and clips or scrolls it horizontally.

### Input

Keys, IME commits, soft keys, and pastes all become bytes in one ordered queue per channel. The queue sends kind 1 frames with bit 0 set, at most 49,152 bytes each.

### Exit and close

When the executor sends an `exit` frame or the actor sends `client_executor_relay_exit`, the app sends `client_executor_relay_close` and classifies the `error` string (next section).

The app also closes channels on its own:

- When you close a terminal tab.
- 60 s after the last viewer leaves. A viewer is a Terminal pane, or a live terminal card on screen, in a window whose lifecycle is `STARTED`. Sending the app to the background stops its windows, so the same rule closes a backgrounded terminal 60 s later.
- When the thread is archived.

The thread socket stays open while any relay channel on it is open, even past the socket's 30 s grace period in `docs/design/architecture.md`, "One socket per visible thread". The 60 s channel rule therefore decides when a backgrounded terminal releases its socket.

Closing matters for cost. An orb pauses only after 5 minutes without agent work and 20 minutes without user interaction, and use of the Terminal pane counts as interaction (Amp's docs). [INFERENCE] An open relay channel counts as Terminal use, so a forgotten channel would keep an orb awake and billing.

[INFERENCE] Closing a channel detaches from the tmux session and leaves the shell running. Reopening the same tab with the same `sessionKey` reattaches. To end the shell, type `exit`.

### What the exit error means

The web client sorts `exit` errors into retryable and final (ampcode.com's web bundle). The app uses the same rules.

| `error` value | Pane state | Automatic reconnect |
|---|---|---|
| Absent | `reconnecting` | Yes, after 3 s |
| Starts with `Terminal access is disabled`, equals `Remote control terminal is disabled`, starts with `Remote terminal control is disabled`, or starts with `Remote terminal access is disabled` | `disabled` | No |
| Ends with `requires sudo passkey authentication` | `passkey-required` | No |
| Starts with `tmux is required for the Amp terminal but was not found on this machine` | `tmux-missing` | No |
| Starts with `Unable to start terminal session` | `failed` | No |
| Anything else, such as `tmux client exited with status 1` (`s08-terminal.jsonl:104`) | `reconnecting` | Yes, after 3 s |

The web schedules the retry 3 s after a retryable exit (ampcode.com's web bundle). [INFERENCE] Typing `exit` ends the shell with the retryable `tmux client exited with status 1`, so the web opens a fresh shell 3 s later. The app matches that while a viewer is present. When no viewer is present, it reconnects the next time one attaches.

### Reconnect and scrollback recovery

[INFERENCE] A relay channel lives on one thread socket, so a dropped socket ends every terminal channel on it. `:sync` reconnects the socket with backoff from 3 s to 30 s and never stops while a viewer is present, then resumes the event stream with `client_resume` (`docs/protocol/private-protocol.md:113`, `:119`, `docs/design/architecture.md`, "One socket per visible thread"). The app does not rely on the actor to replay relay messages. Once the socket is back, it opens a new channel for every terminal tab that was open, with the same `sessionKey` and the current size.

The local VT engine survives the drop, so local scrollback survives too. While the channel is down, the pane dims the last screen and shows "Reconnecting terminal", the web's label for the same overlay (ampcode.com's web bundle).

On reattach, the executor sends a fresh screen. [INFERENCE] tmux redraws the whole visible screen for a newly attached client. A new session in the spike began with `ESC[H ESC[2J` (`s08-terminal.jsonl:96`). The engine does not move lines into scrollback when it clears the screen, so the redraw replaces the visible rows and leaves local scrollback as it was.

Output that scrolled out of view while the app was disconnected is lost. The verified protocol has no call that returns tmux history. The engine appends a dim `[Reconnected]` row to scrollback at the join, so a reader scrolling back sees where the gap is.

Scrollback lives in memory only. After process death, a reopened tab shows the executor's redraw and nothing older (see [Security and logging](#security-and-logging)).

## Several terminals per thread

The web client allows five terminal tabs per thread, with tab ids `terminal`, `terminal:2`, `terminal:3`, `terminal:4`, and `terminal:5` (ampcode.com's web bundle). The first tab opens with no `sessionKey`. Tab `terminal:N` opens with `sessionKey` `tab-N` (ampcode.com's web bundle).

The app uses the same keys, so tab 2 on the phone is the same shell as tab 2 on the web.

| Tab | `sessionKey` | Label |
|---|---|---|
| 1 | none | **Terminal**, or the OSC 0 or OSC 2 title when the shell sets one |
| 2 to 5 | `tab-2` to `tab-5` | **Terminal 2** to **Terminal 5**, or the OSC title |

[INFERENCE] The web's labels for tabs 2 to 5 were not found in the bundle. The numbered labels are this app's.

The pane header holds a row of session chips with a **+** button, which TalkBack reads as "New terminal". The button opens the lowest free tab and is disabled when five tabs are open. Each chip has a close button. Every open tab holds one channel while the pane is visible in some window, and all of them close under the rules in [Exit and close](#exit-and-close). In a pane too narrow for every chip, the row scrolls as the machine tab strip does (`screens.md`, "Machine panes").

The spike exercised only the default session. A keyed session that fails to open shows its own error in its own tab.

## Choosing the VT emulator

### What the emulator must do

- **Take bytes from the network.** The PTY is on the executor. The emulator never starts a local process.
- **Handle full-screen programs.** The spike's output carried the shell's own sequences: bracketed paste on and off (`ESC[?2004h`), mouse mode resets (`ESC[?1000l` through `ESC[?1006l`), cursor key mode, SGR colors, and cursor moves. It carried no tmux status line and no alternate-screen switch (`s08-terminal.jsonl:96`). [INFERENCE] The executor forwards what programs in the pane write, so the emulator must handle whatever vim, less, htop, or an agent CLI writes.
- **Give the app full control of input.** The TriFold has no S Pen, so keyboards, mice, and trackpads are the precision inputs. Terminal and desktop need hardware keyboard, IME, and pointer fidelity (`docs/design/sketch/RATIONALE.md`, "Why native beats a web shell for this brief").
- **Expose text to agent-device.** agent-device reads the accessibility tree and stops at 5000 nodes (the TriFold's published specs).
- **Run in JVM tests.** Protocol contract tests replay the spike captures without a device (`docs/design/verification.md:63`).
- **Carry a license the app can ship.**

### Three candidates

| | Termux `terminal-emulator` and `terminal-view` | Custom Compose renderer over a VT parser | xterm.js or ghostty-web in a WebView |
|---|---|---|---|
| License | The `termux/termux-app` repository "is released under GPLv3 only". The two libraries contain Apache 2.0 code from Jackpal's Terminal Emulator for Android and have no license file of their own ([LICENSE.md](https://github.com/termux/termux-app/blob/master/LICENSE.md)) | The app's own code | MIT ([xterm.js](https://github.com/xtermjs/xterm.js), [ghostty-web](https://github.com/coder/ghostty-web)) |
| Remote byte stream | `TerminalView.attachSession` takes a `TerminalSession` ([TerminalView.java:290](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java#L290)), a final class that starts a local subprocess through JNI ([TerminalSession.java:31, 82, 127](https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java#L31)). `TerminalEmulator` itself takes any `TerminalOutput` ([TerminalEmulator.java:496](https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java#L496)). Using the view needs a fork | Bytes go straight into the parser | Bytes cross the JavaScript bridge as strings |
| Input | Mature IME handling in an Android `View` | The app owns the `InputConnection`, key events, and pointer events | Keys and IME pass through a hidden `textarea` in the WebView. [INFERENCE] IME composition and hardware key routing are harder to control there |
| agent-device mirror | Custom work on a `View` inside `AndroidView` | Compose semantics with `testTag` | WebView DOM nodes. `testTag` does not apply inside the page |
| JVM tests | `TerminalEmulator` imports `android.util.Base64` ([TerminalEmulator.java:3](https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java#L3)) | Pure Kotlin `:vt` runs on the JVM | Needs a device or an emulator |
| Cost | A fork and a GPL decision | Writing and testing the VT core | One WebView per terminal, plus a bridge |
| Base architecture | Not a base, because of the license (`docs/design/sketch/settings.gradle.kts`, `:vt` block) | Matches `:vt` and `:feature:terminal` (`docs/design/sketch/settings.gradle.kts`, `:vt` and `:feature:terminal` blocks) | `:feature:terminal` must never import `android.webkit` (`docs/design/sketch/settings.gradle.kts`, `:feature:terminal` block) |

### Use a Compose renderer over a Kotlin VT core

The app draws the terminal with its own Compose renderer over a pure Kotlin VT parser and screen model in `:vt`.

Termux is out unless the app ships under GPLv3. [INFERENCE] Shipping the Termux libraries makes the app a GPLv3 derivative, because the repository's license is GPLv3 only and the Apache 2.0 exception covers the Jackpal code inside the libraries, not Termux's changes to it. Even with the license settled, the view is bound to a local process and would need a fork.

The WebView route gets parity with the web fastest. It is weakest where this app needs to be strongest, which is hardware keyboards on DeX, IME composition, and stable test tags. It also costs a WebView per terminal tab and per live embed.

The custom renderer is more work, but the work is bounded and testable. The parser takes `ByteArray` and produces an immutable `ScreenSnapshot`, as sketched in `docs/design/sketch/machine/Terminal.kt` (`VtEngine`, `ScreenSnapshot`). The spike's bytes become JVM fixtures on day one.

libghostty-vt is the named alternative for the VT core behind the same `VtEngine` interface. It is a C library that parses escape sequences, keeps terminal state with scrollback and reflow, and encodes keys, mouse events, and pastes ([libghostty-vt](https://libghostty.tip.ghostty.org/)). Ghostty is MIT ([ghostty-org/ghostty](https://github.com/ghostty-org/ghostty)). Amp's web terminal runs Ghostty's VT code too. The web bundle contains ghostty-web, whose error text "Terminal must be opened before use" appears at ampcode.com's web bundle and in [ghostty-web lib/terminal.ts:1300](https://github.com/coder/ghostty-web/blob/main/lib/terminal.ts#L1300). Two facts keep it second. Its documentation says "the API is not yet stable", and it lists macOS, Linux, Windows, and WebAssembly as platforms, not Android. If the Kotlin core fails the conformance fixtures below, the app swaps in libghostty-vt through JNI and keeps the renderer.

### What the VT core handles

- A DEC-compatible parser state machine for C0 controls, ESC, CSI, OSC, and DCS. DCS payloads are consumed and ignored. OSC strings stop at 1 MiB.
- Primary and alternate screens (DECSET 47, 1047, 1049), scroll regions, origin mode, autowrap with a pending-wrap flag, tab stops, insert and delete of characters and lines, and erase in line and display.
- SGR with 16, 256, and 24-bit colors, bold, dim, italic, underline, inverse, and strikethrough. Cursor shape through DECSCUSR. Save and restore cursor.
- Cursor key mode (DECSET 1), cursor visibility (25), mouse reporting (1000, 1002, 1003) with SGR encoding (1006), focus events (1004), and bracketed paste (2004). The spike output used 1, 25, 1000 through 1006, and 2004 (`s08-terminal.jsonl:96`).
- Device attribute and cursor position replies, written back to the PTY as input.
- OSC 0 and 2 for the tab title, OSC 8 for hyperlinks, and OSC 52 for clipboard writes. The web parses OSC 8 too (ampcode.com's web bundle).
- Wide cells for East Asian wide characters and emoji. The spike's prompt contains `🔀 main` (`s08-terminal.jsonl:96`). Combining marks join the previous cell.
- A scrollback ring of 10,000 rows. Each row keeps a `wrapped` flag (`docs/design/sketch/machine/Terminal.kt`, `Row`), so copying a long line joins its wrapped parts.

Conformance fixtures run in JVM tests:

1. The spike's PTY bytes at 80x24 produce rows `/private/tmp/amp-scratch`, ` M calc.py`, and `?? __pycache__/` (`s08-terminal.jsonl:96`).
2. zsh's prompt marker in the same bytes, a reverse-video `%` followed by spaces to the right margin and `\r \r`, leaves no blank line. [INFERENCE] This passes only if autowrap keeps the pending-wrap state at the last column.
3. Recorded output from vim, less, htop, and `git log` with a pager, captured once from a runner, renders the same screens as xterm at the same size.

## Rendering in Compose

The terminal is one `Canvas` node. The parser for each session runs on its own single-threaded dispatcher and publishes a `ScreenSnapshot` with a revision number. The canvas redraws when the revision changes, at most once per display frame, so a burst of output costs one draw.

Glyphs sit on a fixed grid. The renderer places each cell at its column times the cell width, so a fallback glyph from another font, such as an emoji or a CJK character, cannot push later columns sideways. A wide character takes two cells. Runs of cells with the same style draw together, and each row's layout is cached until the row changes. Box-drawing and block characters (U+2500 to U+259F) draw as shapes that fill the cell, so lines join across rows regardless of the font's line spacing.

| Property | Value |
|---|---|
| Font | `FontFamily.Monospace`, the font the layout prototype measured |
| Ligatures | Off, through `fontFeatureSettings = "liga 0, calt 0"` |
| Default size | 12sp with a 16sp line height (`MainActivity.kt:77`). Being in sp, it grows with the system font size |
| Zoom | Pinch, 8sp to 24sp in 1sp steps, remembered per device. **Smaller text** and **Larger text** in the pane menu, and Ctrl+Shift+Minus, Ctrl+Shift+Equals, and Ctrl+Shift+0 on a keyboard |
| Cell width | Measured at runtime as the width of 100 digits divided by 100 (`MainActivity.kt:198-201`) |
| Cursor | Block, no blink. DECSCUSR can change it. A hollow block when the terminal does not have focus |
| Bell | BEL plays a short haptic tick and no sound |

The web's font stack starts with Berkeley Mono V2 and falls back to Menlo, Monaco, Consolas, and Liberation Mono (ampcode.com's web bundle). None of those ship on Android. [INFERENCE] Berkeley Mono is a commercial font, so the app does not bundle it.

In the Terminal arrangement, the terminal spans panels 2 and 3, so the crease at 720dp runs through it (`docs/design/layout.md`, "Windows are measured in panels" and "Arrangements"). The grid stays continuous across the crease. Splitting the grid there would cost columns and break full-screen programs.

### Columns per arrangement

The column count is `floor((paneWidthDp - 24) * density / cellWidthPx)`, where 24dp is the pane's horizontal padding (`MainActivity.kt:203-204`). The row count is the text area's height divided by the line height.

The prototype measured these values on a TriFold-sized emulator at 12sp. The emulator's panels were all 360dp wide. The physical device's panels are 335dp, 382dp, and 363dp (`docs/design/layout.md`, "Windows are measured in panels"), so the landscape Triage and Terminal rows apply the prototype's 7dp cell to the measured panel widths.

| Window | Arrangement | Terminal pane width | Columns | Evidence |
|---|---|---|---|---|
| Inner display, landscape | Triage, Terminal in panel 3 | 363dp | 48 | [INFERENCE] (363 - 24) / 7 = 48.4; the layout prototype's emulator measured 48 at 360dp |
| Inner display, landscape | Terminal, span 2 | 745dp | about 103 | [INFERENCE] (745 - 24) / 7 = 103; the emulator measured 98 at 714dp, `logcat-LayoutProto.txt:11`, `V3-inner-swapped.png` |
| Inner display, landscape | Focus, span 3 | 1080dp | about 150 | [INFERENCE] from a cell width of about 14.0px at density 2.0 |
| Inner display, portrait (Medium) | Triage, Terminal beside the Thread pane | 360dp | 48 | the emulator measured 48 at 360dp, `logcat-LayoutProto.txt:4` |
| Inner display, portrait (Medium) | Terminal, full width beside the rail | 720dp | about 98 | [INFERENCE] from the 714dp measurement, `logcat-LayoutProto.txt:11` |
| Cover display (Compact) | Terminal destination in the bottom bar | 411dp | 53 | `logcat-LayoutProto.txt:102`, `cover-terminal.png` |

These numbers come from the emulator's monospace font. [INFERENCE] One UI may map `FontFamily.Monospace` to a font with different metrics, so the physical TriFold can show other counts. The app always measures at runtime.

48 columns is a glance width. Many tools assume 80, and a `git diff --stat` line wraps at 53 on the cover display (`cover-terminal.png`). For real shell work, **Expand** moves Triage to the Terminal arrangement and about 103 columns (`docs/design/layout.md`, "Arrangements"). [INFERENCE] Pinching down to 8sp in one panel gives about 72 columns.

Folding and unfolding keeps the session (`docs/design/layout.md`, "Folding and unfolding keep your place"). The session lives in `:sync`'s `Machine`, outside every window, so a fold changes only the size and sends one `resize`.

## Input

### Soft keyboard and the extra-keys row

When the terminal has focus and the soft keyboard is up, a row of extra keys sits directly above the keyboard. The row hides when a hardware keyboard is attached, unless you pin it from the pane menu.

| Key | Sends | Test tag |
|---|---|---|
| **Esc** | `0x1B` | `terminal-key-esc` |
| **Ctrl** | Latches Ctrl for the next key | `terminal-key-ctrl` |
| **Alt** | Latches Alt for the next key | `terminal-key-alt` |
| **Tab** | `0x09` | `terminal-key-tab` |
| **←** **↑** **↓** **→** | Arrow sequences, in the form the cursor key mode asks for | `terminal-key-left`, `terminal-key-up`, `terminal-key-down`, `terminal-key-right` |
| **\|** | `\|` | `terminal-key-pipe` |
| **~** | `~` | `terminal-key-tilde` |
| **/** | `/` | `terminal-key-slash` |
| **^C** | `0x03` | `terminal-key-ctrl-c` |
| **Select** | Starts text selection | `terminal-select` |
| **Paste** | Runs the paste path | `terminal-paste` |

The web's accessory row has **Escape**, **Control C**, latched **Control**, **Meta**, and **Shift**, **Tab**, arrow keys, **Select Text**, and **Paste** (ampcode.com's web bundle). The app keeps the ones that make sense on Android. It drops **Shift**, which every soft keyboard has, and **Meta**, which the app never sends to the PTY. It adds `|`, `~`, and `/`. [INFERENCE] Android soft keyboards put those three on a symbols page, and shells need them constantly.

A tap on **Ctrl** or **Alt** latches it for one key. A second tap within 300 ms locks it until the next tap. Latched and locked keys report `checked`, so agent-device can read them (`docs/design/verification.md:12`). A latched Ctrl applies to the next single character the soft keyboard commits, so **Ctrl** then `c` sends `0x03`. Holding an arrow key repeats it.

Each key is 48dp wide, so the 14 keys need 672dp. They fit in the two-panel Terminal arrangement (745dp). In one panel and on the cover display, the row scrolls horizontally.

### Hardware keyboard and DeX

While the terminal has focus, every key goes to the PTY except combinations with Ctrl+Shift or Meta. The app handles those. The only table of them is `screens.md`, "Keyboard shortcuts". Its rows with **Terminal** in the Where column are the terminal's own keys: copy, paste, new and close tab, and text size. Its rows marked "Yes" under In terminal are app shortcuts that keep working here, such as Ctrl+Shift+P for the command palette and Ctrl+Shift+Left and Right to move focus to another pane. Other Meta combinations are left to the system. DeX uses Meta for window shortcuts such as Meta+H (the TriFold's published specs).

[INFERENCE] The legacy xterm encoding sends the same byte for Ctrl+Shift+letter as for Ctrl+letter, so giving Ctrl+Shift letters to the app takes nothing from the shell. Ctrl+Shift with an arrow key has its own encoding, `CSI 1;6 C` and `CSI 1;6 D`, so the shell loses those two keys to pane focus. App shortcuts without Shift, such as Ctrl+Enter to queue, Esc twice to interrupt, and Ctrl+/ for Puck, go to the PTY while the terminal has focus. Esc belongs to the shell there.

The web gives some plain Ctrl combinations to the browser on Windows and Linux, including Ctrl+L, Ctrl+R, and Ctrl+W (ampcode.com's web bundle). The app has no browser shortcuts to protect, so Ctrl+R reaches the shell's history search. Like the web, it copies the selection on Meta+C or Ctrl+Shift+C (ampcode.com's web bundle).

The app reads modifiers with `KeyEvent.isCtrlPressed`, `isAltPressed`, `isShiftPressed`, and `isMetaPressed` (the TriFold's published specs). Keys encode as xterm sequences ([xterm control sequences](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html)):

| Key | Bytes |
|---|---|
| Enter | `CR` (`0x0D`). The spike sent `\r` (`spikes/protocol/s08-terminal.ts:50`) |
| Backspace | `DEL` (`0x7F`) |
| Tab, Shift+Tab | `0x09`, `CSI Z` |
| Esc | `0x1B` |
| Arrows | `CSI A`, `B`, `C`, `D`. `SS3 A` to `D` when DECSET 1 is on |
| Home, End | `CSI H`, `CSI F`. `SS3 H`, `SS3 F` when DECSET 1 is on |
| Insert, Delete, Page Up, Page Down | `CSI 2~`, `CSI 3~`, `CSI 5~`, `CSI 6~` |
| F1 to F4 | `SS3 P`, `Q`, `R`, `S` |
| F5 to F12 | `CSI 15~`, `17~`, `18~`, `19~`, `20~`, `21~`, `23~`, `24~` |
| Modified special keys | `CSI 1;m X` or `CSI n;m ~`, where `m` is 1 plus 1 for Shift, 2 for Alt, and 4 for Ctrl. The web computes the same parameter (ampcode.com's web bundle) |
| Ctrl+letter | `0x01` to `0x1A` |
| Ctrl+Space, Ctrl+[, Ctrl+\\, Ctrl+], Ctrl+^, Ctrl+_ | `0x00`, `0x1B`, `0x1C`, `0x1D`, `0x1E`, `0x1F` |
| Alt+key | `ESC` followed by the key's bytes |

[INFERENCE] On international layouts, Android reports AltGr as right Alt. When right Alt produces a printable character through the key map, the app sends that character without an `ESC` prefix.

### IME composition

The terminal's input node opens an `InputConnection` with `TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS`. Termux uses the same flags for its character-based input mode ([TerminalView.java:323](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java#L323)). [INFERENCE] These flags turn off autocorrect and prediction, so keyboards commit each character as you type it.

CJK input and voice typing need composition. The pane menu has **Keyboard mode**, with **Terminal** (the default above) and **Text**, which uses `TYPE_CLASS_TEXT` with composition. Termux documents that its `TYPE_NULL` fallback breaks Japanese input ([TerminalView.java:331-332](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java#L331)), which is why the app has a text mode instead.

| `InputConnection` call | What the app does |
|---|---|
| `setComposingText` | Draws the composing text at the cursor, underlined. Sends nothing |
| `commitText` | Sends the text as UTF-8, with a latched Ctrl or Alt applied when the commit is one character |
| `deleteSurroundingText(n, 0)` | Sends `n` times `DEL` (`0x7F`) |
| `sendKeyEvent` | Runs the key encoder above |
| `performEditorAction` | Sends `CR` |

### Mouse, trackpad, and touch

| Input | Mouse reporting off | Mouse reporting on (DECSET 1000, 1002, or 1003) |
|---|---|---|
| Left drag | Selects text and copies it on release, like the web, which shows "Copied terminal selection" (ampcode.com's web bundle) | Sent to the PTY in SGR form when DECSET 1006 is on. Shift+drag still selects |
| Double click, triple click | Selects a word, a line | Sent to the PTY |
| Right click | Copies the selection if there is one, otherwise pastes | Same |
| Wheel or two-finger trackpad scroll | Scrolls local scrollback on the primary screen. On the alternate screen sends up and down arrows, at most 5 per event, as the web does (ampcode.com's web bundle) | Sent as wheel buttons 64 and 65 |
| Hover | I-beam pointer. A hand over a link | Same |

Touch works without a mouse:

- Tap focuses the terminal and shows the soft keyboard.
- A one-finger vertical drag scrolls like the wheel.
- Long-press starts a selection with handles and a **Copy** button (`terminal-copy`).
- Pinch changes the font size.

agent-device does not see long-click, so **Select** and **Paste** in the extra-keys row do the same jobs as real buttons.

Links come from OSC 8 and from plain URLs in the text. Ctrl+click or a tap on an underlined link opens it. Portal URLs of the form `https://t-<thread>-p<port>.onamp.dev/` (`spikes/protocol/RESULTS.md:23`) open in the Portal pane. Other URLs open in a Custom Tab. When an OSC 8 link's visible text differs from its target, the app shows the target and asks before opening it.

### Clipboard, paste, and OSC 52

Text paste comes from Ctrl+Shift+V, Meta+V, a right click with no selection, or **Paste**. Before sending, the app runs these steps:

1. It converts `\r\n` and `\n` to `\r`. [INFERENCE] This is what a shell expects from Enter.
2. If bracketed paste is on (DECSET 2004, which zsh turned on in `s08-terminal.jsonl:96`), it removes any `ESC[201~` from the text and wraps the text in `ESC[200~` and `ESC[201~`. The web bundle carries the same markers (ampcode.com's web bundle).
3. If bracketed paste is off and the text has more than one line, it asks first: "Paste 3 lines into the terminal? Each line runs as a command." The buttons are **Paste** (`paste-confirm-paste`) and **Cancel** (`paste-confirm-cancel`).
4. It sends the bytes in chunks of 49,152.

A clipboard that holds a file or an image and no text starts a file upload instead (next section).

Programs write to the clipboard with OSC 52. The web accepts an OSC 52 write only when the terminal and the page have focus and you have interacted with the terminal (ampcode.com's web bundle), and it ignores clipboard reads because its pattern accepts only base64 data (ampcode.com's web bundle). The app follows the same rules. When a write arrives without focus, a snackbar says "The terminal wants to copy text" with a **Copy** button (`terminal-osc52-copy`). Reads are ignored.

## File upload by paste or drop

Amp's docs describe the behavior the app matches. When you paste an image or drop a file into the Terminal tab, Amp uploads it to the orb and inserts its absolute path at the cursor. Clipboards that hold text paste as text. Files can be up to 512 MB (Amp's docs).

The app accepts files three ways:

- A drop onto the pane through `Modifier.dragAndDropTarget`. Drops from other apps call `requestDragAndDropPermissions` (the TriFold's published specs).
- A paste whose clipboard holds a file or an image and no text.
- **Upload file** in the pane menu (`terminal-upload`), which opens the system file picker. This path exists for touch users with one window and for agent-device, which cannot drag between apps.

Each file goes through these steps, one file at a time:

1. The app rejects files over 536,870,912 bytes, the web's limit (ampcode.com's web bundle), with the web's message "`<name>` is larger than 512 MB" (ampcode.com's web bundle).
2. It uploads the file through `POST /api/attachments/resumable` with `prepare`, `PUT`, and `complete`, passing `threadID`. Progress reads "Uploading `<name>`…". This endpoint is VERIFIED for an 80-byte PNG (`spikes/protocol/RESULTS.md:22`). Large multi-part uploads are not verified.
3. It opens a relay channel with service `amp-exec-v1` and sends one text frame: `{"program":"amp","args":["files","get","<url>","--output","/tmp/amp-terminal-files/<uuid>/<name>"],"cwd":"<workspace root>"}`. The executor answers `{"type":"exit","status":<int>}` or `{"type":"error","error":<string>}` (ampcode.com's web bundle). The timeout is 300 s (ampcode.com's web bundle). Progress reads "Saving `<name>` to the orb…" (ampcode.com's web bundle). The workspace root comes from `environment_update.environment.workspaceRoot` (`s08-terminal.jsonl:38`). This step is NOT VERIFIED.
4. On exit status 0, it pastes the absolute path at the cursor through the paste path above, with no Enter.
5. Otherwise it pastes the attachment URL, as the web does (ampcode.com's web bundle), and a snackbar says "Inserted the file's URL. The executor could not save the file." This happens on a nonzero status, an error, a timeout, an unknown workspace root, or a close with "Unsupported executor relay service" (ampcode.com's web bundle). After that last error, the app skips step 3 for that executor for the rest of the session.

The app cleans file names the way the web does (ampcode.com's web bundle). It replaces each run of characters outside `[A-Za-z0-9._-]` with `-`, removes leading `-` and `.`, and cuts the name to 64 characters. An empty result becomes `image.<ext>` or `file.<ext>`, with the extension taken from the MIME type. A cleaned name has no spaces, so the inserted path needs no quoting.

Progress shows in a snackbar tagged `terminal-upload-progress` with a **Cancel** button.

## What the pane shows

Status is text, not only color (`docs/design/verification.md:13`). The `terminal-status` node always holds one of the values below.

| `terminal-status` | When | The pane shows | Buttons |
|---|---|---|---|
| `connecting` | A channel is opening | An empty grid and a progress indicator | none |
| `ready` | `ready` arrived | The terminal | none |
| `reconnecting` | The socket dropped or a retryable exit arrived | The last screen, dimmed, under "Reconnecting terminal" (`terminal-reconnecting`) | **Close tab** |
| `asleep`, `waking` | The thread's orb is asleep or waking (`ExecutorStatus.OrbOffline`) | Nothing: the machine pane shows "Waking up the orb" in place of every tab, and the Terminal opens once the executor connects | **Open on Web** (`machine-orb-open-web`); **Retry** (`machine-orb-retry`) after a failed wake |
| `no-executor` | The thread has no executor and its orb is not asleep | "This thread has no machine attached." | none |
| `disabled` | A disabled error arrived | The runner or CLI message below | **Retry** (`terminal-retry`) |
| `passkey-required` | A passkey error arrived | "This terminal requires sudo passkey authentication." | **Open on web** (`terminal-open-web`) |
| `tmux-missing` | A tmux error arrived | "tmux is required for the Amp terminal but was not found on this machine." | **Retry** |
| `failed` | `Unable to start terminal session`, or no `ready` within 10 s | The error text, or "The terminal did not respond." | **Retry**, **Open on web** |
| `read-only` | You can read the thread but not write to it | "The terminal needs write access to this thread." | none |

[INFERENCE] The relay refuses a read-only `wsToken`. The spike used a token with `capability: "write"` (`spikes/protocol/RESULTS.md:14`). On a shared runner with multiplayer on, other workspace members can use the terminal (Amp's docs).

### Runner terminal disabled

A runner opens terminals only when it was started with `--remote-control-terminal`. In the Mac app, the switch is **Allow Terminal Access** (Amp's docs). A CLI session has the same flag and the variable `AMP_REMOTE_CONTROL_TERMINAL=1` (Amp's docs).

The app cannot know in advance. `observers` has no terminal-access flag for the relay (`s08-terminal.jsonl:51`), and the spike's runner had the flag on (`spikes/protocol/RESULTS.md:3`). The `disabled` state appears only after an `open` returns one of the disabled errors. That path was not exercised live.

The message adapts the web's text (ampcode.com's web bundle), with **Retry** in place of "reload this page". The runner id comes from `executor_status.details.executionEnvironment.environmentId`, which reads `runner:android-spike` in the spike (`s08-terminal.jsonl:50`).

- **Runner thread.** "Terminal access is off on the runner `<runnerId>`. If the Amp app runs this runner, turn on Allow Terminal Access in the app's Settings > Runner. If you started it yourself, restart it with `amp --no-tui --remote-control-terminal`. Then tap Retry. You can continue using this thread."
- **CLI session thread.** "Terminal access is off for this thread. The thread runs in the amp you started in a terminal window, so the Amp app's Allow Terminal Access setting does not affect it. Quit amp in that window, start it again with `amp --remote-control-terminal`, and tap Retry. You can continue using this thread."
- **Unknown executor.** "Terminal access is disabled. Restart Amp with `--remote-control-terminal`, for example `amp --no-tui --remote-control-terminal`, then tap Retry. `AMP_REMOTE_CONTROL_TERMINAL=1` also works."

### A sleeping orb wakes for a shown thread

Opening the thread wakes its sleeping orb, as the web does without Orb Saver Mode (Amp's docs). The thread engine sends `client_spawn_executor{requestId: "spawn_<uuid>", executorDemand: "optional"}` once for a thread a started window shows (`screens.md`, "Wake an orb only for a shown thread"). The Terminal pane never requests an executor itself. Use of the Terminal pane keeps the orb awake (Amp's docs), so closing a channel still matters for cost.

## Live terminal in a reply

Amp can embed a live terminal in a reply so you can type into it without leaving the conversation (Amp's docs). In the web client, the reply's Markdown contains an image whose target is exactly `amp-terminal:`, and the image's alt text is the label, with "Terminal" as the default (ampcode.com's web bundle). The web renders it as an embed placeholder of kind `terminal` (ampcode.com's web bundle).

The app's Markdown renderer turns that image into a terminal card in the transcript.

- The card shows the label, a live view of the bottom 12 rows of the session, and **Open in Terminal** (`terminal-embed-open-<messageId>`), which selects the Terminal tab. On Compact windows, it navigates to the Terminal destination.
- Tapping the card focuses it, and typing goes to the PTY.
- [INFERENCE] The card shows the thread's default session, tab 1. The target carries no key, so an old reply shows the session as it is now, not as it was when the reply was written.
- The card and the Terminal pane share one VT engine and one channel. A card never resizes the PTY while the pane is open. It draws the shared screen clipped to its width with horizontal scroll. When no pane is open, the card opens the channel at its own size.
- Only the newest card that is on screen is live. Older cards show "Live terminal" and **Open in Terminal**.
- A card connects only while it is on screen and the executor is connected. On a paused orb it shows the wake card from the previous section.

The card's tag is `terminal-embed-<messageId>`.

## Accessibility and agent-device

The terminal canvas is one node, so the app mirrors the screen as text for TalkBack and agent-device (`docs/design/sketch/RATIONALE.md`, "Shape", which keeps terminal views line-virtualized under 5000 nodes).

- **`terminal-screen`** is the canvas node. It is focusable, has the content description "Terminal", and carries the full visible screen as its `text`, rows joined by newlines with trailing spaces trimmed. [INFERENCE] agent-device reads it through `get text id=terminal-screen` and matches it through `wait text`, because its label is `text` (the TriFold's published specs).
- **`terminal-row-<n>`** is one invisible text node per visible row, numbered from 0 at the top (`docs/design/sketch/core-design/AmpTag.kt`). TalkBack reads them line by line.
- **`terminal-input`** is the focused editable node that owns the `InputConnection`. Its `SetText` semantics action sends the given text as typed input, which gives agent-device a way to type without the soft keyboard.
- The mirror updates at most 4 times per second. Output does not use a live region, because TalkBack would read every byte. Status changes in `terminal-status` are announced politely.
- A 98-column pane with 45 rows adds about 45 row nodes and 25 controls. That is far below agent-device's 5000-node limit (the TriFold's published specs).

| Tag | Element |
|---|---|
| `pane-terminal` | Pane root (`docs/design/layout.md`, "Test tags for layout") |
| `tab-terminal` | The machine pane tab |
| `nav-terminal` | The Compact bottom bar destination |
| `terminal-status` | Status text |
| `terminal-screen` | Canvas with the full screen text |
| `terminal-row-<n>` | One visible row |
| `terminal-input` | Input node |
| `terminal-softkeys` | Extra-keys row (`AmpTag.kt:71`) |
| `terminal-key-<name>` | Each extra key from the table in [Soft keyboard](#soft-keyboard-and-the-extra-keys-row) |
| `terminal-select`, `terminal-paste`, `terminal-copy` | Selection and clipboard buttons |
| `terminal-tab-<n>`, `terminal-tab-new`, `terminal-tab-close-<n>` | Session chips, new, and close |
| `terminal-reconnecting` | Reconnect overlay |
| `terminal-retry`, `terminal-open-web` | Error actions |
| `terminal-upload`, `terminal-upload-progress` | Upload menu item and progress |
| `terminal-osc52-copy` | OSC 52 snackbar action |
| `paste-confirm-paste`, `paste-confirm-cancel` | Multi-line paste dialog |
| `terminal-font-smaller`, `terminal-font-larger` | Pane menu items |
| `terminal-embed-<messageId>`, `terminal-embed-open-<messageId>` | Live terminal card in a reply |

agent-device has no clipboard command on Android (the TriFold's published specs), so typing and paste need test hooks. Debug builds accept the `terminal-type` and `terminal-paste` app events listed in `verification.md`, under "Debug builds accept app events".

The fake server replays `s08-terminal.jsonl` for the terminal flow (`docs/design/verification.md:69`). The flow opens the thread, presses `id=tab-terminal`, waits for `terminal-status` to read `ready`, sends `pwd` with `terminal-type`, and waits for `/private/tmp/amp-scratch` in `terminal-screen`.

## Security and logging

A terminal carries whatever you type, including passwords at a `sudo` prompt, and whatever programs print, including secrets in environment dumps. The rules:

- Release builds never log PTY bytes in either direction. They never log the `open` frame, OSC 52 contents, the screen text, or file staging commands. `:vt` and `:feature:terminal` contain no logging calls that survive into release, and a lint check fails the build on any log call that takes a relay payload.
- Debug builds log one line per relay frame with kind, flags, a hash of the channel id, and the payload length. Payloads stay out of logs unless a developer turns on payload logging in debug settings for the current session. This matches the frame-header logging in `docs/design/verification.md:43`.
- The debug logger redacts by field name, not by pattern. The spike's JWT pattern replaced the base64 `open` frame with `<redacted-jwt>=` (`s08-terminal.jsonl:58`), because base64 of `{"` begins with `eyJ`. The app logs `dataBase64` as a length only.
- Terminal bytes never touch disk. Scrollback lives in memory, is dropped on process death, and is excluded from crash reports and backups.
- Pastes are sanitized and multi-line pastes are confirmed (see [Clipboard, paste, and OSC 52](#clipboard-paste-and-osc-52)).
- OSC 52 writes need focus, and OSC 52 reads are ignored.
- OSC 8 links whose text differs from their target show the target before opening.
- The text mirror exposes the visible screen to every enabled accessibility service, as any on-screen text is exposed.

## Performance targets

These are targets for the first build, not measurements. The baselines are the only measured numbers.

| Metric | Target | Baseline |
|---|---|---|
| `open` to `ready` | Median under 300 ms on Wi-Fi to a connected runner | 155 ms from a Mac to a local runner (`spikes/protocol/RESULTS.md:18`) |
| Key press to echoed glyph on screen | Network round trip plus at most one frame (16 ms) | First echo byte 136 ms after the input frame (`s08-terminal.jsonl:64-65`) |
| Sustained output | 1 MB/s with no dropped input and under 1 percent janky frames | The same target in `docs/design/verification.md:76` |
| Parser throughput | At least 20 MB/s on the TriFold, measured with a JVM benchmark on recorded output | none |
| Full redraw of a 98 by 45 grid | Under 4 ms of draw time | none |
| Memory per session | Under 8 MB with 10,000 scrollback rows | none |
| Text mirror updates | At most 4 per second | none |
| Semantics nodes in the Terminal pane | At most 120 | Limit 5000 (the TriFold's published specs) |
| `resize` frames | At most one per 100 ms | none |

The web resets its terminal when the write queue overflows and prints "Terminal output was reset to keep this browser responsive." (ampcode.com's web bundle). The app never resets. The parser consumes every byte, and the renderer draws only the newest snapshot. If more than 4 MB of output is waiting to be parsed, the renderer stops drawing until the parser catches up. Input keeps flowing the whole time.

## Where the code lives

| Module | Owns |
|---|---|
| `:sync` | `Machine.terminal(session)`, which takes a nullable `TerminalSessionKey` because tab 1 has none. `TerminalSession`, one per thread and session key, which holds the channel, the `VtEngine`, the viewers, and `TerminalStatus` (`docs/design/sketch/sync/Api.kt`). The relay frame codec and the relay multiplexer (`docs/design/sketch/sync/internal/Transport.kt`). Channel reopen after socket resume, and `amp-exec-v1` for file staging (`TerminalSession.stageFile`) |
| `:vt` | `VtEngine`, `ScreenSnapshot`, key, mouse, and paste encoding, and the conformance fixtures. Pure Kotlin with no Android imports (`docs/design/sketch/settings.gradle.kts`, `:vt` block) |
| `:feature:terminal` | `TerminalPane`, the canvas renderer, the extra-keys row, keyboard and IME handling, selection, the upload menu item, and the text mirror (`docs/design/sketch/settings.gradle.kts`, `:feature:terminal` block) |
| `:feature:thread` | The live terminal card in a reply |
| `:core:model` | `TerminalSessionKey` (`docs/design/sketch/core-model/Ids.kt`), `MachineTab.Terminal` (`docs/design/sketch/core-model/Panes.kt`), and `UnavailableReason` (`docs/design/sketch/core-model/ThreadState.kt`) |

This document wins over the sketch in `docs/design/sketch/`, and the sketch follows it. In particular, the extra-keys row has no `-` key and has `^C`, **Select**, and **Paste**, and one finger, not two, scrolls scrollback.

## Open questions

- Does the relay path work the same way on an orb thread? Rerun `spikes/protocol/s08-terminal.ts` against an orb.
- Does a keyed session such as `tab-2` open and reattach over the relay the way the web uses it?
- When a client reattaches to an existing session, does the executor send a full redraw, or nothing until the next output? The recovery design assumes a redraw.
- Does closing a relay channel leave the tmux session running, as this document assumes?
- When the web and the phone attach to one session at different sizes, which `resize` wins, and does the other client's view break?
- Does `amp-exec-v1` work over the relay for a non-web client, and does `/api/attachments/resumable` accept a 512 MB file?
- Does the relay accept a `read` capability token, or refuse it as this document assumes?
- Should the app show the terminal of external-agent threads? The web opens it with `sessionKey` `external-agent` and the agent's launch command as `initialCommand` (ampcode.com's web bundle) and labels the thread "`<agent>` in a terminal (experimental)" (ampcode.com's web bundle).
- Can the app complete a sudo passkey step natively, or must `passkey-required` always send you to the web?
- Does Android 17's rule that hides password characters while a physical keyboard is attached (the TriFold's published specs) affect the `VISIBLE_PASSWORD` input type the terminal uses?
- Which font does One UI map to `FontFamily.Monospace`, and how many columns does each arrangement get on the physical TriFold?
- Should the terminal colors follow the web's **Terminal Theme** and **Terminal Colors** settings (ampcode.com's web bundle)?
- Does the Kotlin VT core pass the conformance fixtures, and does libghostty-vt build for 64-bit ARM Android if it does not?
