# Screens and surfaces

This document specifies the behavior of every surface in Amp for Android: what each one shows, what each control does, which Amp contract backs it, and what the app does when that contract is not proven. It uses the pane and arrangement names from `layout.md`, the channels from `notifications.md`, and the test-tag rules from `verification.md`. Deeper specs live in `auth-and-security.md` (sign-in protocol and passkeys), `git-and-diffs.md` (the Changes pane), and `terminal.md` (the Terminal pane).

## How to read this document

Each claim about Amp cites a file and line in `spikes/` or `docs/protocol/`, names Amp's docs or ampcode.com's web bundle, or a file plus a type, a row, or a section in `docs/design/sketch/`, whose line numbers keep moving. A claim with no source carries an `[INFERENCE]` label. Statements about what the app does are the spec and need no source.

Contract status uses only the verdicts in `spikes/protocol/RESULTS.md`, rows S1 to S20 (`:11-24` and `:65-72`):

- **Verified** means a spike row observed the call working.
- **Read, not observed** means the CLI, Amp's docs, or the web client shows the call (`docs/protocol/private-protocol.md:172`), but no spike exercised it. Every read-not-observed path in this document names its fallback.
- **Not verified** and **inconclusive** are spike verdicts. The app never presents those paths as working. Each one has a fallback.

Two fallbacks recur:

- **Open on web** opens the thread's ampcode.com page in the account's web view, defined below. On Expanded and wider windows the page opens in a side pane (`web-pane`) that takes the machine pane's place next to the thread. On Compact and Medium windows it opens as a full-screen route. The pane header shows the account's email, so the user sees which account the page acts for. The app does not use the browser's session for this, because one browser session serves all five accounts and can belong to the wrong one (`docs/design/auth-and-security.md:203-213`).
- **Answer on web** is the same action, labeled for a human gate the app cannot render or answer.

A passkey step-up is the one exception. Passkeys do not work in the app's WebView, so its **Open on web** (`stepup-open-web`) opens a Custom Tab after the warning "Your browser may be signed in to a different Amp account." (`docs/design/auth-and-security.md:213`, `:269`).

"Web view" in this document means an ampcode.com page in a WebView that uses the account's own profile, `amp-<accountId>`, so one account's page never sees another's cookies. The first visit per account shows Amp's web sign-in inside the profile. The page frame always offers **Open in browser** (`web-open-in-browser`), which moves the same page to a Custom Tab after the same warning (`docs/design/auth-and-security.md:203-213`). Native code never calls SvelteKit remote functions (`docs/design/sketch/RATIONALE.md`, "What it deliberately does not do"). A hidden web view in the same profile reads the New Thread picker's Self-Hosted list, and native code gets only that list, never the cookie (`docs/design/auth-and-security.md`, "The Self-Hosted orb list").

## Contract status at a glance

| Surface | Primary path | Status | Fallback |
|---|---|---|---|
| Sign-in | Device authorization flow | Verified to `authorization_pending`. Token exchange not observed (`spikes/protocol/RESULTS.md:11`) | Loopback browser sign-in (`docs/design/roadmap.md:21`), specified in `auth-and-security.md` |
| Thread list and status | `listThreads`, user actor `getRecentThreads` and `threadStatusUpdated` | Verified (`spikes/protocol/RESULTS.md:12`, `:24`) | None needed |
| Search and Activity | `/api/threads/find`, user actor `listFeedItems` | Read, not observed (`docs/design/sketch/COVERAGE.md`, row `find-threads-activity-feed-and-query-syntax`) | Filter cached summaries on the device and say so |
| Live thread, resume, paging | Thread actor socket, `client_resume`, `getThreadInitialTranscriptPage`, `getThreadTranscriptPage` | Verified (`spikes/protocol/RESULTS.md:14`, `:19`, `:69`) | None needed |
| Sleeping orb thread | `client_spawn_executor{executorDemand:"optional"}` on the shown thread's socket, as the web's open thread page sends (`987da778.js`, search `pane-refresh`) | Verified live: the sleep frames and one wake (`spikes/protocol/RESULTS.md`, rows S21 and S21b) | **Retry** and **Open on Web**. See [Wake an orb only for a shown thread](#wake-an-orb-only-for-a-shown-thread) |
| Steer and interrupt | `client_append_user_msg{steer:true}`, `client_cancel` | Verified (`spikes/protocol/RESULTS.md:15`) | None needed |
| Queue | `client_append_user_msg` without `steer` while the agent is busy | Read, not observed. Spike row S5 captured only a steer (`spikes/protocol/RESULTS.md:15`). The M3 gate adds a queued send | If a plain append does not queue, **Queue** keeps the message on the device and sends it when the agent goes idle |
| Send retry | Client `messageId` on `client_append_user_msg` | Verified: a resend leaves one copy (`spikes/protocol/RESULTS.md:68`) | None needed |
| Pin, label, rename, unread, archive | Internal RPC and socket messages | Verified (`spikes/protocol/RESULTS.md:20`) | None needed |
| Snooze | `updateThread{snooze}` | `one_hour` and `off` verified (`spikes/protocol/RESULTS.md:20`). The other options are read, not observed | An option the server rejects is hidden for the account, and the menu offers **Open on web** |
| Snooze read | None. `listThreads` has no snooze field (`spikes/protocol/RESULTS.md:20`) | No read path | Device snooze ledger (`docs/design/sketch/RATIONALE.md`, "Tradeoffs accepted") |
| New thread on a runner | `POST /api/thread-actors`, then `client_spawn_executor` | Verified (`spikes/protocol/RESULTS.md:14`) | None needed |
| New thread in an orb | `client_spawn_executor{projectID, environmentResource}` | Read, not observed. The spike account could not start orbs (`docs/design/sketch/COVERAGE.md`, row `spawn-an-orb-thread-ox`) | **Retry** or another executor on the same thread, or **Discard** |
| New worktree on a runner | Runner capability `create-worktree` (`spikes/protocol/RESULTS.md:13`) | Spawn parameter read, not observed (`docs/design/sketch/COVERAGE.md`, row `runner-create-worktree-archive-and-remove-worktree`) | Start in **Current** on the same thread |
| Mode catalog | `listAgentModes`, `getAgentModelCatalog` | Read, not observed | The four Dial modes plus `agentModes` from `listRunners` (`spikes/protocol/RESULTS.md:13`) |
| Tool approvals, `ask_user_choice` | `tool_approval_queue`, `client_tool_approval_response` over the socket | Inconclusive. No approval was queued in S15 (`spikes/protocol/RESULTS.md:65`). `/request/rpc` rejects the answer (`:70`) | **Answer on web** |
| Plugin dialogs | `plugin.ui.request_added`, `plugin.ui.respond`, `plugin.ui.snapshot` | Verified for `ui.confirm`, `ui.input`, `ui.notify`, and recovery after reconnect (`spikes/protocol/RESULTS.md:66`, `:67`). `ui.select` read, not observed | **Answer on web** for a request that fails to decode |
| Plugin commands | `plugin_message` `command.list`, `executor_plugin_message` `command.execute` | Verified (`spikes/protocol/RESULTS.md:66`) | None needed |
| MCP elicitation | Delivery channel unknown | Read, not observed (`docs/design/sketch/COVERAGE.md`, row `mcp-elicitation-questions-from-remote-servers`) | **Answer on web** |
| MCP server approval | `listMCPServerApprovals`, `approveMCPServer` | Read, not observed | **Answer on web** |
| Attachments | `/api/attachments/resumable` | Verified for an image (`spikes/protocol/RESULTS.md:22`) | None needed |
| Dictation | `/api/voice-transcription` | Bearer read, not observed (`docs/design/sketch/COVERAGE.md`, row `dictation`) | On-device `SpeechRecognizer` |
| Changes | Live git over the socket | Verified for runner threads (`spikes/protocol/RESULTS.md:17`) | See `git-and-diffs.md` |
| Terminal | Relay `amp-terminal-v1` | Verified on a runner (`spikes/protocol/RESULTS.md:18`) | See `terminal.md` |
| Files | `client_filesystem_read_directory_v2`, `client_filesystem_read_file_v2`, `client_filesystem_write_file_v3` | Verified for list, whole-file read, and write (`spikes/protocol/RESULTS.md:71`). `range` reads are rejected, and `getThreadFileURLs` answered `permission-denied`, so the app uses neither | **Open Files on web** when a request fails |
| Portal | `resolveThreadPortalURL`, `mintPortalLoginURL` | Inconclusive (`spikes/protocol/RESULTS.md:23`) | Custom Tab with the portal URL |
| Share and visibility | `updateThread{visibility}` | Read, not observed | Share dialog on web |
| Multiplayer | `client_set_thread_open{ttlSeconds}` | Read, not observed | Multiplayer menu on web |
| Desktop | `amp-desktop-v1` over the relay | Read, not observed (`docs/design/sketch/COVERAGE.md`, row `runner-desktop-sharing`) | Later release. **Open on web** meanwhile |
| Space | `space-token` and LiveKit | Bearer read, not observed (`docs/design/sketch/COVERAGE.md`, row `space-voice-video-screen-share-in-a-thread`) | Later release. **Open on web** meanwhile |
| Thread cost | `threadDisplayCostInfo` | Measured (`spikes/protocol/RESULTS.md:46`) | None needed |
| Push | Web Push through our relay and FCM, or through UnifiedPush on devices without Google Play services | Web Push to a non-browser endpoint verified with ntfy (`spikes/protocol/RESULTS.md:21`). Delivery to the relay is checked by spike S11b before M9 (`docs/design/roadmap.md`) | See `notifications.md` |

## Sign-in and accounts

### Sign in

The first launch shows a welcome screen with one button, **Sign In**, and an **Advanced** link. **Advanced** reveals a **Server URL** field (`signin-server-url`) for workspaces that require a custom domain (`docs/design/product.md:27`). Workspaces with a required domain serve the API on that domain (Amp's docs). `auth-and-security.md` owns the sign-in tags and the rest of the flow. This section reuses its tags.

**Sign In** starts Amp's device authorization flow. The app fetches `oauth-config` without auth, requests a device code, and shows it (`spikes/protocol/RESULTS.md:11`).

| Element | Behavior | Tag |
|---|---|---|
| Code | The user code in large monospace text. | `signin-code` |
| **Copy Code** | Copies the code. | `signin-copy-code` |
| **Open Sign-in Page** | Opens `verification_uri_complete` in a Custom Tab. The user can also approve on another device. | `signin-open-browser` |
| Status line | "Waiting for approval" while polling every `interval` seconds. | `signin-status` |
| Expiry | The code expires after `expires_in` (300 s in the spike, `spikes/protocol/RESULTS.md:11`). On expiry the screen shows **Get New Code**. | `signin-new-code` |

The spike stopped at `authorization_pending`, so token exchange and refresh are not observed (`spikes/protocol/RESULTS.md:11`). If the exchange fails, the screen offers **Sign In with Browser**, the loopback flow in `auth-and-security.md` (`docs/design/roadmap.md:21`).

When a window first shows a signed-in account, by any sign-in path or on a later launch, the app opens the Sidebar and asks once for notification permission (`POST_NOTIFICATIONS`): a dialog says "Get notified when a thread needs you or finishes." with **Turn On**, which shows Android's prompt, and **Not Now**. The app never asks on its own again after Not Now or a denial. While notifications are off, Settings > Notifications says so with Turn On, and the Sidebar shows one dismissible line, "Notifications are off", with Turn On (`notifications.md`, "Permission"). Push registration with the relay runs on its own, whether or not the user allows notifications. On a device without Google Play services it runs the UnifiedPush onboarding from `notifications.md` instead.

### Account switcher

The account switcher is the avatar button at the top of the Sidebar (`account-switcher`). The official apps keep up to five accounts signed in (Amp's docs). The app matches that limit.

| Element | Behavior | Tag |
|---|---|---|
| Account row | Avatar, email, workspace name ("Personal" or the team), the server host only when it is not ampcode.com, and the count of threads in **Needs you** for that account ("2 need you", `account-needs-you-<accountId>`, absent at 0). Tapping switches the current window to that account. | `account-row-<accountId>` |
| Credits line | The balance from `userDisplayBalanceInfo` and, when a workspace entitlement applies, "Entitlement: {remaining} remaining of {limit} {windowPeriod} limit ({percentUsed}% used)". The bar turns to the error color above 70% used, as on the web (Amp's docs). | `account-credits-<accountId>` |
| **Add Account** | Starts sign-in for another account. At five accounts it is disabled with the text "You can keep up to 5 accounts signed in." | `account-add` |
| **Sign Out** | Per account, in the row's **Account actions** menu (`account-menu-<accountId>`), so it never sits beside navigation, behind a confirmation (UX-20). `SignOutCoordinator` closes the account's sockets, deletes its tokens, push keys, and cache, unregisters its push instance, and deletes its WebView profile (`docs/design/architecture.md`, "Sign-out has one coordinator", `docs/design/notifications.md:96`). | `account-sign-out-<accountId>` |
| **Manage Account** | Opens account settings in a web view. | `account-manage` |

Each window shows one account. Two windows can show two accounts side by side. Notifications carry their account, and tapping one switches the target window to that account first.

## Sidebar

The Sidebar is the thread list for one account (`layout.md`, "Panes"). It reads `listThreads` and the user actor's `getRecentThreads` and `threadStatusUpdated` events (`spikes/protocol/RESULTS.md:12`, `:24`). It never calls the web's sidebar group functions (`docs/design/sketch/COVERAGE.md`, row `sidebar-organization`).

### Header

The header is one 48dp row: the account avatar, then icon buttons, each with its label as content description and tooltip (UX-02). The Amp wordmark sits after the avatar when the pane is wide enough for it, from about 344dp. The search field is the first item of the list, so it scrolls away with the rows.

| Element | Behavior | Tag |
|---|---|---|
| Account avatar | Opens the account switcher. | `account-switcher` |
| **New Thread** (pen icon) | Opens the new-thread composer in the Thread pane. | `new-thread` |
| **Activity** (pulse icon) | Opens Activity in the Sidebar's slot. | `nav-activity` |
| **Puck** (sparkles icon) | Opens the Puck pane. | `nav-puck` |
| **Refresh** (refresh icon) | Reloads the thread list. | `sidebar-refresh` |
| **Collapse** (panel icon) | Collapses the Sidebar to the rail, offered only where that frees a panel (three-panel Triage, `layout.md` "Arrangements"). | `sidebar-collapse` |
| Search field | Filters the Sidebar with the query syntax in [Activity and search](#activity-and-search). With no match it shows "No threads match", the active filters, and "Try label:, project:, or archived:true." (`thread-search-empty`). | `thread-search` |

The window bar's **Command Palette** opens the [Command palette](#command-palette).

### Sections and order

Sections follow ampcode.com, with **Needs you** added on top (Amp's docs). Each section title sets `heading()` and stays pinned at the top of the list while its rows scroll under it (UX-07). A section with no rows is hidden, so a new account sees "No threads yet" with **New Thread** (`sidebar-empty`, `sidebar-empty-new-thread`) instead of four empty headers (UX-08). Until the first list arrives, the list says "Loading threads…" (`sidebar-loading`).

| Order | Section | Contents | Tag |
|---|---|---|---|
| 1 | **Needs you** | See [Needs you](#needs-you). | `sidebar-section-needs-you` |
| 2 | **Pinned** | Pinned threads, any state. | `sidebar-section-pinned` |
| 3 | One section per project | Active threads of that project, newest activity first. Threads with no project go under **No Project** (Amp's docs). | `sidebar-section-project-<projectId>` |
| 4 | **Inactive Last 72h** | Collapsed by default. Its header has an **Archive All** button that archives every thread in the section after a confirmation (Amp's docs). A chevron icon shows or hides the rows. | `sidebar-section-inactive`, `inactive-archive-all`, `sidebar-section-inactive-toggle` |
| 5 | **Snoozed** | Collapsed by default. | `sidebar-section-snoozed` |
| 6 | **Archived** | Collapsed by default. | `sidebar-section-archived` |

Rows hold still while the user is in the list. While a pointer hovers over the Sidebar, a finger touches it, or a row has keyboard focus, no row changes position. Status text, dots, and badges still update in place, and a new row goes to the end of its section. When the pointer, the finger, and focus have all left, the list sorts again. A tap therefore never lands on a row that moved under it (`docs/design/product.md`, "The core loops").

Child threads that an agent created appear under their parent row as an indented group with a count, collapsed by default. `listThreads` returns `relationships` for each thread (`spikes/protocol/RESULTS.md:12`). Agents create child threads in other orbs and on other runners, so the sidebar fills with them and parentage must be visible (Amp's news).

Snooze state for **Snoozed** comes from the device snooze ledger, because `listThreads` has no snooze field (`spikes/protocol/RESULTS.md:20`). A snooze made on another client appears after the ledger reconciles (`docs/design/sketch/RATIONALE.md`, "Tradeoffs accepted"). Until then the thread shows in its project section.

### Thread rows

Each row is one list item with tag `thread-row-<threadId>`. It shows:

- The status dot and the title on the first line, with the time since last activity. A row with unread replies shows its title in semibold. The app has no unread count, because `getRecentThreads` reports `hasUnreadMessages` as a boolean (UX-23).
- The status text on the second line, which takes the dot's color. Status is always text, never color alone (`docs/design/verification.md:13`), except that **idle** and **inactive** show no word, as they show no dot (UX-06). The row's accessibility state still reads the status.
- The executor badge, a server icon and the orb size such as `a1.medium` or the runner hostname, only when it differs from the executor most of the account's threads use. When no executor covers half of the threads, every row shows its badge (UX-06).
- The project name when the row is outside its project section, such as in **Pinned** or **Needs you**.
- The thread color as a stripe at the start edge, and up to two labels as chips.
- An origin badge for Linear, Slack, automation, or Puck when the thread's first message carries that origin (Amp's docs).

A row with nothing to show on its second line is one line tall.

### Status text

Status comes from `threadStatusUpdated.state` and `hasUnreadMessages` (`spikes/protocol/RESULTS.md:24`), and from the thread socket's agent state when the thread is open (Amp's docs).

| Status text | Shown when | Dot |
|---|---|---|
| needs approval | A tool approval is pending (`awaiting_approval`). | Red |
| needs input | A plugin dialog, `ask_user_choice`, or MCP prompt is pending. For plugin dialogs the user actor reports `indicator{kind:"action-required"}`, even with no client connected (`spikes/protocol/RESULTS.md:66`, `:67`). | Red |
| working | State is `working`, `streaming`, `tool_use`, or `running_tools`. | Amber, pulsing |
| compacting | State is `compacting`. | Amber, pulsing |
| done | State is `idle` and the thread has unread replies. | Blue |
| idle | State is `idle` and nothing is unread. | None |
| error | State is `error`. | Red outline |
| setup failed | Orb setup failed (Amp's docs). | Red outline |
| orb asleep | The orb is paused. | Gray |
| runner offline | The runner is not connected. | Gray |
| waiting for orb | Orb spawn pacing holds the start (Amp's docs). | Gray, animated |

`StatusDot.kt` in `:feature:fleet` holds this column as the app's one status-to-dot table. Sidebar rows, the rail, and Running read it, and the status word takes the dot's color. A status the table does not name, such as "inactive" or a state the app does not recognize, shows no dot or a gray one. On a row the executor and the project name share the width the status word and label chips leave, and each ellipsizes when it does not fit. The working dot breathes once every 2.4 seconds in steps of 100 ms, all dots on one clock, so a working thread costs the window 10 frames a second rather than one every vsync.

Gates come first (UX-01). For any thread a window shows, the thread socket's pending gates decide the status before its agent state does: a pending tool approval is "needs approval", any other pending gate is "needs input", and only then come error, working, and the rest. The row moves into **Needs you** at once and leaves it when the gate settles. `ThreadState.fleetStatus` in `:core:model` is that one derivation, and the thread title bar reads it too. The spike saw `threadStatusUpdated.state` take the values `idle`, `working`, `streaming`, and `tool_use` (`spikes/protocol/RESULTS.md:24`). [INFERENCE] The user actor may not report `awaiting_approval`, so a thread no window shows reads "needs approval" only from an `approval_required` push.

### Row actions

Every row action is a real button, because agent-device cannot see long-press or custom accessibility actions (`docs/design/verification.md:11`).

- **Swipe from the end edge** archives the row. A snackbar offers **Undo**.
- **Swipe from the start edge** opens the snooze menu.
- **Overflow button** (`thread-row-<threadId>-more`) opens a menu with every action below.
- **Right-click** on a row, or a long-press on touch, opens the same menu as a context menu.
- **Open in New Window** opens the thread in its own window (`layout.md`, "Multiple windows").

### Organize actions

These actions appear in the row menu and in the thread's **More Actions** menu. Tags are `thread-row-<threadId>-<action>` in the row menu and `thread-menu-<action>` in **More Actions**.

| Action | Behavior | Backing | Action tag |
|---|---|---|---|
| **Pin** and **Unpin** | Moves the thread into or out of **Pinned**. | `updateThread{pinned}` and `setThreadPinned`, verified (`spikes/protocol/RESULTS.md:20`) | `pin` |
| **Color** | Picks a named color or none. The CLI accepts named colors and `#rrggbb` (Amp's docs). | `updateThread{color}` | `color` |
| **Labels** | Opens a label editor with autocomplete from labels already used. Labels are lowercased, alphanumeric plus hyphen, at most 32 characters, at most 20 per thread (Amp's docs). The editor enforces these rules as the user types. Suggestions sit in one sideways-scrolling row that keeps its height when no label matches, so the dialog never resizes or moves **Add** while the user types. | `addThreadLabels`, verified (`spikes/protocol/RESULTS.md:20`) | `labels` |
| **Rename** | Inline text field. | `client_set_thread_title`, verified. `updateThread{title}` is not used because `listThreads` kept the old title (`spikes/protocol/RESULTS.md:20`) | `rename` |
| **Snooze** | Submenu with **For 1 Hour**, **Until Next Reply**, **Until 8am**, **Until Monday Morning**, and **Indefinitely** (Amp's docs). A snoozed thread shows **Unsnooze** instead. The menu repeats Amp's note "Hides this thread and mutes its notifications. Its automation keeps running without ending the snooze." (Amp's docs). | `updateThread{snooze}`. **For 1 Hour** (`one_hour`) and **Unsnooze** (`off`) are verified (`spikes/protocol/RESULTS.md:20`). The other four are read, not observed, and no spike has seen their wire values. If the server rejects one, the app hides it for that account and the menu offers **Open on web** | `snooze` |
| **Archive** and **Unarchive** | Archives the thread. Archiving keeps the URL working, and child threads stay active (Amp's docs). If the thread has an automation, the confirmation warns that archiving stops it [INFERENCE from Amp's docs]. | `archiveThread`, verified (`spikes/protocol/RESULTS.md:20`) | `archive` |
| **Archive and Remove Worktree** | Shown instead of **Archive** for runner worktree threads (Amp's docs). Amp never forces removal of a dirty worktree. | `removeThreadRunnerWorktree`, read, not observed. Fallback shows the CLI command to run (`docs/design/sketch/COVERAGE.md`, row `runner-create-worktree-archive-and-remove-worktree`) | `archive-remove-worktree` |
| **Delete** | Confirmation dialog "Delete this thread? Deleting is permanent." (Amp's docs). | `deleteThread`, read, not observed. Fallback **Open on web** | `delete` |
| **Mark as Unread** | Marks the thread unread again, so its title shows in semibold. | `client_mark_thread_unread`, verified (`spikes/protocol/RESULTS.md:20`) | `mark-unread` |
| **Open in New Window** | See [Row actions](#row-actions). | Client only | `new-window` |

Reads after these writes can lag by about one second (`spikes/protocol/RESULTS.md:20`). The app applies each change locally at once and reconciles with the next read.

### Rail

The collapsed Sidebar is a 72dp rail of thread avatars with status dots (`layout.md`, "Panes"). Each avatar shows two-letter initials, from the first two words of the title or its first two letters, and the thread color as a stripe at its start edge (UX-24). Each rail item has the thread title and status text as its label, so `is text` can check it. **Needs you** items sort first. The rail keeps **New Thread** and **Puck** as icon buttons at the top and the account avatar in a fixed footer under a divider.

## Needs you

**Needs you** is the first Sidebar section. It turns the core triage loop into one list: threads waiting on you first, then threads that stopped with an error, then finished threads, then nothing else (`docs/design/product.md`, "The core loops"). ampcode.com has no such section. It is an addition for the phone.

| Group | Contents | Row extras |
|---|---|---|
| Waiting on you | Threads with status "needs approval" or "needs input". | The gate summary on the row's second line, such as "Bash: `git push --force`". A tool approval gets **Deny** (x icon) and **Allow** (check icon) under the rules below. Other gates show **Answer**, which opens the thread with the gate focused. |
| Stopped | Threads with status "error" or "setup failed", newest first. | The first line of the error and **Open**. A setup failure also shows **Setup Log**. |
| Finished | Threads with status "done", newest first. | The first line of the final reply when the device has it cached, and **Mark Read** (check icon). The push carries only the body "done" (`spikes/protocol/RESULTS.md:21`), so a thread the device never loaded shows no preview. |

A Needs you row keeps its action on the title line beside the overflow button, so the gate summary below has the row's whole width (UX-05).

Rules:

- A tool approval row shows **Allow** only when the viewer can answer and the whole tool input fits in the row's visible text. When it does not fit, the row shows **Review** in place of **Allow**, which opens the thread with the approval expanded and the full input shown. **Deny** shows in both cases. The notification follows the same rule (`docs/design/notifications.md`, "Answer from the notification").
- Inline **Allow** is outside keyboard list traversal. Arrow keys, Tab, and Enter move between rows and open them, and never land on **Allow**. A pointer or a tap reaches it.
- A snoozed thread leaves **Needs you** unless a gate waits on it, because Amp ends every snooze when a thread needs approval (Amp's docs).
- The row's **Allow** and **Deny** send the same response as the gate dock. When the viewer cannot answer, the row shows only **Review**, and the gate dock in the thread gives the reason (see [Answerability](#answerability)).
- Every other window that shows a different thread shows a banner for a new "Waiting on you" item, with **Open** and **Dismiss** (`docs/design/product.md`, "Product principles"). Tag `needs-you-banner`.
- The section title shows the count of every row in the section, as every other section title does, so the number always matches the rows under it. Each row's status text says whether it waits on the user. The launcher badge shows the count of "Waiting on you" rows only. The app does not set a launcher badge yet.
- Rows hold still while the user is in the list, as in [Sections and order](#sections-and-order).

Rows reuse the tag `thread-row-<threadId>`. Inline buttons are `needs-you-<threadId>-allow`, `needs-you-<threadId>-deny`, `needs-you-<threadId>-review`, `needs-you-<threadId>-answer`, `needs-you-<threadId>-open`, and `needs-you-<threadId>-read`.

## Activity and search

Activity is the feed of every visible thread, the same as `/feed` on ampcode.com (Amp's docs). It opens in the Sidebar's slot and replaces the section list until **Back**, an arrow icon button left of the title. The four chips sit in one row that scrolls sideways, and each chip opens its own menu.

| Element | Behavior | Tag |
|---|---|---|
| Time chip | Shows the chosen window. Its menu lists **Last 24h**, **Last 72h**, **Last 7d**, and **All time** (Amp's docs). Default **Last 24h** (Amp's docs). | Chip `activity-time-more`, items `activity-time-<24h\|72h\|7d\|all>` |
| Status chip | **All**, **Working**, **Needs you**, **Done**, **Archived**. | `activity-status` |
| Users chip | **All** or **Me**. In private-by-default workspaces the default is own threads (Amp's docs). | `activity-users` |
| Projects chip | Multi-select of projects. Default **All**. | `activity-projects` |
| Query field | Free text plus the query syntax below. | `activity-query` |
| Chip defaults | Each chip's menu ends, under a divider, with **Use as Default** and **Reset Default**. The web exposes these through right-click and long-press (Amp's docs). The app stores defaults per account on the device. | `activity-<chip>-use-default`, `activity-<chip>-reset-default` |
| Feed row | A thread row as in the Sidebar, plus the reason it is in the feed: turn completed, mention, followed activity, comment reply, or Space invitation. A plain update has no reason line. | `activity-row-<threadId>` |

Feed events come from the user actor. The spike saw `feedItemUpdated{kind:"thread-turn-completed"}` once per turn end (`spikes/protocol/RESULTS.md:24`). The initial page uses `listFeedItems`, and search uses `/api/threads/find?q=`. Both are read, not observed (`docs/design/sketch/COVERAGE.md`, row `find-threads-activity-feed-and-query-syntax`). Until a spike observes them, Activity and search filter the cached thread summaries on the device. In that mode the list header reads "Filtered on this device. Results include only threads this device has seen." with **Search on web** (`activity-search-web`).

### Query syntax

The query field and the Sidebar search accept Amp's thread query syntax (Amp's docs). Recognized filters render as chips inside the field, and a tap on a chip edits it.

| Filter | Values | Notes |
|---|---|---|
| `id:` | `T-…` | Exact thread. |
| `label:` | Label name | Repeatable. |
| `parent:` | `T-…` | Direct children only. |
| `project:` | Project name | |
| `repo:` | Repository | |
| `author:` | `me` or a user | |
| `visibility:` | `private`, `workspace`, `group`, `unlisted` | |
| `archived:` | `true`, `false` | |
| `snoozed:` | `true`, `false` | Local mode uses the snooze ledger. |
| `pinned:` | `true`, `false` | |
| `commit:` | `true`, `false`, or a SHA | |
| `pr:` | `true`, `false`, or a number | |
| `after:`, `before:` | ISO date, or relative days or weeks | Compare update time (Amp's docs). |
| `updated_after:`, `updated_before:` | Same as above | |
| `puck:` | `true` | Matches conversations with Puck, not threads Puck created (Amp's docs). |

Text outside filters matches titles. In local mode, filters the device cannot evaluate (`commit:`, `pr:`, `repo:`) show a chip with a warning icon and the text "Needs server search".

## New thread

**New Thread** opens a new-thread composer in the Thread pane. It is the same composer as an open thread, with three pickers above the text field: executor, project, and mode. The composer sits at the bottom of the pane, as an open thread's does, with the **New thread** heading at the top. Each picker is a chip that names what it picks: **Executor** (`composer-executor`), **Project** (`composer-project`), and **Mode** (`composer-mode`). **Project** sets the project on the chosen orb or runner. Until an executor is chosen it is disabled and reads "Project: pick an executor first". The composer always shows the mode, the executor, and the orb size before the first send (`docs/design/product.md`, "Product principles").

Sending creates the thread with `POST /api/thread-actors`, then sends `client_spawn_executor` on the socket. Putting `runnerId` in the create call is rejected with 403, so the executor always goes in the second step (`spikes/protocol/RESULTS.md:14`). The thread opens in place, and the pickers become read-only chips in the title bar. Starting a thread needs a live connection and is never queued in the outbox. While offline, **Send** on a new-thread composer is disabled with "Starting a thread needs a connection."

On a fresh new-thread composer, pressing **Enter** twice on an empty field inserts a reference to the thread the user just left, as on ampcode.com (Amp's news). A chip "Reference <title>" (`composer-reference-previous`) offers the same thing on touch.

### Executor picker

The executor picker (`composer-executor`) is a sheet with three sections. Each section title sets `heading()`.

| Section | Rows | Details |
|---|---|---|
| **New Orb** | One row that expands to project, size, and provider. | See below. |
| **Runners** | One row per runner the user owns, from `listRunners` (`spikes/protocol/RESULTS.md:13`). | Hostname, directory count, and running-thread count. A runner whose name ends in `-amp-app` is the Mac app's runner (Amp's docs), and the row says "Mac app". |
| **Shared Runners** | Runners other members shared, with owner avatar and name and running-thread count (Amp's docs). | A note under the section says "Runs as <owner> with their files and credentials. Threads here are visible to the workspace." (Amp's docs, Amp's news). |

Offline runners do not appear. A sleeping Mac leaves Amp's picker within about 90 seconds (Amp's docs), and `listRunners` returned no runners after the spike runner stopped (`spikes/protocol/RESULTS.md:13`). When Cross-Client Access is disabled, the **Runners** and **Shared Runners** sections are hidden and replaced by "Your workspace does not allow starting threads on runners from other clients." (Amp's docs).

**New Orb** fields:

- **Project.** The accessible projects plus **No Project**, which starts a clean orb with no repo (Amp's docs). When the project has no setup scripts, the composer shows Amp's **Set Up Orbs** notice (Amp's docs).
- **Size.** `a1.tiny` (1 CPU, 2 GB, $0.08/h), `a1.small` (2, 4 GB, $0.17/h), `a1.medium` (4, 8 GB, $0.33/h), `a1.large` (8, 16 GB, $0.66/h), `a1.xxlarge` (16, 32 GB, $1.32/h), and `a1.3xlarge` (16, 44 GB, $2.13/h, Gigawatt or Enterprise only). The default is the project default, else `a1.medium` (Amp's docs). The list is a static table and the server validates it on spawn, because the live list is a web-only function (`docs/design/sketch/COVERAGE.md`, row `orb-sizes-and-costs`). Each row shows the hourly price.
- **Provider.** **Built-in Orbs** (`orb-provider-built-in`), or a provider under **Self-Hosted** (`orb-provider-self-hosted-<name>`), as on the web (Amp's docs). When the picker opens, it reads the account's providers, the web composer's `orbProviderOptions`, through the account's web view profile (`auth-and-security.md`, "The Self-Hosted orb list"). Each listed provider shows the web's badges, **Workspace Default** and **Personal**. The picker opens on the provider picked last if it is still offered, else the workspace default, else **Built-in Orbs**, the web composer's rule (web chunk `cbbea68c.js`, `ze()`). The last good list is kept per account, so the next picker shows it at once and refreshes it. Without a web session in the profile, the section shows **Sign in to ampcode.com to list your self-hosted orbs** (`orb-provider-sign-in`), which opens Amp's web sign-in (`/auth/sign-in?returnTo=/settings/orbs`) in the same profile, and the next picker lists the providers by itself. If the read fails, for example with a 404 after Amp moves the page data, the cached list stays and one quiet line says why (`orb-provider-status`). **Add Self-Hosted Provider** (`orb-provider-add`) still takes a provider's name as the workspace shows it in Settings → Orbs (`orb-provider-name`, **Add** `orb-provider-save` or the keyboard's Done), the name `amp --orb-provider` takes. A typed name that a listed provider has merges into that provider. Other typed names stay, and × forgets one (`orb-provider-forget-<name>`). The provider picked last reads "Last used". No source says whether a provider is connected, and Amp's own sizes stay on offer because no source limits them per provider. The executor chip reads "Orb · <provider> · <size>". A self-hosted orb starts with internal `createRemoteExecutorThread{content, agentMode, environmentResource, projectID?, orbProvider}`, which creates the thread with its first message, because `client_spawn_executor` has no provider field. A listed provider goes as `{configID}`, as the web composer sends it, and a typed one as `{name}`. When Amp refuses the call, the composer keeps the draft and shows Amp's reason; what Amp answers for a name the workspace lacks is not observed. **Retry** and **Choose Another Executor** on a failed spawn refuse a self-hosted orb, since only a new thread can name one.

Orb creation is read, not observed (`docs/design/sketch/COVERAGE.md`, row `spawn-an-orb-thread-ox`). `POST /api/thread-actors` creates the thread before `client_spawn_executor` runs, so a failed spawn leaves a thread with no executor. The app keeps that thread and never creates a second one for the same send. The composer stays on the new thread with the draft and its attachments, shows the error, and offers three choices:

- **Retry** (`composer-spawn-retry`) sends `client_spawn_executor` again on the same thread.
- **Choose Another Executor** (`composer-spawn-other`) reopens the executor picker and spawns the chosen executor on the same thread.
- **Discard** (`composer-spawn-discard`) archives the empty thread with `archiveThread`, verified (`spikes/protocol/RESULTS.md:20`), and moves the draft to a fresh new-thread composer.

The first message goes out only after a spawn succeeds.

**Runner** rows expand to a directory step:

- The directory list shows the runner's served directories, grouped as projects and folders (Amp's docs). Worktree directories show a branch icon (Amp's news).
- Every Git checkout offers **Current** and **New Worktree** as two buttons (Amp's news). The **Tab** key toggles between them.
- **New Worktree** opens a form with **Name** (suggested from the prompt), **Directory** (`<repo>-<name>`), **Branch** (`<name>`), and the note "From current HEAD after fetching upstream". The button is **Create Worktree** (Amp's docs). Names allow letters, digits, `-`, and `_`, and start with a letter or digit. The form validates this before send.
- **New Worktree** appears only when the runner reports `create-worktree` and the directory is a main checkout (`spikes/protocol/RESULTS.md:13`, Amp's docs).
- Typing a path that does not exist offers **New Directory** when the runner reports `any-directory` (`docs/design/sketch/COVERAGE.md`, row `runner-create-directory-or-project-from-a-client`). **New Project** opens on web.
- Shared runners hide **New Worktree**, **New Directory**, and **New Project**, because Amp does not support them there (Amp's docs).

The worktree spawn parameter is read, not observed (`docs/design/sketch/COVERAGE.md`, row `runner-create-worktree-archive-and-remove-worktree`). If the spawn is rejected, the same three choices apply on the same thread, and **Choose Another Executor** lists **Start in Current Directory** first.

### Mode picker

The mode picker (`composer-mode`) is a sheet with two tabs, **Amp Modes** and **Raw Models**, and a search field that searches both, as on ampcode.com (Amp's docs).

- **Amp Modes** lists the user's Dial modes first in Dial order, then other built-in modes, then plugin modes (Amp's docs). The Dial is `low`, `medium` (default), `high`, and `ultra` unless the user built their own (Amp's docs, Amp's news).
- Each row shows the mode name, its description, and the agent model and Oracle model (Amp's news).
- Plugin modes show their label and color. Their labels are at most 24 characters (Amp's docs).
- A green dot marks modes that a linked ChatGPT subscription pays for (Amp's news).
- **Raw Models** lists single models. A raw model pins only the main agent (Amp's docs).
- Models that Allowed Models disables appear grayed with "Not allowed by your workspace" (Amp's docs).
- **Claude Code** appears as a mode. Selecting it shows two extra menus under the mode chip, **Model** and **Effort** (Amp's docs). If the account has no Claude subscription linked, selecting it shows "Link your Claude subscription to use Claude Code." with **Link on web** (Amp's docs).
- A **Fast** switch sits under the list. Its caption is "Faster serving for supported models, billed at a premium" (Amp's docs).

The mode is fixed at the first message (Amp's docs). After that, the title bar shows it as a read-only word, and **More Actions** says why.

The catalog comes from `listAgentModes` and `getAgentModelCatalog`, which are read, not observed. If they fail, **Amp Modes** shows the four Dial modes plus the `agentModes` that `listRunners` returns for the selected runner (`spikes/protocol/RESULTS.md:13`), and **Raw Models** shows "Raw models are available on web." with **Open on web**.

## Thread view

The Thread pane holds the title bar, the transcript, the queued messages, the gate dock, and the composer, top to bottom (`layout.md`, "Panes"). Its root carries `pane-thread`. A pane wider than one panel keeps a 600dp measure, about 75 characters, that starts on a crease: in Read and in Focus it starts at the 335dp crease, so switching between them leaves the text in place. With no thread open the pane shows "Pick a thread" (`thread-no-selection`). An account with no threads leaves the pane blank, so the Sidebar's "No threads yet" with **New Thread** is the only one, because there is no list to pick from (UX-08).

App Links for `ampcode.com/threads/*` and workspace custom domains open here (Amp's docs). A thread ID in a commit trailer or a PR body also opens here through the same link (Amp's docs).

### Title bar

| Element | Behavior | Tag |
|---|---|---|
| Title | Tap to rename inline. | `thread-title` |
| Status | Muted text under the title. While the socket is live it uses the sidebar row's words, such as "working", "idle", or "error". Otherwise it names the socket state: "connecting", "catching up", "reconnecting…", "not connected", or "archived" (UX-10). | `agent-status` |
| Project chip | Opens the project's threads in Activity with `project:` set. | `thread-project` |
| Mode word | The mode's name in the status line, such as "Low". Read-only after the first message, and too short to tap, so it opens nothing. **More Actions** lists it under "In the title bar" as a 48dp read-only row, "Mode: Low" over "Fixed after the first message". | `thread-mode`, `thread-menu-mode` |
| Executor word | A state dot and a short name in the status line, such as "Runner" or "Orb". Its content description spells out the state, such as "Runner · running" (UX-11). It opens nothing until the orb or runner sheet exists, so it has no **More Actions** item. | `thread-executor` |
| Parent link | For child threads, "From <parent title>". Opens the parent. | `thread-parent` |
| PR chip | When a PR names this thread, its number and status (Amp's docs). Status comes from the transcript, because the hover-card calls are web-only (`docs/design/sketch/COVERAGE.md`, row `commit-pr-and-ci-previews-in-threads`). | `thread-pr` |
| Presence | Avatars from the `observers` event (`docs/protocol/private-protocol.md:156`). | `thread-presence` |
| Multiplayer | The joystick button. See [Multiplayer](#multiplayer). | `thread-multiplayer` |
| **Share** | An icon button, content description "Share". Opens the share sheet. | `thread-share` |
| **More Actions** | The `⋮` icon button, content description "More Actions". | `thread-menu` |

The title bar matches ampcode.com, which shows the title, **More Actions**, **Share**, the project, and the mode (Amp's docs). The arrangement control is not in the title bar. It lives in the window bar, outside every pane (`layout.md`, "The window bar holds window controls"). The link state shows as a banner under the title bar (`link-banner`), described in [Connection and executor states](#connection-and-executor-states).

The title bar is one 48dp row: the title with the status under it, then the controls, then **More Actions**. Every control in the row is a full 48dp by 48dp target. When the controls do not fit, they leave in priority order, lowest first, and move to the top of **More Actions** under the heading "In the title bar", instead of shrinking or staying as hidden hit areas (UX-12, UX-33). A moved control keeps its behavior and carries `thread-menu-<element>`, such as `thread-menu-share`. The title keeps 160dp (`AmpTheme.sizes.titleMin`), or its own width when that is shorter, and truncates with an ellipsis (UX-11). Presence first shrinks to a count, such as "3 here", and leaves only after that.

| Priority | Control | Why |
|---|---|---|
| 1 | Title and **More Actions** | Never leave |
| 2 | Executor chip | Carries orb state and cost |
| 3 | Mode chip | Says what the next message costs |
| 4 | **Share** | ampcode.com shows it in the bar |
| 5 | Multiplayer | Only on threads where it applies |
| 6 | PR chip | |
| 7 | Project chip | |
| 8 | Parent link | |
| 9 | Presence | |

With an executor chip about 80dp wide and a 48dp mode chip, the 382dp Triage pane keeps the title, both chips, and **More Actions**, and the title gets about 186dp. At 335dp the mode chip leaves too, and the title gets about 187dp (`ThreadTitleBarTest`). The prototype's 282dp Review pane already cut the title to "Fix DC…" with four controls.

### More Actions

The menu has the items ampcode.com lists (Amp's docs), plus Android items. Every item is a real button with tag `thread-menu-<action>`.

| Item | Behavior | Action tag |
|---|---|---|
| **Copy Link** | Copies the thread URL. | `copy-link` |
| **Copy as Markdown** | Copies the loaded transcript as Markdown, rendered on the device. | `copy-markdown` |
| **Export** | Saves Markdown through the system file picker. **Export JSON** appears only for the creator (Amp's docs). | `export` |
| Cost and status | A non-interactive row with cost so far from `threadDisplayCostInfo` (`spikes/protocol/RESULTS.md:46`). For orbs it adds running state and size (Amp's docs). Hidden when the workspace hides costs (Amp's docs). | `cost` |
| **Orb** submenu | Orb threads only. See below. | `orb` |
| Organize items | **Pin**, **Color**, **Labels**, **Rename**, **Snooze**, **Archive**, **Delete**, **Mark as Unread**, as in [Organize actions](#organize-actions). | as listed there |
| **Find in Thread** | Opens the find bar. See [Find in thread](#find-in-thread). | `find` |
| **Command Palette** | Opens the [Command palette](#command-palette). This is the touch entry point on Compact windows, which have no window bar. | `palette` |
| **Watch** | Follows the thread live as a notification (`docs/design/notifications.md`, "Watching a running thread"). | `watch` |
| **Full Width** | Switches the window to the Read arrangement. ampcode.com's **Full Width** widens the transcript (Amp's docs). Hidden on Compact windows. | `full-width` |
| **Open in New Window** | Opens this thread alone in a new window (`layout.md`, "Multiple windows"). | `new-window` |
| **Open on web** | Opens the thread's ampcode.com page in the account's web view, as defined in [How to read this document](#how-to-read-this-document). | `open-web` |
| **Report Bug…** | Opens the bug report sheet with **Thread and App Diagnostics** and **Diff Diagnostics** checkboxes (Amp's docs). | `report-bug` |

The **Orb** submenu has:

- **Orb status**, with state, size, and the pause rule "Pauses 5 minutes after the agent stops or 20 minutes after your last interaction, whichever is later" (Amp's docs).
- **Wake Orb** when paused.
- **Restart Processes** (`restartOrbProcesses`, `docs/design/sketch/COVERAGE.md`, row `orb-lifecycle-pausewake-and-status`).
- **Setup Log**.
- **Network Routing** and **Sync Browser**, shown only when Amp Net is on (Amp's docs).
- **Copy Sync Command**.
- **Reboot on web**, because reboot is a web-only function (`docs/design/sketch/COVERAGE.md`, row `orb-lifecycle-pausewake-and-status`).

**Copy Sync Command** copies `amp sync <thread URL>`. On ampcode.com this item is **Orb › Copy Sync CLI Command** (Amp's docs), and the Changes pane has a **Sync** button (Amp's news). A phone has no checkout to sync into (`docs/design/product.md`, "Not built"), so both places use the one label **Copy Sync Command** and both copy the command. The snackbar reads "Copied. Run it in your checkout to pull this orb's changes." `amp sync` mirrors an orb thread's changes into a local checkout (Amp's docs).

### Transcript structure

The transcript is a `LazyColumn` of turns, so only visible rows are in the accessibility tree (`docs/design/verification.md:14`). Root tag `transcript`.

- **Turns.** A turn starts at a human user message and ends at an assistant message whose state is `cancelled`, `error`, or `complete` with a stop reason other than `tool_use` (Amp's docs). A user message that holds only `tool_result` blocks is not a human turn. Its results attach to the preceding tool calls (Amp's docs).
- **Steps.** Consecutive tool calls in a turn collapse into one step row with a summary, as ampcode.com collapses the agent's work into one expandable summary (Amp's news). The summary shows chips "N finished", "N active", "N cancelled", "N rejected" (Amp's docs), then the aggregate status only when there is more than one chip, because one chip already says it (UX-27). Tag `step-<firstToolUseId>`. A step stays expanded while any of its calls is active.
- **Final reply.** Assistant text after the last tool call renders expanded.
- **Streaming.** A block whose `blockState` is not `complete` renders its partial content (Amp's docs). The spike saw 31 `delta` events in a 13-second turn (`spikes/protocol/RESULTS.md:14`).
- **Activity row.** While the agent is busy, a row under the last turn shows the agent state as text, such as "Running tools" or "Compacting", with elapsed time. Its spinner turns in 8 steps a second, only while the window is started, so a working thread does not redraw the window every frame.
- **Paging.** Long threads load the last page first and page backward with `getThreadTranscriptPage` (`spikes/protocol/RESULTS.md:19`). **Load Earlier** (`transcript-load-earlier`) is a real button at the top. Threads can be compacted 68 times or more (Amp's news).
- **Scroll.** The list follows new content only while it is at the bottom, and stays at the bottom when the keyboard shrinks the pane (UX-15). **Jump to Latest** (`transcript-jump-latest`) appears otherwise.

### Find in thread

**Find in Thread** searches the transcript. It opens with **Ctrl+F** while the Thread pane has focus, or from **More Actions**. A find bar (`thread-find`) covers the title bar until **Close** or **Esc**. The transcript is a lazy list that holds only visible rows, so the app cannot rely on a system find and needs its own.

- The search covers every message page the device has loaded: user and assistant text, tool card summaries, and tool output. Matching ignores case.
- The bar shows "N of M in loaded messages" (`thread-find-count`) with **Previous** and **Next**. **Enter** and **Shift+Enter** do the same. Each move scrolls the transcript to the match, expands a collapsed step or tool card that holds it, and highlights it.
- **Search Older Messages** (`thread-find-older`) shows while older pages exist. It pages backward with `getThreadTranscriptPage` (`spikes/protocol/RESULTS.md:19`) until it finds a new match or reaches the first message. While it runs, the bar reads "Searched back to <date>" with **Stop**. The pages it loads stay in the transcript cache.
- `getThreadTranscriptPage` is an internal RPC method, so searching a paused orb's thread never opens a socket or wakes the orb.

### Messages

| Message | Rendering |
|---|---|
| User message | Right-aligned bubble with Markdown and image thumbnails. Origin badges for Puck, automation, voice call, another thread, a plugin, or another member (Amp's docs). A steered message shows a "Steered" tag (`spikes/protocol/RESULTS.md:15`). Reactions show under the bubble. |
| Assistant message | Full-width Markdown with tool steps. An edited message shows "Edited" with its history (Amp's docs). |
| `info` message | A terminal-styled row for a command the user ran directly (Amp's docs). |
| Space event | A system row such as "Space renamed to …" (Amp's docs). |

The overflow button on a user message (`message-<messageId>-more`) offers **Copy**, **Edit**, and **React**. **Edit** replaces the message and re-runs from there through `client_edit_message`, which is read, not observed (`docs/design/sketch/COVERAGE.md`, row `edit-a-prior-message`). The app mints an `editId` for each edit and keeps it across retries, so a resend never applies the edit twice. Editing no longer rolls back file changes (Amp's news), and the edit dialog says so. An `edit_rejected` event shows "Edit not applied" under the message.

Text selection in the transcript adds **Reply**, **Comment**, and **Reply with Dictation** to the selection toolbar, matching the iOS app (Amp's docs). **Reply** quotes the selection into the composer. **Comment** posts a thread comment, which is read, not observed. Until a spike observes it, **Comment** is hidden.

### Block types

| Block `type` | Rendering |
|---|---|
| `text` | Markdown. Hidden when `hidden: true`. A fenced block with language `amp-view` renders as a prototype (see [amp-view prototypes](#amp-view-prototypes)). A `:::gallery` directive renders as a gallery (see [Galleries](#galleries)). Other code blocks render in monospace with **Copy** and a language label. |
| `image` | Thumbnail. Tap opens the lightbox. |
| `tool_result` | Never standalone. Attaches to its tool call by `toolUseID`. |
| `thinking` | A collapsed "Thinking" row. See [Thinking](#thinking). |
| `redacted_thinking` | A collapsed "Thinking (redacted)" row with no content. |
| `tool_use` | A tool card keyed by `name`. While `complete` is false, the card shows a live preview of the partial input. |
| `server_tool_use` | The same card as `tool_use`, with no client result. |
| `program`, `program_output` | Hidden, as on the web. Their nested tool calls show inside the `code_exec` card. |
| `manual_bash_invocation` | A terminal-styled row with the command, working directory, and output. |

Source for every row is Amp's docs. Plugin link patterns turn matching text into links once the app knows where the patterns are stored. Until then, links are plain URLs (`docs/design/sketch/COVERAGE.md`, row `plugin-link-patterns-in-the-transcript`).

Links in Markdown open in a Custom Tab, except links to ampcode.com threads, which open in the app.

### Tool cards

Each tool call is a card with tag `tool-card-<toolUseId>` and a toggle with tag `tool-card-<toolUseId>-toggle` (`docs/design/verification.md:10`). The header shows the display label, a one-line summary, and the status as text. The label is `metadata.displayTitle` when present (Amp's docs), else the web's label for the name (Amp's docs), else the raw name.

| Card | Tool names | Label | Body |
|---|---|---|---|
| Shell | `shell_command`, `async_shell_command`, `run_terminal_command`, `Bash`, `shell_command_status`, `shell_command_kill` | "Bash", "Shell Command Status", "Shell Command Kill" | Command, working directory, output in monospace with ANSI colors, exit code. Output longer than 40 lines collapses with **Show All**. |
| Edit | `edit_file`, `apply_patch`, `create_file`, `write_file`, `delete_file`, `undo_edit` | "Edit", "Apply Patch", "Create" | Path and an inline unified diff. `edit_file` returns a fenced diff string (Amp's docs). **Open in Changes** selects the Changes tab and scrolls to the file without changing the arrangement (`layout.md`, "Arrangements"). |
| Read and search | `read_file`, `Read`, `finder`, `ripgrep`, `Grep`, `glob`, `Glob`, `get_diagnostics` | "Read", "Search", "Grep" | Path or query, and result lines. |
| Subagent | `Task`, `oracle`, `librarian`, `subagent_stop` | "Subagent", "Oracle", "Librarian", "Stop Subagent" | The task text, the nested calls rendered through the same `ToolCards` map, and the subagent's answer as Markdown. Librarian answers are long, so the card shows the first 12 lines with **Show All** (Amp's docs). |
| Thread | `create_thread`, `send_thread_message`, `wait_for_threads`, `read_thread`, `find_thread`, `read_messages`, `search_messages` | Raw name in title case | Thread chips that open the thread. A created child thread shows its live status. |
| Web | `read_web_page`, `web_search` | "Web Page", "Web Search" | URL or query and the result summary. URLs open in a Custom Tab. |
| Media | `painter`, `view_media`, `present_media` | "Painter", "View Media" | Images inline. See [Images and media](#images-and-media). |
| Choice | `ask_user_choice` | "Ask User Choice" | The questions and the answers given. The live question is a gate in the gate dock. |
| Code exec | `code_exec` | "Code Exec" | Nested tool calls from `nestedToolCalls`. MCP Ask First answers appear below the call (Amp's docs). |
| TODO | `todo_read`, `todo_write`, `task_list` | "Read TODOs", "Update TODOs" | The list. These appear in older threads. TODOs are a removed feature, so no new UI builds on them (Amp's news). |
| Files | `upload_thread_file`, `download_thread_file`, `download_thread_changes` | Raw name in title case | File names with **Open** and **Save**. |
| Plugin and skill | `load_plugin`, `reload_plugins`, `skill`, `reload_skills`, `reload_mcp`, `tool_search`, `manage_amp`, `load_prompt` | Raw name in title case | Generic body. |
| MCP | `mcp__<server>__<tool>` (Amp's docs) | "<server> · <tool>" | Generic body. A **Not in Multiplayer** badge shows when the server blocks contributors (Amp's docs). |

The sample run offered these tools (Amp's docs). The web enum adds legacy and contextual names, including Puck and workspace tools (Amp's docs). Those render through the generic card until they get their own.

**Generic card.** Any tool without a card, and any call whose input fails to decode, renders the generic card (`docs/design/sketch/feature-thread/ToolCards.kt`, `GenericToolCard`). It shows the label, the status, the input as pretty-printed JSON, and the result as text or JSON, collapsed by default. A plugin tool with an empty result shows "(no tool output)" (Amp's docs). A call to an unregistered tool shows Amp's error, "Unknown tool: …" (Amp's docs).

**Status text** on every card maps from the tool run status (Amp's docs):

| Run status | Text |
|---|---|
| `queued` | queued |
| `blocked-on-user` | waiting for you |
| `in-progress` | running |
| `rejected-by-user` | rejected |
| `cancellation-requested` | cancelling |
| `cancelled` | cancelled |
| `done` | done |
| `error` | error, with the message |

An interrupted call shows "cancelled" with the reason "User canceled" (`spikes/protocol/RESULTS.md:15`).

**Grouped plugin calls.** Adjacent calls whose `metadata.transcriptGroup` labels match collapse into one row that reads as active while running and complete after (Amp's docs).

### Thinking

A `thinking` block renders as a row labeled "Thinking", collapsed by default. While it streams, the label reads "Thinking…" and a tap expands it to show the live text. When collapsed after completion, the row shows the first line. A block with an empty `signature` renders normally (Amp's docs). `redacted_thinking` shows "Thinking (redacted)" and cannot expand (Amp's docs). When an assistant message's `usage.thinkingDropped` is set, a small note under the message reads "Earlier thinking was dropped to fit the context window" [INFERENCE: field meaning from its name, Amp's docs].

The app shows no context-fill gauge. Amp manages context automatically and needs no context-percentage chrome (Amp's news).

### Images and media

- User image blocks render as thumbnails in the bubble. Attachment URLs return 401 without the Bearer header and 200 with it (`spikes/protocol/RESULTS.md:22`). Images load through the authenticated loader that `:sync` exposes. It adds the header for the account's own host only and refreshes the token on a 401 (`docs/design/architecture.md`, "Credentials have one owner").
- Tapping an image opens the lightbox, which has pinch zoom, **Share**, **Save**, and a background toggle of light, dark, and checkerboard for transparent PNGs. Painter makes transparent PNGs and labeled variants A, B, and C (Amp's docs). Tag `lightbox`.
- Painter results render inline with their variant labels.
- How `view_media` and `present_media` deliver images is unknown (Amp's docs). The media card renders any image URL or base64 image it finds in the result and falls back to the generic card.
- Video attachments show a poster frame and play in the lightbox.
- An image in assistant or user markdown, `![alt](url)`, renders inline through the same loader and opens the lightbox. One that fails to load shows "Couldn't load image" and **Open on Web** (tags `image-broken`, `image-broken-open-web`).

### Galleries

Amp's markdown has one container directive, the gallery. Its grammar is the web's (`99d00ac2.js`, regex `Hr`, marked extension `gallery`):

- An opening line `:::gallery <layout>`, or `:::carousel`, `:::grid` or `:::side-by-side` alone, indented at most three spaces. A missing or unknown layout word is `grid`. A body of images only, one per line or separated by blank lines. A closing line `:::`.
- Each image shows its alt text as a caption above it, as the web's `<figure>` puts `<figcaption>` first. A tap opens the lightbox.
- `carousel`: one 4:3 frame the pane's width, with 32dp of the next image showing, a swipe between images, and a bar of **Previous image**, `1 / N`, **Next image** under it (48dp targets). The web has the bar and no peek.
- `grid`: columns at least 224dp wide (the web's `14rem`), so one column in the 382dp thread pane and on the cover.
- `side-by-side`: rows of four, two or three images (the web's `Vr`); below 512dp of width each image takes its own row (the web's `32rem` container query).
- A gallery whose body holds anything but images, and any other `:::name` directive, shows its body as markdown without the fences. The web leaves an unknown directive's fences as text; the app hides them.
- A gallery still streaming shows its images as plain images until its closing line arrives, as the web does. A last line of only colons while streaming is a fence still arriving and is hidden.
- Find in Thread matches gallery captions. Copy as Markdown, notifications and Puck drop the fence lines and keep the body (`MarkdownPart.withoutFences`).
- Tags: `gallery`, `gallery-item-<index>`, `gallery-position`, `gallery-previous`, `gallery-next`.

### amp-view prototypes

Amp can reply with a clickable prototype. It is one self-contained HTML page in an `amp-view` fenced block, shown in a sandboxed frame that follows light and dark theme, with no network access (Amp's docs). ampcode.com renders it and the CLI does not (Amp's docs).

The app renders each `amp-view` block as a card holding a sandboxed WebView (`docs/design/sketch/feature-other/WebSurfaces.kt`, `WebSurface.AmpView`):

- JavaScript is on. Network access is off except `data:` and `blob:` URLs.
- The page gets the app's theme through `prefers-color-scheme`.
- The card is height-capped with **Expand**, which opens the prototype in the Focus arrangement. **View Source** shows the HTML.
- While the block streams, the card shows "Building prototype…" and renders once the fence closes.
- The web bundle defines constants `524288` and `1200` for `amp-view` [INFERENCE: a 512 KiB size cap, Amp's docs]. Blocks over 512 KiB show the source with "Prototype too large to render".
- The web registers an action bridge for prototypes whose actions are unknown (Amp's docs). The app exposes no bridge.

Tag `amp-view-<messageId>-<index>`.

### Schedule line

A thread that an automation runs ends its transcript with one quiet line, as ampcode.com does (web chunk `e2ccdf0e.js`, `$a` passed as `idleIndicatorContent`, crawled 2026-10-27). The line takes the slot the activity row holds while the agent works, so it shows only while the agent is idle. Its tag is `transcript-schedule`.

| Automation | Line | Source |
|---|---|---|
| Enabled, a run scheduled | "Next run in 5m", plus "· Last run 2h ago" once it has run | `iV`, `aV` in `e2ccdf0e.js` |
| Paused by the user | "Automation paused" | `rt` in `fa551f92.js` |
| Paused after a failed run | "Automation paused · last run failed", in the danger color | `rt`, `X` (`attention`) |
| No upcoming runs | "Automation completed", only when it ran after the thread opened | `rt`, `$a` |
| Enabled, nothing scheduled | No line | `$a` |

The countdown is the web's: "<1m" under a minute and past due, then "5m", "2h", "2h 5m", "1d", "1d 3h" (`c24136b7.js`, `u`, the CLI's `vzt`). The last run reads "<1m", then the largest whole unit: "5m", "2h", "3d", "1mo", "1y" (`c24136b7.js`, `i` with `approximate`). The web ticks every second. Neither text changes inside a minute, so the app moves the line when its text next changes. Several automations on one thread resolve by the CLI's order: enabled first, then the soonest run, then the title.

The app reads internal `listAutomations` with the account's key. The web's own source, the remote `getThreadAutomationDetails`, answers 401 to that key (`spikes/protocol/RESULTS.md`, S25). The list is read again when the thread opens and when a run ends, which is when the web reads its own.

A tap on the line opens a sheet, the web's automation popover (`fa551f92.js`, `pt`). It holds the title with a switch (**Pause automation** or **Enable automation**), the schedule's label with the line, the reason the automation stopped, and **Delete Automation**. Delete asks first, with the web's words: "“<title>” will be permanently deleted and will no longer run." The switch sends `updateAutomation{automationID, enabled}`, and Delete sends `deleteAutomation{automationID}`. Both read the list again, so the line under the sheet changes when the write lands. A refused write shows "Could not pause automation", "Could not enable automation", or "Could not delete automation" in the sheet. The web's popover also shows the saved prompt, which only `getAutomation` carries. No client is seen calling it with a key, so the sheet leaves it out.

### Other transcript rows

| Row | Behavior | Source |
|---|---|---|
| Recap | A quiet card with "Goal" and "Latest outcome" and a **Dismiss** icon, placed before the turn that holds the recap's `messageID`, else before the latest turn. The app has no read marker, so that turn stands in for the top of the unread part (UX-28). It disappears on a new message. | Amp's news, `client_dismiss_recap` (Amp's docs) |
| Compaction | A divider "Compacted earlier messages". | Amp's docs |
| Error | The error message with **Retry** and **Dismiss**. When the agent state is error but no `error_set` has named the message, the row says "The agent stopped with an error." and adds **Open on Web** (UX-03). A scheduled retry shows "Retrying in N s (attempt X of Y)". | Amp's docs |
| Cancelled | "Interrupted" at the end of the turn. | `spikes/protocol/RESULTS.md:15` |
| Schedule | The thread's automation as one line at the end of an idle transcript. See [Schedule line](#schedule-line). | Web chunk `e2ccdf0e.js`, `idleIndicatorContent` |
| Linear session | An expandable **Linear session** row at the top with the start instructions. | Amp's docs |
| Model not available | "Model Not Available" when Allowed Models disabled the thread's model. | Amp's docs |
| Setup failure | **Repository Setup Failed** with **Setup Log** and **Retry Setup**. | Amp's docs |
| Unknown events | Nothing in the transcript. The degraded banner covers them. | `docs/design/sketch/RATIONALE.md`, "Resilience" |

## Human gates

A human gate is anything that blocks the agent on a person: tool approvals, plugin dialogs, `ask_user_choice`, MCP elicitation, and MCP server approval. The app treats them as one queue (`docs/design/sketch/RATIONALE.md`, "Shape").

### Gate dock

The gate dock sits between the queued messages and the composer. Its root tag is `gate-dock`. It takes at most half of the height the transcript would otherwise keep and scrolls inside, so the transcript above it stays in view (UX-13).

- The first answerable gate shows expanded with its buttons. Other gates show as chips (`gate-<gateId>`) that expand on tap. Only the expanded gate renders the shared action tags such as `approval-allow`, so a tag never matches two nodes.
- Gate fields carry `gate-field-<key>`. The primary action carries `gate-submit`, and decline carries `gate-decline`.
- A gate shows its source: "Built-in", the plugin name, or the MCP server name.
- After an answer, the gate collapses into the transcript as a one-line record, such as "Allowed by you" or "Declined".
- When a gate settles on another client, the dock removes it and shows "Answered elsewhere" for 3 seconds. A plugin dialog that Amp withdraws with `plugin.ui.request_removed` leaves the dock the same way, and its notification is cancelled.
- A haptic tick plays when a new gate arrives in a visible thread.
- Gates also reach the user through **Needs you**, the banner in other windows, and the **Needs you** notification channel (`docs/design/notifications.md:55-62`).

A gate the app cannot decode shows its title if one exists, "This request needs an Amp client that supports it.", and **Answer on web** (`answer-on-web`) (`docs/design/sketch/RATIONALE.md`, "Shape", `UnsupportedGate`).

### Answerability

The viewer's capability comes from the `cap` claim in the socket token: `read`, `contribute`, or `write` (`docs/protocol/private-protocol.md:31`).

| Gate | Owner (`write`) | Contributor in active multiplayer | Reader (`read`) |
|---|---|---|---|
| Tool approval | Answers | Read-only, "Only the thread owner can approve tools." (Amp's docs) | Read-only |
| Plugin select, confirm, non-secret input | Answers | Answers (Amp's docs) | Read-only |
| Plugin secret input, or confirm with a secret field | Answers | Read-only, "Only the thread owner can enter secrets." | Read-only |
| Plugin dialog targeted to one connection | Answers on that connection only | Not shown (Amp's docs) | Not shown |
| `ask_user_choice` | Answers | Read-only. It answers through the tool approval response (Amp's docs). | Read-only |
| MCP elicitation | Answers | Read-only [INFERENCE: treated like a tool approval] | Read-only |
| MCP server approval | Answers. **Always Allow for Workspace** needs admin. | Read-only | Read-only |

A read-only gate shows its full content, the reason in text, and no buttons. A `requireHuman` dialog is always shown to a person, never auto-answered (Amp's docs).

If Amp requires a passkey before the user may answer, the gate shows "This account needs a passkey to act from the app" and **Open on web** (`stepup-open-web`) instead of its buttons (`docs/design/auth-and-security.md:269`). ampcode.com shows **Authenticate to Respond** in the same situation (Amp's docs). The button opens the thread on ampcode.com in a Custom Tab, the one exception in [How to read this document](#how-to-read-this-document), where the user confirms with a passkey and answers. See [Connection and executor states](#connection-and-executor-states).

`skipIfUnwatched` dialogs resolve as dismissed when nobody who can answer has the thread open (Amp's docs). The app reports a thread as watched only while a visible pane shows it, through the user actor's `reportVisibility` (`docs/protocol/private-protocol.md:166`).

### Tool approvals

A tool approval arrives in `tool_approval_queue` and is answered with `client_tool_approval_response{toolCallId, accepted, input{askAnswers?, denyFeedback?}}` (Amp's docs). Amp asks for approval only when a policy plugin or an MCP **Ask First** policy requires it (Amp's docs).

The expanded approval shows:

- The tool label and the full input. The label is the one the transcript's tool card shows, such as "Bash" for `shell_command`, and the approval notification and the one-line record use it too (UX-29). The input is the whole command for shell tools, the path and the edit for edit tools, or the MCP tool and all of its input. Long input scrolls inside the gate and is never cut to one line, so the user always reads what **Allow** approves.
- The policy's title when the plugin gives one, such as "Potentially destructive git operation" (Amp's docs).
- **Allow** (`approval-allow`) and **Deny** (`approval-deny`), below the input.
- **Deny** expands an optional field "Tell the agent why" (`approval-deny-feedback`) with **Send Denial** (`approval-deny-send`).

The approval wire format is read from the web bundle and not observed. Spike row S15 is inconclusive, because no approval was ever queued (`spikes/protocol/RESULTS.md:65`). An answer goes over the socket only, because `/request/rpc` rejects `client_tool_approval_response` (`spikes/protocol/RESULTS.md:70`). If the app cannot decode the queue entry, or the server rejects the response, the gate shows **Answer on web**.

### Plugin dialogs

Plugins on the executor show four kinds of UI (Amp's docs). Spike row S15b observed the wire end to end (`spikes/protocol/RESULTS.md:66`):

1. A dialog arrives as `plugin.ui.request_added` with its `requestId`, `method`, and `params`. The user actor marks the thread with `indicator{kind:"action-required"}`.
2. The app answers with `executor_plugin_message` carrying `plugin.ui.respond{requestId, result}`. A confirm answers `true` or `false`, and an input answers its text.
3. `plugin.ui.request_settled` reports the dialog as responded.

**Cancel** and dismiss answer with `{requestId, error}`, which settles the dialog as errored (`spikes/protocol/RESULTS.md:67`). After every connect, `plugin.ui.snapshot` lists the pending dialogs again, so the dock rebuilds from it and never depends on having seen `request_added` (`spikes/protocol/RESULTS.md:67`). Answers go over the socket only (`spikes/protocol/RESULTS.md:70`). S15b observed `ui.confirm`, `ui.input`, and `ui.notify`. `ui.select` is read, not observed. A request the app cannot decode becomes the **Answer on web** gate.

| Kind | Rendering |
|---|---|
| `notify` | A snackbar with the message and no response. URLs in it are links, because orb plugins fall back to `notify` with a URL (Amp's docs). |
| `input` | `title`, `helpText`, a field with `initialValue` and `placeholder`, and a submit button labeled `submitButtonText` (default "Submit"). **Cancel** sends the error answer described above. A `secret` field masks its text with a show toggle. Its value is never cached, logged, kept in the draft, or written to the outbox. The answer is an `Ephemeral` command that lives in memory only (`docs/design/architecture.md`, "Writes go through an outbox with delivery classes", Amp's docs). `fieldName` sets the autofill hint so password managers can fill it. |
| `select` | `title`, Markdown `message`, one radio button per option, preselected `initialValue`. With `allowOther`, a final text field with placeholder "Other". Empty "Other" text is not submitted (Amp's docs). Option tags are `gate-option-<n>`. |
| `confirm` | `title` with the small `iconURL` image, Markdown `message`, a confirm button labeled `confirmButtonText` (default "Yes"), and **Decline**. With `collapsedMessage`, the dialog starts collapsed and `expandButtonText` reveals the fields (Amp's docs). |

Confirm fields (Amp's docs):

- `text`, optionally `multiline`, `optional`, `placeholder`, or `secret`.
- `file`, with **Choose File** (system picker), **Paste**, and drop. The app refuses files over 64 KiB or not UTF-8 with that reason, and returns the text.
- `image`, a read-only preview of an HTTP or HTTPS image.
- `link`, a button labeled `label` that opens the URL in a Custom Tab while the dialog stays open.

A link field before an editable field renders the fields as numbered steps. A dialog whose only field is one link makes the primary button open the link and then become the confirm button.

When a confirm with `endTurnOnDecline` is declined, the transcript shows that the turn ended at the user's request.

### ask_user_choice

`ask_user_choice` is a built-in tool since September 2026 (Amp's news). The gate shows each question with "Pick one" or "Pick any" under it, its options as radio rows for single choice or checkbox rows for multiple choice (`gate-option-<n>`) (UX-13), a free-text **Other** field when the question allows it, and **Submit**. The answers go back as `askAnswers` in the approval response (`docs/design/sketch/COVERAGE.md`, row `plugin-ui-and-built-in-interactive-tools`). The question shape is not observed [INFERENCE: questions with options, single or multiple choice, and an optional other field, from `docs/design/sketch/core-model/Gates.kt`, `ChoiceGate`]. Any shape the app cannot decode becomes **Answer on web**.

### MCP elicitation

A remote MCP server can ask the user for input mid-call (Amp's docs). The delivery channel is read, not observed, so an undecodable request becomes **Answer on web** (`docs/design/sketch/COVERAGE.md`, row `mcp-elicitation-questions-from-remote-servers`).

- Form mode supports text, number, yes or no, and single choice. Amp cancels multiple-choice requests.
- A field that Submit waits on, a number or a single choice, says "(required)" after its label. While Submit is disabled, a line under it names the first field still missing, such as "Fill in Points to submit" (`gate-submit-blocker`) (UX-30).
- Links in the server's text are plain text, never clickable.
- URL mode shows the page address and **Open in Browser**, which opens a Custom Tab and then becomes **Continue**. The app opens only `https` URLs and `http://localhost`. Any other URL shows "This address can't be opened." with **Decline** only.
- The gate shows a countdown from 10 minutes. Amp cancels the question after 10 minutes, and the gate then reads "Expired".
- **Decline** sends a decline to the server. The agent sees only the tool result.

### MCP server approval

Workspace-config MCP servers need approval before an orb runs them (Amp's docs). The gate shows the server name and its command, with three buttons:

- **Allow Once** for this orb.
- **Always Allow for Me** for later orbs.
- **Always Allow for Workspace**, shown only to workspace admins.

Amp asks again when the server config changes. The app reads and answers through `listMCPServerApprovals` and `approveMCPServer` (`docs/protocol/private-protocol.md:179`), which are read, not observed. On failure the gate shows **Answer on web**.

### MCP sign-in on a runner

A runner asks for MCP OAuth sign-in in a thread dialog. Opening the link on another device ends on a failing `localhost` page, and the user pastes that page's full URL back into the dialog (Amp's docs). The app renders this as an `input` gate with two steps:

1. **Open Sign-in Page** opens the link in a Custom Tab.
2. A field "Paste the address of the page that failed to load" with **Paste from Clipboard** and **Submit**.

The flow is built for a user on another device, which a phone is (Amp's docs).

## Queued messages

The queue strip sits above the gate dock and lists messages waiting to reach the agent (`queue-strip`). The app builds it from `queued_message_added`, `queued_message_removed`, and `queued_message_dequeued` events (`spikes/protocol/RESULTS.md:15`).

| Element | Behavior | Tag |
|---|---|---|
| Row | A one-line preview, the author when it is not you, and the delivery: "Steers at next step" or "After this turn". | `queued-<id>` |
| **Steer Now** | Moves a turn-end message to steer at the next tool boundary (`client_steer_queued_msg`). | `queued-<id>-steer` |
| **Move Up**, **Move Down** | Reorders (`client_reorder_queued_msgs`). Hardware keyboards and drag also reorder. | `queued-<id>-up`, `queued-<id>-down` |
| **Remove** | Removes the message and puts its text back in the composer if the composer is empty (`client_remove_queued_msg`). | `queued-<id>-remove` |

The client verbs are listed in Amp's docs. A steered message waits for the current tool to finish. It is injected at the next tool boundary, never mid-tool (`spikes/protocol/RESULTS.md:15`). The row says so, so the user does not resend it. A message sent while the agent is busy counts as delivered as soon as `queued_message_added` carries its message ID, so its outbox bubble becomes a queue row at once instead of waiting for the tool to finish. Spike row S5 observed this for a steer only. A row that waits for the end of the turn is read, not observed, until the M3 gate adds a queued send.

On ampcode.com, `Enter Enter` on a queued message steers it sooner (Amp's docs). In the app, **Up** in an empty composer selects the last queued message, and **Enter** twice on a selected message steers it.

The web can require a passkey to change queued messages ("Set up a passkey in Settings to change queued messages") [INFERENCE from bundle text, Amp's docs]. When the server rejects a queue change for that reason, the strip shows **Open on web** (`stepup-open-web`), which opens the thread on ampcode.com so the user can make the change there.

## Composer

The composer is the bottom of the Thread pane. Its field is `composer-input`.

### Send, steer, queue, and interrupt

The actions depend on whether the agent is busy. Busy means agent state `compacting`, `working`, `streaming`, `tool_use`, or `running_tools` (Amp's docs). Each action is a real button, so agent-device never needs a long-press (`docs/design/sketch/feature-thread/ThreadPane.kt`, `Composer`).

| Agent | Action | Touch | Hardware keyboard | What Amp does | Tag |
|---|---|---|---|---|---|
| Idle | **Send** | Tap send | **Enter** | Sends the message and starts a turn. | `composer-send` |
| Busy | **Steer** (default) | Tap send | **Enter** | Queues the message as a steer. Amp delivers it after the current step (Amp's docs), at the next tool boundary (`spikes/protocol/RESULTS.md:15`). | `composer-steer` |
| Busy | **Queue** | Long-press send, or the **Queue** button | **Ctrl+Enter** | Holds the message until the whole turn ends (Amp's docs). This path is read, not observed. Spike row S5 captured only a steer (`spikes/protocol/RESULTS.md:15`), and the M3 gate adds a queued send. If a plain append does not queue, the app keeps **Queue** messages on the device and sends each one when the agent goes idle. | `composer-queue` |
| Busy | **Interrupt**, or **Interrupt and Send** when the field holds text | The stop button | **Esc** twice | Cancels the turn with `client_cancel`. The running tool reports "cancelled" and the agent goes idle. The spike measured 564 ms (`spikes/protocol/RESULTS.md:15`). The interrupt goes once over the socket and is never retried. With text in the field, the stop button's content description reads **Interrupt and Send**, and the app sends the text as a separate message once the agent is idle. | `composer-interrupt` |

The composer is one row (UX-14). From the left it holds the attach icon button, the field, and 48dp icon buttons with the primary rightmost. Idle, the row ends in **Send**. Busy, it ends in **Queue**, the stop button, and **Steer**, which is the send glyph with the content description "Steer". The buttons sit on the field's last line, so they stay put as the field grows from one line to six. The **Queue** button means touch users never depend on long-press. The iOS app uses long-press on send to queue (Amp's docs), and the app keeps that gesture on **Steer** too. The outbox notice keeps **Retry** and **Discard** as labeled tertiary buttons, because they are rare.

Ship, Review, and Amp-written prompts always queue (Amp's docs). The **Default Message Send Behavior** setting swaps the defaults so that **Enter** queues and **Ctrl+Enter** steers, as on ampcode.com (Amp's docs). The app stores the setting on the device, because the web setting is a web-only function.

The composer is disabled with a reason in these cases:

| Case | Text | Action |
|---|---|---|
| Reader | "You can read this thread but not send to it." The banner carries `readonly-banner`. | None |
| Archived | "This thread is archived." | **Unarchive** |
| Out of credits | "You're out of credits." | **Add Credits on web** |
| Cross-Client Access disabled, CLI thread | "Your workspace does not allow sending to CLI threads from other clients." (Amp's docs) | None |

### Suggested replies

An agent may end its reply with a `<suggested-replies>` block, one reply per line. The app parses it the way Amp's web does (`ut` in the thread bundle) with the CLI's line-start anchor: the block opens at the last `<suggested-replies>` that starts a line, nothing but whitespace may follow `</suggested-replies>`, list markers and blank lines drop, and a line that starts with `**words**` keeps those words as its lead. The block never shows as text: not in the transcript, Copy as Markdown, Find, or a notification body. While it streams, the block and a half-written opening tag are hidden. Text that only mentions the tag stays as written.

The composer shows the last turn's replies above its field (`suggested-replies`), as Amp's web and Android apps do, at most three, each a chip (`suggested-reply-<index>`) whose bolded lead stays bold. They show only once the turn is done, the agent is idle, nothing sent from this device is pending, and the field is empty. A tap sends the reply's text without its `**` marks, as Amp's web does. A long press puts it in the field to edit first, the touch form of the web's Cmd-click **Edit Before Sending**.

### Touch and hardware keyboard

With only the on-screen keyboard, the keyboard's Enter key inserts a newline and the send button sends. When a hardware keyboard is attached, **Enter** sends and **Shift+Enter** inserts a newline (Amp's docs). The iOS app detects a physical keyboard the same way (Amp's docs).

### Mentions

Typing `@` opens a mention picker over the composer (`composer-mention-picker`). Its tabs are **Members**, **Threads**, and **Files**.

**Members.** Selecting a member inserts a mention. Sending a message with a member mention invites them and turns on multiplayer (Amp's docs). Only non-private threads in an orb or on a shared runner qualify (Amp's docs). On a thread that does not qualify, the picker shows Amp's warning "Mentioned people will be notified, but they can't reply while multiplayer is off." (Amp's docs). The full member list comes from a web-only function (`docs/design/sketch/COVERAGE.md`, row `multiplayer-and-tagging`). The picker lists the thread's participants and recent observers, and **Invite on web** opens the Share dialog on web for anyone else.

**Threads.** The picker searches cached thread summaries by title and ID. Selecting inserts the thread URL. Amp reads a mentioned thread with `read_thread` (Amp's docs). Pasting a thread URL or `@T-…` also works.

**Files.** ampcode.com and the official apps deliberately do not support file mentions. Only the CLI does (Amp's docs). The app's **Files** tab therefore inserts plain text. It writes a repo-relative path in backticks, and the agent reads it as text. The list comes from the Changes file list, which uses live git (`spikes/protocol/RESULTS.md:17`), and from paths in the transcript's tool cards. The tab needs no executor connection and never wakes an orb. When the Files pane is available, the tab also browses the full tree.

Skills need no picker. Users name them in plain text (Amp's docs).

Mention rows carry `mention-member-<userId>`, `mention-thread-<threadId>`, and `mention-file-<n>`.

### Attachments

The attach button (`composer-attach`) opens a sheet. The options match the iOS app (Amp's docs):

| Option | Behavior | Tag |
|---|---|---|
| **Camera** | Takes a photo with the system camera. | `attach-camera` |
| **Photos** | Opens the system Photo Picker for images and videos. | `attach-photos` |
| **Files** | Opens the system document picker for any file type. | `attach-files` |
| **Take Screenshot** | Captures the app's own window as it looked before the sheet opened. | `attach-screenshot` |
| **Record Screen** | Starts a MediaProjection screen recording of the whole device, including other apps. A switch **Record Microphone** sits in the consent step. A persistent notification with **Stop** runs while it records. Stopping attaches the video to this composer. | `attach-record` |
| **Attach Latest Screen Recording** | Attaches the newest video in the device's screen recordings folder, after showing its thumbnail and date. | `attach-latest-recording` |

Attachments also arrive in three other ways. The user can paste an image with **Ctrl+V**, drop files from another app or window onto the composer (`layout.md`, "Multiple windows"), or share into the app from another app (see [Puck pane](#puck-pane)).

Each attachment shows as a chip with a thumbnail, size, upload progress, and **Remove** (`attachment-<n>-remove`). Uploads start at once through the resumable endpoint (`spikes/protocol/RESULTS.md:22`). **Send** waits for uploads and shows "Uploading 2 of 3". Images go into the message as `image` blocks (`spikes/protocol/RESULTS.md:22`). Other files go in as their attachment URLs in the message text, as the CLI's `--attach` does (Amp's docs) [INFERENCE: the web may use a different block].

Each file can be up to 100 MB, or 512 MB on accounts with the higher limit (Amp's docs). A file over the limit shows "Too large to attach (limit 100 MB)" on its chip.

A failed upload stays as a chip with **Retry**. The draft and its attachments survive process death.

### Dictation

The mic button (`composer-mic`) dictates into the composer. It follows ampcode.com (Amp's docs):

- **Tap** starts dictation. The button becomes a level meter with **Stop** (`composer-mic-stop`) and **Send** (`composer-mic-send`). **Stop** inserts the text. **Send** inserts and sends it with the busy-state default.
- **Press and hold** dictates while held and sends on release. This is hold-to-talk.
- **Ctrl+Shift+D** toggles dictation. Holding the shortcut dictates and sends on release (Amp's docs).
- The first use asks for microphone permission (Amp's docs).

The mic button is hidden when the workspace turned off **Enable Dictation** (Amp's docs). Amp holds one active dictation per user across devices with `claimDictation` (`docs/protocol/private-protocol.md:268`). When another device holds it, the button reads "Dictating on another device".

Transcription uses `/api/voice-transcription`, which bills Amp credits and returns 402 when the user is out of credits (`docs/protocol/private-protocol.md:267`). Bearer access to it is read, not observed (`docs/design/sketch/COVERAGE.md`, row `dictation`). If the endpoint rejects the app's token, dictation uses the on-device speech recognizer and the button's menu says "Using on-device speech recognition". Custom vocabulary from `getDictationVocabulary` applies only to Amp's transcription.

### Outbox

A message sent while offline or during a reconnect goes to the outbox. It shows in the transcript as a pending bubble with its state: "Waiting for network", "Sending", or "Not sent" with **Retry** and **Edit** (`composer-outbox-<messageId>`). The iOS app has the same outbox (Amp's docs). The outbox keeps the client message ID, so a resend after an unknown outcome does not duplicate the message. Spike row S16 verified that the server keeps one copy per message ID (`spikes/protocol/RESULTS.md:68`). A message leaves the outbox when `queued_message_added` or `message_added` carries its ID.

Some actions need an answer at once, so they never wait in the outbox. Starting a thread, staging a file in Changes, and uploading into the Files pane are direct calls that need a live connection. While offline their buttons are disabled with "Needs a connection." An interrupt is sent once and never replayed. A secret value, such as a secret plugin input, stays in memory and is never written to the outbox (`docs/design/architecture.md`, "Writes go through an outbox with delivery classes").

## Share and visibility

**Share** opens the share sheet (`share-sheet`), which sits in the Thread pane's widest panel, like every sheet. It matches the web's Share dialog (Amp's docs).

| Element | Behavior | Tag |
|---|---|---|
| **Copy Link** | A Secondary button. Copies the thread URL. | `share-copy` |
| **Share…** | Opens the Android share sheet with the URL. | `share-system` |
| Visibility | **Private**, **Workspace**, **Group**, **Unlisted**, as 48dp radio rows with the label beside the radio. **Group** appears only on Enterprise workspaces. | `share-visibility-<value>` |
| **Contribute** | For **Workspace** or **Group**, a 48dp checkbox row that turns on multiplayer for those members (Amp's docs). | `share-contribute` |
| **Create Workspace** | Shown when the user has no workspace. Opens on web. | `share-create-workspace` |

The wire values are `private`, `thread_workspace_shared`, `thread_group_shared`, and `public_unlisted` (Amp's docs). New threads in the spike defaulted to workspace visibility (`spikes/protocol/RESULTS.md:20`). Options the workspace forbids, such as sharing outside the workspace, are disabled with "Your workspace does not allow this." (Amp's docs). Public discoverable threads no longer exist, so there is no public option (Amp's news).

Choosing a group opens on web, because the group list is not reachable from a user token (`docs/design/sketch/COVERAGE.md`, row `groups-enterprise`). The visibility write is `updateThread{visibility}`, which is read, not observed. If it fails, the sheet shows **Open Share on web**.

## Multiplayer

Multiplayer lets teammates prompt the agent in one thread and share its orb or shared runner (Amp's docs).

The joystick button in the title bar (`thread-multiplayer`) has the tooltip **Enable Multiplayer**. It appears only on threads in an orb or on a shared runner (Amp's docs). Tapping it while off opens the dialog **Enable Multiplayer for Thread?** with **Don't ask me again** and **Enable Multiplayer** (`multiplayer-enable`). The dialog carries Amp's warning that every member can access the thread, the orb or runner, its secrets, files, and terminal, and that costs bill to the owner (Amp's docs). On a private thread the dialog adds that enabling shares the thread with the workspace.

While on, the button opens the multiplayer menu:

- The thread owner, active participants, and away participants (Amp's docs).
- The time left, and **Change Duration** with choices from 5 minutes to 7 days (Amp's docs). The default is 7 days.
- **End Multiplayer** (`multiplayer-end`).

Contributors can prompt, open portals, and use the terminal and files (Amp's docs). They cannot approve tools (see [Answerability](#answerability)). Typing indicators come from `prompt_drafts` and show "<name> is typing" above the composer (Amp's docs).

The write is `client_set_thread_open{ttlSeconds}`, which is read, not observed (`docs/design/sketch/COVERAGE.md`, row `multiplayer`). If it fails, the menu shows **Open Multiplayer on web**.

## Machine panes

Machine panes are Changes, Files, Terminal, Portal, Desktop, and Space. They exist only for threads with an executor (`layout.md`, "Panes"). One pane slot shows a tab strip with tags `tab-changes`, `tab-files`, `tab-terminal`, `tab-portal`, `tab-desktop`, and `tab-space`. Each tab reports `selected`.

A pane narrower than the strip scrolls it. The selected tab stays whole, with the tab before it whole and the first letters of the tab after it. The strip never stops partway into a label, so only the trailing edge cuts a label, at its end. A pane of 335 to 363dp, as on the TriFold, needs this.

### Which tabs appear

Tabs follow Amp's runner and orb matrix (Amp's docs). A tab that does not apply stays visible with its reason, so the user learns how to enable it (`docs/design/sketch/COVERAGE.md`, row `runners-vs-orbs-feature-matrix`). Desktop is the exception. It appears only when the executor reports a desktop.

| Tab | Orb | Runner | Reason text when unavailable |
|---|---|---|---|
| Changes | Yes | Git or Jujutsu checkout | "This directory isn't a Git or Jujutsu checkout." |
| Files | Yes | While connected | "The runner is offline." |
| Terminal | Yes | With `--remote-control-terminal` | "terminal access is disabled. Restart the runner with `--remote-control-terminal`, then reload." (Amp's docs) |
| Portal | Yes | Rolling out | "Portals aren't available on this runner yet." |
| Desktop | Only when the executor reports a desktop | Only when the executor reports a desktop | None. The tab is hidden. See [Desktop](#desktop). |
| Space | Every thread except archived ones | Same | Later release. See [Space](#space). |

### Wake an orb only for a shown thread

Opening a thread whose orb went to sleep wakes it, as the web does. Waking resumes billing, so only a thread a person is looking at may do it.

- The web's open thread page asks for the executor with `client_spawn_executor{requestId: "spawn_<uuid>", executorDemand: "optional"}` on the thread socket while the tab is visible (`987da778.js`, search `pane-refresh`). The app sends the same request once when a thread shown in a `STARTED` window's pane reports a sleeping orb (`ThreadEngine.steerWake`). Two panes or two windows on the thread share one engine, so they send one wake.
- A sleeping orb's socket reports `observers{hasExecutor:false}` and `executor_status{status:"failed", details{reasonCode:"executor_disconnected", executionEnvironment{provider:"orb-provider"}}}` (`spikes/protocol/captures/s21-orb-sleep.jsonl:29`). While Amp brings it back, `executor_status` reads `starting` with `operation:"recovering"`, whoever asked, and the app shows waking for that too (`s21-orb-wake.jsonl:38-43`). The orb connected 11 seconds after the wake in the one live run. A runner that went offline reports the same failure with provider `runner` (`s09-transcript.jsonl`) and still reads as "No executor". "No executor on this thread" means the thread has no executor at all.
- While the orb wakes, the machine pane shows "Waking up the orb" (`machine-orb-waking`) with a spinner and **Open on Web** (`machine-orb-open-web`), and the status line reads "Orb asleep" or "Waking orb". Changes, Files, Terminal, and Portal load on their own once the executor connects.
- The wake fails when Amp refuses it, when an `executor_status` reports `failed` for its `spawnId`, or when the executor has not connected after 120 seconds, the web's `requestConnectedExecutor` timeout (`987da778.js`, `Gv=12e4`). The pane then shows "The orb didn't wake up" (`machine-orb-wake-failed`) with **Retry** (`machine-orb-retry`) and **Open on Web**. **Retry** calls `ThreadHandle.connectLive()`, which sends one more wake.
- A reconnect keeps the wake in flight and sends nothing new. Once the socket closes, 30 seconds after the last lease, the next open may wake the orb again.
- Background code never wakes an orb. Notifications, quick replies, the widget, Puck in the background, and push reach Amp through `BackgroundAccess` or a socket hold, and neither carries a viewer lease (`docs/design/sketch/RATIONALE.md`, item 3). `OrbWakeTest` and `AccountBackgroundTest` check that a hold, a gate peek, and a mark-read on a sleeping orb send no wake.
- The app holds the socket while a window in the `STARTED` state shows the thread, and closes it 30 seconds after the last lease is released (`docs/design/architecture.md`, "One socket per visible thread").

### Changes

The Changes pane shows changed files in intelligent order, the diff, per-hunk change requests, staging, and the action buttons. `git-and-diffs.md` specifies it. Screen-level rules:

- The buttons are **Ship**, **Review**, **Restack**, and **Copy Sync Command**, with **Fix CI** when a PR check failed. ampcode.com's buttons are **Ship**, **Review**, **Sync**, and **Restack** (Amp's news). **Copy Sync Command** replaces **Sync** and appears only on orb threads, as explained under [More Actions](#more-actions).
- **Ship** opens Amp's confirmation, **Ship Changes?**, **Ship?**, or **Push to Branch?**, with the exact target (Amp's docs). The prompt is editable before sending (Amp's docs). The target follows the one rule in `git-and-diffs.md`, "Target ref and the target line". Without live status the target reads "base unknown" and **Ship** stays disabled.
- Ship, Review, and Restack prompts always queue (Amp's docs).
- The app shows no custom Changes buttons. They come from `getChangesPrompts`, a `web-remote` function (`git-and-diffs.md`, "Actions").
- Intelligent order is read, not observed. If it fails, the pane uses alphabetical order and says so (`docs/design/sketch/COVERAGE.md`, row `review-changes-intelligent-file-order`).
- Action tags are `changes-ship`, `changes-review`, `changes-restack`, `changes-rebase`, and `changes-sync`, and files are `diff-file-<path>` (`git-and-diffs.md`, "Test tags", `docs/design/verification.md:10`).

### Files

The Files pane browses and edits the executor's file tree (Amp's docs). It runs on `client_filesystem_read_directory_v2`, `client_filesystem_read_file_v2`, and `client_filesystem_write_file_v3`, which spike row S19 verified on a runner (`spikes/protocol/RESULTS.md:71`). Paths may be relative to the workspace root, and each result arrives as a notification that carries the request's `requestId`.

| Element | Behavior | Tag |
|---|---|---|
| Tree | Directories expand in place. Rows show the name, the kind, and the modified time. A directory entry carries only `[name, kind, {mtimeMs} or null]`, so rows show no size (`spikes/protocol/captures/s19-files.jsonl:36`). | `file-node-<path>` |
| File viewer | Code with syntax colors and line numbers, images, and rendered Markdown with a source toggle. See the read rules below. | `file-viewer` |
| **Upload** | Toolbar button that uploads into the selected directory, or the workspace root when none is selected. Sources are the document picker, camera, and drop from other apps (`layout.md`, "Multiple windows"). See the upload rules below. | `files-upload` |
| **Save** | Saves a file to the device through the system file picker. | `file-save` |
| **Share** | Shares a file to another app as a content URI. Dragging a file out does the same (`layout.md`, "Multiple windows"). | `file-share` |
| **Open in New Window** | Opens the file alone in a window (`layout.md`, "Multiple windows"). | `file-new-window` |
| **Mention** | Inserts the path into the composer. | `file-mention` |

Reads follow what the protocol offers:

- Every read is a whole-file read. `range` reads are rejected with `-32602`, and a read result carries no size (`spikes/protocol/captures/s19-files.jsonl:65`, `:48`). The whole file arrives base64-encoded in one message on the thread socket.
- The app reads one file at a time per thread.
- The app cannot know a file's size before it reads it. Before it opens a file that is likely to be large, it warns "This file may be large. Opening it reads the whole file over the thread connection." with **Open Anyway** and **Cancel**. A file is likely to be large when its extension marks it as binary, media, an archive, or a database, or when its path is under `node_modules`, `.git`, or a build output folder. When a Changes snapshot already gives the file's size, the app uses that size and warns above 1 MiB.
- A file larger than 4 MiB after decoding is not shown. The viewer reads "This file is too large to open in the app (over 4 MiB)." with **Open Files on web**. The cap matches the web's 4 MiB write limit (Amp's docs) [INFERENCE: how large a message the thread socket carries well is unmeasured].
- **Save** and **Share** use the same read and the same cap. `getThreadFileURLs` is not a download path, because it answered `permission-denied` on a runner thread (`spikes/protocol/captures/s19-files.jsonl:76`, `:78`).

Uploads and edits follow what was tested:

- `client_filesystem_write_file_v3` with `overwrite:false` creates a file and refuses an existing one with `ALREADY_EXISTS` (`spikes/protocol/captures/s19-files.jsonl:70`, `:73`). The app asks before it writes with `overwrite:true`.
- The direct write carries a file only up to the largest size a test has written. S19 wrote 15 bytes (`spikes/protocol/captures/s19-files.jsonl:70`). The M8 gate writes a 1 MiB file, and once it passes, the limit becomes 1 MiB. Edits in the file viewer use the same write and the same limit. A larger file opens read-only, with the reason.
- A larger upload goes through the attachment path. The app uploads the file with `/api/attachments/resumable` (`spikes/protocol/RESULTS.md:22`), then asks the executor to save it into the directory with `amp-exec-v1`, the staging step the Terminal uses (`terminal.md`, "File upload by paste or drop"). Staging is not verified. If it fails, the file stays an attachment, and the pane says "Uploaded as an attachment. The executor could not save it to <directory>." with **Mention** to insert its URL into the composer.
- The attachment path takes up to 512 MB per file (Amp's docs).

If a request fails, the pane shows the error and **Open Files on web**, which opens the thread's Files page in the account's web view.

### Terminal

The Terminal pane is a shell in the executor over the relay, verified on a runner (`spikes/protocol/RESULTS.md:18`). `terminal.md` specifies it. Screen-level rules:

- **Expand** moves Triage to the Terminal arrangement, where a 12sp terminal fits 98 columns (`layout.md`, "Arrangements").
- An extra-keys row sits above the on-screen keyboard (`docs/design/roadmap.md:18`).
- Pasting an image or dropping a file uploads it and inserts its absolute path, as on the web (Amp's docs).
- A disabled runner terminal shows the reason in [Which tabs appear](#which-tabs-appear).

### Portal

The Portal pane shows web servers that run in the executor (Amp's docs).

| Element | Behavior | Tag |
|---|---|---|
| Portal list | Links from `.amp/services.yaml` and `.amp/portals/*.json`, with label, note, and folders one level deep (Amp's docs). Opening a declared portal that is not running starts it. | `portal-item-<port>` |
| Address bar | URL, **Back**, **Reload**, and **Open in browser**. | `portal-address`, `portal-back`, `portal-reload`, `web-open-in-browser` |
| Page | A WebView in the account's profile `amp-<accountId>`, so portal cookies do not cross accounts (`docs/design/auth-and-security.md:205`). When the portal cookie lapses, the app mints a fresh login URL instead of showing a web sign-in (`docs/design/auth-and-security.md:211`). | `portal-page` |
| **Portal Options** | **Access** settings and the hostname, which are web-only functions (`docs/design/sketch/COVERAGE.md`, row `portals-tab`). Opens on web. | `portal-options` |
| **Focus** | Offered when a portal opens. The app never enters Focus on its own (`layout.md`, "Arrangements"). | `portal-focus` |

- The page's own review widget works inside the WebView. Its dictation asks for microphone permission per portal hostname (Amp's docs).
- Links with `target="_amp"` leave the portal and open the Amp page in the app (Amp's news). Other navigation away from portal hosts opens a Custom Tab.
- The app never hardcodes `onamp.dev`, because workspaces can use custom portal domains (Amp's docs).

The app opens a portal by resolving its URL and loading a one-time login URL from `mintPortalLoginURL`. The spike's control plane worked, but the page answered 503, so the verdict is inconclusive (`spikes/protocol/RESULTS.md:23`). If the login URL or the page fails, the pane shows the error and **Open in browser**, which opens the portal URL in a Custom Tab where the browser's ampcode.com session completes portal sign-in (`docs/design/sketch/feature-other/WebSurfaces.kt`, `PortalTab`).

### Desktop

Desktop arrives in a later release, because its pointer and keyboard protocol is not decoded (`docs/design/product.md:38`). The tab appears only when the executor reports a desktop (`observers.relayDesktopAvailable` or `macDesktopAvailable`, `docs/protocol/private-protocol.md:156`), so the app never advertises a desktop that does not exist. In the first release, that tab shows a placeholder:

- Title "Desktop", and the text "Viewing and controlling the desktop comes in a later version of the Android app."
- **Open Desktop on web** (`desktop-open-web`), which opens the thread's Desktop page in the account's web view. Amp's Desktop tab works in any browser (Amp's docs).

A runner thread with no desktop has no Desktop tab. Settings, **Runners** explains how to add one: "Start the runner with `--desktop`, or turn on **Share This Mac's Display** in the Mac app." (Amp's news).

### Space

Space calls arrive in a later release, because the token endpoint is `web-plain` tier (`docs/design/product.md:39`). In the first release:

- The Space tab shows "Calls in this thread's Space come in a later version of the Android app." with **Join on web** (`space-open-web`).
- The tab is hidden when the workspace turned off **Enable Space**, and on archived threads, which have no Space (Amp's docs).
- A Space invitation in Activity opens the thread with the Space tab selected.
- The title bar shows no **Enter Space** pill, because the app has no reliable source for who is inside. [INFERENCE: the web gets it from web-only functions.]

## Puck pane

Puck is a personal assistant that starts, monitors, and messages agents (Amp's docs). Puck chat ships in the first release as a pane. Puck voice calls ship later (`docs/design/product.md:40`). Puck conversations are threads (Amp's docs), so the Puck pane is a Thread pane with Puck chrome. Root tag `pane-puck`.

| Element | Behavior | Tag |
|---|---|---|
| Conversation title | Tap to switch conversations. The list has unread markers and an **Inactive Last 72h** group (Amp's docs). | `puck-switcher` |
| **New Chat** | The **+** button. Starts a new conversation. | `puck-new-chat` |
| **More Actions** | **Archive Conversation** (Amp's docs). | `puck-menu` |
| Context chip | "Looking at <thread title>". Puck uses the page the user is viewing as context (Amp's docs). In the app, that is the focused thread in the same window. | `puck-context` |
| **Call Puck** | Opens a placeholder "Voice calls with Puck come in a later version." with **Call on web**. | `puck-call` |
| Media | Images and videos Puck presents open in the lightbox. | `puck-media` |

Behavior:

- In the Puck arrangement, Puck takes panel 3 and follows the focused thread (`layout.md`, "Arrangements"). On Compact widths, Puck is a full-screen route.
- When Puck opens a thread, a diff, a file, or a portal, the app places it in panel 2 instead of replacing the Puck pane. Puck can send `puck_platform_navigation` for these destinations (`docs/protocol/private-protocol.md:159`).
- Puck has no orb. It reads skills but cannot run scripts or plugins (Amp's docs). The empty state says "Puck can start agents to run code for you."
- Puck also manages projects in chat. Asked in words, it changes a project's name, repository URL and aliases, base branch, Ship Behavior and custom prompt, commit author, orb size, or setup scripts, and it deletes cached snapshots. It cannot delete a project (Amp's docs). Feedback and feature requests go the same way. The user tells Puck, with a thread link if it helps (Amp's docs).
- The conversation list comes from `listThreads`, because `listPuckConversations` is a web-only function (`docs/design/sketch/COVERAGE.md`, row `puck-conversations`). Slack-started conversations are not listed, as on the web (Amp's docs).

Entry points:

- **Ctrl+/** opens and closes Puck, as on ampcode.com (Amp's docs).
- The rail and Sidebar **Puck** buttons.
- The launcher shortcut **Dictate to Puck**, which opens Puck and starts dictation (Amp's docs, `docs/design/product.md:41`).
- Sharing into Amp from another app. The app accepts images, text, files, and URLs, which beats the iOS app's images only (Amp's docs). The share screen shows a preview, an optional message field, and **Send to Puck** (`share-to-puck-send`). It sends to the latest Puck conversation [INFERENCE: the iOS routing is unconfirmed, Amp's docs].

## Command palette

The command palette runs a command without editing the prompt, as in the CLI and on ampcode.com (Amp's news). Root tag `palette`.

It opens from four places:

- **Ctrl+Shift+P**, anywhere, including while the terminal has focus.
- **Ctrl+O**, the CLI's binding (Amp's docs), anywhere except the terminal, where Ctrl+O belongs to the shell.
- **Command Palette** in the window bar (`window-palette`) on Medium and wider windows (`layout.md`, "The window bar holds window controls").
- **Command Palette** in the Sidebar header (`sidebar-palette`) and in the Thread pane's **More Actions** (`thread-menu-palette`), which give Compact windows a touch entry point.

The palette is a search field (`palette-input`) over a list of 48dp commands. It opens from no pane, so it sits in the window's widest panel, the middle 382dp one on the TriFold. Typing filters by title and category. Each row (`palette-item-<id>`) shows `Category: Title`, the description, and the shortcut from [Keyboard shortcuts](#keyboard-shortcuts) when the command has one. **Enter** or a tap runs the selected row, and **Esc** or a tap beside it closes the palette.

App commands use the CLI's names where one exists, such as `thread: new`, `thread: new in orb`, `thread: archive`, `project: select`, `puck: open`, and `feedback: report bug` (Amp's news). Every action in this document's menus and in the shortcut table is also a palette command, so a keyboard user never needs a pointer.

Plugin commands come from the focused thread's executor. Spike row S15b observed the whole path (`spikes/protocol/RESULTS.md:66`):

- A `plugin_message` event `command.list` lists each command's `id`, `pluginName`, `title`, `category`, `description`, and `availability`.
- The row reads `Category: Title`, and the category defaults to the plugin name. A command with `availability{type:"disabled", reason}` shows dimmed with its reason and cannot run. A `hidden` command is not listed (Amp's docs).
- Running a command sends `executor_plugin_message` with `command.execute{pluginName, commandId, threadID}` over the socket, once, with no retry. A dialog the command opens arrives in the gate dock, as in [Plugin dialogs](#plugin-dialogs).
- A command that creates a thread and asks to show it opens that thread in the window [INFERENCE: the app treats it like a Puck navigation].
- The list arrives over the thread socket. On a paused orb's thread, which has no socket, the plugin section reads "Connect live to load this thread's plugin commands." with **Connect Live**.

## Plugin status items

A plugin can create a status item with `amp.experimental.createStatusItem()`, which shows a line of text with an optional `command:` link (Amp's docs). Amp's docs say only the CLI status line shows status items and other clients ignore them (Amp's docs). No wire event is known that carries one to an observer (Amp's docs). The app shows no status items. A command that a status item would run stays reachable from the [Command palette](#command-palette).

## Settings

Settings opens from the account switcher and with **Ctrl+,**. Native settings are the ones a phone user changes and the app can back with `cli`-tier calls. Everything else opens in a web view, because it runs on web-only functions whose ids change with each deploy (`docs/design/product.md:43-45`).

### Native settings

| Section | Contents |
|---|---|
| **Accounts** | The account list from the account switcher, and **Add Account**. |
| **Notifications** | Android channels from `notifications.md`, **Suppress While App Is Open**, the push channel in use (relay or UnifiedPush), and **Watch** time limit (`docs/design/notifications.md`, "Watching a running thread"). The official apps offer turning notifications off and suppressing them in the foreground (Amp's docs). |
| **Composer & Dictation** | **Default Message Send Behavior** (Steer or Queue), **Dictation** on or off, custom vocabulary (`getDictationVocabulary` and `setDictationVocabulary`, `docs/protocol/private-protocol.md:269`), and on-device recognition as a choice. |
| **Layout** | Default arrangement per window size. |
| **Runners** | The user's runners with status, directories, and sharing (`shareRunner`, `revokeRunnerShare`, `docs/design/sketch/COVERAGE.md`, row `share-a-runner-with-the-workspace`). A note explains that Android cannot be a runner and shows the `amp --no-tui` command to copy. The help text also shows how to share a desktop with `--desktop`. |
| **MCP Server Approvals** | The approvals list with **Remove** (`listMCPServerApprovals`, `docs/protocol/private-protocol.md:179`). |
| **Usage** | Credit balance and workspace entitlement, as in the account switcher, and **Usage on web**. |
| **App** | **UI Sounds**, **Haptics**, **App Lock** (`app-lock-toggle`, `docs/design/roadmap.md:21`), and **Theme** (system, light, dark). |
| **About** | Version, **Check for Updates** through Play in-app updates, **Service Status** (`ampcodestatus.com`, Amp's docs), **Report Bug…**, the **Data Retention** notice below (`settings-data-retention`), and open-source licenses. |

The **Data Retention** notice reads: "When Amp sends a request through its own model provider connections on Amp credits, Minimal Data Retention limits how long the provider keeps your data, with some exceptions. It does not change how Amp stores your threads. Connections you add with your own key follow that provider's terms." **Learn More** opens Amp's Minimal Data Retention page in a Custom Tab. The text follows Amp's policy summary (Amp's docs).

### Settings in a web view

Each item opens the matching ampcode.com page in a web view, as defined in [How to read this document](#how-to-read-this-document). The first visit per account shows Amp's web sign-in inside the profile. If that sign-in method does not work in a WebView, **Open in browser** moves the page to a Custom Tab (`docs/design/auth-and-security.md:213`). Returning from a page refreshes the app's cached catalogs (`docs/design/sketch/feature-other/WebSurfaces.kt`).

- **Account**, **Billing**, and credit purchase.
- **Security**, including **Passkeys** and **Require Passkey Authentication for Web & App Interaction** (Amp's docs).
- **Notification Preferences**, Amp's per-kind Push, Email, and Slack choices (Amp's docs).
- **Mode Dial**, with **Tune Modes** and **Build Dial**.
- **Model Routing** and **Allowed Models**.
- **Integrations & MCP**, including MCP server sign-in.
- **Plugins**, **Skills**, **Prompts**, and **Instructions**.
- **Projects**, **Domains**, and **Secrets & Env Vars**.
- **Automations** and event-driven triggers, including webhook URLs and plugin triggers.
- **Puck** settings.
- **Workspace Settings**, which opens with **Ctrl+Alt+,** as on the Mac app (Amp's docs).

## Connection and executor states

Each state shows as a banner in the Thread pane under the title bar (`link-banner`), and as status text on the thread's row where it applies.

| State | Trigger | Text | Actions |
|---|---|---|---|
| Offline | No network. | "Offline. Showing saved content from <time>." | Sends go to the outbox. Gates are read-only until reconnect. |
| Reconnecting | The socket dropped while a window in the `STARTED` state shows the thread. Backoff runs 3 to 30 seconds and never gives up while a viewer is present (`docs/design/sketch/RATIONALE.md`, "Resilience"). | "Reconnecting…" | **Retry Now** |
| Resuming | Reconnected and replaying missed events from the saved cursor (`spikes/protocol/RESULTS.md:19`). | "Catching up…" | None |
| Degraded | Amp sent event types the app does not know (`docs/design/sketch/RATIONALE.md`, "Resilience"). | "Some updates in this thread aren't shown in this version of the app." | **Open on web**, **Dismiss** |
| Orb asleep | The orb went to sleep after being idle. A shown thread wakes it (see [Wake an orb only for a shown thread](#wake-an-orb-only-for-a-shown-thread)). | The machine pane reads "Waking up the orb" and the status line "Orb asleep", then "Waking orb". If the wake fails, "The orb didn't wake up". | **Open on Web**; **Retry** after a failure |
| Waiting for orb | Orb spawn pacing holds the start. After a burst of 20 orbs, Amp starts one new orb every 5 minutes (Amp's docs). | "Waiting for an orb, about N min. It starts on its own." | None |
| Setting up | The orb is starting or running setup. | "Setting up the orb…" with the stage | **Setup Log** |
| Setup failed | Setup failed (Amp's docs). | "Repository Setup Failed" | **Setup Log**, **Retry Setup** |
| Runner offline | The runner is not connected. Threads on a sleeping Mac pause and wait (Amp's docs). | "Waiting for <hostname> to reconnect." | None. The composer stays enabled [INFERENCE: Amp holds the message until the runner returns]. |
| Cross-Client Access disabled | The workspace turned off Cross-Client Access (Amp's docs). | On CLI threads, "Your workspace does not allow sending to CLI threads from other clients." | None. CLI threads are read-only, and runners leave the executor picker. |
| Passkey required | The user or workspace requires a passkey before interacting (Amp's docs), and the server rejects an action for that reason. | "This account needs a passkey to act from the app" (`docs/design/auth-and-security.md:269`) | **Open on web** (`stepup-open-web`) opens the thread's ampcode.com page in a Custom Tab, the one exception in [How to read this document](#how-to-read-this-document), and the user acts there. The app does not retry, because a passkey confirmation in the browser belongs to the web session and likely does not elevate the app's session [INFERENCE]. The draft stays on the device, and the result arrives over the socket. Notification actions for the account become **Open** (`docs/design/notifications.md`, "Answer from the notification"). |
| Signed out | The token refresh failed. | "Sign in again to <email>." | **Sign In** |
| Archived | The thread is archived. Opening its socket returns 409 `THREAD_ARCHIVED` (`spikes/protocol/RESULTS.md:20`). | "This thread is archived." | **Unarchive** |
| Access denied | The user lost access. | "You no longer have access to this thread." | **Close** |

The app detects Cross-Client Access and passkey requirements from server rejections, because the policy flags come from private endpoints that are not identified (Amp's docs). The passkey sign-in session differs from a normal session in the `sid` claim of the socket token (`docs/protocol/private-protocol.md:31`).

When a passkey is required, notification actions become **Open** (`docs/design/notifications.md`, "Answer from the notification").

## Keyboard shortcuts

This is the app's only shortcut table. `layout.md`, `terminal.md`, and the sketch refer to it. The app publishes these shortcuts to the system helper through `KeyboardShortcutGroup`, and **Meta+/** shows the system list. Where Amp has a binding, the app reuses it. "App" in the Source column means the binding is this app's choice.

When the Terminal pane has focus, every key goes to the shell except chords with **Ctrl+Shift** and **Meta**. The In terminal column says which shortcuts still work there. Every app shortcut that must work from the terminal therefore uses **Ctrl+Shift**. Rows whose Where column reads Terminal are the terminal's own keys. Shortcuts are fixed, because the app has no keymap customization (`docs/design/product.md`, "Not built").

| Shortcut | Where | Action | In terminal | Source |
|---|---|---|---|---|
| **Enter** | Composer | Send when idle, Steer when busy | No | Amp's docs |
| **Ctrl+Enter** | Composer | Queue | No | Amp's docs |
| **Shift+Enter** | Composer | New line | No | Amp's docs |
| **Esc** twice | Thread | Interrupt, or Interrupt and Send when the field holds text | No | Amp's docs |
| **Up** | Empty composer | Select the last queued message | No | Amp's news |
| **Enter** twice | Selected queued message | Steer it now | No | Amp's docs |
| **Enter** twice | Empty new-thread composer | Reference the previous thread | No | Amp's news |
| **Ctrl+V** | Composer | Paste text or an image | No | Amp's docs |
| **Ctrl+F** | Thread | Find in thread | No | App, as browser find |
| **Ctrl+K** | Anywhere | Focus Sidebar search | No | App |
| **Ctrl+N** | Anywhere | New thread | No | App |
| **Ctrl+O** | Anywhere | Command palette | No | CLI palette binding (Amp's docs) |
| **Ctrl+/** | Anywhere | Open or close Puck | No | Amp's docs |
| **Ctrl+G** then **Z** | Thread | Snooze menu | No | Amp's docs |
| **Ctrl+,** | Anywhere | Settings | No | Mac (Amp's docs) |
| **Ctrl+Alt+,** | Anywhere | Workspace settings on web | No | Mac (Amp's docs) |
| **Ctrl+[**, **Ctrl+]** | Anywhere | Back and forward | No | Mac (Amp's docs) |
| **Ctrl+R** | Thread | Reconnect and reload the thread | No | Mac reload (Amp's docs) |
| **Ctrl+=**, **Ctrl+-**, **Ctrl+0** | Anywhere | App text size up, down, reset | No | Mac zoom (Amp's docs) |
| **Alt+1** to **Alt+6** | Thread | Machine tabs Changes, Files, Terminal, Portal, Desktop, Space | No | App |
| **Ctrl+Shift+P** | Anywhere | Command palette | Yes | App |
| **Ctrl+Shift+1** to **Ctrl+Shift+6** | Anywhere | Arrangements Triage, Review, Terminal, Read, Puck, Focus, mapped per window class (`layout.md`, "Arrangements by window class") | Yes | App |
| **Ctrl+Shift+Left**, **Ctrl+Shift+Right** | Anywhere | Move focus to the previous or next pane | Yes | App |
| **Ctrl+Shift+E** | Machine pane | Expand or Collapse (`layout.md`, "Arrangements") | Yes | App |
| **Ctrl+Shift+A** | Anywhere | Open Activity | Yes | App |
| **Ctrl+Shift+G** | Any window that shows a thread | Move focus to the gate dock | Yes | App |
| **Ctrl+Shift+Enter** | Gate dock | Activate the focused gate's primary action | No | App |
| **Ctrl+Shift+D** | Anywhere | Toggle dictation. Hold to dictate and send on release. | Yes | Amp's docs |
| **Ctrl+Shift+/** | Anywhere | Call Puck. Shows the later-release placeholder in the first release. | Yes | Amp's docs |
| **Ctrl+Shift+N** | Anywhere | New window | Yes | Mac **New Amp Window** (Amp's docs) |
| **Ctrl+Shift+C**, **Meta+C** | Terminal | Copy the selection | Yes | Web terminal (`terminal.md`, "Hardware keyboard and DeX") |
| **Ctrl+Shift+V**, **Meta+V** | Terminal | Paste | Yes | Web terminal |
| **Ctrl+Shift+T** | Terminal | New terminal tab | Yes | App |
| **Ctrl+Shift+W** | Terminal | Close the terminal tab | Yes | App |
| **Ctrl+Shift+=**, **Ctrl+Shift+-**, **Ctrl+Shift+0** | Terminal | Terminal text size up, down, default | Yes | App |
| **Up**, **Down**, **J**, **K** | Lists | Move selection | No | Agents Panel (Amp's news) |
| **Enter** | Lists | Open the selected thread | No | Agents Panel (Amp's news) |
| **Space** | Thread list | Expand the selected row's last message | No | Agents Panel (Amp's news) |
| **E** | Thread list | Archive or unarchive the selected thread | No | Agents Panel (Amp's news) |
| **Tab**, **Shift+Tab** | Anywhere | Move focus between panes and controls | No | `layout.md`, "Keyboard, mouse, and trackpad" |
| **Tab** | Directory picker | Toggle **Current** and **New Worktree** | No | Amp's news |
| **Meta+/** | Anywhere | System shortcut list | Yes | Amp's docs |

No shortcut approves or denies a tool. **Ctrl+Shift+Enter** acts only on the gate that has focus in the dock, where its full input is on screen. Inline **Allow** in **Needs you** is outside keyboard list traversal, so **Enter** in a list opens a thread and never approves.

## Parity checklist map

Each row of the native app parity checklist (Amp's docs) maps to the section that matches or beats it. Row n is at line 404+n of that file.

| # | Official app feature | Section here |
|---|---|---|
| 1 | Same threads, orbs, Puck as ampcode.com | Whole document. Contract gaps fall back to **Open on web**. |
| 2 | Browser sign-in returning to the app | [Sign in](#sign-in) |
| 3 | Up to five accounts, **Switch Account** | [Account switcher](#account-switcher) |
| 4 | Passkey step-up | [Connection and executor states](#connection-and-executor-states), passkey required |
| 5 | Thread-ready notifications | `notifications.md`, [Needs you](#needs-you) |
| 6 | **App Settings** for notifications | [Native settings](#native-settings), Notifications |
| 7 | Notification permission prompt | [Sign in](#sign-in), [Native settings](#native-settings) Notifications, the Sidebar's "Notifications are off" line |
| 8 | Dictation | [Dictation](#dictation) |
| 9 | **Dictate to Puck** shortcut | [Puck pane](#puck-pane), entry points |
| 10 | **Call Puck** shortcut | [Puck pane](#puck-pane). Later release, placeholder now. |
| 11 | Puck call as a system call | Later release (`docs/design/product.md:40`) |
| 12 | Animated Puck voice | Later release, with Puck calls |
| 13 | Camera, photo library, Files | [Attachments](#attachments) |
| 14 | **Take Screenshot** | [Attachments](#attachments) |
| 15 | **Record Screen** | [Attachments](#attachments) |
| 16 | **Attach Latest Screen Recording** | [Attachments](#attachments) |
| 17 | Share image to Puck | [Puck pane](#puck-pane). Beats it with text, files, and URLs. |
| 18 | Puck from the sidebar | [Sidebar](#sidebar) header and rail |
| 19 | Puck window that follows the main window | [Puck pane](#puck-pane), Puck arrangement |
| 20 | Thread links open in the app | [Thread view](#thread-view) |
| 21 | **Open in Mac App** handoff | [Thread view](#thread-view). App Links open threads from Chrome. |
| 22 | Multiple and auxiliary windows | [More Actions](#more-actions), [Files](#files), `layout.md` |
| 23 | Keyboard shortcuts and shortcut list | [Keyboard shortcuts](#keyboard-shortcuts) |
| 24 | Physical keyboard awareness | [Touch and hardware keyboard](#touch-and-hardware-keyboard) |
| 25 | Long-press send to queue | [Send, steer, queue, and interrupt](#send-steer-queue-and-interrupt) |
| 26 | Native composer | [Composer](#composer) |
| 27 | Native new-thread sheet | [New thread](#new-thread) |
| 28 | Native sidebar | [Sidebar](#sidebar) |
| 29 | Native pane tabs | [Machine panes](#machine-panes) |
| 30 | Native Changes drawer and toolbar | [Changes](#changes), `git-and-diffs.md` |
| 31 | Pane drawer lifecycle | [Machine panes](#machine-panes), wake rules |
| 32 | Thread navigator | [Rail](#rail), [Keyboard shortcuts](#keyboard-shortcuts) list navigation |
| 33 | Native thread header | [Title bar](#title-bar) |
| 34 | Native menus and context menus | [Row actions](#row-actions), [More Actions](#more-actions) |
| 35 | Confirmation sheets | Delete, Ship, multiplayer, and archive confirmations in their sections |
| 36 | Plugin form sheet | [Plugin dialogs](#plugin-dialogs) |
| 37 | Mention picker | [Mentions](#mentions) |
| 38 | Transcript selection actions | [Messages](#messages) |
| 39 | Unread-mention indicator | [Thread rows](#thread-rows) mention badge, [Activity and search](#activity-and-search) |
| 40 | Image and video lightbox | [Images and media](#images-and-media) |
| 41 | Toasts with actions | Snackbars with **Undo** and similar actions throughout |
| 42 | Haptics | [Gate dock](#gate-dock), App settings |
| 43 | Screen wake lock | [Attachments](#attachments) recording and `terminal.md`, scoped to the active owner |
| 44 | Clipboard read and write | Copy actions throughout. Secret plugin inputs are never copied. |
| 45 | Native file save | [Files](#files), [More Actions](#more-actions) **Export** |
| 46 | Open external URLs outside the app | [Block types](#block-types), links open in Custom Tabs |
| 47 | Offline message outbox | [Outbox](#outbox) |
| 48 | Home-screen recent threads | Later release with widgets (`docs/design/product.md:41`) |
| 49 | Native create-project flow | [Settings in a web view](#settings-in-a-web-view), **Projects** |
| 50 | Import local projects from other agents | Not applicable. Android runs no local agents. |
| 51 | Project Puck threads | Not built. The feature is undocumented and web-only (Amp's docs). |
| 52 | Space overview | [Space](#space). Later release. |
| 53 | Native app settings | [Native settings](#native-settings) |
| 54 | Self-update | [Native settings](#native-settings), About |
| 55 | Menu-bar icon | Not applicable. The **Watch** ongoing notification covers live status (`docs/design/notifications.md`, "Watching a running thread"). |
| 56 | Mac as a runner | Not applicable. [Native settings](#native-settings) Runners explains it. |
| 57 | **This Mac** label in the picker | [Executor picker](#executor-picker), origin and online state |
| 58 | Allow Terminal Access, Share Display, Amp Net, Keep Awake | [Which tabs appear](#which-tabs-appear), [Desktop](#desktop), [More Actions](#more-actions) Orb submenu |
| 59 | Desktop viewing and control | [Desktop](#desktop). Later release. |
| 60 | Window drag regions and insets | `layout.md` DeX freeform windows |
| 61 | Progress indicator | [Transcript structure](#transcript-structure) activity row |
| 62 | iPad idiom | `layout.md` width classes |
| 63 | UI sounds toggle | [Native settings](#native-settings), App |
| 64 | Diagnostics | [More Actions](#more-actions) **Report Bug…** |
| 65 | **Report Bug…** | [More Actions](#more-actions) |
| 66 | Host visibility signal | [Wake an orb only for a shown thread](#wake-an-orb-only-for-a-shown-thread), socket leases |

## Open questions

- Does the user actor report `awaiting_approval` in `threadStatusUpdated`? If it does not, "needs approval" on a closed thread depends on push alone.
- What are the wire formats of `tool_approval_queue`, `ask_user_choice` questions, MCP elicitation, and `ui.select`? Those gates fall back to **Answer on web** until a spike observes them. Spike row S15 has the rerun for tool approvals (`spikes/protocol/RESULTS.md:65`).
- Does connecting the thread socket wake a paused orb, or only machine panes and messages? The app assumes it does and opens a paused orb's thread with no socket. If it does not, **Connect Live** can become automatic.
- Which wire values does `updateThread{snooze}` take for **Until Next Reply**, **Until 8am**, **Until Monday Morning**, and **Indefinitely**? Only `one_hour` and `off` were observed (`spikes/protocol/RESULTS.md:20`).
- How large a `client_filesystem_read_file_v2` result can the thread socket carry well, and how large a `client_filesystem_write_file_v3`? S19 measured only small files (`spikes/protocol/RESULTS.md:71`).
- Does Amp forward plugin status items to clients other than the CLI (Amp's docs)?
- Do `/api/threads/find` and `listFeedItems` accept the app's token? Until they do, search and Activity cover only threads the device has seen.
- Which time zone do **Until 8am** and **Until Monday Morning** use, the device's or the account's (Amp's docs)?
- Where are plugin link patterns stored on a thread, and how does a client read them (Amp's docs)?
- What do `view_media`, `present_media`, and Painter results look like on the wire (Amp's docs)?
- Does Amp accept non-image attachments as a content block, or only as URLs in the text?
- Does a member list exist that a user token can read, so the mention picker can list the whole workspace?
- Which `ttlSeconds` values does `client_set_thread_open` accept between 5 minutes and 7 days?
