# Notifications

Notifications are the main reason to carry Amp on a phone. The app tells you when a thread is ready for your next message or needs an approval, and lets you answer from the notification when the answer is short.

## Amp sends Web Push, and our relay turns it into FCM

Amp has no Firebase Cloud Messaging path of its own. The server sends standard Web Push (RFC 8030 with RFC 8291 `aes128gcm` encryption) to whatever endpoint URL a client registers. The spike registered an ntfy.sh endpoint, finished a turn, and received an encrypted push about one second later that decrypted to this payload (`spikes/protocol/captures/s11-push.jsonl:78`):

```json
{
	"kind": "thread_idle",
	"threadId": "T-…",
	"threadURL": "https://ampcode.com/threads/T-…",
	"title": "Rename add to add_numbers",
	"body": "done",
	"tag": "amp-thread-T-…-idle-M-…",
	"requireInteraction": false,
	"icon": "…",
	"badge": "…"
}
```

Amp's service worker handles two kinds, `thread_idle` and `approval_required` (`spikes/protocol/RESULTS.md` row S11). The payload is a wire format, so `:sync` decodes it: `:notify` passes the push body to `BackgroundAccess.decodePush`, which returns a `PushEvent` (`architecture.md:30-38`). The decoder accepts unknown kinds, and `:notify` shows them as a generic thread notification with the payload's `title` and `body`.

Android only wakes a sleeping or stopped app reliably through FCM, and Amp cannot call FCM for this app, because sending needs our Firebase project's credentials. So we run a relay: a Cloudflare Worker that gives each account on each device its own push URL, receives Amp's encrypted POST there, and forwards the bytes unchanged to the device as an FCM data message. Only the app holds the decryption key, so the relay never sees thread titles or content. The relay is specified in `push-relay.md`.

The app picks one push channel per device, in this order:

1. **Relay.** Used when Google Play services are available, which covers the TriFold and almost every Android phone. Nothing to install.
2. **UnifiedPush.** Used on devices without Google Play services when a UnifiedPush distributor such as ntfy is installed. ntfy only accepts encrypted Web Push on topics named `up` plus 12 characters with an active subscriber, which is the format its distributor hands out (`RESULTS.md` row S11).
3. **None.** Onboarding offers ntfy. Until the user installs it, the background sync and **Watch**, described below, are the only paths.

**Settings**, **Notifications** shows which channel is in use. The app switches channels only when the device changes, for example when a UnifiedPush distributor is installed on a device without Google Play services. Both channels deliver the same encrypted bytes to `BackgroundAccess.decodePush`, so everything after delivery is identical.

## Subscriptions are per thread

Amp attaches a push subscription to one thread at a time with `client_upsert_notification_subscription{subscription{endpoint, keys{auth, p256dh}}, threadURL}` on the thread socket (`RESULTS.md` row S11). There is no unsubscribe message. Subscriptions from different endpoints accumulate: the app's subscription still got the push after two later upserts from other endpoints on the same thread (`RESULTS.md` row S23b). `:sync` owns the push keys: one key pair per account and per push registration, with the private key in the Android Keystore (`auth-and-security.md:118-133`).

Amp sends no push for a thread while any client has it open. With one client connected through the turn, no subscriber got a push; with none connected, all three did (`RESULTS.md` rows S23 and S23b). An ampcode.com tab left open on a thread therefore silences that thread on the phone too, and so does a socket the app itself holds. The app covers that gap itself, as described under "When Amp stays silent".

The app subscribes a thread when any of these happens:

- You create the thread from the app.
- You open the thread in the app.
- The background sync finds a thread you own that is working or recently active and has no subscription from this device.

The background sync runs as periodic WorkManager work at the 15-minute minimum. It reads `getRecentThreads` through a user actor action and subscribes threads that need it. This catches threads you start from the CLI, the web, Puck, or an automation while the app is closed. The upsert only works on the thread socket, because `/request/rpc` rejects it (`RESULTS.md` row S18), so each new subscription costs one short socket connection through `BackgroundAccess`. When the thread's engine already has a socket open, the upsert rides that socket instead (`architecture.md:101`). A thread this device never opened has no cache, so its short socket resumes from the `threadVersion` that `POST /api/thread-actors` returned and replays no history (`transport.md:275`). Whether connecting the thread socket alone wakes a paused orb is unmeasured, so the sync skips threads whose orb is paused and subscribes them the next time they report activity.

The same sync also reads the user actor's `indicator` field. While a plugin dialog waits, `threadStatusUpdated` carries `indicator{kind: "action-required", description}` and returns to `null` once someone answers (`RESULTS.md` row S15b). The sync turns an `action-required` indicator into a **Needs you** notification even when no push arrived, which covers threads that have no subscription from this device yet. No push arrived for a waiting plugin dialog within 45 seconds in the spike (row S15c), so this path matters.

A turn that ends in `error` sent no push in spike S11b, which watched for 90 seconds after the error (`RESULTS.md` row S11b-2). The background sync therefore also posts failed turns in the **Finished** channel with the error text, from the thread's `error_set` event or from the user actor's thread status [INFERENCE: S14 saw the statuses `idle`, `working`, `streaming`, and `tool_use`, and an error status there is unobserved].

## When Amp stays silent

With **Notify even when a thread is open elsewhere** on, which is the default, the app reads turn ends from the user actor and posts them itself. Settings > Notifications holds the switch, and its line says that the phone checks for itself, a few minutes late or longer while it sleeps. Off leaves only Amp's push.

A turn has ended when a user actor summary is `idle` with a `latestAssistantMessageID` and a `lastTurnEndedAt` later than the moment this account's checks began. `TurnEnds` in `:notify` holds that rule and the tag. The tag is the one Amp's own push carries, `amp-thread-<threadId>-idle-<latestAssistantMessageID>`. In `s11-push.jsonl:77-78`, the pushed tag names the `protocolMessageID` of the turn's last `end_turn` message, and the user actor reports that id as `latestAssistantMessageID` (S14). A local post and a push for the same turn therefore claim the same row in the notification ledger, and whichever comes second posts nothing. When the push comes second, it fills its reply text into the local notification without a second alert, because a local post has no reply text. The rule does not need to see the thread working first, because a check every few minutes can miss a whole turn.

Amp sends the `idle` that ends a turn a few milliseconds before the update that carries the turn's new `latestAssistantMessageID` (S14 lines 40-41 and 52-53). The live path therefore waits 1 second after a thread's last update before it asks, so it never posts the previous turn's id. A background read lands in that gap only by chance [INFERENCE: a gap of 1 to 4 ms, from S14].

The checks run on three paths, and each one leaves an `AmpNotify` line that names it, such as `live thread_idle T-01a1a000: posted on finished`:

- **live**: while the app is in front, one user actor socket per account receives `threadStatusUpdated` and posts at once. It closes when the app leaves the front.
- **poll**: the 15-minute background sync, and a relay wake message, post what `getRecentThreads` shows.
- **chain**: while any of the account's threads is working, the sync queues one more check 3 minutes later, for at most 2 hours from the first. Leaving the front queues the first one. The checks are plain one-time WorkManager work, because WorkManager refuses a delay on expedited work, and Doze and app standby defer them. No foreground service runs.

A finish that the app does not show, because a started window shows the thread or the thread is snoozed, is still written to the ledger, so neither path posts it later. The first check after sign-in, or after the switch comes back on, posts no backlog.

## The app decides what to show

Before posting a notification, the app checks local state:

- If any started window is showing the thread, the app shows nothing. The web and native apps behave the same way.
- If you snoozed or muted the thread, the app shows nothing until the snooze ends. Amp ends every snooze when the thread needs approval, so `approval_required` always shows.
- The payload `tag` is the notification tag, so a repeated push replaces the earlier notification instead of stacking, and the app's own check of the same turn posts nothing.

Channels:

| Channel | Kinds | Default importance |
|---|---|---|
| Needs you | `approval_required` | High, heads-up |
| Finished | `thread_idle` | Default |
| Watching | Live progress of watched threads | Low, ongoing |
| Other | Unknown kinds | Default |

Notifications group by project, with the project name as the group summary.

## Answer from the notification

A **Reply** action on `thread_idle` takes text through `RemoteInput`. The app sends it without opening a socket, through the stateless thread actor RPC: `POST /actors/gateway/threadActor/request/rpc` with the `x-rivet-conn-params` header and a JSON-RPC `client_append_user_msg` body (`RESULTS.md` row S6). The notification updates to show the sent message, and a later push reports the next idle.

An `approval_required` push does not say what needs approval. On receipt, `:notify` runs expedited work that asks `BackgroundAccess` for the thread's gates. `BackgroundAccess` opens a short socket, or joins the thread's open socket, resumes from the cached cursor or from `threadVersion` when the thread has no cache, and reads the pending approval from the snapshot the actor sends after `client_resume`. The notification then updates with the tool name and the command or file. Pending plugin dialogs appear in the same reconnect snapshot (`RESULTS.md` row S15c), so a plugin confirm gets the same treatment.

The actions depend on what the notification can show:

- **Allow** appears only when the whole tool input or dialog text fits in the notification's visible text. Otherwise the action is **Review**, which opens the thread with the gate focused, so nobody approves a command they could not read in full. **Deny** appears in both cases. The same rule applies to the inline gate in the thread (`screens.md`).
- A plugin text input gets a `RemoteInput` field. A secret plugin input gets only **Open**, because the notification cannot mask what the user types. Its answer, sent from the thread, is an `Ephemeral` command that never reaches the outbox table (`architecture.md:105-116`).
- If the account requires passkey authentication for interaction, every action is replaced with **Open**, because the passkey prompt needs the app in front.

Answers cannot use the stateless path. `/request/rpc` accepts only `client_append_user_msg` and rejects the other 17 methods the spike tried with `-32601 Method not found`, including `client_tool_approval_response` (`RESULTS.md` rows S15 and S18). So **Allow** and **Deny** run expedited work that sends `client_tool_approval_response{toolCallId, accepted}` or `plugin.ui.respond` over a short socket or the thread's open socket, waits for the settle event, and releases the socket. The answer is a Guarded command, and its guard is checked after `client_resume` finishes on that socket, so an approval already answered on another client is dropped instead of sent twice.

When a dialog settles or is withdrawn anywhere, its notification goes away. `plugin.ui.request_settled` and `plugin.ui.request_removed` both reach `:notify` through `BackgroundAccess.settledGates`, from any socket of the account, and `:notify` cancels the notification. `request_removed` is read, not observed (`transport.md:316`).

The tool approval wire format comes from the web bundle and was not observed live, because the workspace's model routing blocked every agent turn during the second spike (`RESULTS.md` row S15). The plugin dialog path was observed end to end (row S15b).

## Watching a running thread

A push arrives when a turn ends, not while it runs. For threads you want to follow live, the app offers **Watch** in the thread menu. Watching keeps a foreground service holding the user actor socket, which streams `threadStatusUpdated` events (`idle`, `working`, `streaming`, `tool_use`; `RESULTS.md` row S14). The app shows each watched thread as an Android 16 Live Update (`Notification.ProgressStyle`) with the current state. The service stops when every watched thread is idle, and it never runs longer than the user's chosen limit, 30 minutes by default. When the limit ends, watching falls back to periodic polling.

The foreground service type is `specialUse`, declared for live agent monitoring. The manifest declares `FOREGROUND_SERVICE_SPECIAL_USE` and a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property that names the use case, and Google Play reviews that text with the app's foreground service declaration (read, not observed, [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)). The user-chosen limit is what bounds the service. Earlier drafts used `dataSync` to avoid the Play declaration. That does not work: an app that targets Android 14 or later must declare every foreground service type it uses in Play Console ([Play Console Help 13392821](https://support.google.com/googleplay/android-developer/answer/13392821)). Android also defines `dataSync` as data transfer, such as upload, download, backup, and import, which describes holding a status socket poorly.

`KeepAlivePolicy`, one pure function in `sketch/notify/KeepAlive.kt`, chooses between push, watch, and polling from the current socket leases, watched threads, push registration, the time left in the user's watch limit, and network state.

The app does not watch threads automatically. Push already covers the end of a turn, and a silent foreground service costs battery.

## Permission

On Android 13 and later nothing shows until the user grants `POST_NOTIFICATIONS`. The app asks once, as soon as a window shows a signed-in account. That covers every sign-in path (browser, device code, pasted token) and a launch with an account that signed in before the app learned to ask. The ask is an in-app dialog, "Get notified when a thread needs you or finishes.", with **Turn On** and **Not Now**. Turn On shows Android's prompt. Not Now, or a tap outside the dialog, is final for the automatic ask.

`NotificationAccess` in `:core:model` names the four states, and `WindowNotifications` in `:app` reads them on every resume:

| State | When | Turn On does |
|---|---|---|
| Allowed | Granted, and Amp's notifications are on in Android settings. Channels the user turned off are listed. | Opens Android's settings for Amp |
| FirstAsk | Not granted, never requested, no denial. The dialog shows. | Shows Android's prompt |
| AskAgain | Not granted, and Android would still show its prompt: after Not Now, after one denial, or after a denial in another screen (`shouldShowRequestPermissionRationale`) | Shows Android's prompt |
| SettingsOnly | Requested and denied with no rationale (two denials), or granted but blocked in Android settings | Opens Android's settings for Amp |

The app remembers only whether it explained (Not Now) or requested, in the `notification-prompt` preferences, because a denied permission with no rationale means "never asked" before the first request and "denied for good" after it. Settings > Notifications shows the state, the channels that are off, and Turn On while notifications are off. The Sidebar shows one muted line, "Notifications are off", with Turn On and a dismiss button, which hides it for good.

## Multiple accounts

Each signed-in account gets its own push registration and its own key pair. With the relay, that is one push URL per account. All of them forward to the device's single FCM token, and each forwarded message carries the registration id, so the app knows which account it belongs to (`push-relay.md`). With UnifiedPush, it is one distributor instance per account. At sign-out, `SignOutCoordinator` in `:app` calls `:sync` to delete the relay registration or `:notify`'s `AccountTeardown` to unregister the UnifiedPush instance, and `:notify` cancels the account's notifications (`architecture.md:72-74`). Amp's next push to that URL then gets `410 Gone`, and standard Web Push servers drop such subscriptions [INFERENCE: Amp's handling of 404 and 410 is unverified].

## Testing notifications

Flows inject a push with the `inject-push` app event, which runs a plaintext payload through the same decoder and display logic as a real push (`verification.md:28`). agent-device's `push` command sends an Android broadcast (the TriFold's published specs), and the app registers no debug broadcast receiver for it.

An account on a server on this machine (`localhost`, `127.0.0.1`, `10.0.2.2`, which only debug builds accept) never registers with the deployed relay. Its push URL is `<server>/push/<account id>` on that server (`Endpoint.ownPushUrl`), and `:fake-amp` hands a push to such a URL to the debug app, which passes it to `PushInbox` like a relay message. So a flow can drive the real subscription path: `fake-thread-elsewhere {threadId, title}` starts a thread the app never opened, `background-sync {}` sends the relay's wake message, and `fake-turn-elsewhere {threadId, text}` ends a turn on it once it has a subscription (`flows/notify-outside-thread.ad`).

Like Amp, the fake sends no push for a thread while any socket has it open. `fake-turn-elsewhere` takes three more fields: `openElsewhere: true` opens the thread in another client first, as an ampcode.com tab would, `state: "working"` starts the turn instead of ending it, and `afterMs` ends it that much later, so a flow or a script can send the app to the background first. `flows/notify-open-elsewhere.ad` checks the live path and the switch. The background chain is a JobScheduler job with a 3-minute minimum latency in `adb shell dumpsys jobscheduler`, and `adb shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler com.agentifiers.amp <job id>` runs it at once.

Every push leaves one Info line under the `AmpNotify` tag with its kind, the first 10 characters of the thread id, and what happened, for example `push thread_idle T-01a16738: posted on finished` or `suppressed, Android notifications are off for Amp`. The relay service logs `relay: body received` and why it dropped a message, registration logs `push: registered, 3 subscription(s) added`, and the background sync logs `poll: 1 subscription(s) added`. No line carries payload text, a title, a token, or a full relay id.

Notifications are system UI, so a flow opens the shade with `adb shell cmd statusbar expand-notifications`, takes an `agent-device snapshot`, and asserts on the notification's text, because notification views carry no app resource ids [INFERENCE]. `adb shell cmd statusbar collapse` closes the shade (`verification.md:51-58`).

The end-to-end path, from Amp through the relay and FCM to the app, gets its own live test that finishes a cheap turn on a runner, opens the shade, and waits for the notification text with `agent-device wait`. The relay's own tests are in `push-relay.md`.

## Open questions

- Does Amp send other kinds, such as mentions, multiplayer requests, or Space invitations, over Web Push? The web settings list those preference kinds, but the service worker only names two.
- Does a later upsert from the same endpoint replace the earlier subscription? Does Amp keep one subscription per thread, so that a desktop browser and the phone evict each other's subscription whenever either one connects?
- Does Amp drop a subscription after its endpoint returns 404 or 410?
- Will Amp add FCM for native Android clients? If it does, the relay becomes unnecessary.
