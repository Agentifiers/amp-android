# Transport

This document is the wire reference for `:sync`, the only module that speaks to Amp. It covers every endpoint, socket, frame, and message the app uses, what the protocol spike measured for each, and what the app does when a measurement is missing. Behavior built on top of the wire lives elsewhere:

- Sign-in, the token vault, and refresh are in [Auth and security](auth-and-security.md).
- Push delivery, decryption, and display are in [Notifications](notifications.md).
- Terminal payloads and the terminal session lifecycle are in [Terminal](terminal.md).
- Git operations, diff sources, and the Changes pane are in [Git and diffs](git-and-diffs.md).

## How to read the evidence

Every fact carries one of these sources:

- **Measured.** A capture line or a spike row. `S1` to `S14` are rows of `spikes/protocol/RESULTS.md` at lines 11 to 24, and `S15` to `S20` are at lines 65 to 72. A capture citation such as `captures/s04-live-thread.jsonl:7` names a file in `spikes/protocol/captures/` and the line whose `"n"` field is 7.
- **Read, not observed.** Read from the CLI binary or the web bundles and never seen on the wire. The source is `docs/protocol/private-protocol.md`, cited as `private-protocol.md:<line>`.
- **Inferred.** Marked `[inferred]` with the reason.

Statements that say "the app does" are design decisions of this document. The spike scripts in `spikes/protocol/` are working reference implementations. `lib.ts` holds the shared HTTP client, the gateway URL builder, the subprotocol builder, the relay codec, and the thread socket.

## Native code calls only `cli`-tier names

Amp's private interface has three stability tiers (`private-protocol.md:16-20`):

| Tier | Meaning | Stability |
|---|---|---|
| `cli` | The CLI binary contains the name. Old CLI builds in the wild call it, so the server must keep it working. | Most stable |
| `web-plain` | A plain path, action, or message only the web app uses. | Stable across web deploys, but nothing outside ampcode.com depends on it |
| `web-remote` | A SvelteKit remote function at `/_app/remote/<hash>/<name>`. The hash changes on any web deploy. | Least stable |

`:sync` calls only `cli`-tier names. The thread actor protocol is `cli`: 232 of the 233 message `type` literals in the web schema module also appear in the CLI binary, and the exception is `client_puck_voice_stats` (`private-protocol.md:22`). A feature that has no `cli`-tier path opens the matching ampcode.com page in a web surface instead. The literal `/_app/remote/` may appear only in `:feature:web`, and the M0 build lint enforces it (`docs/design/roadmap.md:11`).

## Every endpoint derives from the account's server origin

An account is the pair `(server origin, userId)`. The default origin is `https://ampcode.com`. Custom servers, their validation rules, and certificate trust are in [A custom server URL serves workspaces with a required domain](auth-and-security.md#a-custom-server-url-serves-workspaces-with-a-required-domain).

| Endpoint | URL | Source |
|---|---|---|
| Internal RPC | `<origin>/api/internal?<method>` | Measured, `captures/s02-threads.jsonl:1` |
| Thread actor credentials | `<origin>/api/thread-actors` | Measured, `captures/s04-live-thread.jsonl:1` |
| User actor credentials | `<origin>/api/user-actor-credentials` | Measured, `captures/s14-user-actor.jsonl:1` |
| Rivet endpoint | `<origin>/actors` | Measured, `captures/s04-live-thread.jsonl:7` |
| Attachments | `<origin>/api/attachments/resumable` | Measured, `captures/s12-attachment.jsonl:1` |
| Auth API | `apiURL` from `GET <origin>/api/auth/oauth-config` | Measured, S1 |
| Portals | The URL that `resolveThreadPortalURL` returns | Measured, `captures/s13-portal.jsonl:2` |

Every Rivet request carries two values that are not per account:

- **Namespace.** `default`.
- **Publishable token.** Written `<rivet-publishable-token>` in this document. The CLI binary `~/.amp/bin/amp` is a Bun single-file executable that stores its JS source as UTF-16LE text. Its function `ou()` builds the Rivet endpoint as `https://default:<rivet-publishable-token>@ampcode.com/actors`, so the namespace is the URL user name and the token is the URL password. Localhost gets no credentials, staging uses a different token, and `RIVET_PUBLIC_ENDPOINT` overrides the endpoint (read, not observed, `private-protocol.md:82`). The spike finds the token by searching the binary for the UTF-16LE string `else t.password="pk_` (`spikes/protocol/lib.ts:13-20`).

The app ships the token as a build constant per server profile. The build reads it from the CI secret store or from the developer's `local.properties`, and the repository never contains it. If Amp rotates the token, older clients keep working only while the server still accepts the old one (`private-protocol.md:332`). Which token a custom domain needs is unknown (`docs/design/auth-and-security.md:112`).

## Auth headers by channel

| Channel | Credential | How it travels | Source |
|---|---|---|---|
| Internal RPC, `/api/thread-actors`, `/api/user-actor-credentials`, `/api/attachments/resumable` | Account access token | `Authorization: Bearer <token>` | Measured. `spikes/protocol/lib.ts:53-56` adds it to every spike HTTP call. |
| Attachment content at `<origin>/user-content/attachments/...` | Account access token | `Authorization: Bearer <token>`. Without it the server answers 401. | Measured. The 401 is in S12. The 200 with Bearer is `captures/probe-att.jsonl:2`. |
| Attachment upload URL on `storage.googleapis.com` | None. The URL is a signed upload session. | No `Authorization` header | Measured, `spikes/protocol/s12-attachment.ts:30`, `captures/s12-attachment.jsonl:3` |
| Thread actor socket | Thread `wsToken` | Inside the `rivet_conn_params.<json>` subprotocol | Measured, `captures/s04-live-thread.jsonl:7` |
| Stateless `/request/rpc` | Thread `wsToken` | `x-rivet-conn-params` header. No `Authorization` header. | Measured, `captures/s06-rpc.jsonl:3` |
| User actor socket | User `wsToken` with `capability:"read"` | Inside the `rivet_conn_params.<json>` subprotocol | Measured, `captures/s14-user-actor.jsonl:3` |
| User actor stateless action | User `wsToken`, `read` or `write` | `x-rivet-conn-params` header. No `Authorization` header. | Measured, `captures/probe-json-int.jsonl:3`, S10 |

The access token is either an OAuth JWT or a pasted personal access token. Its refresh, the compare-and-set rule, and the 401 retry are in [Refresh uses compare-and-set](auth-and-security.md#refresh-uses-compare-and-set). Every channel above that uses Bearer refreshes once on 401 and retries the request once.

A `wsToken` is a short-lived JWT. The web reads its claims `cap`, `sid`, `exp`, `iat`, `sxp`, and `userId`, and schedules a refresh at `exp - min(30 s + random 15 s, 20 % of lifetime)` (read, not observed, `private-protocol.md:31`). The app keeps wsTokens in memory only ([Actor tokens stay in memory](auth-and-security.md#actor-tokens-stay-in-memory)). The spike never held a socket long enough to see a token expire, so the lifetime is unmeasured.

## Internal RPC

### Envelope

| Part | Value | Source |
|---|---|---|
| Request line | `POST <origin>/api/internal?<method>` | Measured, `captures/s02-threads.jsonl:1` |
| Request headers | `Authorization: Bearer <token>`, `Content-Type: application/json` | Measured, `spikes/protocol/lib.ts:53-65` |
| Request body | `{"method":"<method>","params":{...}}` | Measured, `captures/s04-live-thread.jsonl:3` |
| Compression | Bodies over 25,600 bytes go gzipped with `Content-Encoding: gzip` | Read, not observed, `private-protocol.md:171` |
| Success | HTTP 200 `{"ok":true,"result":...}` | Measured, `captures/s04-live-thread.jsonl:4` |
| Error | HTTP 200 `{"ok":false,"error":{"code":"permission-denied","message":"Permission denied"}}` | Measured, `captures/s19-files.jsonl:76` |
| HTTP errors | The same envelope with a non-2xx status | Read, not observed, `private-protocol.md:171` |

The CLI also sends client identification headers built by `je(N())`, which were not decoded (`private-protocol.md:171`). The spike sent none, and every internal call it made returned HTTP 200.

An error can arrive with HTTP 200, so `:sync` decides success from `ok`, never from the status code. A response that has neither `ok:true` with `result` nor `ok:false` with `error.code` is `EngineError.Drift`.

The CLI's schema table lists 271 methods (`private-protocol.md:172`). Parameter names are not uniform. Some methods take `thread` and others take `threadID` for the same value (measured, `captures/s09-transcript.jsonl:1`, `captures/s13-portal.jsonl:1`).

### Methods the app calls

| Method | Params the app sends | App use | Verdict | Evidence |
|---|---|---|---|---|
| `listThreads` | `limit`, `offset?`, `threadIDs?`, `includeArchived?`, `includeEmpty?` | Thread list, one-thread refresh | VERIFIED | S2, `captures/s02-threads.jsonl:2`. Archived threads come back without `includeArchived` (`captures/s10-thread-ops.jsonl:158`). There is no snooze field (S10). |
| `getThreadInitialTranscriptPage` | `thread` | First transcript page and the resume cursor `thread.data.v` | VERIFIED | S9 `captures/s09-transcript.jsonl:2`, S17 `captures/s17-resume-cursor.jsonl:2` |
| `getThreadMessagesPage` | `thread`, `fromIndex`, `limit` | Forward paging. `nextMessageIndex` is `null` on the last page. | VERIFIED | S9 `captures/s09-transcript.jsonl:4`, `:6` |
| `getThreadTranscriptPage` | `thread`, `before`, `anchorID` | Backward paging. A wrong anchor returns `status:"stale"`. | VERIFIED | S9 `captures/s09-transcript.jsonl:8`, `:10` |
| `getThreadTail` | `thread`, `limit` | Confirms a notification reply reached the transcript | VERIFIED | S6 `captures/s06-rpc.jsonl:10` |
| `updateThread` | `thread`, `pinned?`, `snooze?` (`"one_hour"`, `"off"`) | Pin and snooze | VERIFIED for `pinned` and `snooze`. NOT VERIFIED for `title`. | S10. `title` returned `ok` but `listThreads` kept the old title (`captures/s10-thread-ops.jsonl:22`, `:42`). |
| `archiveThread` | `thread`, `archived` | Archive and unarchive | VERIFIED | S10 |
| `addThreadLabels`, `getThreadLabels` | `thread`, `labels` | Labels | VERIFIED | S10, `captures/s04-live-thread.jsonl:3-4` |
| `listRunners` | none | Executor picker | VERIFIED | S3 `captures/s03-runner.live.jsonl:10` |
| `resolveThreadPortalURL` | `threadID`, `port` | Portal URL | VERIFIED (control plane) | S13 `captures/s13-portal.jsonl:2` |
| `mintPortalLoginURL` | `url` | One-time portal login URL | VERIFIED (control plane) | S13 `captures/s13-portal.jsonl:5` |
| `threadDisplayCostInfo` | `threadID`, `markdown:false` | Thread cost | Measured | `captures/cost-check.jsonl:2`, `captures/teardown2.jsonl:8` |
| `getThreadFileURLs` | `threadID`, `paths` | File download links | NOT VERIFIED. Returns `permission-denied` on a runner thread. | S19 `captures/s19-files.jsonl:76`, `:78` |
| `getUserInfo` | none | Account confirmation after sign-in | Observed live by Main, no capture | `private-protocol.md:8` |
| `getThreadProject`, `getProject` | `thread`; `projectID` | Project facts for the Changes pane | Read, not observed | `docs/design/git-and-diffs.md:102` |
| `listAgentModes` | none | Mode picker | Read, not observed | `private-protocol.md:179` |

Reads after writes lag by up to about 1 s (S10). After a write, `:sync` applies the change locally and does not re-read the list for 1 s.

## Thread actor

### Get credentials with `POST /api/thread-actors`

| Case | Request body | Response | Source |
|---|---|---|---|
| New thread | `{"usesThreadActors":true,"agentMode":"low"}` | 201 `{threadId, userId, ownerUserId, threadVersion, usesDtw, usesThreadActors, executorType, agentMode, wsToken, capability:"write", poolName:"k8s"}` | Measured, `captures/s04-live-thread.jsonl:1-2` |
| Existing thread | `{"usesThreadActors":true,"threadId":"<threadId>"}` | 201 with the same fields | Measured, `captures/s04-live-thread.jsonl:5-6` |
| Archived thread | Same | 409 `{"error":"This thread is archived","code":"THREAD_ARCHIVED"}` | Measured, `captures/s10-thread-ops.jsonl:160` |
| `runnerId` in the body | `{..., "runnerId":"<id>"}` | 403 "Runner attachment requires an API key and existing thread" | Measured, S4 |
| Other errors | | `THREAD_DELETED`, `ORB_CREATION_REAUTH_REQUIRED`, HTTP 402 | Read, not observed, `private-protocol.md:45` |

`threadVersion` equalled `thread.data.v` from `getThreadInitialTranscriptPage` for both S17 threads, 110 and 9 (measured, `captures/s17-resume-cursor.jsonl:2`, `:4`, `:106`, `:108`). To attach a runner, create the thread first and then send `client_spawn_executor` on the socket (S4).

### Socket URL

```
wss://<origin-host>/actors/gateway/threadActor/websocket/?rvt-namespace=default&rvt-method=get&rvt-key=<threadId>&rvt-token=<rivet-publishable-token>&rvt-skip-ready-wait=true
```

| Query parameter | Value | Meaning |
|---|---|---|
| `rvt-namespace` | `default` | Rivet namespace |
| `rvt-method` | `get` | Look up an existing actor, do not create one |
| `rvt-key` | `<threadId>` | Actor key |
| `rvt-token` | `<rivet-publishable-token>` | Rivet publishable token |
| `rvt-skip-ready-wait` | `true` | Connect without waiting for the actor's ready signal |

The path ends in `/websocket/` with the trailing slash. The spike built this URL with `URLSearchParams` in this order and opened it with the `https` scheme (measured, `spikes/protocol/lib.ts:82-90`, `captures/s04-live-thread.jsonl:7`). OkHttp's `newWebSocket` takes the same `https` URL.

### Subprotocols and connection params

The client offers four subprotocols, in this order (measured, `captures/s04-live-thread.jsonl:7`):

1. `rivet`
2. `rivet_encoding.bare`
3. `rivet_skip_ready_wait`
4. `rivet_conn_params.<encodeURIComponent(JSON.stringify(params))>`

The server selects `rivet` (measured, `captures/s04-live-thread.jsonl:8`). `:sync` fails the connection if the selected subprotocol is anything else. The `bare` encoding applies only to Rivet's own envelopes. Amp's messages travel as JSON text frames (read, not observed, `private-protocol.md:90`).

The connection params the app sends:

```json
{
	"wsToken": "<wsToken>",
	"clientApplication": {"type": "cli", "name": "amp-android", "version": "<versionName>"},
	"interactiveClient": true,
	"transport": "json-rpc"
}
```

The spike sent exactly this shape with `name:"amp-android-spike"` and `version:"0.0.1"`, and the actor accepted it (measured, `captures/s12-attachment.jsonl:8`, `spikes/protocol/s04-live-thread.ts:5-9`). The CLI observer sends the same four keys (read, not observed, `private-protocol.md:96`). The values the server accepts for `clientApplication.type` are unknown, so the app keeps the measured `"cli"`.

The subprotocol header carries the `wsToken`. Network logs redact every `Sec-WebSocket-Protocol` value and every `x-rivet-conn-params` header (`docs/design/verification.md:43`).

### JSON-RPC framing

| Direction | Shape | Source |
|---|---|---|
| Client request | `{"jsonrpc":"2.0","id":"thread-client-<epochMs>-<counter>","method":"<type>","params":{...}}` | Measured, `captures/s04-live-thread.jsonl:9` |
| Client notification | `{"jsonrpc":"2.0","method":"<type>","params":{...}}`, no `id` | Measured, `captures/s08-terminal.jsonl:57` |
| Server response | `{"jsonrpc":"2.0","id":"<same id>","result":{...}}` or `"error":{"code","message","data"?}` | Measured, `captures/s04-live-thread.jsonl:28`, `captures/s19-files.jsonl:65` |

The app sends relay messages and `executor_plugin_message` as notifications, because the spike did and they worked (measured, `captures/s08-terminal.jsonl:57-58`, `captures/s15b-plugin-dialog.jsonl:63`). Everything else goes as a request. A request times out after 120 s, which is the web's value (read, not observed, `private-protocol.md:107`).

JSON-RPC error codes seen on the socket:

- `-32602 Invalid method params` for a params shape the actor rejects (measured, `captures/s19-files.jsonl:65`, and `captures/s20-changes-params.jsonl:1017` for a file order section without `includeUntracked`).
- `-32601 Method not found` on the stateless path for methods outside its allowlist (measured, S18).

The web also reconnects and retries up to 3 times on `-32603` for replayable messages, and reconnects on `ACTOR_STOPPING` (read, not observed, `private-protocol.md:107`).

### Three inbound text frame shapes

Text frames arrive in three shapes, and binary frames carry relay data. `:sync` normalizes all of them before any decoding.

| Shape | How to recognize it | Normalized to | Source |
|---|---|---|---|
| JSON-RPC | A JSON object with `jsonrpc`. With `id` and `result` or `error` it is a response. With `method` it is a notification whose type is `method` and whose body is `params`. | `Response`, or `Event(type, seq, body)` | Measured, `captures/s04-live-thread.jsonl:10`, `:28` |
| Bare frame | A JSON object with `type` and no `jsonrpc`. The body is the object minus `type`. `seq` sits at the top level. | `Event(type, seq, body)` | Measured, `captures/s04-live-thread.jsonl:11-24`, `captures/s09-transcript.jsonl:22-46` |
| `pong` | The four-character text `pong`, not JSON | `Pong`, ignored | Measured, `captures/s09-transcript.jsonl:20`, `captures/s17-resume-cursor.jsonl:79` |
| Binary | First bytes `0xAE 0x01` | `Relay(frame)` | Measured, `captures/s08-terminal.jsonl:59` |
| Anything else | | `Unparseable(preview)`, counted as drift | |

A JSON array is a batch, and each element is parsed on its own (read, not observed, `private-protocol.md:106`). A notification whose type ends in `_result` and whose body has `requestId` goes to the request correlator, not the reducer. `client_executor_relay_exit` goes to the relay multiplexer.

Bare frames carry the connect burst and history replay. Live events after the burst arrive as JSON-RPC notifications. Four connect-burst types arrive as JSON-RPC notifications instead of bare frames: `observers` (first copy only), `prompt_drafts`, `comment_typing`, and `discussion_typing` (measured, `captures/s17-resume-cursor.jsonl:80`, `:99-101`).

`pong` arrived exactly once on 17 of the 18 thread sockets the spike opened, right after the client sent `client_resume`. The one socket without it was the first connection to a brand-new thread (measured, `captures/s04-live-thread.jsonl:8-28`). The client never sent a ping.

### Results arrive twice, and only the notification carries data

Requests that carry a `requestId` get two answers with the same `requestId` (measured, S7, S19):

- The JSON-RPC response, whose `result` is only `{"ok":true}`.
- A `*_result` notification with the real outcome.

The JSON-RPC result says nothing about the operation. A second `client_filesystem_write_file_v3` with `overwrite:false` got `{"ok":true}` as its response and `ok:false, error{code:"ALREADY_EXISTS"}` in the notification (measured, `captures/s19-files.jsonl:72-73`). The response is the actor's receipt, and the result follows once the executor has done the work: 155 ms later for a diff snapshot and 104 ms later for a file read (measured, `captures/s07-changes.jsonl:70-71`, `captures/s19-files.jsonl:51-52`). On a large repository, a large file, or a slow orb the gap can exceed any fixed wait [inferred]. The response came first in 13 of the 14 measured pairs. For `client_request_capture`, the notification came first (measured, `captures/s07-changes.jsonl:74-75`).

`RequestCorrelator` settles each `requestId` with these rules (`docs/design/sketch/sync/internal/Transport.kt`, `RequestCorrelator.register(rpcId, requestId?)`):

- A `*_result` notification settles the request with its body, whether it arrives before or after the response.
- A response with `error` settles the request as failed at once. A later notification for that id is dropped.
- A response with `result` never settles the request. It only confirms that the actor accepted it.
- A request with no notification 120 s after it was sent settles as timed out (`RequestCorrelator.TIMEOUT_MS`), the web's request timeout (read, not observed, `private-protocol.md:107`). An AtMostOnce request then shows its outcome as unknown.
- Anything for an id that is already settled is dropped and counted.

Requests without a `requestId`, such as `client_append_user_msg`, `client_resume`, and `client_cancel`, settle on their JSON-RPC response, or as unknown after the same 120 s.

| Request | Result notification | Source |
|---|---|---|
| `client_git_command` | `client_git_command_result` | Measured, `captures/s07-changes.jsonl:59` |
| `client_git_diff_snapshot` | `client_git_diff_snapshot_result` | Measured, `captures/s07-changes.jsonl:71` |
| `client_changes_file_order` | `client_changes_file_order_result` | Measured, `captures/s20-changes-params.jsonl:1020` |
| `client_request_capture` | `client_request_capture_result` | Measured, `captures/s07-changes.jsonl:74` |
| `client_filesystem_read_directory_v2` | `client_filesystem_read_directory_result` | Measured, `captures/s19-files.jsonl:36` |
| `client_filesystem_read_file_v2` | `client_filesystem_read_file_result` | Measured, `captures/s19-files.jsonl:48` |
| `client_filesystem_write_file_v3` | `client_filesystem_write_file_result` | Measured, `captures/s19-files.jsonl:69` |

The notification name drops the `_v2` or `_v3` suffix.

### Integers at or above 2^31 go out as `.0` literals

In Rivet's JSON encoding, an integer argument of 4294967296 (2^32) makes a user actor action fail with HTTP 500 `{"code":"internal_error","group":"rivetkit",...}` (measured, `captures/probe-json-int.jsonl:9-10`). The probe's other values passed:

| Body sent to `getRecentThreads` | Status | Source |
|---|---|---|
| `"sinceMs":1792643912226.0` | 200 | `captures/probe-json-int.jsonl:3-4` |
| `"sinceMs":2147483647` | 200 | `captures/probe-json-int.jsonl:5-6` |
| `"sinceMs":2147483648` | 200 | `captures/probe-json-int.jsonl:7-8` |
| `"sinceMs":4294967296` | 500 | `captures/probe-json-int.jsonl:9-10` |

The probe did not test values between 2^31 and 2^32, or exponent notation. `:sync` therefore follows one rule on every channel: every integer value of 2^31 or more goes out as a decimal literal with a `.0` suffix. That covers internal RPC bodies, thread actor JSON-RPC params, user actor actions, and user actor socket frames, all through one encoder, `RivetNumber` (`docs/design/sketch/sync/internal/Transport.kt`). Kotlin's `Double.toString` produces exponent notation such as `1.792643912226E12`, which is untested, so the encoder writes the literal itself:

```kotlin
// Rivet JSON rejects integer literals at 2^32 (captures/probe-json-int.jsonl:9-10).
// A literal with a fractional part passes (captures/probe-json-int.jsonl:3-4).
private const val RIVET_INT_LIMIT = 1L shl 31

internal object RivetNumber {
	fun encode(value: Long): String = if (value < RIVET_INT_LIMIT) "$value" else "$value.0"
}
```

The probe covered user actor actions only. Thread actor JSON-RPC and internal RPC were not probed, and none of the params in this document need an integer that large there, so the rule costs nothing on those channels and keeps one encoder for all of them. In the other direction, the user actor returns request ids as `["$BigInt","1"]` (measured, `captures/s14-user-actor.jsonl:16`), and the thread actor sends some numbers with fractions, such as `mtimeMs:1792729569472.0803` (measured, `captures/s19-files.jsonl:36`). Decoders accept a number, a fractional number, or the `$BigInt` pair wherever an integer is expected.

### Connect and resume

The connect sequence, with the order measured on every thread socket in the captures (`captures/s04-live-thread.jsonl:5-28`, `captures/s17-resume-cursor.jsonl:74-102`, `:107-138`):

1. `POST /api/thread-actors {usesThreadActors:true, threadId}` mints a fresh `wsToken`.
2. The client opens the socket.
3. The client sends `client_resume{version}` as its first frame.
4. The actor sends `pong`, then one `observers` notification.
5. The actor replays every Sequenced event with `seq > version` as bare frames, plus `thread_title` and `tool_progress` frames from the same history (measured, `captures/s09-transcript.jsonl:23`, `:57`).
6. The actor sends the connect burst of current-state frames listed under [Event classes](#events-fall-into-three-classes).
7. The actor answers the request with `{replayMessageCount, storedEventCount, replayThroughSeq}`. The response always came last.

The link state is `Resuming` from step 3 until the response arrives (`docs/design/screens.md`, link states). The outbox drains only after the response, so a guarded command is checked against a caught-up state (`docs/design/sketch/sync/internal/Routing.kt`, `OutboxDrainer`).

**Choosing `version`.**

- **Cold open.** Use `thread.data.v` from the same `getThreadInitialTranscriptPage` response that supplied the transcript rows. Resuming at that value replays 0 messages and still delivers the full connect burst (measured, S17, `captures/s17-resume-cursor.jsonl:2`, `:102`). Resuming at 0 replays the whole history. For a thread with 28 messages and 110 stored events, that was 28 `message_added` frames, and the response reported `replayMessageCount:40`, a count that also includes the `thread_title`, queue, and `cancelled` frames from the same history (measured, `captures/s17-resume-cursor.jsonl:71`, `:73`).
- **Background short socket for a thread with no cache.** Use `threadVersion` from the `POST /api/thread-actors` response that minted the `wsToken` (`WsGrant.threadVersion`). It equals `thread.data.v` (see [Get credentials](#get-credentials-with-post-apithread-actors)), so the socket replays no history and delivers only the connect burst, which is all a background answer or push subscription needs.
- **Warm reconnect.** Use the highest `seq` the reducer has applied.
- **After the response.** Set the cursor to `max(cursor, replayThroughSeq)` [inferred: S17 shows `replayThroughSeq` equal to `thread.data.v`, and the web resumes from the last applied seq, `private-protocol.md:113`].

**Sequenced frames inside the burst.** The connect burst repeats `error_set` with a `seq` at or below the requested version. A resume at version 9 received `error_set{seq:9}` (measured, `captures/s17-resume-cursor.jsonl:157`). A client whose cursor came from `thread.data.v` never saw that event during replay. The reducer therefore upserts Sequenced events by their key, `messageId` for messages and `seq` for the rest, instead of dropping every frame at or below the cursor. Applying the same `error_set` twice is harmless under upsert. `message_added` repeats are covered by the same rule (S16).

### Reconnect with backoff

`:sync` reconnects a thread socket when any of these happens:

- The socket closes or fails.
- A request returns `ACTOR_STOPPING`, or `-32603` on a message the web marks replayable (read, not observed, `private-protocol.md:107`).
- `client_reauthorize` fails.

The delay starts at 3 s, doubles up to 30 s, and is multiplied by a random factor from 0.8 to 1.2. The web stops after 5 attempts, and the CLI uses the same jitter (read, not observed, `private-protocol.md:119`). The app does not stop while a viewer lease holds the thread and the network is up. It shows `Reconnecting` with **Retry Now** instead (`docs/design/screens.md`, link states, `docs/design/sketch/sync/internal/Transport.kt`, `Backoff`). Terminal channels follow the same rule (`docs/design/architecture.md:100`).

Every reconnect mints a new `wsToken` first, because the old one may have expired. Volatile state from the old socket is dropped (`docs/design/sketch/sync/internal/Facts.kt`, `ThreadFact.Volatile`). Relay channels end with the socket and reopen as described in [Reconnect and scrollback recovery](terminal.md#reconnect-and-scrollback-recovery).

### Token refresh with `client_reauthorize`

Before the `wsToken` expires, `:sync` mints a new token with `POST /api/thread-actors {threadId}` and sends `client_reauthorize{wsToken}` as a request on the open socket. The schema accepts exactly one of `wsToken`, `apiKey`, or `orbToken` (read, not observed, `private-protocol.md:38`). If the request fails, `:sync` closes the socket and reconnects with the new token (read, not observed, `private-protocol.md:120`). The web marks `client_reauthorize` as `reject`, so it is never resent after a reconnect (read, not observed, `private-protocol.md:115`). The stateless path rejects it with `-32601` (measured, `captures/s18b-rpc-allowlist.jsonl:72`).

## Events fall into three classes

The class decides how the reducer and the cache treat an event. Class membership comes from two measurements:

- **Carries `seq`.** Only six types ever carried `seq` across all captures.
- **Repeats in the connect burst.** A resume at the current version still delivered these types (measured, `captures/s17-resume-cursor.jsonl:80-101`, `:147-168`).

To regenerate the counts, run this from `spikes/protocol`:

```sh
jq -r 'select(.dir=="recv" and (.ch|startswith("thread"))) | .data | select(type=="object") | (.method // .type) as $t | select($t != null and .id == null) | "\($t) \(if (.params.seq // .seq) != null then "seq" else "-" end)"' captures/*.jsonl | sort | uniq -c
```

| Class | Rule | Types measured, with frame counts | Types read, not observed |
|---|---|---|---|
| Sequenced | Carries `seq`. Replayed after `version` on resume. Upserted by key and cached. A frame of a Sequenced type without `seq` is malformed. | `message_added` 210, `error_set` 39, `error_cleared` 7, `queued_message_added` 8, `queued_message_dequeued` 8, `cancelled` 8 | `message_updated`, `queued_message_removed`, `thread_truncated` (`private-protocol.md:152-153`) |
| Snapshot | No `seq`. Repeats in every connect burst. The newest frame replaces the whole aspect and is cached, except `plugin.ui.snapshot`, which is never cached. | `observers` 48, `thread_title` 38, `thread_open_state` 20, `thread_settings` 18, `thread_features` 18, `queued_messages` 18, `tool_approval_queue` 19, `background_work` 18, `background_subagents` 18, `compacting_subagents` 18, `environment_update` 18, `executor_status` 26, `executor_health` 16, and `plugin_message` events `command.list`, `link-pattern.list`, `plugin.ui.snapshot` | `thread_recap` (`private-protocol.md:155`) |
| Volatile | No `seq`. Memory only, dropped on every reconnect. Some repeat in the burst, but they describe only the live moment. | `agent_state` 60, `delta` 65, `tool_progress` 16, `inference_tools` 11, `executor_workspace_maybe_changed` 5, `retry_cancelled` 18, `prompt_drafts` 18, `comment_typing` 18, `discussion_typing` 18, and `plugin_message` events `ui.notify` and plugin responses (`message.type:"response"`) | `retry_scheduled`, `retry_started`, `compaction_started`, `compaction_complete` (`private-protocol.md:151-152`) |

`plugin_message` totals 76 frames across all its events. Two plugin events change one entry of the plugin dialog list by `requestId`: `plugin.ui.request_added` and `plugin.ui.request_settled` (measured, `captures/s15b-plugin-dialog.jsonl:64`, `:67`). A third, `plugin.ui.request_removed{requestId}`, removes an entry without settling it (read, not observed, Amp's docs). The reducer drops that dialog and `:notify` cancels its notification. The sketch calls all three Keyed facts and does not cache them, because `plugin.ui.snapshot` lists every pending dialog again on each connect (measured, S15c, `captures/s15c-plugin-dialog-detached.jsonl:101`). For the same reason the app never caches `plugin.ui.snapshot` itself.

Request results are not events. The `client_*_result` types (14 frames) go to the correlator, and `client_executor_relay_exit` (1 frame) goes to the relay multiplexer.

`thread_open_state` carries `openStateSeq`, not `seq`, and is a Snapshot (measured, `captures/s17-resume-cursor.jsonl:82`). `tool_progress` and `thread_title` also appear in history replay without `seq` (measured, `captures/s09-transcript.jsonl:23`, `:57`). The reducer applies them like live frames.

## Outbound messages

All of these go on the thread socket. Params are the ones the app sends. `requestId` is a client-minted UUID string.

### Messages and turns

| Message | Params | Answer and effects | Verdict |
|---|---|---|---|
| `client_append_user_msg` | `content[]`, `messageId`, `agentMode`, `userState{currentlyVisibleFiles, timeZone}`, `steer?` | `{ok:true}`, then `message_added` with the same `messageId` and `agent_state working` | VERIFIED, S4 `captures/s04-live-thread.jsonl:39`, `:43-46` |
| Same, with `steer:true` while a tool runs | as above | `queued_message_added{message{steer:true, batchKind:"singleton", batchState:"closed", queuedMessage}, seq}`, where `queuedMessage.messageId` is the client's `messageId`. At the next tool boundary, `queued_message_dequeued{queuedMessageId, seq}` and then `message_added` with `meta.steer:true`. The steer is not delivered mid-tool. | VERIFIED, S5 `captures/s05-steer-interrupt.jsonl:55-56`, `:63-64` |
| Same, without `steer`, while a turn runs (queue) | as above | [inferred] `queued_message_added` with `steer` false or absent, then `queued_message_dequeued` and `message_added` after the turn ends | Read, not observed. S5 sent only `steer:true`. The M3 gate sends one (`roadmap.md:14`). |
| `client_cancel` | `{}` | `message_added` with a `tool_result` of `run{status:"cancelled", reason:"User canceled"}`, `agent_state idle` 564 ms after the request, then `cancelled{seq, messageId}`. The app sends it as its own AtMostOnce `Interrupt` command, never over `/request/rpc`, which rejects it (S18). | VERIFIED, S5 `captures/s05-steer-interrupt.jsonl:108-112` |
| `client_retry` | `{}` | Not observed | Read, not observed, `private-protocol.md:132` |
| `client_edit_message` | `messageId`, `replacementMessageId?`, `editId`, `content`, `agentMode?`. The app mints `editId` once per edit, so a resend names the same edit. | `edit_rejected{editId, message}` on rejection | Read, not observed, `private-protocol.md:131`, `:152` |
| `client_remove_queued_msg` | `queuedMessageId` | Not observed | Read, not observed, `private-protocol.md:132` |
| `client_reorder_queued_msgs` | `queuedMessageIds` | Not observed | Read, not observed, `private-protocol.md:132` |
| `client_steer_queued_msg` | `queuedMessageId` | Not observed | Read, not observed, `private-protocol.md:132` |
| `client_dismiss_active_error` | `seq?` | Not observed | Read, not observed, `private-protocol.md:132` |

**Content blocks.** Text is `{type:"text", text}`. An image is `{type:"image", source{type:"url", url}, sourcePath}` with a URL from the attachment flow (measured, `captures/s12-attachment.jsonl:68-69`).

**`messageId`.** The client mints it as `M-` followed by 22 characters from `[0-9A-Za-z]` (`spikes/protocol/lib.ts:96-100`). The actor accepted client-minted ids and echoed them (measured, `captures/s04-live-thread.jsonl:39`, `:43`).

**Dedupe and acknowledgement.** Resending an append with the same `messageId` is safe on the socket and on `/request/rpc`. Each resend returns `{ok:true}` and repeats `message_added` with the original `seq`, without adding a message or starting a turn (measured, S16, `captures/s16-dedupe.jsonl:34-46`, `captures/s16-recount.jsonl:3`). The outbox therefore resends an append after a lost acknowledgement. A send counts as acknowledged when `queued_message_added` or `message_added` carries its `messageId`, whichever arrives first (`captures/s05-steer-interrupt.jsonl:56`), so a steer stops resending once it is queued instead of after the tool finishes. S16 measured dedupe for a message appended at once. Whether a resend of a message that is still queued also dedupes is unmeasured. The reducer upserts `message_added` by `messageId`.

### Thread state

| Message | Params | Answer and effects | Verdict |
|---|---|---|---|
| `client_set_thread_title` | `title` | `thread_title{title, source:"manual"}`, and `listThreads` shows the new title | VERIFIED, S10 `captures/s10-thread-ops.jsonl:113` |
| `client_mark_thread_unread` | `{}` | `getRecentThreads.hasUnreadMessages` becomes `true` | VERIFIED, S10 `captures/s10-thread-ops.jsonl:134` |
| `client_mark_message_read` | `messageId` of the last assistant message | `hasUnreadMessages` becomes `false` | VERIFIED, S10 `captures/s10-thread-ops.jsonl:140` |
| `client_set_thread_open` | `ttlSeconds` from 300 to 604800, or `null` to close | `thread_open_state{openExpiresAt, openStateSeq}` | Read, not observed, `private-protocol.md:134`, `:284` |

### Human gates

| Message | Params | Answer and effects | Verdict |
|---|---|---|---|
| `client_tool_approval_response` | `toolCallId`, `accepted`, `input?{askAnswers?, denyFeedback?}` | Not observed. No approval was ever queued, because every turn failed with `MODEL_PROVIDER_ERROR` before a tool call. | Read, not observed, S15 INCONCLUSIVE, `private-protocol.md:133` |
| `executor_plugin_message` (notification) running a command | `message{type:"request", id, method:"command.execute", params{pluginName, commandId, threadID}}` | `plugin.ui.request_added` for each dialog the command opens, then a command response `{ok:true}` | VERIFIED, S15b `captures/s15b-plugin-dialog.jsonl:63-64` |
| `executor_plugin_message` (notification) answering a dialog | `message{type:"request", id, method:"plugin.ui.respond", params{requestId, result}}`, or `params{requestId, error}` to dismiss | `plugin.ui.request_settled{request{status:"responded", response}}` and `plugin_message{message{type:"response", id, result{status:"accepted"}}}`. With `error`, the request settles as `status:"errored"`. | VERIFIED, S15b `captures/s15b-plugin-dialog.jsonl:66-68`, S15c `captures/s15c-plugin-dialog-detached.jsonl:111`, `:115` |

The tool approval queue item shape is `{id, toolCallId, toolName, args, reason?, toAllow?[], context, subagentToolName?, parentToolCallId?, timestamp, matchedRule?{tool, action, matches?}, ruleSource?}` (read, not observed, S15). An item that fails to decode becomes an `UnsupportedGate` with **Answer on web** (`docs/design/sketch/core-model/Gates.kt`, `UnsupportedGate`).

For `plugin.ui.respond`, `result` carries the raw answer. It is `true` or `false` for `ui.confirm` and a string for `ui.input` (measured, S15b). The spike used ids of the form `plugin-ui-respond-<requestId>-1` and `spike-command-<uuid>`. The id is client-chosen. Commands come from `plugin_message` `command.list` (measured, `captures/s15b-plugin-dialog.jsonl:50`).

### Executor, git, and files

| Message | Params | Answer and effects | Verdict |
|---|---|---|---|
| `client_spawn_executor` for a runner | `requestId`, `executor{type:"runner", runnerId, workingDirectory}` | `{ok:true}`, then `executor_status` from `starting` to `running` and `observers{hasExecutor:true}` | VERIFIED, S4 `captures/s04-live-thread.jsonl:30-42` |
| `client_spawn_executor` for a runner worktree | As above, plus `runnerWorktree` [inferred: the field name the CLI passes to `createRemoteExecutorThread`, `private-protocol.md:482`] | Not observed | Read, not observed. Every thread, worktree or not, is created with `POST /api/thread-actors` and then spawned, so a failed spawn retries on the same thread (`docs/design/git-and-diffs.md:159`). The app does not call `createRemoteExecutorThread`. |
| `client_git_command` | `requestId`, `operation{type, ...}` | `client_git_command_result{ok, exitCode, stdout, stderr}` | VERIFIED, S7. Operations are in [Live git reference](git-and-diffs.md#live-git-reference). |
| `client_git_diff_snapshot` | `requestId`, `baseRevision`, `headRevision:"worktree"`, `includeUntracked:true`, `content:"renderable"` | `client_git_diff_snapshot_result` with per-file `oldContent` and `newContent` | VERIFIED, S7 `captures/s07-changes.jsonl:69-71` |
| `client_changes_file_order` | `requestId`, `sections[{sectionID, diffHash, baseRevision, headRevision, includeUntracked, files[{path, changeType, diff ≤ 20,000 characters}]}]` | `client_changes_file_order_result{requestId, result:{status:"success", sections[{sectionID, diffHash, orderedPaths, summary?, codeTour?}]} or {status:"failure", error}}`. Without `includeUntracked`, `-32602`. | VERIFIED, S20b `captures/s20-changes-params.jsonl:1016-1020` |
| `client_request_capture` | `requestId` | `ok:false, error{code:"INVALID_ARGS"}` on a runner thread. Captures exist only for orb threads. | NOT VERIFIED, S7 `captures/s07-changes.jsonl:74` |
| `client_filesystem_read_directory_v2` | `requestId`, `path` (absolute, or relative to the workspace root, `"."` for the root) | `entries` as tuples `[name, kind, {mtimeMs} or null]` | VERIFIED, S19 `captures/s19-files.jsonl:34-40` |
| `client_filesystem_read_file_v2` | `requestId`, `path` | `{requestId, ok, contentBase64}`, with no size field | VERIFIED, S19 `captures/s19-files.jsonl:46-58` |
| `client_filesystem_read_file_v2` with `range{start, end}` | | `-32602 Invalid method params` | NOT VERIFIED, S19 `captures/s19-files.jsonl:65` |
| `client_filesystem_write_file_v3` | `requestId`, `path`, `contentBase64`, `overwrite` | Creates the file. With `overwrite:false` on an existing file, `ok:false, error{code:"ALREADY_EXISTS"}`. Only a 15-byte file was written. | VERIFIED, S19 `captures/s19-files.jsonl:66-73` |
| `client_filesystem_delete_v2` | `requestId`, `path`, `recursive` | Not observed | Read, not observed, `private-protocol.md:138` |

The web marks `client_filesystem_write_file_v3` as `reject`, so the app never resends a write after a lost acknowledgement (read, not observed, `private-protocol.md:115`). Spawning an orb executor and the worktree spawn field are not measured (`docs/design/sketch/COVERAGE.md`).

### Relay

| Message | Params | Verdict |
|---|---|---|
| `client_executor_relay_open` (notification) | `channelId:"relay-<uuid>"`, `service:"amp-terminal-v1"`, `binaryFrames:true` | VERIFIED, S8 `captures/s08-terminal.jsonl:57` |
| `client_executor_relay_data` (notification) | `channelId`, `dataBase64`, `binary:false` | VERIFIED, S8 `captures/s08-terminal.jsonl:58`. Used only until the first binary frame arrives. |
| `client_executor_relay_close` (notification) | `channelId` | VERIFIED, S8 `captures/s08-terminal.jsonl:106` |

The binary frame format is in [Relay frames](#relay-frames-share-the-thread-socket).

### Session and push

| Message | Params | Verdict |
|---|---|---|
| `client_resume` | `version` | VERIFIED, S4, S17 |
| `client_reauthorize` | `wsToken` | Read, not observed, `private-protocol.md:38` |
| `client_upsert_notification_subscription` | `subscription{endpoint, keys{auth, p256dh}}`, `threadURL` | VERIFIED, S11 `captures/s11-push.jsonl:64-65` |

## The stateless `/request/rpc` path accepts one method

```
POST <origin>/actors/gateway/threadActor/request/rpc?rvt-namespace=default&rvt-method=get&rvt-key=<threadId>&rvt-token=<rivet-publishable-token>&rvt-skip-ready-wait=true
x-rivet-conn-params: {"wsToken":"<wsToken>","transport":"json-rpc"}
Content-Type: application/json

{"jsonrpc":"2.0","id":"thread-one-shot-client-<epochMs>-0","method":"client_append_user_msg","params":{...}}
```

The answer is HTTP 200 with a JSON-RPC body, `{"jsonrpc":"2.0","id":"...","result":{"ok":true}}` (measured, S6, `captures/s06-rpc.jsonl:3-4`). The CLI names this path `/rpc`, and the gateway path on the wire is `/request/rpc` (S6). The query parameters match the socket URL.

The allowlist is exactly one method, `client_append_user_msg`. S18 probed 18 methods with bogus params. Only `client_append_user_msg` reached parameter validation and returned `-32602`. The other 17 returned HTTP 200 with `-32601 Method not found: <method>` (measured, `captures/s18b-rpc-allowlist.jsonl:4-72`):

`client_tool_approval_response`, `client_cancel`, `client_retry`, `client_set_thread_title`, `client_upsert_notification_subscription`, `client_mark_thread_unread`, `client_mark_message_read`, `client_dismiss_active_error`, `client_remove_queued_msg`, `client_steer_queued_msg`, `client_update_thread_settings`, `client_set_thread_open`, `client_resume`, `client_spawn_executor`, `client_filesystem_read_directory_v2`, `client_git_command`, `client_reauthorize`.

`StatelessThreadRpc` therefore has one method, `appendUserMessage` (`docs/design/sketch/sync/internal/Transport.kt`, `StatelessThreadRpc`). A notification reply uses it with a freshly minted `wsToken` (`docs/design/notifications.md:68`). Every other action from the background, such as approving a gate or stopping a turn, opens a short-lived socket, or joins the thread's socket when the engine already has one open (`docs/design/architecture.md:101`). A send through this path is deduped by `messageId` like a socket send (measured, S16, `captures/s16-dedupe.jsonl:41-43`).

## User actor

The user actor streams per-user state. It reports the status of every thread, the activity feed, and change signals for cached lists.

### Credentials and connect

`POST <origin>/api/user-actor-credentials {"capability":"read"}` with Bearer returns 201 `{poolName, userId, wsToken}` (measured, `captures/s14-user-actor.jsonl:1-2`). The socket URL is:

```
wss://<origin-host>/actors/gateway/userActor/connect?rvt-namespace=default&rvt-method=get&rvt-key=<userId>&rvt-token=<rivet-publishable-token>&rvt-skip-ready-wait=true
```

The subprotocols are `rivet`, `rivet_encoding.json`, `rivet_skip_ready_wait`, and `rivet_conn_params.<encoded {"type":"user","wsToken":"<wsToken>"}>` (measured, `captures/s14-user-actor.jsonl:3`). The encoding is `json`, not `bare`, so no BARE codec is needed. Every frame in both directions is a JSON text frame with a `body{tag, val}` envelope.

### Frames

| Direction | Frame | Source |
|---|---|---|
| Client | `{"body":{"tag":"SubscriptionRequest","val":{"eventName":"<name>","subscribe":true}}}`, one per event name | Measured, `captures/s14-user-actor.jsonl:5-13` |
| Client | `{"body":{"tag":"ActionRequest","val":{"id":1,"name":"getRecentThreads","args":[{"limit":3}]}}}` | Measured, `captures/s14-user-actor.jsonl:14` |
| Server | `{"body":{"tag":"Init","val":{"actorId","connectionId"}}}` | Measured, `captures/s14-user-actor.jsonl:15` |
| Server | `{"body":{"tag":"ActionResponse","val":{"id":["$BigInt","1"],"output":...}}}` | Measured, `captures/s14-user-actor.jsonl:16` |
| Server | `{"body":{"tag":"Event","val":{"name":"<name>","args":[{...}]}}}` | Measured, `captures/s14-user-actor.jsonl:17` |

The client sends `id` as a plain number. The server returns it as `["$BigInt","<n>"]`. The RivetKit source also describes a `connectionToken` in `Init` (read, not observed, `private-protocol.md:321`), but the measured `Init` has none.

The app subscribes to `threadStatusUpdated`, `feedItemUpdated`, and `resourceRevisionUpdated`. The spike also subscribed to `draftUpdated`, `threadMentionUpdated`, `threadMentionStatusUpdated`, `followedThreadActivityUpdated`, `threadCommentUnreadsUpdated`, and `dictationStateUpdated`, and none of them fired during the run (measured, `captures/s14-user-actor.jsonl:5-13`).

### Stateless actions

```
POST <origin>/actors/gateway/userActor/action/<name>?rvt-namespace=default&rvt-method=get&rvt-key=<userId>&rvt-token=<rivet-publishable-token>&rvt-skip-ready-wait=true
x-rivet-encoding: json
x-rivet-conn-params: {"type":"user","wsToken":"<wsToken>"}
Content-Type: application/json

{"args":[{...}]}
```

The response is `{"output":...}` (measured, `captures/probe-json-int.jsonl:3-4`, `spikes/protocol/lib.ts:114-121`). Reads use a `read` token. Each write mints a fresh `write` token (`docs/design/auth-and-security.md:159`).

| Action | Args | Use | Verdict |
|---|---|---|---|
| `getRecentThreads` | `{limit, sinceMs?}` | Thread summaries, background sync | VERIFIED, S2 `captures/s02-threads.jsonl:6`. `sinceMs` follows the integer rule above. |
| `listRunners` | none | Runners with `agentModes` | VERIFIED, S3 `captures/s03-runner.live.jsonl:8` |
| `setThreadPinned` | `{threadId, pinned}`, write token | Unpin | VERIFIED, S10 |
| `reportVisibility` | `{visible, platform, nativePlatform, threadId, present}` | Tells Amp a thread is on screen | Read, not observed, `private-protocol.md:166` |
| `markFeedItemSeen` | `{itemId}` | Feed read state | Read, not observed, `private-protocol.md:166` |

### Events

**`threadStatusUpdated`** carries one thread summary in `args[0]` (measured, `captures/s14-user-actor.jsonl:23-40`). Across the 49 `threadStatusUpdated` events in all captures, eight fields were always present: `threadId`, `title`, `state`, `executorConnected`, `backgroundWork`, `indicator`, `lastUserMessageAt`, and `updatedAt`. The rest appeared in some events only: `runnerID` 47, `workspace` 44, `explicitWorkingDirectory` 44, `currentTurnStartedAt` 42, `lastTurnEndedAt` 40, `hasUnreadMessages` 28, `latestAssistantMessageID` 28, `unreadStatusUpdatedAt` 28, `archived` 2, and `pinned` 2. `hasUnreadMessages` was `true` 26 times, `false` twice, and absent 21 times. `:sync` merges an event over the last known summary for that thread, so an absent field keeps its previous value [inferred: the semantics of an absent field are not measured].

`state` followed the turn: `idle`, `working`, `streaming`, `tool_use`, `streaming`, `idle`, and `hasUnreadMessages:true` at turn end (measured, S14).

`indicator` is `null` or `{kind, description, observedAt}`. While a plugin dialog waits, it is `{kind:"action-required", description:"<dialog title>", observedAt}`, and it returns to `null` once the dialog is answered (measured, S15b `captures/s15b-plugin-dialog.jsonl:65`, S15c `captures/s15c-plugin-dialog-detached.jsonl:66`, `:117`). The indicator also appears with no thread observer connected (measured, S15c). `observedAt` is an HTTP date such as `Fri, 23 Oct 2026 05:32:17 GMT`, not ISO 8601 like the other timestamps (measured, `captures/s15b-plugin-dialog.jsonl:65`). Whether approvals set the indicator is unknown (S15).

**`feedItemUpdated`** fires once per turn end with `{id:"thread-turn-completed:<threadId>", kind:"thread-turn-completed", agentMode, createdAt, replies{messages[{messageId, unread}], statusUpdatedAt, unreadCount}, seenAt, source{...}}` (measured, `captures/s14-user-actor.jsonl:33`).

**`resourceRevisionUpdated`** is `{revision, scope}` with `scope` of `threads` or `labels` (measured, `captures/s14-user-actor.jsonl:17-22`). When a scope's revision grows past the cached one, `:sync` refetches that list.

User actor reconnects use the thread socket's backoff and resubscribe every event name. Events missed while disconnected are not replayed [inferred: the protocol has no cursor for the user actor], so `:sync` calls `getRecentThreads` after every reconnect.

## Relay frames share the thread socket

Relay data uses binary WebSocket frames on the thread socket. The codec is `encodeRelayFrame` and `decodeRelayFrame` in `spikes/protocol/lib.ts:126-145`.

| Offset | Size | Field | Values |
|---|---|---|---|
| 0 | 1 | Magic | `0xAE` |
| 1 | 1 | Version | `0x01` |
| 2 | 1 | Kind | `1` = to the executor, `2` = from the executor |
| 3 | 1 | Flags | bit 0 = payload is binary, bit 1 = last chunk (`fin`) |
| 4 | 1 | Channel id length | 1 to 255 |
| 5 | n | Channel id | UTF-8, such as `relay-<uuid>` |
| 5 + n | rest | Payload | |

Every observed frame had `fin` set. Control payloads such as `{"type":"ready"}`, `{"type":"resize",...}`, and `{"type":"exit",...}` traveled with bit 0 clear, and PTY output traveled with bit 0 set (measured, `captures/s08-terminal.jsonl:59-62`, `:104`). Payloads are chunked at 1,000,000 bytes and reassembled up to 16 MiB (read, not observed, `private-protocol.md:108`).

The client sends its first control message as JSON `client_executor_relay_data`, and switches to binary frames once the actor sends its first binary frame, as the web does (measured, S8). `client_executor_relay_exit{channelId}` arrives as a notification after the executor's `exit` payload (measured, `captures/s08-terminal.jsonl:104-105`). The `amp-terminal-v1` payloads and limits are in [Relay wire format](terminal.md#relay-wire-format).

## Attachments use a resumable upload

1. `POST <origin>/api/attachments/resumable` with Bearer and `{action:"prepare", jobID, mediaType, sizeBytes, filename, threadID}`. `jobID` is a client UUID. The answer is `{uploadURL}`, a Google Cloud Storage resumable session on `storage.googleapis.com` with `uploadType=resumable` and `ifGenerationMatch=0` (measured, `captures/s12-attachment.jsonl:1-2`). The answer can instead be `{url}` when the file is already stored, and then the flow ends here (read, not observed, `private-protocol.md:259`).
2. `PUT <uploadURL>` with the bytes, `Content-Type: <mediaType>`, and no `Authorization` header. The answer is 200 with a JSON object that has `generation` (measured, `captures/s12-attachment.jsonl:3`).
3. `POST <origin>/api/attachments/resumable` with the same fields plus `action:"complete"` and `generation`. The answer is `{url:"<origin>/user-content/attachments/<sha>-<filename>"}` (measured, `captures/s12-attachment.jsonl:4-5`).
4. Send the URL in a `client_append_user_msg` image block (measured, `captures/s12-attachment.jsonl:68`).

Reading the attachment URL needs Bearer (measured, S12). Images load through `:sync`'s `AuthenticatedImageLoader`, which sends the account's Bearer token only to the account's origin and refreshes the token once on a 401.

If the upload breaks off, `:sync` asks the session for its committed size and continues from there [inferred: this is the Google Cloud Storage resumable protocol, not exercised in the spike]. When the direct upload fails or the server is a custom domain, the CLI sends the bytes through `POST <origin>/api/attachments/upload-proxy` with headers `x-amp-upload-url`, `x-amp-upload-reason`, `x-amp-upload-offset`, and `x-amp-direct-upload-status` (read, not observed, `private-protocol.md:259`). The app implements the proxy path but treats it as unverified.

## Portals have a working control plane and an unproven data plane

The control plane works with Bearer (S13):

1. `resolveThreadPortalURL{threadID, port}` returns `{url:"https://t-<id>-p<port>.onamp.dev/"}` (measured, `captures/s13-portal.jsonl:1-2`).
2. `mintPortalLoginURL{url}` returns `{url:"<portal>/__amp_auth/headless?code=...&state=...&return_to=...", expiresAt, oneTime:true}` (measured, `captures/s13-portal.jsonl:4-5`).
3. Opening the login URL answers 302 back to the portal root and sets the cookies `__Host-amp_portal_auth_v2` and `__Host-amp_portal_auth_top` (measured, `captures/s13-portal.jsonl:8`).

The data plane is INCONCLUSIVE. After login, the portal root answered 503 with an empty body (measured, `captures/s13-portal.jsonl:9`). The runner never received `tunnel_credentials`, so runner portals looked disabled for that account or runner (S13). An anonymous request to a portal answers 302 to `<origin>/api/portal-auth/authorize?...` (measured, `captures/s13-portal.jsonl:3`). `POST /api/portal-auth/embed` with Bearer returns the web app HTML, not JSON, so the app does not use it (measured, `captures/s13-portal.jsonl:6-7`).

The app opens the minted URL in the account's WebView profile ([Web pages run in a per-account WebView profile](auth-and-security.md#web-pages-run-in-a-per-account-webview-profile)). A 5xx from the portal shows "This portal is not reachable from the app" with **Open on web**.

## Web Push subscription goes over the thread socket

`client_upsert_notification_subscription{subscription{endpoint, keys{auth, p256dh}}, threadURL}` returns `{ok:true}` (measured, S11, `captures/s11-push.jsonl:64-65`). `threadURL` is `<origin>/threads/<threadId>`, so each upsert names one thread. No unsubscribe message exists. `/request/rpc` rejects the upsert (measured, `captures/s18-rpc-breadth.jsonl:22`), so it always needs a socket.

How the server stores subscriptions is unmeasured. S11 sent exactly one upsert (`captures/s11-push.jsonl:64`). The design assumes that a later upsert from the same endpoint replaces the earlier one [inferred]. If the server instead keeps one subscription per thread, a desktop browser and the phone would replace each other's subscription each time either one connects, and only the last to connect would get the push. That is an open question below.

While push is registered for the account, `:sync` sends the upsert on every new thread socket (`docs/design/sketch/sync/internal/Facts.kt`, `Effect.UpsertPushSubscription`). The endpoint in that upsert is the account's push relay URL, or a UnifiedPush endpoint on devices without Google Play services. `BackgroundAccess.decodePush` decodes the decrypted payload into a `PushEvent`. Push channels, keys, payload kinds, and display are in [Notifications](notifications.md), and the relay is in [Push relay](push-relay.md).

## Quirks

| Quirk | Evidence | What `:sync` does |
|---|---|---|
| An integer of 2^32 in Rivet JSON args fails with 500 `rivetkit` `internal_error`. | `captures/probe-json-int.jsonl:9-10` | Writes integers of 2^31 or more with a `.0` suffix on every channel. |
| User actor request ids come back as `["$BigInt","<n>"]`. | `captures/s14-user-actor.jsonl:16` | Decodes a number or the pair. |
| History replay and the connect burst arrive as bare `{type}` frames. Live events arrive as JSON-RPC notifications. | `captures/s09-transcript.jsonl:22-46`, `captures/s04-live-thread.jsonl:33` | Normalizes both shapes before decoding. |
| A non-JSON `pong` text frame arrives once after connect. | `captures/s17-resume-cursor.jsonl:79` | Ignores it. |
| Request results arrive twice, and the JSON-RPC result is `{ok:true}` even when the operation failed. | `captures/s19-files.jsonl:72-73` | Settles only on the `*_result` notification, an error response, or the 120 s timeout. |
| A resent append returns `ok` and repeats `message_added` with the original `seq`. | S16 | Upserts by `messageId`. Resends appends freely. |
| The connect burst repeats `error_set` with a `seq` at or below the resume version. | `captures/s17-resume-cursor.jsonl:157` | Upserts Sequenced events by key instead of dropping them by cursor. |
| `updateThread{title}` returns `ok` but does not change the title. | `captures/s10-thread-ops.jsonl:22`, `:42` | Renames with `client_set_thread_title`. |
| `listThreads` returns archived threads without `includeArchived`. | `captures/s10-thread-ops.jsonl:158` | Filters on `archived` client-side. |
| `listThreads` has no snooze field. | S10 | Keeps a local snooze ledger (`docs/design/sketch/core-model/ThreadState.kt`, `SnoozeState`). |
| Reads after writes lag by up to about 1 s. | S10 | Applies writes locally first. |
| Paged messages carry `messageId` as the numeric index and `protocolMessageID` as the `M-` id. | S9 | Keys messages by `protocolMessageID` on pages and `messageId` on events. |
| `anchorID` must be the `protocolMessageID` at index `before`, or the page is `status:"stale"`. | `captures/s09-transcript.jsonl:8-10` | Reloads the first page on `stale`. |
| `runnerId` in `POST /api/thread-actors` returns 403 "Runner attachment requires an API key and existing thread". | S4 | Spawns the runner with `client_spawn_executor` on an existing thread. Whether that spawn accepts an OAuth token is unmeasured (`auth-and-security.md:82`). |
| Internal RPC errors come with HTTP 200. | `captures/s19-files.jsonl:76` | Reads `ok`, not the status. |
| Internal RPC parameter names mix `thread` and `threadID`. | `captures/s09-transcript.jsonl:1`, `captures/s13-portal.jsonl:1` | Names each method's params in its `RpcMethod` encoder. |
| `/request/rpc` answers HTTP 200 with JSON-RPC `-32601` for 17 of 18 probed methods. | `captures/s18b-rpc-allowlist.jsonl:8-72` | Exposes only `appendUserMessage`. |
| `/api/threads/<id>/transcript-*` returns 404 with Bearer. | `captures/s09-transcript.jsonl:12`, `:14` | Pages with internal RPC only. |
| `POST /api/portal-auth/embed` with Bearer returns HTML. | `captures/s13-portal.jsonl:7` | Uses `mintPortalLoginURL`. |
| Attachment URLs return 401 without Bearer. | S12 | Loads images through `AuthenticatedImageLoader`. |
| `indicator.observedAt` is an HTTP date, not ISO 8601. | `captures/s15b-plugin-dialog.jsonl:65` | Parses RFC 1123 dates for this field. |
| `threadStatusUpdated` omits fields from event to event. | `captures/s14-user-actor.jsonl:23-40` | Merges over the last known summary. |
| `Init` has no `connectionToken`, although the RivetKit source describes one. | `captures/s14-user-actor.jsonl:15` | Does not depend on it. |
| A steer waits for the next tool boundary. | S5 | Acknowledges the send on `queued_message_added` and shows the steer in the queue strip until `queued_message_dequeued`. |
| Directory entries are positional tuples, and file reads carry no size. | `captures/s19-files.jsonl:36`, `:48` | Decodes tuples by position. |
| Result notification names drop the `_v2` and `_v3` suffixes. | `captures/s19-files.jsonl:36`, `:69` | Correlates by `requestId`, not by name. |
| The CLI updated itself during spike 2, from `0.0.1792728062-g40cf3f` to `0.0.1792730408-g23e163`. | `RESULTS.md:57` | Expects the server side to change without notice. See drift detection below. |

## Verdicts and fallbacks

| Capability the app depends on | Verdict | Evidence | App fallback |
|---|---|---|---|
| Device flow start | VERIFIED | S1 | None needed |
| Device flow token exchange and OAuth refresh | Read, not observed | S1, `private-protocol.md:68-69` | Personal access token ([A personal access token is the last resort](auth-and-security.md#a-personal-access-token-is-the-last-resort)) |
| Internal RPC envelope and error envelope | VERIFIED | `captures/s04-live-thread.jsonl:3-4`, `captures/s19-files.jsonl:76` | None needed |
| Gzip request bodies over 25,600 bytes | Read, not observed | `private-protocol.md:171` | On HTTP 400 or 415 for a gzipped body, resend it once uncompressed |
| Thread list and summaries | VERIFIED | S2 | None needed |
| Runner list | VERIFIED | S3 | None needed |
| New thread, runner spawn, live turn | VERIFIED | S4 | None needed |
| Orb spawn and worktree spawn field | Read, not observed | `docs/design/sketch/COVERAGE.md` | Show the server's error with **Open on web** |
| Steer and interrupt | VERIFIED | S5 | None needed |
| Queue a message while a turn runs (append without `steer`) | Read, not observed | S5 sent only `steer:true` | The M3 gate observes it. Until then, the message shows as queued from `queued_message_added` or as sent from `message_added`, whichever arrives. |
| Queue remove, reorder, steer-now | Read, not observed | `private-protocol.md:132` | On `-32601` or `-32602`, hide the action for the session and offer **Open on web** |
| Retry and edit | Read, not observed | `private-protocol.md:131-132` | Same as the queue actions |
| Send through `/request/rpc` | VERIFIED | S6 | Open a short-lived socket |
| Any other method through `/request/rpc` | NOT VERIFIED, rejected with `-32601` | S18 | Open a short-lived socket |
| Send dedupe by `messageId` | VERIFIED | S16 | None needed |
| Resume from `thread.data.v` | VERIFIED | S17 | Resume at 0 and upsert the replay |
| Transcript paging | VERIFIED | S9 | None needed |
| Pin, unpin, labels, rename on the socket, unread, read, snooze, archive | VERIFIED | S10 | None needed |
| Rename through `updateThread{title}` | NOT VERIFIED | S10 | Not used |
| Web Push upsert and delivery | VERIFIED | S11 | 15-minute background sync ([Subscriptions are per thread](notifications.md#subscriptions-are-per-thread)) |
| A later upsert replaces the earlier subscription | Inferred, one upsert measured | `captures/s11-push.jsonl:64` | The background sync and the user actor `indicator` |
| Push for a pending plugin dialog | NOT VERIFIED | S15c | User actor `indicator` and the background sync |
| Attachment upload and use | VERIFIED | S12 | None needed |
| Attachment upload proxy | Read, not observed | `private-protocol.md:259` | Show the upload error and keep the draft |
| Portal control plane | VERIFIED | S13 | None needed |
| Portal data plane | INCONCLUSIVE | S13 | **Open on web** after a 5xx |
| User actor socket and events | VERIFIED | S14 | Poll `getRecentThreads` |
| Tool approval queue and answer | INCONCLUSIVE, answer read, not observed | S15 | `UnsupportedGate` with **Answer on web** when the item fails to decode or the answer fails |
| Plugin commands and dialogs | VERIFIED | S15b | None needed |
| `plugin.ui.request_removed` | Read, not observed | Amp's docs | The next `plugin.ui.snapshot` drops the dialog on reconnect |
| Plugin dialog recovery after reconnect | VERIFIED | S15c | None needed |
| Live git status and diffs | VERIFIED | S7 | None needed |
| Recorded diff captures | NOT VERIFIED | S7 | Live git with an explicit wake ([Git and diffs](git-and-diffs.md)) |
| Terminal over the relay | VERIFIED | S8 | **Open on web** |
| Directory list, file read, file write v3 | VERIFIED. Writes measured at 15 bytes. | S19 | None needed |
| Ranged file reads | NOT VERIFIED | S19 | Read whole files |
| `getThreadFileURLs` | NOT VERIFIED | S19 | Read the file over the socket and save it locally |
| `client_reauthorize` | Read, not observed | `private-protocol.md:38`, `:120` | Reconnect with a new `wsToken` |
| `client_set_thread_open` | Read, not observed | `private-protocol.md:134` | **Open on web** |
| Rivet publishable token for a custom domain | NOT VERIFIED | `docs/design/auth-and-security.md:112` | Custom-server accounts are marked unverified at sign-in |
| Network log line for `agent-device network dump` | VERIFIED (parser run, no device) | S20 | None needed |

## Detecting protocol drift

Amp can change any of this without notice, as the mid-run CLI update in spike 2 shows (`RESULTS.md:57`). `:sync` detects drift at three levels.

### Every frame is counted, never thrown

- A text frame that is not `pong` and not JSON becomes `InboundFrame.Unparseable` (`docs/design/sketch/sync/internal/Transport.kt`, `InboundFrame`).
- An event type with no entry in `WireDecoders` becomes `ThreadFact.Unrecognized`. A known type that fails to decode becomes `ThreadFact.Malformed`. A Sequenced type without `seq` is `Malformed` (`docs/design/sketch/sync/internal/Facts.kt`, `ThreadFact`).
- A `*_result` notification for an unknown `requestId` is dropped and counted.
- A user actor frame with an unknown `body.tag` is counted.
- An internal RPC or HTTP answer in an unknown shape is `EngineError.Drift`.

Each count goes into `Degradation.Seen{unknownTypes, malformedTypes, count}` for the thread (`docs/design/sketch/core-model/ThreadState.kt`, `Degradation`). Debug builds log the type name under the `AmpWs` tag, never the body (`docs/design/verification.md:43`).

### Degraded is a link state the user sees

When a thread's `Degradation` is `Seen`, the link banner shows the Degraded state with the text "Some updates in this thread aren't shown in this version of the app." and the actions **Open on web** and **Dismiss** (`docs/design/screens.md`, link states). The transcript keeps working. Unknown tools render with `GenericToolCard`, and undecodable gates render as `UnsupportedGate`.

### Tests catch drift before users do

1. **Contract tests on every change.** Every capture in `spikes/protocol/captures/` replays through the parser and the reducer on the JVM. An unknown frame must produce `Unrecognized`, never an exception (`docs/design/verification.md:63`, `docs/design/sketch/sync/test/ThreadReducerReplayTest.kt`).
2. **Nightly live check.** A runner on a Mac and a dedicated test account run the flow in `docs/design/verification.md:71`, which creates a low-mode thread, sends one message, opens the terminal, reads the diff, and archives the thread. Auth adds its own steps (`docs/design/auth-and-security.md:390`). The live run fails when any of these transport checks fails:
	- The run saw any `Unrecognized`, `Malformed`, or `Unparseable` frame.
	- The set of connect-burst types differs from the Snapshot and Volatile lists in this document.
	- `client_resume` at `thread.data.v` replays a message.
	- `/request/rpc` accepts any probe method other than `client_append_user_msg`, or rejects that one.
	- `getRecentThreads` with `sinceMs` of 2^32 written as `4294967296.0` fails.
3. **Capture refresh.** The live run writes its frames in the capture format, redacted with the same rules as `lib.ts` `redact()`. A diff against the committed captures shows new fields and new types for review.

## Open questions

- Which Rivet publishable token does a custom domain need, and what happens to installed apps if Amp rotates the ampcode.com token?
- Where between 2^31 and 2^32 does Rivet JSON start rejecting integers, does exponent notation pass, and do thread actor JSON-RPC and internal RPC share the limit? The `.0` rule covers all of them until this is measured.
- Does a later upsert replace the earlier subscription for the same endpoint, and do a desktop browser and the phone evict each other's subscription for the same thread (S11)?
- Does an append without `steer` while a turn runs queue the same way a steer does, and does a resend of a still-queued message dedupe?
- Which Sequenced types besides `error_set` repeat in the connect burst at or below the resume version? `error_cleared` and `queued_message_*` were not observed there.
- What values does the server accept for `clientApplication.type`, and does it change behavior? The spike sent `"cli"`.
- How long does a thread `wsToken` live, and does `client_reauthorize` work on a live socket?
- What is `pong` answering, why was it missing on the first connection to a new thread, and does the actor answer WebSocket ping frames or close idle sockets?
- Does an absent field in `threadStatusUpdated` mean "unchanged" or "cleared"?
- What does the approval wire look like on a working model, and does an approval set the user actor `indicator` or send a push (S15)?
- Does Amp push for a pending plugin dialog when the thread is not in an error state (S15c)?
- Does a runner or orb portal serve content after `mintPortalLoginURL` on an account with tunnels enabled (S13)?
- Does the server accept gzipped internal RPC bodies from a non-CLI client?
- The sketch's `AmpHttp.transcribe` calls `/api/voice-transcription` and `AmpHttp.spaceToken` calls `/api/threads/<id>/space-token`, both `web-plain` (`private-protocol.md:267`, `:287`). They break the `cli`-only rule. Do they get a `cli`-tier path, move to a web surface, or become a documented exception?
