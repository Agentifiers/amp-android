# Auth and security

This document covers how the app signs in to Amp, keeps up to five accounts apart, stores and refreshes credentials, reacts to workspace policies, and keeps secrets out of screenshots and logs. It ends with a threat model and a plain statement of the risk that comes from speaking Amp's private protocol.

Citations point at `spikes/` or `docs/protocol/`, or name Amp's docs or ampcode.com's web bundle as the source. A claim with no citation carries an `[INFERENCE]` label. Android platform behavior the design depends on is listed once, with links, in [Android behavior this design assumes](#android-behavior-this-design-assumes), and the body refers to those rows as A1 to A13.

## What is verified and what is not

The live spike ran every protocol check with one credential, "the stored CLI API key" (`spikes/protocol/RESULTS.md:3`), the user's personal access token. The OAuth path the app prefers has a verified start and an unobserved finish.

| Step | Status | Evidence |
|---|---|---|
| `GET /api/auth/oauth-config` with no auth | VERIFIED | `spikes/protocol/captures/s01-auth.jsonl:2` |
| Device authorization start at `<apiURL>/user_management/authorize/device` | VERIFIED | `s01-auth.jsonl:4` |
| One device poll returns 400 `authorization_pending` | VERIFIED | `s01-auth.jsonl:6` |
| Device token exchange after approval | NOT VERIFIED | `spikes/protocol/RESULTS.md:11` ("token exchange and refresh are not observed") |
| An OAuth access JWT used as Bearer on `/api/internal`, `/api/thread-actors`, and the actor gateway | NOT VERIFIED. The CLI source sends it that way, confidence high. | `docs/protocol/private-protocol.md:36-37` |
| Refresh with `grant_type=refresh_token` and rotation | NOT VERIFIED | `docs/protocol/private-protocol.md:69`, `RESULTS.md:11` |
| `POST /api/auth/oauth-logout` | NOT VERIFIED | `docs/protocol/private-protocol.md:70` |
| Loopback PKCE (`/auth/cli/authorize`, `/api/auth/cli-redeem`) | NOT VERIFIED | `docs/protocol/private-protocol.md:56-64` |
| Personal access token as Bearer across internal RPC, thread actor, user actor, relay, push, and attachments | VERIFIED | `RESULTS.md:3`, rows S2 to S14 |
| Attachment URLs need Bearer | VERIFIED | `RESULTS.md:22` |
| Portal control plane: `resolveThreadPortalURL` and `mintPortalLoginURL` with Bearer | VERIFIED | `s13-portal.jsonl:2`, `:5` |
| Portal data plane after the login URL | INCONCLUSIVE (the runner had no tunnel, root answered 503) | `RESULTS.md:23`, `s13-portal.jsonl:9` |
| wsToken capability `write` from `POST /api/thread-actors` | VERIFIED for the owner | `RESULTS.md:14` |
| wsToken capabilities `read` and `contribute` | NOT OBSERVED. Read from the web bundle. | `docs/protocol/private-protocol.md:31`, `:285` |
| Passkey step-up for a Bearer client | NOT OBSERVED | none |
| Cross-Client Access disabled, seen from a Bearer client | NOT OBSERVED | none |
| A server other than `https://ampcode.com` | NOT OBSERVED | none |

The rest of this document never treats a NOT VERIFIED, NOT OBSERVED, or INCONCLUSIVE row as working. Each one has a named fallback.

## Sign-in tries three methods in a fixed order

Amp offers a third-party Android client no public way to sign a user in. Sign in with Amp applications "are not generally available", and only machine-to-machine applications can be created (Amp's public OpenAPI description, `info.description`). The user scopes Sign in with Amp would grant do not cover threads anyway (Amp's docs). The official macOS and iOS apps open the ampcode.com sign-in page and return to the app (Amp's docs). The CLI signs in with OAuth against the same server and gets the credential this app needs (`docs/protocol/private-protocol.md:51-70`). The app therefore uses the CLI's login flows, with the `clientID` that `oauth-config` returns at runtime (`s01-auth.jsonl:2`).

The sign-in screen offers the methods in this order:

1. Device flow, approved in the browser. This is the default.
2. Loopback PKCE in the browser. This appears under **Other ways to sign in** and runs on its own when the device flow cannot start.
3. Paste a personal access token. This is the last item under **Other ways to sign in**.

### Device flow is the default

The device flow needs no listener, no redirect, and no custom scheme that another app could claim. The secret half, `device_code`, never leaves the app. The browser only ever sees `user_code`, which is useless without `device_code` (`docs/protocol/private-protocol.md:65-68`). Its start is the only OAuth step verified live (`RESULTS.md:11`). The user can also approve on a laptop, which suits someone who starts work at a desk (`docs/design/product.md:7`).

The flow:

1. The app reads `GET <server>/api/auth/oauth-config` and keeps `clientID` and `apiURL`. On ampcode.com, `apiURL` is `https://authapi.ampcode.com` (`s01-auth.jsonl:2`).
2. The app posts `client_id` to `<apiURL>/user_management/authorize/device` and gets `device_code`, `user_code`, `verification_uri_complete`, `expires_in` 300, and `interval` 5 (`s01-auth.jsonl:4`).
3. The screen shows the `user_code` in large type (`signin-code`) and the button **Open sign-in page** (`signin-open-browser`). The page is `<server><deviceLoginPath>?user_code=<code>` when `deviceLoginPath` is present, and `verification_uri_complete` otherwise (`docs/protocol/private-protocol.md:67`). On ampcode.com that is `https://ampcode.com/auth/cli/device?user_code=…` (`s01-auth.jsonl:2`).
4. The page opens in a Custom Tab, so the user's browser session, saved passkeys, and SSO all work. A **Use a private browser tab** switch (`signin-private-tab`) opens an Ephemeral Custom Tab instead, which keeps no ampcode.com session in the browser afterward (A2). The default is off, because a normal tab makes approval one tap for a user already signed in to ampcode.com.
5. The app polls `<apiURL>/user_management/authenticate` with `grant_type=urn:ietf:params:oauth:grant-type:device_code` every `interval` seconds. It adds 5 seconds on `slow_down`, stops on `access_denied` or `expired_token`, and honors `Retry-After` on 429 and 5xx (`docs/protocol/private-protocol.md:68`).
6. On success the app brings `MainActivity` to the front, which closes the tab (A3), and shows the account confirmation described below.

The device flow uses a Custom Tab, not an Auth Tab. An Auth Tab hands control back only when the page navigates to a registered redirect (A1). The device page has no redirect to the app [INFERENCE: the CLI only polls and never waits for one, `docs/protocol/private-protocol.md:65-68`]. Auth Tab becomes the right choice when Amp offers a redirect to the app, which is the first item in [What we would ask Amp for](#what-we-would-ask-amp-for).

When the code expires after `expires_in`, the screen shows **Code expired** in the status line (`signin-status`) and **Get a new code** (`signin-new-code`). The page that approves the code names the CLI [INFERENCE], because the app uses the CLI's OAuth client.

If the token exchange fails in a way the spike never saw, such as an unknown error or a token the internal API rejects, sign-in ends with `SignInFailure.ServerRejected` (`docs/design/sketch/sync/Api.kt`, `SignInFailure`) and the screen offers the other two methods. The first build runs this flow to completion on a test account with a person approving the code, then repeats spike rows S2 and S4 with the resulting token, including attaching a runner with `client_spawn_executor`. Roadmap milestone M10 gates on that run (`docs/design/roadmap.md:21`).

### Loopback PKCE is the fallback

Loopback PKCE is the CLI's browser flow and yields the same tokens (`docs/protocol/private-protocol.md:56-63`). It ranks second because nothing in it has been exercised, and because it depends on two things a phone does not guarantee. The app process must stay alive while the user is in the browser, and the browser must navigate from an https page to `http://127.0.0.1:<port>` (A4).

The flow:

1. The app binds a listener to `127.0.0.1` on a random port, never to other interfaces. It creates `state` and a PKCE `verifier` of 32 random bytes each, and `challenge = base64url(sha256(verifier))`.
2. It opens `<server>/auth/cli/authorize?challenge=<c>&port=<port>&state=<state>`, plus `&redeem=amp` when `cliCodeRedemption` is true (`docs/protocol/private-protocol.md:58`), in a Custom Tab. An Auth Tab cannot carry this flow, because it rejects http redirects (A1).
3. The listener accepts one request to `/callback`. A missing code, an `error` parameter, or a `state` mismatch fails the attempt with 400. The listener closes after that request or after 10 minutes, the CLI's timeout (`docs/protocol/private-protocol.md:59`).
4. A code that starts with `amp-auth.` goes to `POST <server>/api/auth/cli-redeem` with `{code, verifier, redirect_uri}`. Any other code goes to `<apiURL>/user_management/authenticate` with `grant_type=authorization_code` and `code_verifier` (`docs/protocol/private-protocol.md:61-62`).

PKCE keeps a stolen code useless, because only the app holds `verifier`. If the process dies while the user is in the browser, the listener is gone and the user starts again.

### A personal access token is the last resort

A personal access token is the `sgamp_` key from **Settings**, **Security**, **Access Token** on ampcode.com (Amp's docs). It is the only credential verified across the whole protocol (`RESULTS.md:3`). It still ranks last:

- It is long-lived and has no refresh, so a copy works until the user revokes it on the web.
- The app cannot revoke it. Sign-out only deletes the local copy.
- It reaches the phone through the clipboard, where other apps and clipboard history can see it.
- It can drive executors. Spike row S4 attached a runner with this key. Passing `runnerId` while creating a new thread returned 403 "Runner attachment requires an API key and existing thread", and creating the thread first and then sending `client_spawn_executor` worked (`RESULTS.md:14`). A copy of it on a phone carries that power.

The S4 403 also points at a risk for the default sign-in. Its text names an API key, so runner attachment may refuse an OAuth token [INFERENCE: S4 never tried an OAuth token]. Milestone M10 reruns S4 with an OAuth token, including `client_spawn_executor` on a runner (`docs/design/roadmap.md:21`). If attachment refuses OAuth tokens, runner threads need a personal access token until Amp changes that.

The paste screen exists for three cases. The OAuth exchange fails on a server, a server reports `defaultLoginMethod: "api-key"` (`docs/protocol/private-protocol.md:55`), or a developer build needs a credential before milestone M10 (`docs/design/roadmap.md:12`). The input field (`signin-token-input`) is a password field and its window sets `FLAG_SECURE`. After a successful paste the app offers **Clear clipboard**.

### Every method ends with an account confirmation

After any method succeeds, the app fetches the account's identity before it stores anything:

1. `POST /api/user-actor-credentials` returns `userId` (`docs/protocol/private-protocol.md:47`, verified with Bearer at `:8`).
2. `getUserInfo` gives the email to display [INFERENCE: its result fields were never captured]. When the OAuth response carries `user.email`, the app uses that (`docs/protocol/private-protocol.md:63`).
3. The screen asks **Add <email> on <server host>?** with **Add account** (`signin-confirm-account`) and **Cancel**.

The confirmation protects against a device code approved by the wrong account, such as a code someone else approved with their own account. If the `(server, userId)` pair is already in the roster, the app refuses with "This account is already signed in", the same rule the web uses for saved accounts (ampcode.com's web bundle).

## A custom server URL serves workspaces with a required domain

Workspaces with a required custom domain serve the API on that domain (Amp's public OpenAPI description, `info.description`). The CLI keeps accounts per server (Amp's docs) and stores keys under `apiKey@<ampURL>` (`docs/protocol/private-protocol.md:36`). The app follows the same model, so an account is the pair `(server origin, userId)`.

The sign-in screen shows **Server: ampcode.com** with **Change** (`signin-server-url`). The rules for a custom server:

- The user types the server. The app never accepts a server URL from a deep link, a share intent, or a push, because a planted server would show its own sign-in page.
- The app normalizes the input to an origin such as `https://amp.example.com` and drops any path.
- Only `https` is accepted. Debug builds also accept `http` for `localhost`, `127.0.0.1`, and `10.0.2.2`, which matches the CLI's rule for `apiURL` (`docs/protocol/private-protocol.md:55`) and serves the fake server in `docs/design/verification.md:24`.
- The server must answer `oauth-config` with a `clientID` and an https `apiURL`. Anything else stops sign-in with "This server did not return Amp sign-in settings".
- The device-code screen, the confirmation, and every account row show the server host.

After sign-in, every request for the account goes to its server origin: internal RPC, `/api/thread-actors`, and the Rivet endpoint at `<server>/actors` (`docs/protocol/private-protocol.md:82`). Portal URLs come from `resolveThreadPortalURL`, never from a hostname the app builds, so a workspace's default portal domain works without setup (Amp's docs).

The CLI uses a different Rivet publishable key on staging than on ampcode.com (`docs/protocol/private-protocol.md:82`). Which key a custom domain needs is unknown, so a custom-server account is unverified until someone signs in against a real one. It is listed in [Open questions](#open-questions).

The app does not pin certificates. Amp controls its own certificates and can rotate them at any time, and a pin the app cannot update would lock every user out [INFERENCE]. The app trusts only system certificate authorities by default. Enterprise proxies that re-sign TLS need the user's installed authorities (Amp's docs), so each custom-server account has an opt-in switch, **Trust certificates installed on this device**, that applies to that account's origin only.

## Tokens live in a per-account vault

### Storage

Each account has one vault file, `vault-<accountId>.bin`, encrypted with a Tink AEAD keyset that an Android Keystore key wraps, in StrongBox when the device has it. `:sync` owns the vault through its internal `TokenVault`, and no other module reads it (`docs/design/sketch/sync`, `TokenVault`; `architecture.md:40-42`). The library is Tink for Android (`com.google.crypto.tink:tink-android`) with `AndroidKeysetManager`. The app does not use `androidx.security:security-crypto`, whose `EncryptedSharedPreferences` is deprecated (A5).

The vault holds two entries per account, and each has its own compare-and-set. `TokenEntry` holds the access token, its expiry, and the refresh token, and its compare-and-set keys on the refresh token. `PushKeys` holds the push key alias and the auth secret, and its compare-and-set keys on the alias. A token refresh therefore never writes back push keys it read before a push registration changed them.

| Secret | Where it lives |
|---|---|
| Access token, the OAuth JWT or the pasted `sgamp_` key | Vault, `TokenEntry` |
| OAuth refresh token | Vault, `TokenEntry` |
| Access token expiry | Vault, `TokenEntry` |
| Web Push private key (P-256) | A non-exportable Keystore key with `PURPOSE_AGREE_KEY`, alias `amp_push_<accountId>` (A6), named in `PushKeys` |
| Web Push auth secret (16 bytes) | Vault, `PushKeys` |
| wsTokens for actors | Memory only |

The push private key never leaves the Keystore, which matches `docs/design/notifications.md:35`. `:sync` performs the ECDH step of RFC 8291 decryption inside the Keystore and the rest in software, when `:notify` passes it a push body. Keystore ECDH needs API 31, so the app's `minSdk` is 31 (A6).

No key requires user authentication or an unlocked device. Background refresh, push decryption, and notification fetches must work while the phone is locked (A13). App lock, below, guards the interface, not the keys.

Vault files, account databases, and WebView profile data are excluded from cloud backup and device transfer through `dataExtractionRules`. Keystore keys never leave the device, so a restored vault could not be opened anyway. The server holds the truth, and a restored phone signs in again.

### Refresh uses compare-and-set

The CLI refreshes an OAuth access token 2 minutes before it expires and retries once after a 401 (`docs/protocol/private-protocol.md:37`). It uses compare-and-set on stored secrets so that two processes never rotate the same refresh token twice (`docs/protocol/private-protocol.md:69`). The access token lives for under an hour, because the CLI refuses the `amp login` session token in `AMP_API_KEY` for that reason (Amp's docs). An active app therefore refreshes about once an hour.

The refresh procedure, per account:

1. Take the account's refresh `Mutex`. Every caller in the process reaches the token through `:sync` and waits on it: windows, `:notify` workers through `BackgroundAccess`, the background sync, and `AuthenticatedImageLoader`.
2. Read the vault. If another caller already refreshed and the token is valid for more than 2 minutes, release and use it.
3. Post `grant_type=refresh_token` with the stored refresh token and `client_id` to `<apiURL>/user_management/authenticate`.
4. Write the new entry with `TokenVault.compareAndSet(account, expectedRefresh = <the token sent>, next)`, which touches `TokenEntry` only. If the stored refresh token changed in the meantime, discard this result and read again.
5. Retry the request that triggered the refresh, once.

`invalid_grant` sets `AuthHealth.NeedsSignIn` (`docs/design/sketch/core-model/Panes.kt`, `AuthHealth`). The account stays in the roster with its cached threads. Its sockets close, a banner reads **Sign in again**, and that action reruns the device flow. The new tokens replace the old ones only if the new `userId` matches the account. A different user is refused with "You signed in as <email>. This account is <email>."

One failure mode remains. If the server rotates the refresh token and the process dies before step 4 writes it, the stored token is dead and the next refresh gets `invalid_grant`. The user signs in again. A personal access token has no refresh, so a 401 on one goes straight to `NeedsSignIn`.

### Actor tokens stay in memory

Actor sockets use short-lived wsTokens minted over Bearer HTTP: `POST /api/thread-actors {threadId}` for a thread and `POST /api/user-actor-credentials {capability}` for the user actor (`docs/protocol/private-protocol.md:45-47`). The app keeps them in memory and never writes them to disk.

- The long-lived user actor socket uses a `read` token. Each write action mints a fresh `write` token, as the web does (`docs/protocol/private-protocol.md:164`).
- A thread socket refreshes its token before expiry with `client_reauthorize{wsToken}` and opens a new socket if that fails (`docs/protocol/private-protocol.md:120`). The web schedules refresh at `exp - min(30 s + random 15 s, 20% of lifetime)` (`docs/protocol/private-protocol.md:31`), and the app uses the same rule.
- A notification reply mints a token for one stateless `/request/rpc` call and drops it (`RESULTS.md:16`).

## Sign-out ends the session and wipes the account

**Sign out** on an account row (`account-sign-out-<accountId>`) runs these steps in order. `SignOutCoordinator` in `:app` runs them, because it is the only module that reaches `:sync`, `:notify`, `:feature:web`, and the image cache (`architecture.md:72-74`):

1. Close the account's sockets and cancel its WorkManager work, which carries the tag `account:<accountId>`.
2. If the outbox holds unsent messages, list them and ask **Sign out anyway?** first.
3. Delete the account's push registration. With the relay, `:sync` deletes it at the relay (`docs/design/push-relay.md`), and the relay answers Amp's next push for that account with `410 Gone`. With UnifiedPush, `:notify` unregisters the instance. Amp has no unsubscribe message (`RESULTS.md:21`), so Amp may keep pushing to the dead endpoint until it reacts to the error. Those pushes are encrypted to a key the next step deletes, so no one can read them.
4. For an OAuth account, call `POST <server>/api/auth/oauth-logout` with Bearer (`docs/protocol/private-protocol.md:70`, NOT VERIFIED). If the call fails or the device is offline, the vault keeps only the refresh token, marked pending revocation. A WorkManager job with a network constraint refreshes it once and calls `oauth-logout`, then wipes it. The job gives up after 7 days.
5. Delete everything the account owns: through `:sync`, the vault entries, the push key, and the Room database `amp-<accountId>.db`; through `:notify`, the account's notifications; through `:feature:web`, the WebView profile; then the image cache, launcher shortcuts, and widget rows.
6. Remove the account from the roster.

Two things outlive sign-out, and the sign-out screen says so:

- A personal access token stays valid on the server. The screen reads "This token still works until you revoke it in Settings, Security, Access Token on ampcode.com."
- The browser session that approved the device code stays signed in, unless the user chose a private tab. The screen links to **Active Sessions** in ampcode.com settings (Amp's docs).

If revocation is NOT VERIFIED when the app ships, the screen also says that Amp may keep the session alive until it expires, and links to **Active Sessions**.

## Up to five accounts, isolated from each other

The official apps keep up to five accounts signed in on one device (Amp's docs). The app uses the same cap. A sixth sign-in stops before the browser opens with `SignInFailure.TooManyAccounts` (`docs/design/sketch/sync/Api.kt`, `SignInFailure`). The account switcher (`account-switcher`) lives in the Sidebar (`docs/design/layout.md:29`). Rows carry `account-row-<accountId>`, and **Add account** carries `account-add`. The full screen layout and its other tags are in `screens.md`.

Each signed-in account gets its own engine, `AccountScope`, which owns every socket, cache, and token for that account (`docs/design/sketch/sync/Api.kt`, `AccountScope`). Nothing is shared between scopes:

| Resource | Per-account key | Deleted at sign-out |
|---|---|---|
| Access and refresh tokens | `vault-<accountId>.bin` with its own Keystore wrapping key, owned by `:sync` | Yes |
| Web Push key pair | Keystore alias `amp_push_<accountId>` | Yes |
| Push registration | Relay: `rid`, the registration secret, and the push URL in the account's vault entry in `:sync` (`docs/design/push-relay.md`). UnifiedPush: instance name `<accountId>` (`docs/design/notifications.md`, "Multiple accounts") | Deleted at the relay, or unregistered |
| Thread cache, drafts, outbox, notification ledger | Room database `amp-<accountId>.db`, one instance per process (`architecture.md:68`) | Yes |
| HTTP client | One OkHttp client per scope. Its Bearer interceptor attaches the token only to the account's server origin and its `apiURL`. | Discarded |
| Images | Coil disk cache in `images/<accountId>/`, loaded through `:sync`'s `AuthenticatedImageLoader`, which refreshes the token on a 401 | Yes |
| Portal, account, and **Open on web** pages | WebView profile `amp-<accountId>` (next section) | Yes |
| Background work | WorkManager tag `account:<accountId>` | Cancelled |
| Launcher shortcuts and widget rows | IDs prefixed with `<accountId>` | Yes |

The Bearer rule matters for images. Attachment URLs on the account's server need Bearer (`RESULTS.md:22`), but a transcript can also link images on any host. Only the account's own origin ever receives its token, so an image link the agent writes can never collect one.

Each window shows one account at a time, and two windows can show two accounts. The window state and the saved workspace record the window's account. A notification carries its account, because the push arrives on that account's endpoint (`docs/design/notifications.md:96`), and tapping it opens the thread in a window for that account. When more than one account is signed in, the notification shows the account's email as its subtext so the user knows which account a reply goes to.

### Web pages run in a per-account WebView profile

Three kinds of web page need an Amp session in the app. Portals are web apps that run in the executor (`docs/protocol/private-protocol.md:337`). Account pages are settings that only the web implements, such as billing and workspace administration (`docs/design/product.md:45`). **Open on web** and **Answer on web** open a thread's ampcode.com page, the fallback for anything the app cannot do natively. All three run in a WebView that uses the account's `androidx.webkit` `Profile`, named `amp-<accountId>` (A7). A profile has its own cookies, storage, and cache, so account B's page never sees account A's cookies. Sign-out deletes the profile with `ProfileStore.deleteProfile`.

A Custom Tab would use the browser's session instead. The browser has one ampcode.com session for all five accounts, so a Custom Tab can show account A's billing, or answer account A's approval, while the app is on account B. Sign-out also cannot clear a browser session, so a Custom Tab would leave the account signed in on a shared phone.

**Open on web** and **Answer on web** open in a side pane on Expanded and wider windows and as a full-screen route on Compact and Medium windows (`screens.md`). The pane header shows the account's email, so the user sees which account will act. On the first visit the profile may need Amp's web sign-in, as described for account pages below. The one exception is passkey step-up, which opens a Custom Tab (see [Passkey-required accounts](#passkey-required-accounts)).

**Portals** sign in without any web session. The app calls `mintPortalLoginURL{url}` with Bearer and loads the returned URL in the profile (`RESULTS.md:23`). The URL is one-time and expires 60 seconds after it is minted (`s13-portal.jsonl:5`). It sets the host-only cookies `__Host-amp_portal_auth_v2` and `__Host-amp_portal_auth_top` and redirects to the portal root (`s13-portal.jsonl:8`). When the portal cookie lapses, the portal redirects to `<server>/api/portal-auth/authorize` (`s13-portal.jsonl:3`). The WebView intercepts that navigation and mints a fresh login URL instead of showing a web sign-in. The spike never saw a portal page load after login (`RESULTS.md:23`), so the Portal pane keeps **Open in browser** as a fallback until a portal loads end to end on an orb.

**Account pages** need an ampcode.com web session in the profile, and no Bearer-to-web-session handoff is known [INFERENCE: none appears in `docs/protocol/private-protocol.md`, and the iOS app uses its own `ampNativeAuthenticate` hook, Amp's docs]. On the first visit per account, the profile shows Amp's web sign-in. Some sign-in methods fail inside a WebView. Passkeys need a Digital Asset Links association between the app and ampcode.com (A8), Google refuses embedded WebViews (A9), and SSO providers may as well (Amp's docs). The page frame therefore always shows **Open in browser** (`web-open-in-browser`), which moves the same URL to a Custom Tab. Before it opens, the app shows "Your browser may be signed in to a different Amp account." **The Self-Hosted orb list** is the one piece of web data the app shows natively. Amp serves it only to the web session: the remote queries `1igxvbu/getWorkspaceSelfHostedOrbs` and `18dvixz/getPersonalOrbProviders` answer 401 to the Bearer key (`spikes/protocol/captures/s24-orb-providers.jsonl:2`, `:4`), and the web composer reads `orbProviderOptions` from the `/(app)` layout's page data (web chunk `aa7cdc32.js`, `l.data.orbProviderOptions`). When the New Thread picker opens, `:feature:web` makes a WebView on the account's profile that is never shown and never navigates, and loads a page of its own as a page of the account's origin. That page fetches `/__data.json?x-sveltekit-trailing-slash=1&x-sveltekit-invalidated=110` the way SvelteKit's client does (`bd6913a5.js`, `function Ei`), so the profile's cookie goes with the request and only the root and `/(app)` layouts are computed. It posts back each option's `configID`, `name`, `isDefault`, and `owner`, and whether a `user` is set, through a `WebViewCompat.addWebMessageListener` channel that only the account's origin can reach. The rest of the page data never leaves the page. `:sync`'s `OrbProviderPage` parses the message. Native code never reads, copies, or stores the cookie. Without a web session the page data answers 401 or a redirect, or has no `user` [INFERENCE: what ampcode.com answers a signed-out profile is not observed], and the picker then offers the web sign-in in the same profile.

Every WebView the app hosts follows the same rules:

- No `addJavascriptInterface`. MCP App pages get a `postMessage` channel that accepts only `mcpAppCallTool` and `mcpAppReadResource` (`docs/design/sketch/settings.gradle.kts`, `:feature:web`). The hidden Self-Hosted orb reader gets a one-message channel that only the account's origin can reach (above).
- File and content access are off. Safe Browsing is on.
- Navigation to a non-https scheme, including `intent:`, asks the user before it leaves the app.
- Camera, microphone, and location requests are denied unless the user allows them for that origin.
- Downloads go through the Storage Access Framework with a confirmation.
- The pane frame shows the page's real host in native chrome, outside the page, so a page cannot fake it.
- The app never adds an `Authorization` header to a WebView request.
- WebAuthn stays off in WebViews (A8), so a portal page cannot ask for the user's Amp passkey.

## Capability decides what the thread view offers

Each thread socket's wsToken carries `cap`, which is `read`, `contribute`, or `write` (`docs/protocol/private-protocol.md:31`). The owner gets `write` (`RESULTS.md:14`). A multiplayer contributor gets `contribute`, and the web treats `contribute` plus an open thread as access (`docs/protocol/private-protocol.md:285`). Read-only viewers get `read`. The sketch maps these to `Viewer.Owner`, `Viewer.Contributor`, and `Viewer.Reader`, with `NotYetGranted` before the first grant (`docs/design/sketch/core-model/ThreadState.kt`, `Viewer`).

The server enforces capability. The app hides what a viewer cannot do so the user never meets a dead button, and states the reason in text, never with a missing control alone.

| Surface | `write` | `contribute`, thread open | `read` |
|---|---|---|---|
| Composer: send, steer, queue, interrupt | Yes | Yes (Amp's docs) | Replaced by "View only" (`ComposerBlock.ReadOnlyViewer`) |
| Tool approvals | Answer | Shown with "Only the thread owner can approve" (Amp's docs) | Shown, not answerable |
| Plugin dialogs without a secret field | Answer | Answer (Amp's docs) | Shown, not answerable |
| Secret inputs and confirms with a secret field | Answer | "Only the thread owner can answer" (Amp's docs) | Same |
| Rename, labels, color, visibility, multiplayer, archive, delete | Yes | Hidden [INFERENCE] | Hidden [INFERENCE] |
| Pin, mark unread, snooze | Yes | Yes. These are the viewer's own state on the user actor [INFERENCE: `setThreadPinned` is a user actor action, `RESULTS.md:20`]. | Yes, same reason |
| Changes: read the diff | Yes | Yes | Yes when the server returns it [INFERENCE] |
| Changes: stage, **Ship**, **Review**, **Sync** | Yes | Hidden [INFERENCE] | Hidden |
| Terminal and Files | Yes | Yes (Amp's docs) | Hidden [INFERENCE] |
| Portal | Yes | Yes, except contributors who must use a passkey (Amp's docs) | Yes, thread viewers can open portals (Amp's docs) |

Capability can change while a thread is open, for example when a multiplayer window ends. The link supervisor sets `Viewer` from each new grant (`docs/design/sketch/sync/internal/ThreadEngine.kt`, `LinkSupervisor`), and the screen recomputes. A draft the user can no longer send stays on the device with "You can no longer send to this thread."

Before the first grant, `NotYetGranted` shows controls from the cached thread and queues commands, and the grant then decides (`docs/design/sketch/core-model/ThreadState.kt`, `Viewer.NotYetGranted`).

## Policy gates the app cannot read in advance

Three workspace or user policies can block an action. None of them has a known `cli`-tier read, so the app reacts to the server's answer.

### Passkey-required accounts

A user can turn on **Require Passkey Authentication for Web & App Interaction**, and Enterprise admins can require it for every member (Amp's docs). The web calls the elevated state a sudo session. It lasts `sudoSessionDurationHours` hours (ampcode.com's web bundle) and runs on `web-remote` functions such as `9rl4ty/beginSudoPasskeyAuthentication` and `9rl4ty/completeSudoPasskeyAuthentication` (ampcode.com's web bundle).

The web recognizes a step-up demand in three ways, and the app watches for the same three:

- A response with `error: "SUDO_AUTH_REQUIRED"`, or HTTP 401 whose message starts with "Passkey authentication required" (ampcode.com's web bundle).
- A thread grant with less than the owner's capability. Before a write, the web runs the passkey prompt and then waits up to 30 seconds for write access, or shows "Could not reconnect to the thread with write access. Try again." (ampcode.com's web bundle).
- A gate flagged `sudoAuthRequired`, which the web answers with **Authenticate to Respond** (ampcode.com's web bundle).

Whether any of these reach a client that signs in with the CLI's OAuth client is NOT OBSERVED. A sudo session belongs to a web session, which the `sid` claim identifies (`docs/protocol/private-protocol.md:31`). The app's Bearer session is a different session, so a passkey ceremony in the browser would not elevate it [INFERENCE].

When the app sees a step-up demand, it does this:

1. Set `AuthHealth.NeedsStepUp` on the account and `EngineError.StepUpRequired` on the action (`docs/design/sketch/core-model/Panes.kt`, `AuthHealth`; `docs/design/sketch/sync/Api.kt`, `EngineError`).
2. Keep the user's draft or answer on the device.
3. Replace the blocked control with "This account needs a passkey to act from the app" and **Open on web** (`stepup-open-web`). This is the one **Open on web** that does not use the account's WebView profile. It opens the thread's ampcode.com page in a Custom Tab, because passkeys work in the browser and not in the app's WebView (A8). Before it opens, the app shows "Your browser may be signed in to a different Amp account." The web runs its own step-up, and the user acts there.
4. Pick up the result from the thread socket as soon as the web action lands.

Notification actions for such an account turn into **Open** (`docs/design/notifications.md:76`).

The app does not run a native passkey ceremony. The ceremony's endpoints are `web-remote` and bound to the web session, and native code never calls `web-remote` (`docs/design/sketch/build-logic/ModuleGraphRules.kt`, the `/_app/remote/` ban).

The app also does not try to bypass the policy. If testing shows that Amp does not apply this policy to the app's Bearer session, the app cannot tell whether a user or workspace requires passkeys. That case is a release gate in the next subsection.

### Cross-Client Access disabled

An admin can turn off **Cross-Client Access** in Member Settings. Members then cannot send messages to CLI threads from the web or start threads on their runners from outside the CLI, and the web hides runners (Amp's docs). The workspace flag is `disableRemoteControl`, and the web reads it from settings data (ampcode.com's web bundle). The passkey flag sits next to it as `requireSudoForRemoteControl` (`:76658`).

The app reads the server's answer [INFERENCE: runner and CLI threads count as CLI threads]:

- If a runner or CLI thread grants less than `write` to its owner, or a send fails with a policy error, the thread opens read-only with the banner "Your workspace turned off Cross-Client Access. Continue this thread in the CLI." (`readonly-banner`).
- If `client_spawn_executor` on a runner fails with a policy error, the executor picker hides runners for that account with the same reason, and orbs stay available. Machine panes show `UnavailableReason.PolicyDisabled` (`docs/design/sketch/core-model/ThreadState.kt`, `UnavailableReason`).
- The app keeps the policy bit per account and checks it again at each sign-in and once a day.

**Release gate.** The app must not become a way around a workspace policy. Before release, a test runs on an Enterprise workspace with Cross-Client Access off and passkeys required. If the server blocks the app, the behavior above handles it. If it does not, the app needs a `cli`-tier way to read both policies, perhaps from `getUserInfo`, and enforces them itself. Without such a read, release waits for Amp's answer to the policy question in [What we would ask Amp for](#what-we-would-ask-amp-for).

### Orb creation that needs a recent sign-in

`POST /api/thread-actors` can fail with `ORB_CREATION_REAUTH_REQUIRED` (`docs/protocol/private-protocol.md:45`). Workspaces set a maximum auth age for orb creation (Amp's docs), and the web answers with "Could not create thread" and **Sign In Again** (ampcode.com's web bundle). The app shows the same message, and **Sign In Again** reruns the device flow for that account with the same `userId` check as `invalid_grant`. The new-thread draft survives the sign-in.

## App lock is optional and guards the interface

**App lock** is off by default. In Settings, the toggle (`app-lock-toggle`) offers **Lock after** with the choices Immediately, 1 minute, 5 minutes (the default once on), and 30 minutes. Unlock uses `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, so a fingerprint or the device PIN works (A11).

While the app is locked:

- Every window, on every display, shows the lock screen with **Unlock** (`app-lock-unlock`). One lock state covers the process, so unlocking one window unlocks all of them.
- The Recents thumbnail is hidden (A12).
- Background work keeps running. Refresh, push decryption, and notification fetches do not wait for unlock.
- Tapping a notification opens the lock screen first, then the thread.

App lock addresses an unlocked phone in someone else's hands. It does not protect data at rest, which relies on Keystore keys and Android's file encryption. Its key is not bound to biometrics, because binding it would stop background work.

## Notification actions require an unlocked device

**Reply**, **Allow**, and **Deny** run an agent action from the lock screen. Each one is built with `setAuthenticationRequired(true)`, so the system asks for the device unlock before the action runs (A10). This applies whether or not app lock is on. A locked phone left on a desk should not let a passerby approve a shell command.

Notifications also use `VISIBILITY_PRIVATE` with a public version that shows only "Amp" and the channel name, such as "Needs you". Whether the lock screen shows the private version follows the user's system setting.

## Secrets stay out of screenshots

`FLAG_SECURE` blacks out a window in screenshots, screen recordings, casting, and Recents (A12). On the TriFold one window often holds three panes, so the flag hides the whole arrangement while it is set. The app sets it only while a secret is on screen:

| Screen | When the flag is set |
|---|---|
| Personal access token paste (`signin-token-input`) | While the screen is open |
| A gate with a secret input or a secret field in a plugin confirm | While the field is visible |
| Native secret editors, such as env var secrets if they are native | While the editor is open |
| Account pages under the security routes, such as **Access Token**, **Passkeys**, and **Sessions** | While the page is on a security route |

The device-code screen does not set the flag. A `user_code` lets someone approve the sign-in, but only the app holding `device_code` gets a token, and the account confirmation catches a code approved by the wrong person.

Secret fields also turn off autocorrect and IME learning, mask input, and never write their value to the draft store, the outbox, or a log. A secret travels as an `Ephemeral` command: it stays in memory, and the outbox codec accepts only durable commands, so the compiler rejects writing one to the outbox table (`architecture.md:105-116`). Amp never echoes secret plugin inputs into thread history, and the client must not persist them (Amp's docs).

agent-device screenshots of a window with `FLAG_SECURE` come out black. Flows that cover these screens assert on the accessibility tree, which is what agent-device reads (`docs/design/verification.md:7`), and these screens have no screenshot baselines.

## Redaction rules for logs

Debug builds write one `AgentNet` logcat line per finished HTTP exchange, in the format `agent-device network dump` parses, and one `AmpWs` line per socket frame (`docs/design/verification.md:35-43`). Release builds write neither. These rules apply to both.

The spike's `redact()` is the starting point (`spikes/protocol/lib.ts:23-35`). It missed one case. `s01-auth.jsonl:5` holds a `device_code` in plain text, because the code sat in a form-encoded body and the redactor only matched JSON keys and query names that start with `code`. That code expired 300 seconds after it was issued (`s01-auth.jsonl:4`). A live `device_code` in a log lets whoever reads it race the app for the token, so the app's redactor parses form bodies too.

| What | Where it appears | Rule |
|---|---|---|
| `Authorization` header with `Bearer` or `AmpActor` | Every HTTP call | Value becomes `<redacted>` |
| `x-rivet-conn-params` header and the `rivet_conn_params.<json>` subprotocol | Actor HTTP calls and sockets | Value becomes `<redacted>` |
| `Cookie` and `Set-Cookie` | Any response that sets cookies | Values removed, cookie names kept, as in `s13-portal.jsonl:8` |
| Query parameters `rvt-token`, `token`, `code`, `user_code`, `device_code`, `ticket`, `sig`, `signature`, `X-Goog-Signature`, `X-Goog-Credential`, `upload_id` | Gateway URLs, portal login URLs, signed upload URLs | Value becomes `<redacted>` (`spikes/protocol/lib.ts:34`, extended) |
| JSON and form keys `wsToken`, `apiKey`, `orbToken`, `token`, `ingressToken`, `authorization`, `device_code`, `access_token`, `refresh_token`, `password`, `p256dh`, `auth` (`spikes/protocol/lib.ts:23`), plus `code`, `code_verifier`, `verifier`, `client_secret`, and `value` inside secret-input answers | Request and response bodies | Value becomes `<redacted>` |
| JWTs, `sgamp_` keys, and the Rivet publishable key | Anywhere in a line | Pattern replacement (`spikes/protocol/lib.ts:29-33`) |
| Push endpoint URLs | `client_upsert_notification_subscription` and relay registration responses | Host kept, path redacted. A relay push URL and an ntfy topic are both capability URLs. The relay registration secret is never logged. |
| One-time portal login URLs | `mintPortalLoginURL` results (`s13-portal.jsonl:5`) | Whole query redacted |
| Message text, tool input and output, file contents, terminal bytes | Bodies and frames | Never logged. Frames log only `type` and `seq` (`docs/design/sketch/sync/internal/Transport.kt`). |

Bodies stay off by default in debug builds (`docs/design/verification.md:43`). Network exceptions reach the interface as `EngineError` values without raw messages, because OkHttp exception text can contain URLs. The **Report bug** diagnostics bundle (`docs/design/sketch/core-model/Intents.kt`) passes through the same redactor and includes thread content only if the user ticks a box.

Two checks keep the rules honest:

1. A JVM test runs the redactor over every spike capture and over a fixture of unredacted secrets in every encoding above. Any surviving secret fails the build. The RESULTS redaction check (`spikes/protocol/RESULTS.md:26-35`) becomes this test.
2. `:fake-amp` issues tokens that contain the canary string `AMPCANARY`. After each agent-device flow, the harness searches `adb logcat -d` and the `network dump` output for the canary. Any hit fails the run.

## Threat model

| Threat | What an attacker gets without mitigation | What the app does | What remains |
|---|---|---|---|
| Lost phone, locked | Notification text on the lock screen, and Reply, Allow, or Deny from the lock screen | `VISIBILITY_PRIVATE` with a minimal public version. Every action requires device unlock (A10). Keys and files stay encrypted at rest (A13). | Nothing beyond what the device lock protects. The user can end sessions in ampcode.com **Active Sessions** [INFERENCE: whether that list shows CLI OAuth sessions is unknown]. A personal access token must be revoked on the web. |
| Lost or borrowed phone, unlocked | Every thread, terminal, portal, and the power to send, approve, and Ship | Optional app lock with biometrics. Recents thumbnail hidden while locked. | Without app lock the phone is as open as the user left it. Server-side revocation is the remedy. |
| Malicious portal page | Phishing for Amp credentials, cookies of other accounts, the user's Bearer token, intents into other apps, device permissions | One WebView profile per account. Portal login by one-time URL, never by Bearer header. No JavaScript bridge, no file access, no WebAuthn, confirmation before non-https navigation, permissions denied by default, real host in native chrome. | The page can still show a convincing fake form inside its own rectangle. The native frame shows the true host. |
| Prompt-injected agent output | Links that phish, images that collect tokens, MCP App or amp-view pages that act on the user's behalf | Bearer goes only to the account's own origin. Links open in a Custom Tab with no app credentials. MCP App pages reach only `mcpAppCallTool` and `mcpAppReadResource`. | A tool call that an MCP App requests still runs with the user's rights, as on the web. |
| Shared runner | A teammate's shared runner executes as its owner with the owner's files and credentials (Amp's news). Multiplayer gives every member the thread, orb or runner, secrets, files, and terminal (Amp's docs). | The executor picker shows each runner's owner. The first use of another person's runner asks "Runs on <owner>'s machine as <owner>". Terminal on a shared runner labels the owner. Enabling multiplayer shows Amp's security warning. | The runner owner can read anything the thread sends to their machine. That is how Amp's shared runners work. |
| Leaked log, from logcat over adb, `network dump`, or a bug report | Bearer tokens, wsTokens, a live `device_code`, signed upload URLs, thread content | Redaction rules above. No `AgentNet` or `AmpWs` lines in release. Canary test on every flow. Apps without `READ_LOGS` cannot read logcat (A12). | A debug build on a shared device exposes request paths and timing, but no secrets. |
| Malicious app on the same device | A stolen OAuth callback, a planted server URL, intents that trigger sends or approvals | Device flow has no callback to steal. Loopback uses PKCE and `state` on `127.0.0.1` only. Server URLs are typed, never received. No exported entry point performs an action, and every external intent only navigates. Notification receivers are not exported. `PendingIntent`s are explicit and immutable, except the `RemoteInput` reply. The `amp-debug://` scheme exists only in debug builds (`docs/design/verification.md:20`). | A malicious accessibility service can read the screen. Android leaves that to the user's consent. |
| Network attacker or corporate TLS proxy | Traffic and tokens | TLS only, cleartext only to localhost in debug builds. User-installed authorities only on an opt-in per custom-server account. | A user who opts in trusts their organization's proxy with their tokens. |
| Compromised push relay, FCM service account, or UnifiedPush distributor | Push content and endpoint | Pushes are RFC 8291 encrypted to a key only the app holds (`docs/design/notifications.md`, "Amp sends Web Push, and our relay turns it into FCM"). A forged push fails decryption and is dropped. A relay wake message only makes the app sync from Amp over its authenticated channel (`docs/design/push-relay.md`, "Security"). | The relay and FCM see timing, size, and the registration id. Either can withhold or delay pushes. |

## The private protocol is the main risk

Amp publishes no API that lets a third-party app sign a user in, read threads, or drive an agent. The public External API is read-only for threads, and its user sign-in is not generally available (Amp's public OpenAPI description). Everything the app does runs on the protocol the CLI uses. It is private and undocumented, and Amp can change it without notice. The app signs in with the CLI's OAuth client, so to Amp's servers its tokens look like CLI tokens.

The protocol is not arbitrary, though. Shipped CLIs call these endpoints, so the server must keep them working for old CLI builds, "but not forever" (`docs/protocol/private-protocol.md:18`, `:331`).

### What could break

- **Sign-in.** Amp can restrict the CLI's OAuth client, change `oauth-config`, or turn off the device grant. New sign-ins then fail, and existing accounts fail at their next refresh. The pasted-token path keeps working as long as `sgamp_` keys do.
- **Transport.** RivetKit is a `main`-branch build. Gateway query names (`rvt-*`), subprotocol prefixes, and the publishable key can change with a Rivet upgrade (`docs/protocol/private-protocol.md:331-332`).
- **Messages.** The thread actor's message types are shared with the CLI, 232 of 233 (`docs/protocol/private-protocol.md:22`). New types appear as features ship, and fields can change.
- **Push.** Amp could limit Web Push endpoints to browser push services (`docs/protocol/private-protocol.md:346`). Notifications would then fall back to Watch and polling (`docs/design/notifications.md:84-92`).
- **Policy.** Amp could start applying "Web & App" policies to CLI-client tokens. That would show up as `SUDO_AUTH_REQUIRED` and reduced grants, which the app already handles.
- **Terms.** No clause in Amp's terms of service or acceptable use policy names third-party clients. The acceptable use policy says free usage "may only be used through Amp products and services". The app drives Amp's own agents, so it should count as using Amp [INFERENCE]. Amp decides that, not us.

### How the app detects drift

- Each sign-in checks that `oauth-config` has a `clientID` and an https `apiURL`. Each response is checked for the `{ok, result}` or `{ok:false, error}` envelope (`docs/protocol/private-protocol.md:171`). A failure becomes `EngineError.Drift` with the endpoint name.
- An unknown thread event raises `Degradation.Seen` and a visible banner. An unknown tool gets `GenericToolCard`, and an unknown gate becomes `UnsupportedGate` with **Answer on web** (`docs/design/transport.md:615-627`, `docs/design/sketch/RATIONALE.md`, "Drift").
- **Settings**, **Diagnostics** shows each account's drift counters and the last failing endpoint. The **Report bug** bundle includes them.
- Contract tests replay every spike capture through the parser on each change (`docs/design/verification.md:63`).
- The nightly live drift check (`docs/design/verification.md:71`) adds three auth steps. It fetches `oauth-config`, starts a device authorization without approving it (as spike row S1 did safely), and refreshes the token of a dedicated test account, storing the rotated refresh token back in the CI secret store. A failure in any of them alerts the team before users notice, and the fix ships through a Play in-app update.

### What we would ask Amp for

1. **Sign in with Amp for third-party apps.** A client ID of our own instead of the CLI's, scopes that cover threads and the actor protocol, an HTTPS redirect so the app can use an Auth Tab (A1), and a documented revocation endpoint. Today Sign in with Amp is not generally available and its scopes exclude threads (Amp's docs).
2. **FCM or a stable push contract.** Either native Android registration like the APNs path behind `dispatchUserNotification(…, activeNativePlatform?)` and `getNativeAppIdentity` (`docs/protocol/private-protocol.md:180`, `:275`), or a written promise that `client_upsert_notification_subscription` accepts any RFC 8030 endpoint, plus an unsubscribe message, which does not exist today (`RESULTS.md:21`).
3. **An android shell dialect.** `dataset.ampShell = "android-v1"` and recognition of an Android user agent (Amp's docs), so pages the app shows can hand control back. This would include `/sudo?returnToApp=1`, which already exists for the apps (ampcode.com's web bundle). It would also include a native step-up hook like `ampNativeSudoAuthenticated` and a Bearer-to-web-session handoff like `ampNativeAuthenticate` (Amp's docs).
4. **This app in ampcode.com's `assetlinks.json`.** That gives verified App Links for thread URLs and passkeys inside the app's WebView (A8).
5. **A `cli`-tier read of effective policy.** Cross-Client Access, the passkey requirement, and the orb-creation auth age, so the app can hide what a policy forbids instead of failing on it.
6. **A ruling on policy scope.** Do "Web & App Interaction" policies cover CLI-client tokens used from a phone? The release gate above depends on the answer.

## Android behavior this design assumes

Each row is [INFERENCE]. It comes from Android or Chrome documentation and has not been exercised in this project.

| ID | Assumption | Source |
|---|---|---|
| A1 | Auth Tab (`androidx.browser` 1.9.0, Chrome 137 and later) returns a result only on navigation to a custom-scheme redirect or a Digital Asset Links verified https redirect. It rejects http redirects and falls back to a Custom Tab on older browsers. | https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab |
| A2 | An Ephemeral Custom Tab keeps cookies and history isolated and deletes them when the tab closes. | https://developer.chrome.com/docs/android/custom-tabs/guide-ephemeral-tab |
| A3 | A Custom Tab runs in the caller's task, so starting `MainActivity` with `FLAG_ACTIVITY_CLEAR_TOP` and `FLAG_ACTIVITY_SINGLE_TOP` closes it. | https://developer.chrome.com/docs/android/custom-tabs |
| A4 | Chrome on Android lets a top-level navigation from an https page reach `http://127.0.0.1:<port>` on the same device. | Not documented for this case. The first build tests it. |
| A5 | `androidx.security:security-crypto` `EncryptedSharedPreferences` is deprecated in 1.1.0. Tink with an Android Keystore master key is the maintained path. | https://developer.android.com/reference/kotlin/androidx/security/crypto/EncryptedSharedPreferences |
| A6 | Android Keystore supports ECDH key agreement with a non-exportable EC P-256 key (`KeyProperties.PURPOSE_AGREE_KEY`) from API 31. | https://developer.android.com/reference/android/security/keystore/KeyProperties#PURPOSE_AGREE_KEY |
| A7 | `androidx.webkit` `ProfileStore.getOrCreateProfile` and `WebViewCompat.setProfile` give each profile its own cookies, storage, and cache, behind `WebViewFeature.MULTI_PROFILE`. | https://developer.android.com/reference/androidx/webkit/ProfileStore |
| A8 | Passkeys in a WebView need `WebSettingsCompat.setWebAuthenticationSupport(WEB_AUTHENTICATION_SUPPORT_FOR_APP)` and a Digital Asset Links association between the app and the site. | https://developer.android.com/identity/sign-in/credential-manager-webview |
| A9 | Google sign-in refuses requests from embedded WebViews. | https://developers.googleblog.com/upcoming-security-changes-to-googles-oauth-20-authorization-endpoint-in-embedded-webviews/ |
| A10 | `Notification.Action.Builder.setAuthenticationRequired(true)`, API 31, makes the system require device unlock before the action's intent is sent. | https://developer.android.com/reference/android/app/Notification.Action.Builder#setAuthenticationRequired(boolean) |
| A11 | `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` accepts a strong biometric or the device PIN, pattern, or password. | https://developer.android.com/identity/sign-in/biometric-auth |
| A12 | `FLAG_SECURE` blacks out the window in screenshots, recordings, Recents, and non-secure displays. `Activity.setRecentsScreenshotEnabled(false)`, API 33, hides only the Recents thumbnail. Apps without `READ_LOGS` cannot read other apps' logcat, and adb can. | https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE |
| A13 | Keystore keys created without `setUserAuthenticationRequired` or `setUnlockedDeviceRequired` stay usable while the screen is locked, after the first unlock since boot. App files live in credential-encrypted storage. | https://developer.android.com/privacy-and-security/keystore |

## Open questions

- Does the device-flow token exchange succeed with the CLI's `clientID` from an Android client, and does the resulting JWT work as Bearer on `/api/internal`, `/api/thread-actors`, and the actor gateway? The first build answers this before milestone M10.
- Does runner attachment with `client_spawn_executor` accept an OAuth token, or only an API key, as the S4 403 text suggests? M10 reruns S4 with an OAuth token.
- Does refresh return a rotated refresh token every time, and how long does a refresh token live? Is there a grace window for a refresh token used twice?
- Does `POST /api/auth/oauth-logout` revoke both tokens, and does ampcode.com **Active Sessions** list CLI OAuth sessions?
- What does `getUserInfo` return? Does it include the email, the workspace, `disableRemoteControl`, or `requireSudoForRemoteControl`?
- Do "Web & App Interaction" passkey requirements and Cross-Client Access apply to a CLI-client Bearer token? If they do, what error or grant does the app see?
- Which Rivet publishable key does a workspace custom domain need, and is the actor endpoint `<server>/actors` there too?
- If a workspace requires a custom domain and the user signs in at ampcode.com, does the server refuse, redirect, or accept?
- Does Chrome on the TriFold allow the loopback redirect to `http://127.0.0.1:<port>` (A4)?
- Does a portal page load in a WebView profile after `mintPortalLoginURL` on an orb thread? Spike row S13 stopped at a 503 on a runner without a tunnel.
- Which ampcode.com sign-in methods work inside a WebView profile for account pages and **Open on web**? If most users must use **Open in browser**, the profile adds little for those pages, and the warning before **Open in browser** carries the account safety alone.
- Which headers identify a client to the internal API (`docs/protocol/private-protocol.md:350`), and does Amp gate features on the user agent? The app sends an honest `AmpAndroid/<version>` user agent and never claims to be the CLI's terminal client or the iOS app.
