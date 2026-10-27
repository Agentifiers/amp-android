# Amp private client protocol (web app + CLI)

Sources, read-only:

- Web bundles: `ampcode.com's web bundle offset>`. Offsets are character offsets into the minified file.
- One chunk fetched live because it is not in the mirror: `https://ampcode.com/_app/immutable/chunks/e88f9517.js` (the desktop client, `WaymoteSession`). Cited as `e88f9517.js(live)`.
- CLI binary `~/.amp/bin/amp`, version `0.0.1792713681-g9b55f0` (Bun single-file executable). It embeds the CLI JS source as UTF-16LE text. Cited as `amp@<byte offset>` for UTF-8 hits and `amp(u16)@<byte offset>` for UTF-16 source hits. The binary was never executed.
- Live checks done by Main (not by me): Bearer `AMP_API_KEY` works on `POST /api/internal?getUserInfo` and on `POST /api/user-actor-credentials`.

Confidence labels: **high** means the code path was read end to end. **medium** means the shape was read but one link is inferred. **low** means a guess from names or fragments.

## Stability tiers

Every endpoint and method below carries one tier tag.

| Tier | Meaning | How tested |
|---|---|---|
| `cli` | The CLI binary uses the string. Old CLI builds in the wild call it, so the server must keep it working. Most stable. | String present in `~/.amp/bin/amp` as UTF-8 or UTF-16LE (`B.find(name)` over the file bytes). |
| `web-plain` | A plain, unhashed name or path that only the web app uses (REST path, Rivet action, WS message). Stable across web deploys, but nothing outside ampcode.com depends on it. | Present in web bundles, absent from the CLI binary. |
| `web-remote` | A SvelteKit remote function `/_app/remote/<hash>/<name>`. The hash is a per-build module id, so the URL can change on any web deploy. Least stable. | Pattern `(\`<hash>/<name>\`)` in web bundles. |

The thread-actor wire protocol is `cli`. 232 of the 233 message `type` literals in the web schema module `11958a39.js` are also in the CLI binary. The one exception is `client_puck_voice_stats`.

## Auth

### Browser (web app)

- **HTTP.** Same-origin `fetch` with no `Authorization` header. The session is a cookie. The internal-API client in the web build has a `getToken()` that returns `undefined` (`Wa={async getLatest(){return{settings:{url:window.location.origin},secrets:{async getToken(){}}}}}`), so no bearer is ever attached. ampcode.com's web bundle. Thread-actor token refresh uses `credentials:"include"`. `801.dfc6ad0a.js:51652` (`gb`), `62d3d593.js:404591`. Confidence high.
- **Cookie names.** Not found in the bundles. HttpOnly cookies are invisible to JS. [INFERENCE] The session cookie is HttpOnly.
- **Actor WebSockets** (thread, user, workspace actors). These do not use the cookie. Each one uses a short-lived JWT `wsToken` minted over cookie-authenticated HTTP and passed in Rivet connection params (see Thread actor sync). Confidence high.
- **wsToken claims** read by the web client: `cap` (`read` | `contribute` | `write`), `sid` (session id, also used to tell a `sudo` session from a normal `session`), `exp`, `iat`, `sxp` (session expiry), `userId`. `62d3d593.js:402301-402700`. Refresh is scheduled at `exp - min(30s + rand*15s, 20% of lifetime)` (`Qy=3e4,$y=15e3,eb=.2`). `62d3d593.js:402123`. Confidence high.
- **HTTP calls into an actor** (`fetchActor`) send `Authorization: AmpActor <wsToken>`. The token is refreshed when fewer than 30 s remain. `62d3d593.js:407921`. Confidence high.

### CLI and other native clients

- Every HTTP call sends `Authorization: Bearer <apiKey>`, where `apiKey` is the value in secret storage under `apiKey@<ampURL>` (`AMP_API_KEY` overrides). `f4769e5d.js:52500` (shared internal-API client: `Authorization:\`Bearer ${i}\``), `amp(u16)` `XT()` helper next to `zN` (user-actor credentials). Confidence high.
- When the stored `apiKey` is an OAuth JWT (`/^[\w-]+\.[\w-]+\.[\w-]+$/`), the client refreshes it before it expires (`pxe=120000`, 2 min) and retries once on 401. `f4769e5d.js:52500` (`st(a)`/`ct(a)` refresh-on-401), `amp(u16)@121808328` (region `q2n`). Confidence high.
- Thread actor connections from the CLI pass `wsToken` from `POST /api/thread-actors` as a Rivet conn param. Executor connections can instead pass `{apiKey}` or `{orbToken}`. The schema of `client_reauthorize` accepts exactly one of `wsToken | apiKey | orbToken`. `11958a39.js` (`client_reauthorize`), `amp(u16)` (`createClient` builds `{apiKey, threadId, executorClientId, transport:"json-rpc"}`). Confidence high.
- Actor HTTP routes accept the plain bearer too: `threadActor.get([id]).fetch("/inference-messages",{headers:{Authorization:\`Bearer ${apiKey}\`}})`. `amp(u16)` (`Z$i`). Confidence high.

### Token sources (tier `cli` unless noted)

| Endpoint | Request | Response | Tier | Evidence |
|---|---|---|---|---|
| `POST /api/thread-actors` | Bearer. JSON `{threadId?, usesThreadActors:true, executorType?, agentMode?, agentModeDisplay?, threadAgent?, runnerId?, parentThreadID?, projectID?, features?, multiplayerTTLSeconds?, threadMeta?, repositoryURL?, repositoryURLs?, credentialContextToken?}`. Omit `threadId` to create a thread. Include it to get credentials for an existing thread. | `{threadId, wsToken, ownerUserId, threadVersion, poolName?, capability?, usesThreadActors?, usesDtw?, executorType?, agentMode?}`. Errors carry `code`: `THREAD_ARCHIVED`, `THREAD_DELETED`, `ORB_CREATION_REAUTH_REQUIRED`, and HTTP 402. | `cli` | `amp(u16)` `Wa`/`Y$i`. high |
| `POST /api/thread-actors?threadId=<id>&capability=<read\|contribute\|write>` | Cookie (`credentials:include`). Body `{threadId, capability}`. | `{wsToken}`. 402/403/404 are fatal. A userId mismatch between old and new token is rejected. | `web-plain` | `62d3d593.js:404591`, `801.dfc6ad0a.js:51652`. high |
| `POST /api/user-actor-credentials` | Body `{capability?:"read"\|"write"}`. | `{userId, wsToken, poolName}` | `cli` | `7ff06b1e.js:44411` (`yn`), `amp(u16)` `zN`. Main verified it with Bearer. high |
| `POST /api/workspace-actor-credentials` | Body `{}`. | `{workspaceId, wsToken, poolName}` | `web-plain` | `7ff06b1e.js:45128` (`bn`). high |
| `POST /api/thread-actors/<id>` | Body `{executorType?}`. Marks an imported thread. | n/a | `cli` | `amp(u16)` `vGt`. high |

## CLI login flow

All of this is in the CLI source. `amp(u16)@121785000-121810000` holds functions `KY`, `F2n`, `Q2n`, `G2n`, `V2n`, `J2n`, `Y2n`, `ePn`, `$hr`, `fxe`. The legacy flow is at `amp(u16)@121987760` (`Sbt`, `fMn`, `mMn`, `dMn`). Tier `cli` for all of it. Confidence high.

1. **Discovery.** `GET <ampURL>/api/auth/oauth-config` returns `{clientID, apiURL, defaultLoginMethod:"oauth"|"api-key", deviceLoginPath?, cliCodeRedemption?:boolean}`. `apiURL` must be https, or http on localhost.
2. **Browser + loopback flow** (`amp login`, default when a browser is available and the session is not SSH-without-display):
   - The CLI listens on `127.0.0.1:<random port>` at path `/callback`. It generates `state` (32 random bytes, base64url) and a PKCE `verifier` (32 random bytes, base64url). `challenge = base64url(sha256(verifier))`.
   - It opens `<ampURL>/auth/cli/authorize?challenge=<c>&port=<port>&state=<state>`, plus `&redeem=amp` when `cliCodeRedemption`.
   - The browser comes back to `http://127.0.0.1:<port>/callback?code=<code>&state=<state>`. A `state` mismatch gives 400. A missing code or an `error` param fails the login. The wait times out after `W2n=600000` ms (10 min).
   - Code exchange:
     - If the code starts with `amp-auth.`: `POST <ampURL>/api/auth/cli-redeem` with `{code, verifier, redirect_uri:"http://127.0.0.1:<port>/callback"}`.
     - Otherwise: `POST <apiURL>/user_management/authenticate` as form data `{grant_type:"authorization_code", code, code_verifier, client_id}`. This is the WorkOS-style user-management API. [INFERENCE] The `apiURL` host is `auth.ampcode.com`, based on the OpenAPI `openIdConnectUrl`. The string is not in the binary.
   - Token response `{access_token, refresh_token, user?{email}}`. The client stores `apiKey=access_token` and `oauth-refresh-token=refresh_token`, both keyed by ampURL.
   - For non-`amp-auth.` codes, a best-effort `POST <ampURL>/api/auth/cli-complete` follows, with Bearer and `{challenge}`.
3. **Device flow** (`amp login --oauth` with no browser, or the fallback):
   - `POST <apiURL>/user_management/authorize/device` with form `{client_id}`. Response `{device_code, user_code, verification_uri, verification_uri_complete, expires_in=300, interval=5}`.
   - The shown URL is `<ampURL><deviceLoginPath>?user_code=…` when the server supplies `deviceLoginPath`. Otherwise it is `verification_uri_complete`.
   - Poll `POST <apiURL>/user_management/authenticate` with `{grant_type:"urn:ietf:params:oauth:grant-type:device_code", device_code, client_id}`. Handle `authorization_pending`, `slow_down` (+5 s), `access_denied`, `expired_token`, and 429/5xx with `Retry-After`.
4. **Refresh.** `POST <apiURL>/user_management/authenticate` with `{grant_type:"refresh_token", refresh_token, client_id}`. The client uses compare-and-set on stored secrets so concurrent processes do not double-rotate. `invalid_grant` forces a re-login.
5. **Logout.** `POST <ampURL>/api/auth/oauth-logout` with Bearer.
6. **Legacy API-key flow** (non-OAuth servers): open `<ampURL>/auth/cli-login?authToken=<random>&callbackPort=<port>`, where the port is the first free one from 35789. The web page calls remote function `linkCLI({authToken, callbackPort, regenerateKey})` (`web-remote`, `905.8472f22d.js:431`) and redirects to `http://127.0.0.1:<port>/auth/callback?authToken=…&accessToken=<key>` (or `apiKey=`). The manual fallback (`/auth/cli-login/manual`) shows a base64 JSON `{token, accessToken|key}` for the user to paste.
7. **Native-app OAuth for third-party connectors.** The web app routes `…/callback?appCallback=1` to the native shell via the `completeConnectOAuth` CustomEvent. Paths: `/auth/github/callback`, `/auth/github-app/callback`, `/auth/chatgpt/callback`, `/auth/workspace-ghe-cloud/callback`, `/auth/linear-app/callback`, `/auth/gmail/callback`, `/api/slack/oauth`. `1729d049.js:0`. There is also a route `/auth/app/callback` (`app.9efc7055.js` route table). Confidence medium. The shell side was not read.

**Android reuse.** Option 3 (device flow) needs no loopback server and no custom scheme. It works as-is with `client_id` from `/api/auth/oauth-config`. Option 2 needs a loopback listener on the device. `127.0.0.1` listeners work on Android, but the browser-to-app handoff is clumsy. [INFERENCE] Device flow plus `verification_uri_complete` opened in a Custom Tab is the cleanest path. It produces the same JWT `apiKey` and refresh token the CLI uses.

## Thread actor sync

Transport is RivetKit (`rivetkit` version `0.0.0-main.78336a1`, `62d3d593.js:64036`), with Amp's own JSON-RPC protocol running over a raw actor WebSocket. Tier `cli`. Confidence high unless noted.

### Endpoint and connect

- **Rivet endpoint.** `<ampURL>/actors`. The CLI builds `https://default:<publishable key>@ampcode.com/actors`, meaning Rivet namespace `default` and token `pk_9tm4qz…` in URL userinfo. Localhost gets no credentials. Staging uses a different key. `amp(u16)` `ou()`. The web probe hits `HEAD /actors/gateway/` and the presence client uses `new URL('/actors', origin)`. `801.dfc6ad0a.js:53355`, `18f05116.js:13101`. `RIVET_PUBLIC_ENDPOINT` overrides it in the CLI.
- **Gateway URL** (RivetKit `po()`):

  ```
  <endpoint>/gateway/threadActor/websocket/?<extra>&rvt-namespace=default&rvt-method=get&rvt-key=<threadId>&rvt-token=<pk>&rvt-skip-ready-wait=true
  ```

  `62d3d593.js:83934-85200` (`po`, `mo`), `137371` (`df`: raw websocket path prefix `/websocket/`). The web adds `?amp-browser-page-load-id=<uuid>` as `<extra>` (`tb`, `62d3d593.js:402144`). The CLI uses `.webSocket("/")`. Confidence medium. The exact query ordering and the `rvt-token` placement come from reading the library, not from a captured request.
- **WebSocket subprotocols** (RivetKit `vo()`, `62d3d593.js:85897`): `rivet`, `rivet_encoding.bare`, `rivet_conn_params.<encodeURIComponent(JSON.stringify(params))>`. Raw-socket encoding is fixed to `bare`, but Amp's payloads are JSON text frames, so the encoding only matters for Rivet's own envelopes. Header-style equivalents exist (`x-rivet-encoding`, `x-rivet-conn-params`, `x-rivet-token`, `x-rivet-target`, `x-rivet-actor`). `62d3d593.js:78303`.
- **Conn params.**

  | Client | Params | Evidence |
  |---|---|---|
  | Web | `{browserPageLoadID, wsToken, transport:"json-rpc", traceparent…}` | `62d3d593.js:403250` (`mb`) |
  | CLI observer | `{wsToken, clientApplication, interactiveClient, transport:"json-rpc"}` | `amp(u16)` `kk` |
  | CLI executor | `{apiKey \| orbToken, threadId, executorClientId, transport:"json-rpc"}` | `amp(u16)` `createClient` |

- **One-shot HTTP RPC.** `threadActor.get([id],{params:{wsToken,transport:"json-rpc"}}).fetch("/rpc",{method:"POST",body:<JSON-RPC>})`. This sends a single command without opening a socket. The CLI uses it for `client_append_user_msg`. `amp(u16)` class `iWt`. This is the cheapest path for a widget or notification-reply action.
- **Session roles.** `observer` (UI clients) and `executor` (the machine that runs tools). Session state is `{transport:{state, reconnectAttempt, reconnectCause}, identity:{role, requiredRole, clientId}, ready, resume:{state, version}, actorStopping}`. `62d3d593.js:239951` (`bg`). The executor role is confirmed by an `executor_connected` event. `62d3d593.js:282404`.

### Framing

- Every Amp message is `{type, ...fields}`. On the wire with `transport:"json-rpc"`:
  - client to actor: `{jsonrpc:"2.0", id:"thread-client-<ms>-<n>", method:<type>, params:<fields>}`, a request expecting a result. `62d3d593.js:241702` (`Ng`, `Pg`).
  - actor to client: notifications `{jsonrpc:"2.0", method:<type>, params}` and responses `{jsonrpc:"2.0", id, result|error{code,message,data}}`. Arrays (batches) are accepted. `62d3d593.js:5355-5900` (`an`/`on`/`sn`/`cn`/`hn`).
- The request timeout is 120 s (`vg=12e4`). JSON-RPC `-32603` internal errors on replayable messages trigger a reconnect and retry up to 3 times (`yg=3`). An `ACTOR_STOPPING` error means reconnect and retry. `62d3d593.js:239951`, `308225`.
- **Binary relay frames** share the same socket. Layout: `[0xAE, 0x01, kind(1=data to executor, 2=output from executor), flags(bit0 binary, bit1 fin), channelIdLen]`, then `channelId` (UTF-8, 1-255 bytes), then payload. The payload is chunked at 1,000,000 bytes and reassembled up to 16 MiB. `62d3d593.js:5900-7600` (`Dn`, `On`, `kn`, `An`).

### Snapshot vs delta, resume

- **Initial state is HTTP, not WS.** The web thread page loads SvelteKit `__data` (`/threads/<id>/__data?...`), which carries `thread`, `transcriptPage`, `threadActorConfig{baseURL, poolName, wsToken, ampURL, capability, directTerminalURL?}` and more. `21.4da66522.js:0-4200`. The CLI loads history with internal methods `getThreadInitialTranscriptPage`, `getThreadMessagesPage`, `getThreadTranscriptPage` (Transcript paging).
- **Resume.** The client sends `client_resume{version}` where `version` is the last applied `seq`, starting from `initialHistory.version`. The result is `{replayThroughSeq}`. The actor replays every event after `version`. Each event carrying `seq` advances the cursor. `62d3d593.js:265074` (`resumeFromVersion`), `308467` (`advanceResumeCursor`), `382331`.
- **Client-side model.** The actor sends no full-snapshot message. The web builds an `observerSnapshot` reducer (`{thread, session, executor{status, observers}, compaction, errors, …}`) from events. `9935e562.js:15390`. Streaming assistant output arrives as `delta` events with `blocks`, `blockIndex`, `contentMode` (`append`|`snapshot`) and `state` (`start`|`generating`|`tool_use`|`complete`|`error`|`aborted`). `11958a39.js:33035`, `20000`.
- **Replay policy on reconnect.** Each client message type is tagged `replay` (resent after reconnect) or `reject`. For example `client_filesystem_write_file_v3` and `client_reauthorize` are `reject`. `62d3d593.js:243766` (`zg`).

### Reconnection

- `ensureConnected` backoff uses base 3 s, max 30 s, 5 attempts (`hg/gg/_g`). The CLI uses exponential backoff with ×0.8-1.2 jitter (`y$i`). `62d3d593.js:254635`, `amp(u16)` `yGt`.
- Token expiry is handled by sending `client_reauthorize{wsToken}`. If that fails, the client replaces the transport and opens a new socket. `62d3d593.js:407289` (`refreshActorAuthorization`).
- Error codes on the `error` event: `ACCESS_DENIED`, `ACTOR_STOPPING`, `AUTHORIZATION_EXPIRED`, `CONNECTION_ERROR`, `MAX_RECONNECT_EXCEEDED`, `MESSAGE_ERROR`, `LOOP_RUNNING`, `NO_THREAD_ID`, `NO_ERROR`, `INVALID_MESSAGE`, `PARSE_ERROR`, `UNKNOWN_TYPE`, `INTERNAL_ERROR`, `THREAD_NOT_OPEN`. `11958a39.js:24451`.

### Message catalogue

Schemas live in ampcode.com's web bundle. The inbound union is `Vr` at `:18050`. The outbound union is `Fo` at `:37945`. Field names are exact. `?` means optional.

**Client (observer) to actor:**

| Area | Types |
|---|---|
| Messages | `client_append_user_msg{content, messageId, agentMode?, userState?, meta?, steer?}`, `client_edit_message{messageId, replacementMessageId?, editId, content, agentMode?}`, `client_truncate_thread{messageId}`, `client_set_message_reaction{messageId, reaction, active}`, `client_append_manual_bash_invocation{args{cmd,args?,cwd?}, run, hidden?}` |
| Queue / steer / interrupt | `client_remove_queued_msg{queuedMessageId}`, `client_reorder_queued_msgs{queuedMessageIds}`, `client_steer_queued_msg{queuedMessageId}`, `client_close_pending_batch{batchId}`, `client_cancel`, `client_retry`, `client_dismiss_active_error{seq?}`, `client_dismiss_recap{recapSeq}` |
| Approvals | `client_tool_approval_response{toolCallId, accepted, input?{askAnswers?, denyFeedback?}}` |
| Thread state | `client_update_thread_settings{settings}`, `client_update_thread_feature{feature, enabled}`, `client_set_thread_title{title}`, `client_set_thread_open{ttlSeconds: 300..604800 \| null}`, `client_mark_message_read/unread{messageId}`, `client_mark_thread_unread` |
| Comments | `client_add_thread_comment{commentId, anchorMessageId, content≤10000, selection}`, `client_add_discussion_comment{…anchorTextIndex}`, `client_set_thread_comments_resolved{commentIds, resolved}`, `client_set_thread_comment_reaction` |
| Presence / typing | `client_update_prompt_draft{text, revision}`, `client_update_comment_typing{selections, revision}`, `client_update_discussion_typing{discussionIDs, revision}` |
| Executor lifecycle | `client_spawn_executor{requestId?, repositoryURL?, repositoryBaseBranch?, projectID?, additionalRepositories?, environmentResource?, executor?{type:e2b\|runner…}, skipSetup?, executorDemand?, retryFailedSetup?, threadAgent?, agent?, agentSelection?}`, `client_orb_services_ensure{requestId}` |
| Files | `client_filesystem_read_directory[_v2]{requestId, uri\|path}`, `client_filesystem_read_file[_v2]{requestId, uri\|path, range?}`, `client_filesystem_write_file[_v2\|_v3]{requestId, uri\|path, contentBase64, overwrite(v3)}`, `client_filesystem_delete[_v2]{requestId, uri\|path, recursive}` |
| Git / changes | `client_git_command{requestId, operation}` (operations listed under Diffs), `client_git_diff_snapshot{requestId, baseRevision, headRevision, includeUntracked, content, repositoryRoot?}`, `client_changes_file_order{…}` / `client_cancel_changes_file_order`, `client_explain_diff_selection{…}` / `client_cancel_explain_diff_selection`, `client_request_capture{requestId}` |
| Terminal / relay / desktop | `client_terminal_open{terminalId, cols, rows}`, `client_terminal_input{terminalId, dataBase64}`, `client_terminal_resize`, `client_terminal_close`, `client_executor_relay_open{channelId, service, binaryFrames?}`, `client_executor_relay_data{channelId, dataBase64, binary}`, `client_executor_relay_close`, `client_desktop_viewing{display:"orb"\|"mac"}` |
| Puck voice | `client_puck_voice_start{version?:"v2"\|"v3", transport?{type:"webrtc",sdp}\|{type:"webrtc_attach",sessionID}, downloadFormat?:"pcm16_24k"\|"adpcm_ima_16k"\|"opus_24k", mediaKinds?, transcript?, binaryAudio?}`, `client_puck_voice_audio{audioBase64≤32000, format?}`, `client_puck_voice_stop`, `client_puck_voice_replay{events}`, `client_puck_voice_stats` (web-only) |
| Notifications | `client_upsert_notification_subscription{subscription{endpoint(url), keys{auth, p256dh}}, threadURL}` |
| Session | `client_resume{version}`, `client_reauthorize{wsToken\|apiKey\|orbToken}` |

**Executor to actor** (also in `Vr`; a native client only needs these to act as an executor): `executor_connect`, `executor_tools_register/unregister`, `executor_tools_bootstrap_complete`, `executor_tool_lease_ack`, `tool_progress`, `executor_tool_result`, `executor_guidance_snapshot/discovery`, `executor_skill_snapshot`, `executor_environment_snapshot/update`, `executor_system_info`, `executor_tool_approval_request`, `executor_plugin_message`, `executor_terminal_output/exit`, `executor_relay_output/exit`, `executor_*_result`, `executor_git_blob_chunk`, `bootstrap_*`.

**Actor to observers** (`Fo`):

| Area | Types |
|---|---|
| Agent loop | `agent_state{state, messageId?, agentMode?, agent?}` where state is one of `idle`, `compacting`, `working`, `streaming`, `tool_use`, `running_tools`, `awaiting_approval`, `error` (`11958a39.js:19261`). Also `inference_tools`, `delta`, `cancelled{seq}`, `retry_scheduled{retryAt, attempt, maxAttempts, reason}`, `retry_started`, `retry_cancelled`, `error`, `error_set{seq, error}`, `error_cleared{seq}` |
| Transcript | `message_added{message, seq, parentToolUseId?}`, `message_updated{message, seq}`, `edit_rejected{editId, message}`, `thread_truncated{seq, truncateFromMessage}`, `compaction_started`, `compaction_complete{cutMessageId?}`, `compaction_records`, `compacting_subagents`, `background_work{active}`, `background_subagents{subagents[{toolUseID, description}]}` |
| Queue | `queued_messages{messages, openConversationBatchId?}`, `queued_message_added/removed/dequeued{…, seq}` |
| Approvals | `tool_approval_queue{approvals}` |
| Thread meta | `thread_settings`, `thread_features`, `thread_title{title, source:"generated"\|"manual"}`, `thread_recap{recap{text≤400, messageID, generatedAt}\|null, recapSeq}`, `thread_open_state{openExpiresAt\|null, openStateSeq?}`, `thread_status{status:"merging"\|"merged"\|…}` |
| Multiplayer | `observers{count, observers[{userId, name, avatarURL?}], hasExecutor?, executorType?, directTerminalAvailable?, file2URIsAvailable?, gitBlobHTTPAvailable?, relayDesktopAvailable?, macDesktopAvailable?, macDesktopStopped?}`, `prompt_drafts{drafts[{connectionId, userId, text}]}`, `comment_typing`, `discussion_typing`, `thread_comments`, `discussion_comments`, `mention_seen_receipts` |
| Executor | `executor_status{spawnId?, status:"starting"\|"running"\|"failed", message?, executorId?, details?{reasonCode, executionEnvironment{stage, setupState, setupPhase, provider:"e2b"\|"orb-provider"\|"macos"\|"runner", providerState…}, actions[{type:"billing",label}], orbCreation{spawnId,status,message}, usageAvailability}}`, `executor_connected`, `environment_update`, `executor_health{revision, issues[workspace_disk\|…, severity:"warning"\|"critical"], lastOOMKill?}`, `executor_workspace_maybe_changed{toolCallId, toolName}`, `tunnel_credentials{gatewayURL, token, tunnelID, expiresAt, ingressToken}` |
| Request results | `client_filesystem_*_result`, `client_git_command_result`, `client_git_diff_snapshot_result`, `client_changes_file_order_result`, `client_explain_diff_selection_result`, `client_request_capture_result`, `client_orb_services_ensure_result`, `client_terminal_output{terminalId, dataBase64}`, `client_terminal_exit`, `client_executor_relay_output`, `client_executor_relay_exit` |
| Puck | `puck_voice_state{status:"connecting"\|"listening"\|"thinking"\|"ended"\|"error", userSpeaking, puckSpeaking, …}`, `puck_voice_audio{audioBase64, sampleRate:24000\|16000, format?}`, `puck_voice_transcript{delta}`, `puck_platform_navigation{destination: thread \| thread_view(portal\|diffs) \| file(diffs\|files) \| portal(url)}`, `puck_media_presentation{presentation{id, title?, items[{id, kind:image\|video, url, mimeType?, caption?, expiresAt?, sizeBytes?}]}\|null}` |
| Plugins | `plugin_message{message}`. Plugin UI events (`plugin.ui.snapshot`, `plugin.ui.request_added`, …) per DigestCustomizeEnterprise. Not re-verified here. |

## User actor (sidebar, feed, unread, drafts, dictation lock)

- Connect with `userActor.get([userId],{params:{type:"user", wsToken}})` on the same `/actors` endpoint. Use read credentials for the long-lived connection. Writes get a fresh `capability:"write"` token and go through a stateless action call: `POST <endpoint>/gateway/userActor/action/<name>?rvt-…` with header `x-rivet-encoding` and `x-rivet-conn-params`. `1cdee55b.js:12819`, `62d3d593.js:140099`. Tier `cli` for every action below (all present in the CLI binary). Confidence high.
- Reads (actions): `listRunners`, `listDrafts`, `listFeedItems`, `listFollowedThreadActivity`, `listThreadMentions`, `listUnreadThreadMentionStatuses`, `listThreadCommentUnreads{threadId}`, `getRecentThreads{limit, sinceMs}`, `getResourceRevisions`, `getDictationState`.
- Writes (actions): `setDraft`, `markFeedItemSeen{itemId}`, `markThreadFollowSeen{threadId}`, `markAllThreadMentionsSeen`, `markThreadMentionSeen{mentionId}`, `markThreadCommentsRead`, `setThreadMentionMessageUnread`, `setThreadPinned{threadId, pinned}`, `recordInteraction`, `reportVisibility{visible, platform, nativePlatform, threadId, present}`, `claimDictation`, `releaseDictation`.
- Events: `threadStatusUpdated`, `draftUpdated`, `feedItemUpdated`, `threadMentionUpdated`, `threadMentionStatusUpdated`, `followedThreadActivityUpdated`, `threadCommentUnreadsUpdated`, `resourceRevisionUpdated`, `spaceInvitationReceived`, `spaceInvitationDeclined`, `dictationStateUpdated`. `1cdee55b.js` `.on()` list. Thread summaries arrive through `observeThreadSummaries` (`7ff06b1e.js:25305`, `43982`).

## Internal RPC

- **Wire.** `POST <ampURL>/api/internal?<method>` with body `{"method":<method>,"params":<object>}` and `Content-Type: application/json`. Bodies over 25,600 bytes are gzipped with `Content-Encoding: gzip`. The response is `{ok:true, result}` or `{ok:false, error{code, message, …}}`. HTTP errors carry the same envelope. `f4769e5d.js:53756` (`Ba`, `Va`), `amp(u16)` `lRe` (literal `/api/internal?getUserInfo`). Extra headers come from `je(N())`, client identification headers that were not decoded. Tier `cli`. Confidence high.
- **Method table.** The CLI embeds the zod schema table `r6t = {method: [paramsSchema, resultSchema], …}` with **271 methods**. It runs from `getSystemHealth` to `googleCalendarWrite` and includes `getUserInfo`, `mcpAppCallTool` and `mcpAppReadResource`, which are confirmed live or in the web bundle. Region `amp(u16)@~130,200,000-130,420,000`. Appendix A lists every method with its parameter keys. ✓ marks the 122 methods the CLI source itself calls (`Or.<m>(` / `hi.<m>(` call sites). Unmarked methods are called by other actors: server-side executors, orb spawners, Puck workers, the macOS app. Many of those need special credentials (orb tokens, actor tokens). [INFERENCE] Do not assume a user key can call them.
- **The web app barely uses `/api/internal`.** Only `mcpAppReadResource` and `mcpAppCallTool` were found (`f4769e5d.js:54500`). Everything else in the web app goes through SvelteKit remote functions (`web-remote`, Appendix B, 931 names).
- **Key client-relevant methods** (all `cli`, parameter keys exact):
  - Threads: `listThreads✓(excludeLabelNames?, excludeCreatedByThreadAgentModes?, includeArchived?, includeEmpty?, installationID?, limit?, offset?, threadIDs?)`, `getThread(thread)`, `getThreadMeta✓(thread)`, `setThreadMeta✓(thread, meta)`, `updateThread✓(thread, title?, pinned?, color?, visibility?, archived?, snooze?, labels?)`, `archiveThread✓(thread, archived, selfArchive?)`, `deleteThread✓(thread)`, `getThreadLabels✓/setThreadLabels✓/addThreadLabels✓(thread, labels)`, `threadDisplayCostInfo✓(threadID, …)`, `createRemoteExecutorThread✓(content, agentMode?, runnerId?, projectID?, repositoryURL?, environmentResource?, orbProvider?, labels?, multiplayerTTLSeconds?, title?, …)`, `sendMessageToThread✓(threadID, content, steer?, messageId?, userState?, …)`.
  - Transcript: `getThreadInitialTranscriptPage✓(thread)`, `getThreadMessagesPage✓(thread, fromIndex, limit)`, `getThreadTranscriptPage✓(thread, before, anchorID)`, `getThreadTail✓(thread, limit)`.
  - Projects / runners / orbs: `listAccessibleProjects✓`, `getProject✓(projectID)`, `getThreadProject✓(thread)`, `resolveProjectByRepositoryURLs✓`, `listRunners✓(capability?)`, `shareRunner✓`, `revokeRunnerShare✓`, `listRunnerShares✓`, `getOrbSystemMetrics✓(threadID)`, `restartOrbProcesses✓(threadID)`, `getMacSidecar✓(threadID)`.
  - Portals: `resolveThreadPortalURL✓(threadID, port)`, `setThreadPortalHostname✓(threadID, port, hostname, takeOver?)`, `mintPortalLoginURL✓(url)`.
  - User / settings: `getUserInfo✓`, `listAgentModes✓`, `getAgentModelCatalog✓`, `userDisplayBalanceInfo✓`, `getDictationVocabulary✓/setDictationVocabulary✓`, `listMCPServerApprovals✓/approveMCPServer✓`.
  - Notifications (server-side senders, not user-callable as far as can be told): `dispatchUserNotification(threadID, kind, title, body, url, activeNativePlatform?, interruptionLevel?, …)`, `dispatchThreadMentionNotification(…)`, `getNativeAppIdentity()`.

## Transcript paging

| Endpoint | Params | Response | Tier | Evidence |
|---|---|---|---|---|
| `GET /api/threads/<id>/transcript-page` | `before=<idx>&anchorID=…`, or `after=<idx>&anchorID=…&limit=`, or `around=<idx>&limit=` | `{messages, bounds{firstMessageIndex, firstMessageID, lastMessageIndex, lastMessageID, hasMoreBefore, hasMoreAfter}}`. **409 = stale** (the version moved; reload). | `web-plain` | `cc840d97.js:2719`, `900-2400`. high |
| `GET /api/threads/<id>/transcript-range` | `start, end, anchor, anchorID` (expands collapsed turns) | `{messages}`, 409 = stale | `web-plain` | `2b810fbd.js:930`, `6f693bfc.js` (`Vu`). high |
| `GET /api/threads/<id>/transcript-outline` | none | `[{messageID, messageIndex, text, senderUserID\|null, timestamp\|null, humanConversation}]` | `web-plain` | `801.dfc6ad0a.js:477754` (`HI`). high |
| `GET /api/threads/<id>/transcript-search` | `q, offset` | JSON (shape not decoded) | `web-plain` | `cc840d97.js:3419`. medium |
| `GET /api/threads/<id>/message-locations` | repeated `id=<messageID>`, batches of 50 | `[{messageID, messageIndex}]` | `web-plain` | `801.dfc6ad0a.js:410565`. medium |
| `POST /api/threads/<id>/selections` | `{selection{v:1, parts[{rowID, messageIndex, start, text}]}}` | shareable selection link | `web-plain` | `cc840d97.js:23038`, `11958a39.js:2687`. medium |
| internal `getThreadInitialTranscriptPage` / `getThreadMessagesPage` / `getThreadTranscriptPage` / `getThreadTail` | see Internal RPC | first page `{thread{messages}, firstMessageIndex, firstMessageID, hasMoreBefore}`. Paged calls can return `status:"stale"`. | `cli` | `amp(u16)` (`getThreadInitialPageFromServer`, `getThreadPageFromServer`). high |
| `GET /api/v2/threads/{threadID}/messages` | public OAuth API | see `amp-openapi.json` | public | Amp's public OpenAPI description |

## Terminal

Three transports, tried in this order (`62d3d593.js:406300-408600`, `openTerminal`). Confidence high.

1. **WebRTC data channel** to the executor, when enabled. ICE servers come from remote function `getWebRTCIceServers` (`web-remote`, `10.6a2d8696.js`). The data channel carries the same relay framing as option 3. `62d3d593.js:390488-392600` (`Ly`).
2. **Direct portal WebSocket**, when `observers.directTerminalAvailable` is true and `threadActorConfig.directTerminalURL` is set.
   - URL: `directTerminalURL` with its path replaced by `/.amp/terminal` and the scheme switched to ws(s).
   - Subprotocols: `["amp-terminal-v1", <wsToken>]`. `62d3d593.js:385475` (`xy`, `Sy`), `11958a39.js:18906` (`Hr`, `Ur`).
   - Preconnect pool of up to 7 sockets per tab, idle for 10 min (`wy=7`, `Ty=6e5`).
3. **Actor relay** over the thread socket: `client_executor_relay_open{channelId, service:"amp-terminal-v1", binaryFrames:true}`, then binary relay frames. `62d3d593.js:385475` (`gy`).

**Terminal framing** is the same for all three:

- Client sends text JSON `{"type":"open","cols","rows","sessionKey"?:[A-Za-z0-9_-]{1,128},"initialCommand"?:≤8192 chars}`, then `{"type":"resize","cols","rows"}`.
- Server sends text JSON `{"type":"ready"}` or `{"type":"exit","error"?}`.
- Binary frames are raw PTY output, server to client.
- Client input goes as UTF-8 binary chunks of 49,152 bytes (`Gr=49152`). Up to 196,608 bytes (4 chunks) are buffered before `ready`. There is a 10 s connect timeout.
- `sessionKey` reattaches to an existing PTY (tmux-like persistence). [INFERENCE]

Legacy message-based terminal: `client_terminal_open/input(dataBase64)/resize/close` paired with `client_terminal_output/exit`. Tier `cli` for the constants and message types. The `directTerminalURL` source is `web-plain`, because it comes from SvelteKit `__data` and does not appear in the CLI. [INFERENCE] A native client that cannot get `directTerminalURL` should use the actor relay. It needs only the thread wsToken.

## Diffs / Changes pane

- **Live (executor connected)**, over the thread socket (`cli`):
  - `client_git_command{requestId, operation}`. `operation.type` is one of `repository_root`, `head`, `branch`, `status`, `status_snapshot`, `comparison_base_ref`, `comparison_base_head`, `ahead_count`, `behind_count`, `ahead_commits`, `remote_branches{query?}`, `branch_comparison_base{branch}`, `live_sync_git_state{headHash, baseCandidateHashes, preferThreadBase?}`, `diff_range_snapshot{baseHash, headHash?}`, `file_diff{path, changeType, full}`, `stage_file{path}`, `unstage_file{path}`, `file_diff_in_repository`, `stage_file_in_repository`, `unstage_file_in_repository`. The result is `client_git_command_result{ok, exitCode, stdout, stderr, error?}`. `11958a39.js:10485`.
  - `client_git_diff_snapshot{baseRevision, headRevision, includeUntracked, content, repositoryRoot?}` returns a snapshot `{provider, capturedAt, repositoryRoot, repositoryName, branch, head, baseRevision, headRevision, includeUntracked, content, diffHash, files[{id, path, previousPath?, changeType, old{kind:"file",path,blobSHA,mode,sizeBytes}\|{kind:"absent"}, new, isBinary, diffStat{added, deleted}}]}`. `3a8e98d6.js` (`Pt`, `Xn`, `Qn`).
  - Blob content: `executor_git_blob` / `executor_git_blob_chunk{offset, data, done, status:200|404|413|500}` relayed, or HTTP to the actor when `observers.gitBlobHTTPAvailable` (with `AmpActor` auth).
  - AI ordering: `client_changes_file_order{…}` returns `{sections≤42}` with `{sectionID, diffHash, codeTour?, summary?, orderedPaths}` (`3a8e98d6.js` `In`/`Ln`).
  - Explain selection: `client_explain_diff_selection{path, side, startLine, endLine, fileStatus, selectedCode, contextScope, fileHead, contextBefore, contextAfter}` returns `{explanation}` (`Hn`/`Un`).
  - Refresh triggers: `executor_workspace_maybe_changed`, and `workspaceMaybeChangedRevision` in the snapshot.
- **Recorded captures (no executor needed):**
  - `GET /api/threads/<id>/diff-captures/latest`. Uses ETag / `If-None-Match`. 304 means unchanged, 204 means no capture. The body is `{ranges[{id, sections[{id, files[{id, path, previousPath?, diffStat, isBinary, oldBlobSHA?, newBlobSHA?}]}]}]}`. Tier `cli`. `27378598.js:4160`. high
  - `GET /api/threads/<id>/diff-captures/blob/<sha>/raw` returns raw bytes. 404 means missing, 413 means too large. The client diffs old and new blobs itself. Tier `cli`. `27378598.js:7249`.
  - `GET /api/threads/<id>/diff-captures/diff?from=<sha>&to=<sha>` returns `{diff}` and is used for large files. Tier `web-plain`. `27378598.js:7878`.
- **Project repository (no thread):** public External API v2 `GET /api/v2/workspace/projects/{projectID}/repository/{diff,contents,…}`.
- **Ship:** `web-remote` `startThreadShipping`, `cancelQueuedThreadShipping`, `setRequireShipAndPushConfirmation`. Internal `resolveShipWorkflowPrompt(thread)` exists but the CLI does not call it. The project field `customShipPrompt` and `changesWorkflow` live on `updateProject✓`. [INFERENCE] There is no `cli`-tier "ship" call. Ship is a prompt sent through `client_append_user_msg` plus web-remote bookkeeping.

## Portals

- **Hostname scheme.** `https://t-<base36(threadUUID without "T-")>-p<port>.<portalDomain>/`, where the base36 value is padded to 25 characters. The default `portalDomain` is `onamp.dev` (`AMP_PORTAL_DOMAIN` overrides). Custom portal hostnames match `<prefix>--<namespace>.<domain>`. `1f38093b.js:700`, `6cc4539f.js:0`. Tier `cli` (`onamp.dev` is in the binary). Confidence high.
- **Auth for embedding.**
  - `POST /api/portal-auth/embed` with `{url, reviewThreadID?, capabilityOnly?}` returns `{url:<authenticated portal URL>, threadID, port, reviewCapability?}`.
  - 400 `{"error":"Invalid portal URL"}` means not a portal.
  - The web retries at most 3 times. Denied access lands on `/portal-auth/denied`.
  - Tier `web-plain`. `f3238452.js:3172-6105`. high
- **CLI path.** Internal `resolveThreadPortalURL✓(threadID, port)` and `mintPortalLoginURL✓(url)`. [INFERENCE] The second returns a one-time login URL that sets the portal cookie. Tier `cli`. Medium.
- **Tunnels.** The `tunnel_credentials{gatewayURL, token, tunnelID, expiresAt, ingressToken}` event and the internal `getTunnelCredentials(threadID)` back CLI-local portals (`amp net`, exit nodes). Not needed by a viewer.
- **Native:** load the URL from `/api/portal-auth/embed` or `mintPortalLoginURL` in a WebView. Portal content is arbitrary web content, so it has no non-web path by nature.

## Desktop

- **Client.** `WaymoteSession` (`48432b1e.js:58756` lazy-loads `e88f9517.js`), a custom Wayland remote-desktop protocol. The executor side runs `/opt/amp-desktop/start` under wlroots headless (`WLR_BACKENDS=headless`, …) as systemd unit `amp-desktop.service`. `48432b1e.js:2034`, `amp@113217176`. Not VNC. WebRTC only as a transport.
- **Endpoint.** Port `58322` on the portal hostname: `https://t-…-p58322.onamp.dev/`. Three WebSockets: `/stream` (video), `/audio`, `/control`. Subprotocols `["amp-desktop-v1", <token>]`. The macOS sidecar uses paths under `/mac` and subprotocol `amp-mac-desktop-v1`. `48432b1e.js:1828`, `53583`. Tier `cli` (constants present). high
- **Transports**, in order: a WebRTC data channel through the executor relay, an actor relay (`ws://executor-relay.invalid` placeholder URL with relay service channels), or a direct portal WebSocket. `48432b1e.js:61502`, `46404`.
- **Wire** (`e88f9517.js(live)`):
  - **Video frames.** Binary, header ≥ 40 bytes. `byte0=2`, `byte1=1`, `byte2` flags (bit0 keyframe). Decoded with WebCodecs `VideoDecoder`. The codec string arrives in a JSON config message. The client sends `{type:"ping",id}` and `{type:"feedback", queue, dropped, fps, rtt, active}`. high
  - **Audio frames.** Binary, 24-byte header. `byte0=2`, `byte1=2`, u64 LE timestamp at offset 12, u32 config generation at offset 20. Opus decoded with `AudioDecoder`. high
  - **Control.** JSON `type` values: `key`, `text`, `clipboard-write`, `display-select{id}`, `reset`, `samples`, `ping`. The server reports `display-presented` and the display list `{displays, selectedID, generation, switching, error}`. Resolution snaps to multiples of 16 within 320×180..6000×6000 and at most 8,294,400 pixels. Medium (pointer messages not decoded).
- **Viewer signal.** The client sends `client_desktop_viewing{display:"orb"|"mac"}` on the thread socket. Availability shows in `observers.relayDesktopAvailable` and `macDesktopAvailable`.

## Attachments

| Path | Flow | Tier | Evidence |
|---|---|---|---|
| CLI | `POST /api/attachments/resumable` `{action:"prepare", jobID, mediaType, sizeBytes, filename?, publicArtifact?, temporaryFile?, threadID?}` returns `{url}` (already stored) or `{uploadURL}`. Upload bytes to `uploadURL`. Then `{action:"complete", …}` returns `{url}`. On custom domains or direct-upload failure, send bytes through `POST /api/attachments/upload-proxy` with headers `x-amp-upload-url`, `x-amp-upload-reason`, `x-amp-upload-offset`, `x-amp-direct-upload-status`. | `cli` | `amp(u16)` `xP`, `ipi`, `npi`, `QQt`. high |
| Web | remote commands `createPromptAttachmentUpload{mediaType, sizeBytes, filename, threadID, temporaryFile}` → `{uploadID, uploadURL}`, `getPromptAttachmentUploadStatus{uploadURL, sizeBytes}` → `{state, nextOffset}`, `completePromptAttachmentUpload{uploadID, expectedSizeBytes, threadID, temporaryFile, proxyReason}` → `{url}`. Same resumable PUT plus upload-proxy fallback, 60 s stall timeout, retries. | `web-remote` | `2214b539.js:0-3273`. high |
| Downloads | `/api/artifacts/<filename>` (`cli`). Internal `getThreadFileURLs(threadID, paths)` and `resolveUserContentFileToPublicURL` (`cli` names). Web `publishThreadFileForDownload` (`web-remote`). | mixed | `amp(u16)`, Appendix B |

The resulting `url` goes into the `client_append_user_msg` content blocks.

## Dictation

- **Transcription.** `POST /api/voice-transcription`, `multipart/form-data`, field `audio` (`dictation.webm|mp4`) plus timing fields. Streaming mode sends the multipart body as a `ReadableStream` (`duplex:"half"`) while recording. It retries on 408/502/503/504 up to 3 times and falls back to a blob upload. The response is `{text}`. 402 means out of credits. Tier `web-plain` (the CLI has no dictation). `2c3ac269.js:20718`, `c2703be1.js:564`. high
- **Cross-device lock.** User-actor actions `claimDictation`, `releaseDictation`, `getDictationState` and event `dictationStateUpdated`. These hold a single active dictation per user across devices. Tier `cli` names. `1cdee55b.js`.
- **Vocabulary.** Internal `getDictationVocabulary✓` / `setDictationVocabulary✓(operation, vocabulary)` (`cli`). Settings: `setDictationEnabled` (`web-remote`). Workspace kill switch: `updateWorkspaceDisableDictationSetting` (`web-remote`).
- **Diagnostics.** `/api/dictation-report` (`web-plain`).
- **Puck voice** (live voice agent) is separate. It uses `client_puck_voice_*` on the thread socket with WebRTC SDP or base64 PCM/ADPCM/Opus frames (Thread actor sync catalogue).

## Notifications / presence

- **Push.** The web uses only Web Push. It registers `/service-worker.js`, subscribes with a VAPID `applicationServerKey`, and sends `client_upsert_notification_subscription{subscription{endpoint, keys{auth,p256dh}}, threadURL}` on the thread socket, once per thread the user wants alerts for. `d57aad74.js:8049`. Tier `cli` for the message type. No FCM strings in the web bundle. Native iOS/macOS use APNs via the server-side `dispatchUserNotification(…, activeNativePlatform?, interruptionLevel?)` and `getNativeAppIdentity` (internal table). [INFERENCE] Android has no first-party push channel today. Options are polling the user actor feed, or registering a Web Push endpoint the device controls (a UnifiedPush distributor gives an RFC 8030 endpoint) through `client_upsert_notification_subscription`. Whether the server accepts non-browser push endpoints is unknown.
- **Settings.** `getUserNotificationSettings`, `setNotificationPreferencesCommand`, `listUserNotificationHistory`, `updateNotificationThreshold` (`web-remote`).
- **Presence.**
  - Per thread: the `observers` event lists connected viewers, `prompt_drafts` carries live composer text, and `comment_typing` / `discussion_typing` carry typing state (`cli`).
  - Per user: `reportVisibility`, `recordInteraction`, `subscribeInteractions` → `interactionsUpdated` (staff) (`cli` except `subscribeInteractions`).
  - Per workspace: workspace actor `workspaceActor.get([workspaceId],{params:{type:"user", wsToken, presenceSessionID}})` with actions `getPresenceSnapshot`, `getUserPresenceSnapshot`, `setUserPresence`, `getChatSnapshot`, `getChatReadSequence`, `markChatRead`, `sendChatMessage`, and events `threadPresenceUpdated`, `userPresenceUpdated`, `chatMessageAdded`, `chatReadUpdated`. Tier `web-plain`. `7ff06b1e.js:12964-17425`. high

## Multiplayer

- **Open a thread to collaborators.** `client_set_thread_open{ttlSeconds}`, 300 s to 7 days, or `null` to close. State echoes back in `thread_open_state{openExpiresAt, openStateSeq}`. Tier `cli`. Web-remote counterparts: `setThreadMultiplayerCommand`, `requestThreadMultiplayerCommand`, `dismissMultiplayerEnableNotice`, `updateThreadExpiry`. The internal `setPluginThreadMultiplayer✓(threadID, ttlSeconds)` exists for plugins.
- **Capabilities.** A contributor's wsToken carries `cap:"contribute"`. The web treats "contribute + thread open" as access. `21.4da66522.js:1306` (`viewerHasActiveContributeAccess`). Read-only viewers get `cap:"read"`. Access requests: `requestThreadAccessCommand`, `listThreadAccessRequestsQuery`, `dismissThreadAccessRequestCommand` (`web-remote`).
- **Collaboration primitives** (`cli`): `thread_comments` / `client_add_thread_comment`, discussions, reactions, `mention_seen_receipts`, `prompt_drafts`, and `observers`. Followers: `setThreadFollowCommand`, `inviteThreadFollowersCommand`, `removeThreadFollowerCommand` (`web-remote`). Internal `setThreadFollow`, `listThreadFollowers` exist but the CLI does not call them.
- **Spaces and calls.** `POST /api/threads/<id>/space-token` with `{action:"reconcile"}` or `{action:"invite", userID}`. The space call is LiveKit (`e8c6fe35.js:529001`). The string `space-token` is in the CLI binary, but only in a secret-scanner list, so the honest tier is `web-plain`. Spaces data: `updateSpace` (internal and remote), `listSpaceDiscussions`, `generateSpaceCatchup` (`web-remote`).

## Feature → tier map

| Client feature | Buildable on `cli` tier | Needs `web-plain` | Needs `web-remote` |
|---|---|---|---|
| Thread list / search | internal `listThreads✓`, `GET /api/threads/find?q=&limit=`, user actor `getRecentThreads` + `observeThreadSummaries`, `listRunners` | none | rich sidebar groups (`listThreadListSidebar`, `listSidebar*`) |
| Open / live thread | `POST /api/thread-actors {threadId}` → wsToken + poolName. Rivet `/actors` gateway WS, JSON-RPC, `client_resume`. History via internal `getThreadInitialTranscriptPage✓` etc. | `/api/threads/<id>/transcript-*` (nicer paging, outline, search) | none |
| Send / steer / queue / interrupt | `client_append_user_msg{steer}`, `client_steer_queued_msg`, `client_remove_queued_msg`, `client_reorder_queued_msgs`, `client_cancel`, `client_retry`, `client_edit_message`. One-shot `/rpc`. Internal `sendMessageToThread✓`. | none | none |
| New thread | `POST /api/thread-actors` (no threadId) or internal `createRemoteExecutorThread✓` | none | `createProjectThread` (web flow) |
| Approvals | `tool_approval_queue` event + `client_tool_approval_response` | none | none |
| Changes / diff | `client_git_*`, `client_git_diff_snapshot`, `diff-captures/latest`, `diff-captures/blob/<sha>/raw` | `diff-captures/diff` | `getChangesPrompts`, `setBuiltinPromptShownInChanges` |
| Files | `client_filesystem_*_v2/_v3`, internal `getThreadFileURLs` | none | `getProjectFileTree`, `getProjectFileList`, `getProjectReadme` (projects page); public v2 repository API covers project files |
| Terminal | relay `client_executor_relay_*` + `amp-terminal-v1` framing | `directTerminalURL` (from `__data`) | `getWebRTCIceServers` (WebRTC path only) |
| Portals | internal `resolveThreadPortalURL✓`, `mintPortalLoginURL✓`, hostname scheme | `/api/portal-auth/embed` | portal settings (`setPortalHostname`, `setPortalPublicAccess`, …) |
| Desktop | `amp-desktop-v1` constants, relay, `client_desktop_viewing` | none | `getWebRTCIceServers` (WebRTC path only) |
| Attachments | `/api/attachments/resumable` + `upload-proxy` | none | none (web uses `*PromptAttachmentUpload`) |
| Dictation | `claimDictation` / `releaseDictation`, vocabulary methods | `/api/voice-transcription` | `setDictationEnabled` |
| Ship | prompt via `client_append_user_msg`. `updateProject✓{customShipPrompt, changesWorkflow}` | none | `startThreadShipping`, `cancelQueuedThreadShipping` |
| Labels / pin / snooze / archive | internal `updateThread✓{pinned, snooze, archived, labels, color, visibility}`, `archiveThread✓`, `setThreadLabels✓`, `addThreadLabels✓`, user actor `setThreadPinned` | none | `snoozeThreadCommand`, `pinThreadCommand`, `addThreadLabel`, `archiveThreadCommand`, `listSidebarSnoozedThreads` |
| Unread / mentions / feed | user actor actions + events | none | `markThreadsReadCommand`, `listSidebarThreadMentions` |
| Multiplayer open / collab | `client_set_thread_open`, comments, drafts, observers | none | access requests, followers, invites |
| Presence / workspace chat | thread `observers` | workspace actor | none |
| Push notifications | `client_upsert_notification_subscription` (Web Push only) | none | notification settings |
| Puck voice | `client_puck_voice_*` | none | Puck settings / conversations |
| Spaces call | none | `/api/threads/<id>/space-token` + LiveKit | spaces CRUD |

## Native client feasibility

**A Kotlin client can implement the following on stable (`cli`) contracts:**

- Login. OAuth device flow or loopback PKCE against `/api/auth/oauth-config` + `user_management/*`, refresh rotation, and `apiKey` storage. This is the identical credential the CLI uses.
- Every `/api/internal?<method>` call. The 271-method zod table gives exact parameter names. Gzip bodies over 25,600 bytes.
- The thread actor. Mint `wsToken` with `POST /api/thread-actors {threadId, usesThreadActors:true}` (Bearer). Open a WebSocket to `/actors/gateway/threadActor/websocket/` with Rivet query params and subprotocols. Speak JSON-RPC 2.0 notifications and requests (`{type,…}` ↔ `{method,params}`). Resume with `client_resume{version}`. Refresh with `client_reauthorize`. OkHttp WebSocket handles subprotocols and binary frames. The relay binary frame format is five header bytes plus the channel id.
- The user actor via RivetKit stateless HTTP actions (`/gateway/userActor/action/<name>`) and a connected socket for events. This is the hardest piece. Persistent RivetKit connections use the `/connect` path (`Ma`) with a versioned envelope whose body is a tagged union that includes `Event` and action responses keyed by request id, and an init carrying `{actorId, connectionId, connectionToken}` (`62d3d593.js:86000-86800`, `130626`). The default encoding is `bare`, and the client config also accepts `json` and `cbor` (`62d3d593.js:80300`). [INFERENCE] Choose `rivet_encoding.json` to avoid porting BARE. Fallback: poll `getRecentThreads` / `listFeedItems` over stateless actions.
- Terminal over the actor relay, with xterm-style rendering in Compose (Termux's `terminal-view` or a custom VT parser). Framing is simple JSON control frames plus binary.
- Changes. The recorded capture endpoints plus WS git commands. Diffing happens client-side from blobs.
- Attachments (resumable endpoint) and portals (`resolveThreadPortalURL`/`mintPortalLoginURL` + WebView).
- Desktop. The Waymote protocol is small: video via `MediaCodec` (codec string from the config message), Opus via `MediaCodec`, JSON control. The pointer/keyboard event schema still needs full decoding from `e88f9517.js` before shipping.

**Fragile:**

- Anything `web-remote`. Hash prefixes such as `162cgw9/…` are module ids that change per deploy. Thread-list sidebar groups, snooze/pin commands, ship, project settings, notification settings, workspace admin and billing are all on it. A client can resolve hashes at runtime by fetching the web app's JS chunks and regex-matching `\`<hash>/<name>\``, but that is scraping. Payloads are devalue-serialized (`T(a.data, decoders)`), not plain JSON. `24d50c5f.js:0-2011`.
- The SvelteKit `__data` endpoint that carries `threadActorConfig` (`directTerminalURL`). It is devalue-encoded, cookie-authenticated, and route-shaped.
- RivetKit itself is a `main`-branch build (`0.0.0-main.78336a1`). Gateway query names (`rvt-*`), subprotocol prefixes and the BARE schemas can change with a Rivet upgrade. The CLI pins whatever it ships, so the server must stay compatible with old CLIs for a while, but not forever.
- The publishable Rivet token embedded in the CLI's `ou()`. If it is rotated, older clients rely on the server accepting the old one.
- Executor-only messages and internal methods meant for orb spawners and actors. They need non-user tokens.

**No non-web path:**

- Portal page content (arbitrary web apps). A WebView is required, after `/api/portal-auth/embed` or `mintPortalLoginURL`.
- MCP App UIs (`text/html;profile=mcp-app` resources rendered in a sandboxed iframe at `<sandbox.origin>/mcp-app-sandbox`). `f4769e5d.js:54500`, `74093`.
- Space calls. LiveKit has a native Android SDK, so this has a non-web path but depends on the `web-plain` token endpoint.
- Web Push delivery to Android. No FCM path exists today (see Notifications).
- Billing/Stripe portal sessions and WorkOS admin portal (`startCustomerStripePortalSession`, `getWorkOSOrganizationAdminPortalCommand`). These open external web pages.

## Open questions

- Does `POST /api/thread-actors?threadId=…&capability=…` (the web refresh path) accept Bearer, or only cookies? The CLI uses the body-only form `POST /api/thread-actors {threadId}`, so a native client should use that one.
- Does the server accept a non-browser Web Push `endpoint` (UnifiedPush/ntfy) in `client_upsert_notification_subscription`? An endpoint-host allowlist would block it.
- What is the exact `rvt-*` query layout the gateway expects for raw `websocket/` paths with a key query, versus a resolved actor id? Capture one real handshake (Chrome devtools on ampcode.com) before coding.
- What does `mintPortalLoginURL` return exactly, and is `resolveThreadPortalURL` enough without the embed call?
- What is the Waymote pointer/scroll event schema, and which video codec does the server pick (H.264/AV1)? It is not decoded here.
- What extra headers does `je(N())` add to internal-API requests (client name and version)? Server-side gating may key on them.
- What request headers identify the native apps (`AmpNativeApp/…` user agent per DigestCollabApps)? Some features (`activeNativePlatform`) may depend on them.

## Appendix A. Internal API methods (`/api/internal?<method>`, tier `cli`)

Extracted from the CLI's embedded zod table `r6t` (`amp(u16)@~130.2M-130.4M`). The format is `method(paramKeys)`. `?` marks an optional key. `✓` marks a method the CLI source calls. `()` means no params, or a schema that could not be flattened (union or preprocessed). Some unflattened entries are wrapped in `gD(...)` (orb spawner).

- `getSystemHealth` ()
- `listCustomDomains` ✓ (target)
- `addCustomDomain` ✓ (target, hostname)
- `recheckCustomDomain` ✓ (target, domainID)
- `removeCustomDomain` ✓ (target, domainID)
- `listSiteAdminSessions` (user)
- `revokeSiteAdminSessions` (operation, reason, sessions)
- `grantFeatureFlag` ()
- `previewGrantFeatureFlag` ()
- `lookupSiteAdminUsers` (users)
- `setFeatureRolloutPercent` (operation, feature, rolloutPercent)
- `testModelProviderInference` ✓ (thread, model, modelProviderKeyID?, prompt?, reasoningEffort?)
- `createAccessToken` ()
- `createGitAccessToken` ✓ ()
- `getGitHubGitAccessToken` ✓ (credentialContextToken?, host, projectID?, protocol, repositoryPath?)
- `getThreadMeta` ✓ (thread)
- `signCommit` ✓ (commitObject, filesChanged?, credentialContextToken?)
- `previewSiteAdminTxEmail` ()
- `sendSiteAdminTxEmail` ()
- `setThreadMeta` ✓ (thread, meta)
- `getThreadMetadata` (thread)
- `updateSpace` (thread, name?, goal?, color?)
- `updateThread` ✓ (thread, title?, pinned?, color?, visibility?, archived?, snooze?, labels?)
- `removeThreadRunnerWorktree` (thread)
- `sendReport` ✓ ()
- `openCustomBugReport` ✓ (threadID)
- `listBugReports` (assigneeEmail?, status?, submitterEmail?, workspaceName?, limit?)
- `listMyBugReports` (limit?)
- `updateMyBugReport` (bugReportID, followUp?, withdraw?)
- `attachBugReportDiagnostics` ✓ (bugReportID, threadID, files, metadata)
- `deleteThread` ✓ (thread)
- `archiveThread` ✓ (thread, archived, selfArchive?)
- `archiveThreads` ()
- `notices` ✓ (onlyStandardDisplay)
- `updateSiteAdminNotice` ()
- `newsCard` ✓ ()
- `logNewsCardAction` ✓ (slug, action)
- `splashDispatch` ✓ ()
- `logSplashDispatchAction` ✓ (key, action)
- `logNoticeAction` ✓ (key, action)
- `recordCLISignIn` ✓ (installMethod)
- `devTunnelStart` (sandboxId)
- `devTunnelStop` (sandboxId)
- `resolveThreadPortalURL` ✓ (threadID, port)
- `setThreadPortalHostname` ✓ (threadID, port, hostname, takeOver?)
- `restartOrbProcesses` ✓ (threadID)
- `readRepository` ()
- `listRepositoryProjects` (thread, limit, offset, pattern?)
- `getThreadProject` ✓ (thread)
- `getProjectSandboxSnapshot` (projectID, maxAgeMs, environmentResource?)
- `planProjectSandboxSnapshot` (projectID, sandboxProvider?, environmentResource?, templateSource, setupContractVersion, rootMaxAgeMs, sourceRevisionVersion?, ampHostedSnapshotSources?, repositoryURL?, additionalRepositoryURLs?)
- `claimProjectOrbPrewarm` (projectID, sandboxProvider, environmentResource, sourceID, sourceKind, sourceRevision, baseSourceRevision, rootCreatedAt, templateSource, setupContractVersion, rootMaxAgeMs)
- `waitProjectOrbPrewarm` (projectID, sandboxProvider, environmentResource, consumerToken)
- `retargetStaleProjectOrbPrewarms` (projectID, sandboxProvider, environmentResource, staleSourceRevision, sourceRevision)
- `enqueueSandboxSnapshotDeletion` (projectID, prewarmClaimToken?, sandboxProvider?, snapshotId)
- `invalidateProjectSandboxSnapshot` (projectID, snapshotId)
- `recordProjectSandboxSnapshotAllocationFailure` (projectID, environmentResource, snapshotId)
- `claimProjectSandboxSnapshotPublication` (projectID, prewarmClaimToken?, sandboxProvider?, environmentResource, generation, holderID, claimDurationMs)
- `releaseProjectSandboxSnapshotPublicationClaim` (projectID, prewarmClaimToken?, sandboxProvider?, environmentResource, generation, claimToken)
- `publishProjectSandboxSnapshot` (projectID, prewarmClaimToken?, sandboxProvider?, environmentResource, generation, claimToken, snapshotId, rootCreatedAt)
- `upsertProjectSandboxSnapshot` (projectID, snapshotId, environmentResource)
- `webSearch2` ✓ (objective, searchQueries?, maxResults?, thread)
- `extractWebPageContent` ✓ (url, objective?, searchQueries?, fullContent?, forceRefetch?, thread)
- `readPluginSkillReference` ✓ (url, supportedFileFormats?)
- `readPluginSkillReferenceFile` ✓ (url, revision, path, supportedFileFormats?)
- `listEnvVarSecrets` ✓ (target)
- `listEnvVarSecretEvents` ✓ (target, name?)
- `getEnvironmentVariable` ✓ (target, name)
- `setEnvVarSecret` ✓ (target, name, value, kind?)
- `deleteEnvVarSecret` ✓ (target, name)
- `getWorkspaceThreadInfo` ✓ (threadID)
- `getClaudeCodeActorCredentials` (threadID, connectionID?)
- `getActorEnvironmentVariables` (projectID?, policyOnly?)
- `getRunnerThreadEnvironmentVariables` ✓ (threadId, runnerId)
- `getSecretFingerprints` ✓ (threadId)
- `readGitLab` ()
- `getGitHubRepositoryCIStatus` (repository, commitSHA?, workflowRunID?)
- `createProject` ✓ ()
- `updateProject` ✓ (projectID, additionalRepositories?, icon?, name?, description?, listedOnAmpStore?, remixable?, repositoryURL?, repositoryMode?, remoteURLs?, changesWorkflow?, customShipPrompt?, desktopResumeScript?, baseBranch?, environmentResource?, orbCommitAuthor?, macSidecarImage?, preCloneScript?, preSetupScript?)
- `listExternalAPIOAuthClients` ✓ (workspaceName)
- `createExternalAPIOAuthClient` ✓ (workspaceName, type, name, description?, scopes, redirectURIs)
- `updateExternalAPIOAuthClient` ✓ (workspaceName, clientID, name?, description?, scopes?, redirectURIs?)
- `createPermaOrb` ✓ (...Nm.shape, environmentResource?)
- `listPermaOrbs` ✓ ()
- `getPermaOrb` ✓ ()
- `execPermaOrb` ✓ (...Nm.shape, deploymentName?, deploymentAttemptID?, command, timeoutMs?, gitCredentials?)
- `deletePermaOrb` ✓ ()
- `listPermaOrbDeployments` ✓ ()
- `getPermaOrbDeployment` ()
- `preparePermaOrbNamedDeployment` ✓ (...t$.shape, repositoryURL, branch?, port?)
- `recordPermaOrbNamedDeploy` ✓ (...t$.shape, attemptID, outcome)
- `deletePermaOrbDeployment` ✓ ()
- `preparePermaOrbDeployment` ✓ (...Nm.shape, repositoryURL?, branch?, port?)
- `recordPermaOrbDeploy` ✓ (...Nm.shape, outcome)
- `getProject` ✓ (projectID)
- `listProjectSandboxSnapshots` ✓ (projectID)
- `deleteProjectSandboxSnapshots` ✓ (projectID, environmentResource?)
- `getOrbGitIdentity` (projectID, principal?)
- `getProjectGalleryMedia` ✓ (projectID)
- `updateProjectGalleryMedia` ✓ (projectID, galleryMedia)
- `deleteProject` ✓ (projectID)
- `registerPluginWebhookTrigger` ✓ (creationThreadID, pluginIdentity, triggerKey, headers)
- `listAutomations` ✓ ()
- `getAutomation` (automationID)
- `createAutomation` (idempotencyKey?, title, prompt, targetThreadID, trigger, runMode?, agentMode?)
- `updateAutomation` ✓ (automationID, title?, prompt?, trigger?, runMode?, agentMode?, enabled?)
- `deleteAutomation` ✓ (automationID)
- `listGitHubAutomations` (threadID?)
- `getGitHubAutomation` (workspaceID?, automationID)
- `createGitHubAutomation` (workspaceID?, configuration, idempotencyKey)
- `updateGitHubAutomation` (workspaceID?, automationID, configuration?, enabled?)
- `deleteGitHubAutomation` (workspaceID?, automationID)
- `listGitHubAutomationRuns` (workspaceID?, automationID)
- `listSlackAutomations` (workspaceID?, threadID?)
- `getSlackAutomation` (workspaceID?, automationID)
- `createSlackAutomation` (workspaceID?, configuration, idempotencyKey)
- `updateSlackAutomation` (workspaceID?, automationID, configuration?, enabled?)
- `deleteSlackAutomation` (workspaceID?, automationID)
- `listSlackAutomationRuns` (workspaceID?, automationID)
- `listLinearAutomations` (workspaceID?)
- `createLinearAutomation` (workspaceID?, configuration, idempotencyKey)
- `deleteLinearAutomation` (workspaceID?, automationID)
- `createLinearIssueComment` ()
- `listLinearAgentRouting` (workspace?, includeLinearTargets?)
- `updateLinearAgentRouting` (workspace?, update)
- `createRemoteExecutorThread` ✓ (agentMode?, agentModeDefaulted?, modelOverride?, content, skipSetup?, userState?, runnerId?, runnerWorkingDirectory?, runnerWorktree?, daemonId?, fromPuck?, topicID?, spaceID?, credentialContextToken?, executeMode?, labels?, messageId?, multiplayerTTLSeconds?, projectID?, environmentResource?, orbProvider?, repositoryURL?, sourceToolUseID?, requestedBy?, features?, threadMeta?, title?)
- `getThreadOrbCredentialAccess` (sandboxID?, providerConfigID?)
- `getThreadOrbProviderConfig` (configID?)
- `orbSpawnerEnqueueJob` (...TZr, job)
- `orbSpawnerReportSandboxState` ()
- `orbSpawnerReportGuestVersion` (...X3, ampVersion)
- `orbSpawnerWaitJob` (configID, jobID, holdMs)
- `orbSpawnerClaimJob` (spawnerID, spawnerVersion?)
- `orbSpawnerHeartbeatJob` (jobID, spawnerID)
- `orbSpawnerCompleteJob` (jobID, spawnerID)
- `orbSpawnerReleaseClaim` (spawnerID)
- `orbSpawnerWaitLogRead` (jobID, spawnerID, holdMs)
- `orbSpawnerSendLogChunk` (jobID, spawnerID, chunk)
- `createPluginAgentThread` (agentMode, excludeTools?, parentThreadID?, visibility?, multiplayerTTLSeconds?, project?, features?, origin?)
- `runPluginAgent` (agentMode, message, timeoutMs, generatedByPlugin, parentThreadID?, visibility?, multiplayerTTLSeconds?, project?, features?, origin?)
- `setPluginThreadVisibility` ✓ (threadID, visibility)
- `setPluginThreadMultiplayer` ✓ (threadID, ttlSeconds)
- `publishThreadSandboxAccess` (sandboxID, threadID, trafficAccessToken)
- `getTunnelCredentials` (threadID, executorType?)
- `recordOrbSetupRun` (spawnID, durationMs, outcome)
- `getIsFirstProjectSandboxThread` ()
- `canConsumeSandboxCredits` (projectID?, workspaceID?, environmentResource?, minimumBalanceCredits?)
- `admitSandboxSpawn` (projectID?, workspaceID?, environmentResource?, minimumBalanceCredits?)
- `mcpListAccountServers` ✓ ()
- `mcpAppReadResource` ✓ (serverID, uri, toolName?)
- `mcpAppCallTool` ✓ (serverID, name, arguments)
- `manageRemoteMCPServers` ✓ ()
- `manageCustomAgents` ()
- `mcpListPreloadedTools` ()
- `mcpGetLocalCapabilitySummaries` (servers)
- `mcpGetAccessToken` (serverID, forceRefresh?, rejectedAccessTokenFingerprint?, toolName?, approved?, threadMultiplayer?)
- `selectActorInferenceModel` (candidateModels)
- `selectActorDecisionModel` (candidateModels)
- `prepareActorInference` (provider, model, eapModel?, features?, modelProviderKeyID?, purpose?)
- `resolveUserContentFileToPublicURL` (url, fit?)
- `recordActorInference` (requestId, provider, model, resolvedModel, usage?, modelProviderKey?)
- `dispatchUserNotification` (threadID, kind, title, body, url, activeNativePlatform?, interruptionLevel?, startedByThread?, resultVersion?)
- `dispatchThreadMentionNotification` (recipientUserID, messageID?, anchorMessageID?, senderUserID?, sentByAgent?, threadTitle, messagePreview)
- `getThreadParticipantGitIdentity` (userID)
- `wakeThreadSnoozeForContributorMessage` (messageCreatedAt)
- `wakeThreadSnoozeForApproval` ()
- `getThreadSnoozeState` ()
- `setThreadFollow` (userID, following)
- `listThreadFollowers` ()
- `sendMessageToThread` ✓ (content, userState?, fromPuck?, generatedByPlugin?, messageId?, multiplayerTTLSeconds?, sourceToolUseID?, requestedBy?, steer?, threadID, prompt?)
- `postToTopic` (topicID, content, tags, clientKey, sourceToolUseID?)
- `readTopic` (topicID, afterSeq?, limit?)
- `addTopicParticipant` (topicID, threadID)
- `listPrompts` (thread)
- `manageSavedPrompts` ()
- `expandPrompt` (thread, name)
- `resolveShipWorkflowPrompt` (thread)
- `getThreadFileURLs` (paths, sourceThreadID?, sourcePuckThreadID?, threadID)
- `getThreadUserInputRequests` (threadID)
- `answerThreadUserInputRequest` (threadID, requestID, result?, error?)
- `startMacSidecar` (threadID, size?, image?)
- `getMacSidecar` ✓ (threadID)
- `renewMacSidecar` (threadID)
- `stopMacSidecar` (threadID, reason?)
- `appStoreConnect` ()
- `appStoreConnectSubscriptions` ()
- `approveMacSidecarSigning` (threadID, approvedByUser)
- `getAppStoreConnectSigningKey` ✓ (threadID)
- `getAppStoreConnectConnection` ✓ (threadID)
- `connectAppStoreConnect` ✓ ()
- `mintPortalLoginURL` ✓ (url)
- `resolveEmailAttachments` (urls)
- `sendMessageToPuckThread` (message, sourceThreadID?, threadID)
- `openPuckThread` ✓ (archiveThreadID?)
- `getUserInfo` ✓ ()
- `getNativeAppIdentity` ()
- `getGitHubAppInstallations` ()
- `getRepositoryPushAccess` (projectID?, repositoryURL)
- `getDictationVocabulary` ✓ ()
- `setDictationVocabulary` ✓ (operation, vocabulary)
- `getAgentSettings` (scope?, keys?)
- `updateAgentSetting` (key, value, workspace?, confirm?)
- `manageAgentGitIdentity` ()
- `loadPlugins` ✓ ()
- `loadSkills` ✓ ()
- `loadAgents` ()
- `getAgentModelCatalog` ✓ ()
- `listAccessibleProjects` ✓ ()
- `resolveProjectByRepositoryURLs` ✓ (repositoryURLs)
- `shareRunner` ✓ (runnerId, workspaceID)
- `revokeRunnerShare` ✓ (shareID)
- `listRunnerShares` ✓ ()
- `listRunners` ✓ (capability?)
- `setThreadNetRoute` ✓ (threadID, exitNode)
- `getThreadNetRoute` ✓ (threadID)
- `listMCPServerApprovals` ✓ ()
- `approveMCPServer` ✓ (serverName, specHash, command, scope)
- `notifyBrowserCookieSharingStarted` ✓ ()
- `listWorkspaceMembers` ()
- `resolveWorkspaceUserMentions` (userIDs)
- `requestThreadCommentAgentReply` (anchorMessageId, selection, anchorMessageText, anchorAgentReplyText, modelPin, requesterUserID, triggeringCommentId, comments)
- `addThreadCommentAsAgent` (text)
- `findSharedPluginsAndSkills` (query?, kind?, ownerUserID?, limit?)
- `listProjectsForSandbox` ()
- `listAgentModes` ✓ ()
- `listGlobalPluginRepositories` ✓ ()
- `listGlobalSkillRepositories` ✓ ()
- `listServerPluginAgentModes` (projectID?, repositoryURL?)
- `notarizePluginSource` ✓ (url, content)
- `getThreadLabels` ✓ (thread)
- `setThreadLabels` ✓ (thread, labels)
- `addThreadLabels` ✓ (thread, labels)
- `getUserLabels` (query?)
- `getOrbSystemMetrics` ✓ (threadID)
- `threadDisplayCostInfo` ✓ (threadID, markdown?, details?, start?, end?)
- `getThread` (thread)
- `getThreadInitialTranscriptPage` ✓ (thread)
- `getThreadTail` ✓ (thread, limit)
- `getThreadMessagesPage` ✓ (thread, fromIndex, limit)
- `getThreadTranscriptPage` ✓ (thread, before, anchorID)
- `listThreads` ✓ (excludeLabelNames?, excludeCreatedByThreadAgentModes?, includeArchived?, includeEmpty?, installationID?, limit?, offset?, threadIDs?)
- `userDisplayBalanceInfo` ✓ (markdown?, details?, workspace?, days?, start?, end?)
- `setModelProviderKeyActive` ✓ (target?, connectionID, active)
- `setModelProviderKeyPriority` ✓ (connectionID, priority)
- `deleteModelProviderKey` ✓ (target?, connectionID)
- `listModelProviderKeys` ✓ (target)
- `getModelProviderWorkloadIdentity` ✓ (target)
- `checkModelProviderAccess` ✓ (operation, type)
- `createModelProviderKey` ✓ (target, type, name?, secret, config?, active?, acknowledgedChineseProvider?)
- `testModelProviderKey` ✓ (target?, connectionID)
- `setModelProviderKeySecret` ✓ (target?, connectionID, secret)
- `updateModelProviderKey` ✓ (target, connectionID, name?, config?, configPatch?, acknowledgedChineseProvider?)
- `slackListChannelMembers` (channelID, limit?, cursor?)
- `slackListUserChannels` (userID?, types?, excludeArchived?, limit?, cursor?)
- `slackReadUserProfile` (userID)
- `reportPuckWorkerActivity` (activity, step)
- `slackWrite` (postAs?, text?, clientMessageID?, operation?, attributionKind?, attributionText?, channelID?, channelName?, messageTs?, userID?, userIDs?, userName?, userNames?, threadTs?, attachmentURLs?)
- `xReply` (text, inReplyToPostID)
- `xRead` (postID, limit)
- `slackResponseStream` ()
- `slackRead` ()
- `gmail` ✓ ()
- `googleDriveSearch` (query, pageSize?, pageToken?)
- `googleDriveDownload` ()
- `googleDocsRead` (documentID)
- `googleDocsWrite` ()
- `googleSheetsRead` ()
- `googleSheetsWrite` ()
- `googleCalendarListEvents` (date?, days?, timeZone?, calendarID?, query?, maxResults?, pageToken?)
- `googleCalendarWriteContext` (calendarID?, eventID?)
- `googleCalendarWrite` ()

## Appendix B. SvelteKit remote functions (tier `web-remote`)

Wire: query `GET /_app/remote/<hash>/<name>?payload=<devalue>`. Command `POST /_app/remote/<hash>/<name>` with `{payload, refreshes}`. Form `POST` with a multipart blob and headers `x-sveltekit-pathname` and `x-sveltekit-search`. The response is `{type:"result"|"error"|"redirect", data:<devalue>}` (`24d50c5f.js:0-2011`, `2011`, `17641`). The hash is build-specific. Example: `162cgw9/createPromptAttachmentUpload`. Kinds: Q query, C command, F form, ? not resolved (imported through a re-export). ★ marks a name that also appears in the CLI binary. Most of those are same-named `/api/internal` methods or user-actor actions.

**Threads & transcript** (175): `addThreadLabel` C ★, `addToAmpInsiders` C, `archiveInactiveThreadsCommand` C, `archivePuckConversation` C, `archiveSpaceCommand` C, `archiveThreadAndRemoveWorktreeCommand` C, `archiveThreadCommand` C, `canReadThreadQuery` Q, `cancelQueuedThreadShipping` C, `changeThreadVisibilityCommand` C, `classifySidebarThreadStatuses` C, `classifyThreadNextActionCommand` ?, `clearAdminWorkspaceSlackSharedChannel` C, `convertExternalAgentThreadForUser` C, `countArchivableInactiveThreadsQuery` Q, `createMessage` ? ★, `createProjectThread` C, `createWorkspaceEntitlementCommand` C, `deleteLabel` C, `deleteLabels` C, `deleteThreadAutomation` C, `deleteThreadCommand` C, `deleteWorkspaceEntitlementCommand` C, `dismissMultiplayerEnableNotice` C, `dismissThreadAccessRequestCommand` C, `dismissThreadOrbSetupWarning` ?, `dismissThreadPushAccessWarning` ?, `dismissThreadSharingTip` ?, `dismissWorkspaceSlackSharedChannelBannerCommand` C, `forceThreadVersionSync` C, `getAccessToken` C, `getAdminWorkspaceSlackSharedChannel` Q, `getBugReportRelatedThreadsQuery` Q, `getBugReportThreadStartQuery` Q, `getCustomThreadColorsQuery` Q, `getExternalAgentThreadsForUsers` Q, `getLabelsForDeletion` Q, `getMentionInviteOptionsQuery` Q, `getModelUsageThreadsQuery` Q, `getMultiplayerEnableNoticeEligible` Q, `getPermaOrbThreadTarget` C, `getPersonalThreadsUsageTable` Q, `getPortalPublicAccessSettings` Q, `getProjectOrbReadiness` Q, `getProjectReadme` Q, `getProjectThreadCount` Q, `getPublicThread` Q, `getRecentCurrentUserThreadLabels` Q, `getSidebarThreadCommitCIStatuses` Q, `getThreadAutomationDetails` Q, `getThreadCommitCIFailureDetails` Q, `getThreadCommitCIStatuses` Q, `getThreadDisplayCostInfo` Q, `getThreadGitActivityDetails` Q, `getThreadGitHubAutomations` ?, `getThreadHoverCardData` Q, `getThreadNetRouteOptions` ?, `getThreadOrbInfo` ?, `getThreadOrbSetupFailure` ?, `getThreadOrbSetupOnboardingEligibility` ?, `getThreadOrbSetupWarning` ?, `getThreadOrbSystemMetrics` ?, `getThreadRepositoryPushDenials` ?, `getThreadSharingTips` ?, `getThreadSlackAutomations` ?, `getThreadSpaceGoal` Q, `getThreadTitles` Q, `getThreadTopic` ?, `getThreadTopics` ?, `getThreadTracesQuery` Q, `getThreadUsagePageData` Q, `getUserActorThreadStatsForUser` Q, `getWorkspaceEntitlementsSettingsPanelData` Q, `getWorkspaceExpensiveThreads` Q, `getWorkspaceGroupVisibilitySettingsCommand` C, `getWorkspaceModelAccessDirectoryGroups` Q, `getWorkspaceModelAccessPolicyData` Q, `getWorkspaceSlackSharedChannelStatus` Q, `interruptUserActiveWorkingThreads` C, `inviteMentionedEmailsCommand` C, `inviteThreadFollowersCommand` ?, `joinWorkspaceSlackSharedChannelCommand` C, `listLinkedThreads` ?, `listMentionableWorkspaceMembersQuery` Q, `listProjectPuckArchivedWorkers` ?, `listProjectPuckFollowers` ?, `listSidebarArchivedThreads` Q, `listSidebarFollowedThreads` Q, `listSidebarLabelGroup` Q, `listSidebarLabelGroups` Q, `listSidebarMentionedThreads` Q, `listSidebarOwnedThreadsTookPartIn` Q, `listSidebarPresenceThreads` Q, `listSidebarRealtimeChildThreads` Q, `listSidebarRecentThreads` Q, `listSidebarSnoozedThreads` Q, `listSidebarSnoozes` Q, `listSidebarThreadMentions` Q, `listSpaceSubthreadTimeline` ?, `listThreadAccessRequestsQuery` Q, `listThreadFollowerIDsQuery` ?, `listThreadInviteCandidatesQuery` ?, `listThreadListSidebar` Q, `listWorkspaceEntitlementDirectoriesCommand` C, `listWorkspaceEntitlementDirectoryGroupsCommand` C, `logAttachmentPickerSelection` C, `markThreadUnreadCommand` C, `markThreadsReadCommand` C, `notifyWorkspaceChatMentionsCommand` C, `openPuckThread` C ★, `persistCreateThreadModePreference` ?, `persistCreateThreadOrbProviderPreference` ?, `persistCreateThreadOrbTypePreference` ?, `persistCreateThreadPreferredRunner` ?, `pinThreadCommand` C, `previewWorkspaceEntitlementDirectoryGroupUsersCommand` C, `publishThreadFileForDownload` ?, `rebootThreadOrb` C, `recordThreadOrbSetupInvestigation` ?, `recordThreadPushAccessWarningAction` ?, `removeFromAmpInsiders` C, `removeThreadFollowerCommand` ?, `removeThreadLabel` C, `renameThreadCommand` C, `requestGoogleAccessCommand` C, `requestThreadAccessCommand` C, `requestThreadMultiplayerCommand` C, `resetWorkspaceEntitlementQuotaCommand` C, `restartThreadActor` C, `restartThreadExecutor` C, `retryThreadsWithInternalError` C, `rotateUserAccessToken` C, `rotateWorkspaceMemberAccessTokens` C, `saveWorkspaceModelAccessPolicy` C, `scheduleThreadResume` C, `searchCurrentUserThreadLabels` Q, `searchThreadLabels` Q, `searchThreads` ? ★, `setAdminWorkspaceSlackSharedChannel` C, `setAdminWorkspaceSlackSharedChannelDisabled` C, `setColorFilters` C, `setMessageSendBehavior` C, `setPersonalPluginSkillVisibility` C, `setPinnedSidebarProjects` C, `setPortalPublicAccess` C, `setPublicSharingNotificationsEnabled` C, `setSidebarThreadLayout` C, `setThreadAutomationEnabled` C, `setThreadColorCommand` C, `setThreadColorMode` C, `setThreadFollowCommand` C, `setThreadMultiplayerCommand` C, `setThreadNetRouteCommand` ?, `setThreadsArchivedCommand` C, `setUserActorThreadIndicator` C, `snoozeThreadCommand` C, `snoozeThreadsCommand` C, `startBugReportThreadCommand` C, `startMyBugReportThreadCommand` C, `startThreadShipping` C, `suggestCurrentUserThreadLabels` C, `updateDefaultThreadVisibilitySetting` C, `updatePublicArtifactsSetting` C, `updatePublicSharingSetting` C, `updateThreadExecutor` C, `updateThreadExpiry` F, `updateThreadViewStateCommand` C, `updateWorkspaceAllowGroupsDefaultToGroupVisibilitySetting` C, `updateWorkspaceDisablePublicProfilesSetting` C, `updateWorkspaceDisableRunnerSharingSetting` C, `updateWorkspaceDisableSandboxThreadCreationSetting` C, `updateWorkspaceEntitlementCommand` C, `updateWorkspaceGroupVisibilitySettingsCommand` C, `updateWorkspaceReadOnlyPublicCodeAccessCommand` C, `updateWorkspaceSandboxThreadCreationExceptionsSetting` C.

**Projects, repos, files & git** (106): `approveEnterpriseCreditPurchasePricing` C, `calculateEnterpriseCreditPurchasePricing` C, `checkPersonalProjectTransfer` Q, `checkProjectCommitSigning` C, `clearWorkspaceProjectSandboxSnapshot` C, `clearWorkspaceProjectSandboxSnapshots` C, `connectGitLabCommand` C, `createAmpHostedProject` C, `createGitHubProject` C, `createMappedProject` C, `createProjectPuck` C, `createProjectRepositoryCloneCredentialCommand` C, `createWorkspaceGHECloudProject` C, `deleteGitIdentityCommand` C, `deleteOwnedProject` C, `deleteProfilePictureCommand` C, `deleteProjectIconCommand` C, `deleteProjectOrbSnapshot` C, `deleteProjectRepositoryCloneCredentialCommand` C, `deleteWorkspaceProfilePictureCommand` C, `disableLegacyGlobalPluginSourceProject` C, `disconnectGitHubCommand` C, `disconnectGitLabCommand` C, `dismissMacIntelCard` C, `getAdminProjectsCardData` Q, `getAdminRepositoriesCardData` Q, `getBugReportCommitsQuery` Q, `getCommitHoverCardData` Q, `getCommitsBranchName` Q, `getDailyProjectsQuery` Q, `getGitIdentitiesSettingsData` Q, `getGitLabConnections` Q, `getGlobalRepositoryBrowserBlob` Q, `getGlobalRepositoryBrowserData` Q, `getGlobalRepositoryRevision` Q, `getMacIntelCardEligible` Q, `getNewProjectGitHubConnectionStatus` Q, `getOAuthScopeReconciliation` Q, `getOrCreatePersonalGitHubRepositoryProject` C, `getProjectFileList` Q, `getProjectFileTree` Q, `getProjectGitIdentityData` Q, `getProjectMacSidecarImageSetting` Q, `getProjectNewOrbAgentModeOptions` Q, `getProjectOrbSnapshot` Q, `getProjectPuckHome` Q, `getProjectPuckParent` ?, `getProjectPuckSettingsData` Q, `getProjectRepositorySettings` Q, `getProjectSettingsData` Q, `getProjectStats` Q, `getProjectsSettingsPanelData` Q, `getRecentCommits` Q, `getReengagementEmailPreview` Q, `getWebRTCIceServers` ?, `getWorkspaceGitLabSettings` Q, `listAdditionalRepositoryProjects` Q, `listNewOrbSubscriptionProjects` Q, `listNewProjectGitHubSSORequiredOrgs` Q, `listNewProjectRepositorySuggestions` Q, `listNewsCardDebugItems` Q, `listProjectPuckWorkers` ?, `listProjects` Q ★, `listRecentProjectIDs` Q, `listReengagementUsers` Q, `listSidebarProjects` Q, `listWebPlacedNoticeDebugItems` Q, `projectNeedsGitHubConnection` Q, `refreshGitHubProjectMetadata` C, `removeGitHubAutomation` ?, `removeUserProfilePicture` C, `removeWorkspaceGitLabInstanceCommand` C, `requestGitIdentityVerification` C, `resetAllProjectSettingsToDefaultCommand` C, `resetProjectSettingToDefaultCommand` C, `resolveProjectByRepositoryURLs` Q ★, `saveWorkspaceGitLabInstanceCommand` C, `searchFeedProjects` C, `setDefaultGitIdentityCommand` C, `setFileAutosaveEnabled` C, `setGitHubAutomationEnabled` ?, `setGlobalPluginRepositoryRequireSignedCommits` C, `setGlobalSkillRepositoryRequireSignedCommits` C, `setOrbCommitSigningEnabled` C, `setPersonalLinearAgentSessionProjectCommand` C, `setProjectAmpCoauthorEnabledCommand` C, `setProjectCoauthorIdentityCommand` C, `setProjectGitIdentityCommand` C, `setProjectMacSidecarImageCommand` C, `setProjectPostReceiveHookURLCommand` C, `setProjectPuckSettings` C, `setProjectUpstreamAutoUpdateCommand` C, `transferOwnedProjectToWorkspace` C, `triggerProjectUpstreamUpdate` C, `updateGitHubAutomationText` ?, `updateOrbGitIdentityModeCommand` C, `updateOwnedProject` C, `updateOwnedProjectRepositoryMode` C, `updateProjectDefaultsCommand` C, `updateProjectGalleryMediaCommand` C, `updateUserProfileForm` F, `updateWorkspaceAmpHostedProjectCreationCommand` C, `updateWorkspaceProfileCommand` C, `uploadProfilePictureForm` F, `uploadProjectIconForm` F, `uploadWorkspaceProfilePictureForm` F.

**Orbs, runners, executors, portals, desktop** (82): `addPortalToLauncher` C, `addWorkspaceSpawnerOrbProvider` C, `checkPortalHostnameAvailability` Q, `clearSandboxCache` C, `clickMacOrbsCard` C, `createExecutorRunnerDirectory` C, `createNewOrbSubscription` C, `createNoticeForm` F, `createWorkspaceServiceAccount` C, `deleteAdminPermaOrb` C, `deleteNoticeCommand` C, `deleteWorkspaceServiceAccount` C, `disableWorkspaceServiceAccount` C, `dismissMacOrbsCard` C, `dismissOrbSetup` C, `enableWorkspaceMemberSubscriptionSelfService` C, `enableWorkspaceServiceAccount` C, `getCurrentAvailableOrbResources` Q, `getHourlySignupsAndMonetizedDAUSeriesQuery` Q, `getLauncherEditorData` Q, `getLauncherOrbPage` Q, `getLauncherOrbPortals` Q, `getMacOrbsCardEligible` Q, `getMacSidecarSettings` Q, `getModelRoutingChoices` Q, `getMonetizedDailyActiveUsersSeriesQuery` Q, `getNewOrbAgentModeOptions` Q, `getOrbActivitySeriesQuery` Q, `getOrbAdopterTimingsQuery` Q, `getOrbAdoptionByCohortSeriesQuery` Q, `getOrbSetupDismissed` Q, `getOrbSpeedStickinessQuery` Q, `getOrbsSettingsPanelBillingData` Q, `getOrbsSettingsPanelData` Q, `getPortalHostnameSettings` Q, `getPortalLoadWarningsEnabled` Q, `getSandboxCacheStatus` Q, `getSandkastenSandboxCharges` Q, `getSandkastenSandboxLedger` Q, `getSidecarUsagePanelData` Q, `getWebNotices` Q, `getWorkOSOrganizationAdminPortalCommand` C, `getWorkspaceBillingInvoices` Q, `getWorkspaceLauncherEditorData` Q, `getWorkspaceMonetizedDAUChangesQuery` Q, `getWorkspaceOrbCounts` Q, `getWorkspaceSelfHostedOrbs` Q, `getWorkspaceServiceAccountsPanelData` Q, `listModelRoutingChoices` Q, `listNotices` Q, `listUserExecutorRunners` Q, `loadLinearAgentRoutingChoicesCommand` C, `logMacOrbsCardView` C, `logSubscriptionConnectNoticeAction` C, `logWebNoticeAction` C, `logWebPlacedNoticeAction` C, `portals` ? ★, `rebootOrbCommand` C, `removeLauncherItem` C, `removeWorkspaceOrbProvider` C, `resetWebPlacedNoticeActions` C, `rotateWorkspaceOrbProviderToken` C, `rotateWorkspaceServiceAccountKey` C, `sendEnterpriseCreditPurchaseStripeInvoice` C, `setDefaultPortalDomainCommand` C, `setLauncherItems` C, `setMacSidecarAllowedSizes` C, `setOrbSaverMode` C, `setPortalHostname` C, `setPortalLoadWarningsEnabled` C, `setPuckVoice` C, `setWorkspaceDefaultOrbProviderCommand` C, `setWorkspaceLauncherItemsCommand` C, `setWorkspaceSelfHostedOrbsEnabledCommand` C, `startCustomerStripePortalSession` C, `stopOrbProcessCommand` C, `toggleNoticeActive` C, `updateEnterpriseCreditPurchaseInvoiceReference` C, `updateNoticeForm` F, `updateWorkspaceOrbCreationAllowExecuteModeSetting` C, `updateWorkspaceOrbCreationMaxAuthAgeSetting` C, `updateWorkspaceOrbProvider` C.

**Ship & changes** (8): `changeAutoReloadPaymentMethod` C, `confirmEmailChangeCommand` C, `getAnnualizedSpendChangesQuery` Q, `getChangesPrompts` Q, `getQualifiedWorkspaceChangesQuery` Q, `sendEmailChangeCodeCommand` C, `setBuiltinPromptShownInChanges` C, `setRequireShipAndPushConfirmation` C.

**Puck, spaces & voice** (125): `addWorkspaceDetail` C, `cancelWorkspaceMemberSubscription` C, `chatgpt` ? ★, `checkWorkspaceNameAvailability` Q, `createWorkspaceApplicationCommand` C, `createWorkspaceApplicationSecretCommand` C, `createWorkspaceByAdmin` F, `createWorkspaceForm` F, `createWorkspaceGroupCommand` C, `createWorkspaceInviteLinkCommand` C, `declineSpaceInvitationsCommand` C, `deleteWorkspace` C, `deleteWorkspaceApplicationCommand` C, `deleteWorkspaceApplicationSecretCommand` C, `deleteWorkspaceCommand` C, `deleteWorkspaceGroupCommand` C, `deleteWorkspaceInviteLinkCommand` C, `disconnectChatGPTModelProviderCommand` C, `disconnectWorkspaceGHECloudCommand` C, `disconnectWorkspaceGHECloudUserCommand` C, `disconnectWorkspaceSlackInstallationCommand` C, `disconnectWorkspaceXCommand` C, `dismissNewSpaceTip` C, `dismissSpaceCatchup` ?, `endWorkspaceMemberSudoSessions` C, `generateSpaceCatchup` ?, `generateWorkspaceGHECloudWebhookSecretCommand` C, `getChatGPTSubscriptionUsageQuery` Q, `getCustomerWorkspaceForecastQuery` Q, `getNewSpaceTip` Q, `getPuckMCPConnectionInfo` Q, `getPuckMCPOAuthClient` Q, `getQualifiedWorkspacesSeriesQuery` Q, `getWorkspaceApplicationSecrets` Q, `getWorkspaceApplicationsSettingsPanelData` Q, `getWorkspaceConnectionsSettingsPanelData` Q, `getWorkspaceCreationPanelData` Q, `getWorkspaceDeletionPreview` Q, `getWorkspaceDialEditorData` Q, `getWorkspaceGroupsSettingsPanelData` Q, `getWorkspaceInviteLinksSettingsPanelData` Q, `getWorkspaceLinearSettings` Q, `getWorkspaceManagedSettingsData` Q, `getWorkspaceMemberMCPUsage` Q, `getWorkspaceMembersSettingsPanelData` Q, `getWorkspaceModeTuningData` Q, `getWorkspaceNameQuery` Q, `getWorkspacePayments` Q, `getWorkspacePuckSettingsData` Q, `getWorkspaceRefundAutoReloadWarning` Q, `getWorkspaceSettingsPanelData` Q, `getWorkspaceUsageAgentModeMetrics` Q, `getWorkspaceUsageBreakdownMetrics` Q, `getWorkspaceUsageGroupMetrics` Q, `getWorkspaceUsageMetrics` Q, `getWorkspaceUsageSettingsPanelData` Q, `getWorkspaceUsageTimelineData` C, `getWorkspaceUsageUserMetrics` Q, `hasConnectedChatGPTUsage` Q, `joinWorkspaceViaLinkCommand` C, `leaveWorkspaceGroupCommand` C, `listDiscoverableSidebarSpaces` Q, `listPuckConversations` Q, `listSidebarSpaceMembers` Q, `listSpaceDiscussions` ?, `listSpaceInvitationsQuery` Q, `listWorkspaceMemberThresholdAdmins` Q, `previewWorkspaceBaseMemberTier` C, `previewWorkspaceMemberSubscriptionStart` C, `refundWorkspacePayment` C, `removeWorkspaceGHECloudConnectionCommand` C, `requireWorkspaceSSOReauthentication` C, `resolveWorkspaceUsersQuery` Q, `respondToWorkspaceInvitation` C, `revokePendingWorkspaceInvitation` C, `saveWorkspaceDialModes` C, `saveWorkspaceGHECloudConnectionCommand` C, `saveWorkspaceManagedSettings` C, `saveWorkspaceModeModelOverrides` C, `searchCustomerWorkspacesQuery` Q, `searchWorkspaceOptions` Q, `searchWorkspacesQuery` Q, `setChatGPTModelProviderActiveCommand` C, `setDictationEnabled` C, `setDictationVocabulary` C ★, `setPuckInstructions` C, `setPuckMCPOAuthClientID` C, `setWorkspaceManualVerification` C, `setWorkspacePuckInstructions` C, `startChatGPTOAuth` C, `startWorkspaceMemberSubscription` C, `suggestSpaceForPrompt` C, `syncSpaceCatchup` ?, `testChatGPTModelProviderConnectionCommand` C, `toggleWorkspaceLowCreditAlerts` C, `unblockWorkspaceAutoReload` C, `uncancelWorkspaceMemberSubscription` C, `updateSpace` C ★, `updateWorkspaceAPITokensDisabledSetting` C, `updateWorkspaceAllowChinaHostedModelProvidersSetting` C, `updateWorkspaceAllowMembersInviteSetting` C, `updateWorkspaceAllowMembersPersonalModelProviderKeysSetting` C, `updateWorkspaceAllowMembersViewMemberListSetting` C, `updateWorkspaceApplicationCommand` C, `updateWorkspaceBaseMemberTier` C, `updateWorkspaceBugReportURLSetting` C, `updateWorkspaceClaudeFableSetting` C, `updateWorkspaceDisableDictationSetting` C, `updateWorkspaceDisableHangoutChatSetting` C, `updateWorkspaceDisableRemoteControlSetting` C, `updateWorkspaceDisableSpaceSetting` C, `updateWorkspaceGlobalAgentGuidanceSetting` C, `updateWorkspaceGroupMembersCommand` C, `updateWorkspaceInstallPageMarkdownAdmin` C, `updateWorkspaceInstallPageMarkdownSetting` C, `updateWorkspaceIntegrationEnabledCommand` C, `updateWorkspaceManualPaidCredits` F, `updateWorkspaceMcpRegistryUrlSetting` C, `updateWorkspaceNeocloudModelsSetting` C, `updateWorkspaceOAuthMaxSessionSetting` C, `updateWorkspacePlatformFee` F, `updateWorkspaceRequireSudoForRemoteControlSetting` C, `updateWorkspaceRolesCommand` C, `updateWorkspaceSingleExclusiveWorkspaceSetting` C, `updateWorkspaceXPolicyCommand` C.

**Attachments & media** (5): `completePromptAttachmentUpload` C, `createPromptAttachmentUpload` C, `getAttachmentContents` Q, `getPromptAttachmentUploadStatus` C, `listBugReportAttachmentsQuery` Q.

**Agents, modes, models, plugins, MCP, automations** (82): `checkAvailableMCPServerAuthentication` Q, `connectLinearCommand` C, `connectMCPServer` C, `createModelProviderConnectionCommand` C, `createRemoteMCPServer` C, `deleteAgent` C, `deleteGlobalSkillFromSettings` C, `deleteModelProviderConnectionCommand` C, `deletePluginTrigger` C, `deleteRemoteMCPServer` C, `deleteSavedPromptFromSettings` C, `detectRemoteMCPServerAuthentication` C, `disconnectLinearCommand` C, `disconnectRemoteMCPServer` C, `disconnectSlackCommand` C, `enableOrganizationMode` F, `getAdminCreditFallbackModel` Q, `getAgentBuilderData` Q, `getAvailableMCPServers` Q, `getCreditFallbackModelSetting` Q, `getDailyCreditsUsageByAgentModeSeriesQuery` Q, `getDialEditorData` Q, `getExternalAgentPickerEnvironment` ?, `getGlobalAgentsSettingsData` Q, `getGlobalPluginsSettingsData` Q, `getGlobalSkillDraftForEditor` Q, `getGlobalSkillsSettingsData` Q, `getMCPServerApprovals` Q, `getModeTuningData` Q, `getModeTuningReminderConnections` Q, `getModelProviderKeyGroupExclusions` Q, `getModelProviderWorkloadIdentity` Q ★, `getModelProvidersSettingsPanelData` Q, `getModelRoutingDashboardQuery` Q, `getModelRoutingGraph` Q, `getModelUsageUsersQuery` Q, `getPersonalLinearSettings` Q, `getRemoteMCPServerTools` Q, `getRemoteMCPServers` Q, `getSavedPromptsSettingsData` Q, `getSlackConnectionStatus` Q, `listAgentModes` ? ★, `listModelProviderConnectionEvents` Q, `listModelProviderKeyExclusionDirectories` C, `listModelProviderKeyExclusionDirectoryGroups` C, `listPluginTriggers` Q, `listServerAgentModes` ?, `listUnmappedRouterModels` Q, `pauseModelProviderConnectionCommand` C, `refreshRemoteMCPServerTools` C, `removeMCPServerApproval` C, `removeSlackAutomation` ?, `replaceModelProviderAPIKeyCommand` C, `reportPasskeyPromptDismissed` C, `saveAgent` C, `saveCreditFallbackModel` C, `saveGlobalSkill` C, `saveSavedPrompt` C, `saveUserDialModes` C, `saveUserModeModelOverrides` C, `saveUserPainterModel` C, `scanPluginToolUsageBatch` Q, `setAdminCreditFallbackModel` C, `setGlobalAgentGuidance` C, `setModelProviderConnectionActiveCommand` C, `setModelProviderConnectionExposeSecretInEnvCommand` C, `setModelProviderConnectionPriorityCommand` C, `setModelProviderKeyGroupExclusions` C, `setPluginTriggerEnabled` C, `setSlackAutomationEnabled` ?, `setTrainingMode` C, `testCustomURLModelCommand` C, `testModelProviderConnectionCommand` C, `testRemoteMCPServer` C, `uninstallLinearAppCommand` C, `updateAllowTrainingModeSetting` C, `updateBillingMode` F, `updateDefaultAgentModeSetting` C, `updateLinearAgentRoutingCommand` C, `updateModelProviderConnectionCommand` C, `updateRemoteMCPServer` C, `updateSlackAutomationText` ?.

**Account, auth, notifications, settings** (144): `addDesignPartner` C, `addSigningKey` C, `allowlistUserEmailInWorkOSRadar` C, `applyToInsiders` F, `assignBugReportsCommand` C, `assignOfferCards` C, `beginPasskeySignIn` C, `beginSudoPasskeyAuthentication` C, `beginSudoPasskeyRegistration` C, `checkUsernameAvailability` Q, `chooseUsername` C, `clearUserRiskActionTakenStatus` C, `completePasskeySignIn` C, `completeSudoPasskeyAuthentication` C, `completeSudoPasskeyRegistration` C, `continueWithEmail` C, `createMissingOAuthScope` C, `createPasswordResetLink` C, `createSubscriptionStartSetupSession` C, `deleteEnvVarSecretSetting` C, `deleteSigningKey` C, `deleteSudoPasskeyCommand` C, `deleteUser` C, `deleteUserForm` F, `deleteUserMFAFactor` C, `deleteWorkOSOnlyOAuthScope` C, `disableUserPasskeyAuthenticationRequirement` C, `disableUserPasskeyAuthenticationSetting` C, `dismissPasskeyMigrationCard` C, `enableFeatureForUserIdentifiers` C, `enableUserPasskeyAuthenticationSetting` C, `endSudoSession` C, `expireSudoSessionInThreeSeconds` C, `generateSigningKeypair` C, `getAccountSettingsPanelData` Q, `getActiveUsersByEntryPathQuery` Q, `getAdminUserPersonalSpendUsage` Q, `getAdminUserUsageTimelineData` Q, `getAllActiveUsersSeriesQuery` Q, `getAppStoreConnectSettings` Q, `getAuthorizedApplicationsSettingsData` Q, `getCurrentSudoSession` Q, `getFailedFirstUseEmailPreview` Q, `getIntegrationsSettingsPanelData` Q, `getListenersSettingsPage` Q, `getNewActivatedUsersSeriesQuery` Q, `getNewSubscriptionsByPlanSeriesQuery` Q, `getPasskeyMigrationCardEligible` Q, `getPersonalTotalTokens` Q, `getProviderKeyTypeRevenueAndUsersSeriesQuery` Q, `getSecuritySettingsPanelData` Q, `getSigningKeysSettingsPanelData` Q, `getSignupBreakdownSeriesQuery` Q, `getSignupFunnelWeeklyQuery` Q, `getSignupsBySourceWeeklyQuery` Q, `getSubscriberGiftReminderEmailPreviews` Q, `getUserAllCreditsUsage` C, `getUserClusters` Q, `getUserFeatureRolloutOptions` Q, `getUserMFAFactors` Q, `getUserNotificationSettings` Q, `getUserPayments` Q, `getUserQuotaHistory` Q, `getUserQuotas` Q, `getUserRefundAutoReloadWarning` Q, `getUserRiskSignals` Q, `getUsernameSuggestions` Q, `getUsers` Q, `getWeeklySignupsSeriesQuery` Q, `grantBonusToUsers` C, `grantComplimentarySubscriptionForUser` C, `joinWaitlistByEmail` F, `listEnvVarSecretEventSettings` Q, `listEnvVarSecretSettings` Q, `listFailedFirstUseUsers` Q, `listHitSpendThresholdUsers` Q, `listProactiveCommunicationsByUserId` Q, `listStoppedUsingUsers` Q, `listTxEmails` Q, `listTxEmailsByUserId` Q, `listUserNotificationHistory` Q, `logNewsCardAction` C ★, `markUserEmailVerified` C, `overwriteOAuthScope` C, `previewBulkDeleteUsers` C, `previewEmail` Q, `previewSplashDispatchUser` Q, `refundUserPayment` C, `replaceClaudeSubscriptionTokenCommand` C, `requestPasswordReset` C, `resetNewsCardActions` C, `resetNewsCardActionsForSlug` C, `resetPassword` C, `resetUserQuotas` C, `resetUserSudoPasskeys` C, `revokeAPIToken` C, `revokeClusterUserFreeTier` C, `revokeClusterUsersFreeTier` C, `revokeMySession` C, `revokeSigningKey` C, `rotateTokenCommand` C, `saveUserKeymap` C, `searchFeedUsers` C, `searchUserOptions` Q, `sendBugReportReplyEmailCommand` C, `sendEmail` F, `sendEnterpriseCreditPurchaseOrderFormToDocuSign` C, `sendFailedFirstUseEmail` C, `sendReengagementEmail` C, `sendSignInCode` C, `sendToUsers` C, `setAppStoreConnectAskBeforeMacSigningCommand` C, `setBonusEmailOptIn` C, `setFeatureForAmpUsers` C, `setNewsSplashSettingsCommand` C, `setNotificationPreferencesCommand` C, `setUserBlockedStatus` C, `setUserManualVerification` C, `setUserToggleableFeature` C, `signInWithEmailCode` C, `signInWithPassword` C, `signUpWithPassword` C, `simulateEnterpriseCreditPurchaseCountersignPending` C, `simulateEnterpriseCreditPurchaseCountersigned` C, `simulateEnterpriseCreditPurchaseCustomerSigned` C, `simulateEnterpriseCreditPurchaseSentForSignature` C, `startAppleOAuth` C, `startBulkDeleteUsers` C, `startGoogleOAuth` C, `syncDirectoryUsers` C, `toggleBillingEmailReceipts` C, `toggleUserPauseRiskActions` C, `updateListenerSettings` C, `updateNotificationThreshold` F, `updateUserAPITokensDisabledSetting` C, `updateUserEnabledFeature` C, `updateUserFreeCredits` C, `updateUserFreeTierMultiplier` C, `updateUserOAuthMaxSessionSetting` C, `updateUserPaidCredits` C, `updateUserSiteRole` C, `verifyEmailCode` C, `verifyRadarEmailCode` C, `zeroOutUserFreeCredits` C.

**Workspace admin & billing** (59): `addCustomDomain` C ★, `addDomainCommand` C, `addFreeCredits` F, `bulkGrantSubscriptionMultipliers` C, `bulkImportDomainsCommand` C, `bulkRevokeFreeTierAndZeroCredits` C, `cancelEnterpriseCreditPurchase` C, `cancelEnterpriseCreditPurchaseRequest` C, `cancelMySubscription` C, `cancelSubscriptionAndRefund` C, `createEnterpriseCreditPurchaseDraft` C, `creditRecentSubscriptionUsage` C, `disconnectXCommand` C, `finishSubscriptionStartSetup` C, `generateEnterpriseCreditPurchaseOrderForm` C, `getAdminAuditLog` Q, `getAmpCreditsSwitchEstimateQuery` Q, `getCancelSubscriptionRefundPreview` Q, `getCreditRecoverySubscription` Q, `getCustomDomains` Q, `getDisposableDomainsSyncStatus` Q, `getDocsSearchIndex` Q, `getEnterpriseCreditPurchaseDetails` Q, `getExternalSubscriptionConnectionsSeriesQuery` Q, `getHourlyCreditsUsageByTypeSeriesQuery` Q, `getPaidUsageRetentionQuery` Q, `getPersonalSpendUsage` Q, `getPersonalUsageTimelineData` Q, `getRecentSubscriptionUsageCreditPreview` C, `getRevenueConcentrationAppendixQuery` Q, `getSelectedSubscriptionUsageResetPreview` C, `getSubscriptionDetails` Q, `getSubscriptionMetricsSeriesQuery` Q, `getSubscriptionsDashboardSeriesQuery` Q, `getUsageDashboardSeriesQuery` Q, `grantBugReportFreeCreditsCommand` C, `inviteMemberForm` F, `previewSubscriptionStart` C, `purchaseCredits` C, `recheckCustomDomain` C ★, `removeCustomDomain` C ★, `removeDomainCommand` C, `removeMemberCommand` C, `removePaymentMethod` C, `removeWorkOSOrganizationDomainCommand` C, `resetFreeTierBucketUsage` C, `resetSelectedSubscriptionUsage` C, `resetSubscriptionUsage` C, `revokeFreeTierAndZeroCredits` C, `setClaudeUsageCommand` C, `setCustomDomainIncludeHostnameRoutingCommand` C, `setEnterpriseCreditPurchaseAutoAllocation` C, `startDomainConnectSetup` C, `startSSO` C, `startSubscription` C ★, `triggerDisposableDomainsSyncCommand` C, `uncancelMySubscription` C, `updateRequiredDomain` F, `updateSubscriptionMultiplier` C.

**Other** (145): `actionSalesContactRequest` C, `addBugReportCommentCommand` C, `addCandidate` C ★, `addCandidateNote` C, `addCountryCommand` C, `addIPCommand` C, `addPosition` C, `apple` ? ★, `approveApplication` C, `blockCode` C, `buildSubscriberGiftReminderRoster` C, `bulkBlockCodes` C, `bulkGrantFeatureFlag` C, `bulkImportIPsCommand` C, `bulkRevokeFeatureFlag` C, `bulkSaveEnvVarSecrets` C, `bulkUnblockCodes` C, `call` ? ★, `cancel` ? ★, `commentOnMyBugReportCommand` C, `connectAppStoreConnectCommand` C, `connectReloadBank` C, `createFeatureRollout` C, `createNudgeForm` F, `createOfferCardBatch` C, `createOfferForm` F, `declareEducationDiscount` F, `deleteFeatureOverride` C, `deleteFeatureRollout` C, `deleteNudgeCommand` C, `disableAutoReload` C, `disableBankReload` C, `disconnectAppStoreConnectCommand` C, `disconnectGoogleCommand` C, `dismissIOSAppCard` C, `dismissLocalSyncHint` ?, `duplicateFeatureRollout` C, `enableAutoReload` C, `enableBankReload` C, `findOfferCard` Q, `finishReloadBankSetup` C, `get` ? ★, `getAnnualRunRateSeriesQuery` Q, `getAppStoreConnectAccount` Q, `getBankReloadQuote` C, `getBugReportCommentsQuery` Q, `getCohortRetentionQuery` Q, `getControls` Q, `getConversionCohortsQuery` Q, `getFailedFirstUseCampaignResults` Q, `getFeatureOverrides` Q, `getFeatureRollouts` Q, `getGrowthAccountingSeriesQuery` Q, `getIOSAppCardEligible` Q, `getInferenceErrorInstancesQuery` Q, `getInterestingClusters` Q, `getJellywareChallengeQuery` Q, `getKeyMetricsHourlyRunRateSeriesQuery` Q, `getLocalImportOwnerInfo` Q, `getLocalSyncHintEligible` ?, `getLogoGrowthAccountingQuery` Q, `getOnboardingData` Q, `getPQLCohortsQuery` Q, `getParallelismSeriesQuery` Q, `getProfitDashboardSeriesQuery` Q, `getProviderKeyRevenueSeriesQuery` Q, `getPullRequestHoverCardData` Q, `getReauthStatusCommand` C, `getRevenueGrowthAccountingQuery` Q, `getSandkastenFailedCharges` Q, `getSandkastenOverview` Q, `getStickinessSeriesQuery` Q, `getSubscriberGiftReminderOverview` Q, `getTheNumberEntryPathSeriesQuery` Q, `getTheNumberSeriesQuery` Q, `getToolFailureInstancesQuery` Q, `getValueReachCohortsQuery` Q, `google` ? ★, `installAmpStoreApp` C, `joinWaitlist` C, `linkCLI` C, `list` ? ★, `listApplications` Q, `listBugReportsBySubmitterQuery` Q, `listNudges` Q, `listOffers` Q, `logIOSAppCardAction` C, `logNativeCaptureAttached` C, `makeEnvVarSecretSecret` C, `markProactiveCommunication` C, `mp3` ? ★, `mp4` ? ★, `mpeg` ? ★, `notifyCustomBugReportOpenedCommand` C, `ogg` ? ★, `previewSplashDispatch` Q, `recordClientAppActivity` C, `redeemOffer` C, `rejectApplication` C, `removeCountryCommand` C, `removeEducationDiscount` C, `removeIPCommand` C, `replaceAll` F ★, `replaceSelected` C, `resendVerificationCode` C, `result` ? ★, `revokeAuthorizedApplication` C, `revokeFreeTierEligibility` C, `saveEnvVarSecret` C, `selectOrganization` C, `sendFailedFirstUseBatch` C, `sendRadarSMSChallenge` C, `sendSubscriberGiftReminderBatch` C, `setActivityCalendarEnabled` C, `setCandidatePriority` C, `setCandidateStage` C, `setControl` C, `setDevmanFeatureFlag` C, `setFeatureOverride` C, `setFeatureRolloutPercent` C ★, `setLevel` ? ★, `setListenerEnabled` C, `setOfferCardBatchNotes` C, `setOfferCardNotes` C, `setOfferCardsDisabled` C, `setPositionStatus` C, `submitBugReportCommand` C, `submitContactSales` F, `takeBugReportCommand` C, `toggleActive` C, `toggleNudgeActive` C, `unblockAutoReload` C, `unblockCode` C, `unblockIndividualAutoReload` C, `updateBugReportStatusesCommand` C, `updateCandidate` C, `updateNudgeForm` F, `updateOfferForm` F, `updateRoleCommand` C, `verifyRadarSMSCode` C, `verifyTotpCode` C, `waiveSandkastenFailedCharge` C, `wav` ? ★, `webm` ? ★, `withdrawMyBugReportCommand` C.
