# Coverage: every `must` and `should` capability

Columns:

- **Served by**: the module and type in `docs/design/sketch/`.
- **Contract**: the tier (`cli`, `web-plain`, `web-remote`), the spike verdict where one exists (S1-S14 spike 1, S15-S20 spike 2, `spikes/protocol/RESULTS.md`), and the fallback for anything not VERIFIED.

The **web view** rows say which surface: `MachineTab.Portal` (rendered by `:feature:web`), `WebSurface.McpApp` and `WebSurface.AmpView` are sandboxed in-app WebViews. `AccountWebPage.*` renders in the account's own WebView profile `amp-<accountId>`, never the browser's session; `Passkeys` alone opens in a Custom Tab. **out** rows say why.

No row calls a `web-remote` function from native code. Where the only backing is `web-remote`, the row is `web view`. The lint `AmpSourceBans` fails the build on the literal `/_app/remote/` outside `:feature:web`.

Counts: 118 must and 80 should rows (198 in total) are listed below. 182 are served natively (a few only partly, with the gap named). 12 are web view only, each because its backing is `web-remote`, its content is web, or `product.md` puts it in a web view. 4 are out: three CLI process I/O modes, plus hosting a runner on the device. The picker side of runners is native. Account settings rows read `AccountResource` keys and write `AccountCommand`s (`core-model/Settings.kt`, `core-model/Intents.kt`).

## cli-sdk

| id | rel | Served by | Contract / fallback |
|---|---|---|---|
| cli-account-management | should | `:feature:settings` Accounts page; `AmpEngines.roster/signIn/signOut` | `cli` device flow. S1 VERIFIED to `authorization_pending`; exchange not observed. If exchange fails, fall back to the loopback PKCE flow (`private-protocol.md:56-64`). |
| image-attachment-in-the-prompt | must | `:feature:thread` Composer · `AttachmentDraft`; `:sync` `AmpHttp.uploadAttachment` | `cli` `/api/attachments/resumable`, S12 VERIFIED. `AuthedImage` sends Bearer (S12 note). |
| choose-where-a-new-thread-runs-executor | must | `:feature:fleet` `NewThreadPane` · `ExecutorChoice`; `Catalog.runners/projects` | `cli` `listRunners` S3 VERIFIED; `client_spawn_executor` S4 VERIFIED (runner). |
| command-palette-and-keyboard-shortcuts | should | `:app` `CommandPalette` (window bar button `window-palette` on touch); shortcuts are the one table in `screens.md` "Keyboard shortcuts" | Client-side. Plugin commands come from `command.list` and run through `ThreadIntent.RunPluginCommand` (S15b VERIFIED). |
| prompt-composition-steer-queue-newline | must | `Composer`: Enter = primary (Steer while busy), Shift+Enter = newline, Queue per `screens.md`, each also a real button | `cli` S5 VERIFIED for steer. Queue (append without `steer` while busy) is read from the web client, not observed; the M3 gate adds a queued send. |
| execute-mode-x | should | out | CLI process mode. The user outcome (start with a prompt, watch it run) is `NewThreadPane` + `ThreadPane`. |
| fast-mode-and-thread-features | should | `ThreadIntent.SetFeature`; thread menu | `cli` `client_update_thread_feature`. |
| agent-mode-the-dial-at-creation | must | `ModeChip.Choosable` → `Locked`; `Catalog.modes` | `cli` `listAgentModes✓`. |
| spawn-an-orb-thread-ox | must | `ExecutorChoice.Orb`; `AccountScope.startThread` → `StartedThread` | `cli` `POST /api/thread-actors` + `client_spawn_executor{projectID, environmentResource}`. Orb spawn was not spiked (account could not start orbs). On failure, `AccountScope.respawn` retries `client_spawn_executor` on the same thread, or with another executor; it never creates a second thread. |
| attach-files-to-a-remote-execute-message | must | Composer attachments; `Files.upload` | `cli` S12 VERIFIED (attachments). `client_filesystem_write_file_v3` VERIFIED (S19) for a 15-byte write. Larger files go as an attachment upload plus `amp-exec-v1` staging (NOT VERIFIED); its fallback is the attachment URL in the message. |
| continue-a-thread | must | `ThreadHandle` + Composer | `cli` S4/S5 VERIFIED. |
| streaming-json-output | should | out (see execute-mode-x) | stdout format of the CLI. The live transcript is the native equivalent. |
| streaming-json-input-multi-turn-over-stdin | should | out (see execute-mode-x) | stdin mode of the CLI. Multi-turn is the composer. |
| non-interactive-authentication | should | `SignIn` device flow | The code can be approved on any other device. `cli`. |
| start-a-runner | must | out as host; `Catalog.runners` + Settings → Runners shows the `amp --no-tui` command | Android cannot host a runner. The picker side is native (S3). |
| runner-share-network-and-browser-amp-net | should | `ThreadIntent.SetNetRoute`; thread menu "Route through runner" | `cli` `setThreadNetRoute✓/getThreadNetRoute✓`. Android is never the exit node. |
| runner-and-cli-terminal-access-from-other-clients | must | `:feature:terminal` + `Machine.terminal` → `TerminalSession` | `cli` relay `amp-terminal-v1`, S8 VERIFIED. |
| runner-desktop-sharing | must | `:feature:desktop` `DesktopTab` + `:waymote` `WaymoteSession` | `cli` constants. Relay service names and pointer schema NOT VERIFIED. Degraded mode: [Open on web] (`AccountWebPage.ThreadOnWeb`). `product.md` defers Desktop to a later release. |
| runner-serve-multiple-directories | must | `RunnerInfo.directories` in picker | `cli` S3 (directories listed). |
| runner-create-directory-or-project-from-a-client | should | Picker free-text directory when runner has `any-directory` | `cli` capability `any-directory` (S3) + `client_spawn_executor{workingDirectory}`. The web's `createExecutorRunnerDirectory` (web-remote) is not used. |
| runner-create-worktree-archive-and-remove-worktree | must | `WorktreeChoice.NewWorktree` when runner has `create-worktree`; archive via `ThreadIntent.Archive` | Capability `cli` (S3). The spawn parameter name is unverified. Remove-worktree tries internal `removeThreadRunnerWorktree`, else shows the CLI command. |
| runner-secrets-env-vars-injection | should | `AccountResource.Secrets(SecretTarget)` + `AccountCommand.SetSecret` (Ephemeral: never stored) | `cli` `listEnvVarSecrets✓/setEnvVarSecret✓`. |
| share-a-runner-with-the-workspace | must | `AccountResource.RunnerShares` + `AccountCommand.ShareRunner` (Replayable) | `cli` `shareRunner✓/revokeRunnerShare✓/listRunnerShares✓`. |
| runners-vs-orbs-feature-matrix | must | `MachineCaps` · `Availability.No(reason)` per pane | Derived from `observers` flags (`cli`). Reasons are shown, never hidden, except the Desktop tab, which appears only when the executor reports a desktop. |
| runner-troubleshooting | should | `ExecutorState.RunnerOffline`; Settings → Runners help text | `cli` `listRunners`; no host repair on Android. |
| tui-as-runner | should | `ExecutorKind.LocalCli` | Same thread actor (`cli`). |
| cross-client-access | must | `ThreadHandle` on any thread; `AuthHealth.NeedsStepUp` | `cli`. Passkey step-up → web view (next row). |
| passkey-authentication-for-web-and-app-interaction | must | web view: `AccountWebPage.Passkeys` on `EngineError.StepUpRequired`, then retry | Sudo passkey flow is `web-remote` only. The browser handles passkeys (Credential Manager). |
| thread-management-from-the-cli | must | `Fleet` + organization `ThreadIntent`s | `cli` S10 VERIFIED. |
| live-active-thread-monitor-amp-top | must | `:feature:fleet` `RunningPane` · `Fleet.running` | `cli` user actor `threadStatusUpdated`, S14 VERIFIED. |
| account-usage-and-credits | should | `Catalog.credits` · `CreditsGlance`; `ThreadHeader.cost` · `CostGlance` | `cli` `userDisplayBalanceInfo✓`, `threadDisplayCostInfo✓` (measured `cost-check.jsonl:2`). |
| sdk-multi-turn-streaming-input-and-idempotent-sends | must | `ThreadIntent.Send(messageId = MessageId.mint())` is `Replayable`; `:store` `Outbox` | Client-minted `M-` ids measured (`s05-steer-interrupt.jsonl:55`). Server dedupe VERIFIED (S16, `s16-dedupe.jsonl:35-46`): a resend returns ok, re-emits the original `seq`, and leaves one transcript copy. |
| sdk-threadsnew-threadsmarkdown-threadssetmultiplayer-typescript-only | must | `startThread`; thread menu "Copy as Markdown" (local render); `ThreadIntent.OpenToCollaborators` | `cli` `POST /api/thread-actors`, `client_set_thread_open`. |
| sdk-tool-permissions-and-errors | should | `ToolRun.Rejected/Error`; `ToolApprovalGate` | `cli` `tool_approval_queue`. Payload NOT VERIFIED (S15) → `UnsupportedGate`. |
| portal-sign-in-for-non-browser-clients | must | `Portals.loginUrl` → web view `MachineTab.Portal` (`PortalTab`, `:feature:web`) | `cli` `resolveThreadPortalURL✓/mintPortalLoginURL✓`. S13 INCONCLUSIVE (data plane 503). Fallback: Custom Tab with the portal URL. |
| external-api-oauth-client-management | must | `AccountResource.OAuthClients` + `AccountCommand.CreateOAuthClient` (AtMostOnce) | `cli` `listExternalAPIOAuthClients✓`, `create…✓`, `update…✓`. |

## collab-apps

| id | rel | Served by | Contract / fallback |
|---|---|---|---|
| install-and-update | must | `:app` Play in-app updates | Distribution only. No web layer to refresh. |
| sign-in-and-multiple-accounts | must | `SignIn`, `AmpEngines.roster`, one `AccountScope` per account, one account per window (`WindowState`), `TokenVault` in `:sync` | `cli` (S1). Per-account DB, vault and push instance. No account cap beyond 5 per docs parity. |
| notifications | must | `:notify` (`PushChannelSelector`, `RelayMessagingService`, `UnifiedPushReceiver`, `KeepAlivePolicy`, `WatchService` (`specialUse`), `PollWorker`, `Notifier`, `NotificationLedger`), `:sync` (`PushRelayClient`, `BackgroundAccess.ensurePushRegistration`), `relay/` (Cloudflare Worker, `docs/design/push-relay.md`) | `cli` `client_upsert_notification_subscription`, S11 VERIFIED (ntfy). Delivery to the relay's host: spike S11b before M9. Fallbacks: UnifiedPush without Play services, user-actor socket while watching (S14), `getRecentThreads` polling (S2). The user's watch limit ends the watch service, and the policy then picks push or polling. |
| dictation | must | `ComposerState.dictation` · `DictationState` | `web-plain` `/api/voice-transcription`; Bearer NOT VERIFIED → on-device `SpeechRecognizer`. Lock: `cli` `claimDictation`. |
| os-shortcuts-dictate-to-puck-and-call-puck | must | `:notify` `LauncherSurfaces` (static shortcuts, QS tile) → `PaneDestination.Puck` | Client-side. |
| call-puck-as-a-phone-call-iphone | must | `:feature:puck` `PuckVoiceChannel` + Core-Telecom self-managed call | `cli` `client_puck_voice_*` (not spiked). |
| attach-screenshots-and-screen-recordings-ios | must | Composer attach sheet: camera, Photo Picker, SAF, MediaProjection recording, latest recording from MediaStore | `cli` attachments S12. |
| share-to-puck-share-sheet | must | `:app` share target → `PuckDirectory.open` + `ThreadIntent.Send` | `cli` `openPuckThread✓`. |
| open-threads-from-links | must | `:app` `EntryRouter` (App Links incl. per-account custom domains) | Client-side. |
| mac-windows-and-menus | must | `:app` multi-instance windows, `Workspace` per windowKey, one account per window, the `screens.md` shortcut table, menus of real buttons | Client-side. |
| queue-a-message-from-the-phone | must | `ComposerActionKind.Queue` (real button) | `cli` `client_append_user_msg` without `steer` while busy: read from the web client, not observed (S5 captured steer only). The M3 gate adds a queued send. |
| report-a-bug-from-a-thread | should | `ThreadIntent.ReportBug` (AtMostOnce) | `cli` `openCustomBugReport✓`, `attachBugReportDiagnostics✓`. |
| see-a-runners-desktop-from-the-phone | must | `:feature:desktop` | See runner-desktop-sharing. |
| this-mac-in-the-executor-picker | should | `RunnerInfo` hostname, shared and online badges | `cli` S3. |
| allow-terminal-access | should | `TerminalStatus.Unavailable(RunnerFlagMissing)` with fix text | `cli`. |
| share-this-macs-display | must | `DesktopTarget.Mac` (`macDesktopAvailable`, `amp-mac-desktop-v1`) | `cli` constants. NOT VERIFIED → [Open on web]. |
| keep-this-mac-awake-and-sleep-behavior | must | `ExecutorState.RunnerOffline`; picker hides offline runners | `cli` `executor_status`, `listRunners` (S3 shows the runner gone after stop). |
| open-puck | must | `:feature:puck` Puck pane | `cli` `openPuckThread✓`. |
| puck-conversations | must | `PuckDirectory.conversations` | Puck threads from `listThreads` filtered by Puck mode (inferred). `listPuckConversations` is web-remote, not used. |
| start-steer-find-and-coordinate-via-puck | must | Puck `ThreadHandle`; `puck_platform_navigation` → `layout.place` | `cli`. |
| saved-prompts-and-changes-tab-buttons | should | Built-ins as `ChangesAction` (Ship, UpdateBase, Restack, Review); saved prompts → web view `AccountWebPage.SavedPrompts` | `getChangesPrompts` is web-remote, so the app shows no custom Changes buttons (accepted gap). |
| skills-and-connected-services-in-puck | should | Rendered as tool cards in the Puck thread | Server-side. |
| call-puck-voice-conversation | must | `PuckVoiceChannel` | `cli` `client_puck_voice_*`. |
| slack-integration-puck-in-slack | should | App Links open Slack-linked threads; origin badge; setup → web view `AccountWebPage.Integrations` | Setup is web-remote. |
| agent-to-agent | must | `Fleet.children`, `ThreadToolCard`, `PromptOrigin.Agent`; side by side via **Open in New Window** (layout.md) | `cli` `listThreads` relationships (S2 fields). |
| automations-schedules | must | `AccountResource.Automations` + `AccountCommand.SetAutomationEnabled/DeleteAutomation`; `PromptOrigin.Automation` | `cli` `listAutomations✓`, `updateAutomation✓`, `deleteAutomation✓`. Created by prompt. |
| run-quietly-in-a-snoozed-thread | must | `SnoozeState`; `Notifier` posts only `needs_you` for snoozed threads | Write `cli` S10: only `one_hour` and `off` observed; the other `SnoozeUntil` cases are read, not observed. Read via `SnoozeLedger` + `find?q=snoozed:true` (NOT VERIFIED). |
| event-driven-orbs-webhooks-and-trigger-settings | should | web view `AccountWebPage.ThreadOnWeb`; archive warning when the automations list names the thread | `listPluginTriggers` is web-remote. |
| desktop-tab | must | `:feature:desktop` | See runner-desktop-sharing. |
| self-hosted-orbs-user-facing-parts | should | `OrbProvider.SelfHosted.Listed`/`.Named` in picker; `OrbProviderSource` read by `:feature:web`'s hidden WebView; `ExecutorState.SetupFailed` + `SetupLogRef` | `cli` `createRemoteExecutorThread✓{orbProvider}` starts a self-hosted orb: `{configID}` for a listed provider, as the web composer sends (web chunk `cf27bdbc.js`), `{name}` for a typed one, as `amp -ox --orb-provider <name>` does; `client_spawn_executor` has no provider field (web chunk `b0f88c20.js`). The list is cookie-only: `1igxvbu/getWorkspaceSelfHostedOrbs` and `18dvixz/getPersonalOrbProviders` answer 401 to the Bearer key (S24). The web composer's `orbProviderOptions` (`configID`, `name`, `isDefault`, `owner`) is `/(app)` layout data (web chunk `aa7cdc32.js`), so a never-shown WebView on the account's profile fetches `/__data.json?x-sveltekit-invalidated=110` and posts back only those four fields per provider; native code never sees the cookie (`auth-and-security.md`, "The Self-Hosted orb list"). Without a web session the picker offers the web sign-in; typed names stay as the fallback. Not observed on ampcode.com: which layout node carries `user`, and what a signed-out profile gets. |
| workspaces | should | `Account.workspace`; web view `AccountWebPage.WorkspaceAdmin/Members` | Admin is web-remote. |
| groups-enterprise | should | `Visibility.Groups` display; group picker → web view `AccountWebPage.WorkspaceGroups` | Group list is web-remote / M2M API. |
| multiplayer | must | `ThreadIntent.OpenToCollaborators`, `Collaboration.Open`, `Viewer.Contributor`, `Presence` | `cli` `client_set_thread_open`, `observers`, `prompt_drafts`. |
| space-voice-video-screen-share-in-a-thread | must | `:feature:space` `SpaceTab` (`MachineTab.Space`) · `SpaceAccess` | `web-plain` `space-token` + LiveKit SDK. Bearer NOT VERIFIED → `SpaceAccess.WebOnly` (the thread page in the account's WebView profile). `product.md` defers Space to a later release. |

## core

| id | rel | Served by | Contract / fallback |
|---|---|---|---|
| multi-client-one-thread-everywhere | must | `ThreadEngine` resume (`client_resume{version}`); `ThreadFact` classes and invariants I1-I8 | `cli` S9 VERIFIED (replay equals paging). Cursor after paged history = `thread.data.v` of `getThreadInitialTranscriptPage` (S17 VERIFIED: resume at `v` replays 0 messages). |
| ready-for-next-message-notifications | must | `:notify` `Notifier.postReady` | S11 VERIFIED `thread_idle`. |
| the-dial-pick-a-mode-before-the-first-message | must | `ModeChip` | `cli` `listAgentModes✓`. |
| mode-routing-models-behind-each-mode-oracle-per-mode | should | Mode info sheet (read-only) | `cli` `getAgentModelCatalog✓`. |
| claude-code-mode-claude-subscription | should | Appears as an `AgentMode`; transcript renders generically | `cli`. Connecting the subscription → web view `Integrations`. |
| build-dial-and-share-it | should | web view `AccountWebPage.PuckSettings` (dial editor route TBD) | Editing is web-remote. |
| modes-not-on-the-dial-custom-and-plugin-modes | must | `ModeChip.Choosable.options` | `cli` `listAgentModes✓`. |
| specialist-subagents-search-oracle-librarian-task-read-thread | must | `SubagentCard`, `ThreadToolCard` | Transcript (`cli`). |
| system-models | should | Mode info sheet | `cli` `getAgentModelCatalog✓`. |
| oracle | must | `SubagentCard` | Transcript. |
| librarian | must | `SubagentCard` | Transcript. |
| painter-image-generation-and-editing | must | `MediaCard` + `AuthedImage` | Transcript; images need Bearer (S12). |
| designing-ui-interactive-prototypes-amp-view | must | web view `WebSurface.AmpView` | Inherently HTML: sandboxed, network-off WebView. |
| designing-ui-review-the-real-app-in-a-portal | should | web view `MachineTab.Portal` beside the transcript (Triage), or Focus | See portals. |
| built-in-tool-catalog-and-permissions | must | `ToolCards` (one list; `ToolCardsTest` fails on a duplicate name) + `GenericToolCard`; `ToolApprovalGate` | Transcript + `tool_approval_queue` (`cli`). |
| reference-other-threads | should | Thread links and chips → `PaneDestination.Thread` | Client-side. |
| find-threads-activity-feed-and-query-syntax | must | `ActivityPane`, `ThreadQuery`, `Fleet.activity/search` | `cli` `/api/threads/find`, `listFeedItems` (both NOT VERIFIED) → local filtering over cached summaries, `serverFiltered=false`. |
| sidebar-organization | must | `groupSidebar` → `Sidebar` | Derived from `cli` (S2, S14). The web-remote sidebar groups are not used. |
| start-a-thread | must | `NewThreadPane`, `startThread` | `cli` S4 VERIFIED. |
| thread-view-title-bar-and-panes | must | `ThreadTitleBar`; `:app` `MachinePane` tab strip over an exhaustive `when` on `MachineTab` | `cli`. |
| review-changes-intelligent-file-order | must | `FileOrder.Intelligent` | `cli` `client_changes_file_order` (not spiked) → `FileOrder.Alphabetical` on failure. |
| composer-send-steer-queue-interrupt | must | `Composer`, `ComposerAction`, `SendMode`, `ThreadIntent.Interrupt` (AtMostOnce, its own command) | `cli` S5 VERIFIED for steer and interrupt. Queue is read, not observed. |
| edit-a-prior-message | should | `ThreadIntent.EditMessage` with a minted `editId`; `edit_rejected` → marker | `cli` `client_edit_message`. Dedupe by `editId` is read, not observed. |
| attach-images | must | Composer attachments | S12 VERIFIED. |
| voice-input-dictation | must | `DictationState` | See dictation. |
| continue-a-thread-anywhere | must | `ThreadHandle` | `cli`. |
| share-a-thread-and-set-visibility | must | Share sheet: `ThreadIntent.SetVisibility` + Android share of the URL | `cli` `updateThread✓{visibility}`. Group choice per groups row. |
| organize-pin-color-labels-rename-snooze-archive-delete-mark-as-unread | must | Row [⋯] menu + thread menu → `Pin/Color/SetLabels/Rename/Snooze/Archive/Delete/MarkUnread` | S10 VERIFIED, except snooze values other than `one_hour` and `off` (read, not observed). Rename via `client_set_thread_title` (S10 note). |
| multiplayer-and-tagging | must | `@` picker from `Presence` + thread participants; invites of others → web view Share | Mentions within the thread are `cli`. `listMentionableWorkspaceMembersQuery` is web-remote. |
| pricing-tiers-credits-and-per-thread-cost | should | `CostGlance`, `CreditsGlance`; plans → web view `AccountWebPage.Billing` | `cli` plus web. |
| enterprise-controls-signals-only | should | `Availability.No(PolicyDisabled)`, `SpaceAccess.Disabled` | Policy flags from `getUserInfo✓` (inferred) and server errors. |
| github-integration | should | PR and commit chips from transcript; connect → web view `Integrations` | Connect flow is OAuth on the web. |
| linear-agent-sessions-assign-or-mention-amp | should | Threads render with origin badge | Server-side. |
| slack-and-other-mcp-services | should | MCP tool cards; connect → web view | Server-side. |
| puck-entry-point | should | Rail button, the Call Puck shortcut in `screens.md`, launcher shortcuts | Client-side. |

## customize-enterprise

| id | rel | Served by | Contract / fallback |
|---|---|---|---|
| plugin-dialogs-notify-input-confirm-select | must | `PluginGate.Confirm/Input/SecretInput/Select`; notify → snackbar; answer via `ThreadIntent.Answer` (`GateResponse.PluginAnswer`), or `ThreadIntent.AnswerSecret` (Ephemeral) for a secret input | `cli` wire VERIFIED for `ui.confirm`, `ui.input`, `ui.notify` (S15b): `plugin.ui.request_added` / `request_settled`, answer `executor_plugin_message` `plugin.ui.respond{requestId, result|error}`, re-listed in `plugin.ui.snapshot` on reconnect (S15c). `ui.select` renders natively but is read, not observed. `request_removed` is read, not observed. Any other method, or a payload that fails to decode → `UnsupportedGate` + [Answer on web]. |
| plugin-tool-call-approvals-and-turn-control | must | `ToolApprovalGate`; `ThreadIntent.Answer` is `Guarded(GatePending)` | `cli` `client_tool_approval_response` over the socket. NOT VERIFIED (S15) → `UnsupportedGate` + [Answer on web]. |
| plugin-commands-in-the-command-palette | should | `CommandPalette` from `ThreadState.pluginCommands`; `ThreadIntent.RunPluginCommand` (AtMostOnce) | `cli` VERIFIED (S15b): `plugin_message` `command.list` lists them; `executor_plugin_message` `command.execute` runs one. |
| plugin-link-patterns-in-the-transcript | should | Markdown linkifier hook in `:core:design` | Storage unknown (`customize-enterprise.md:357`). Plain links until known. |
| plugin-tool-rendering-in-the-transcript | must | `PluginToolCard` → `GenericToolCard` | Transcript (`cli`). |
| custom-agent-modes-from-plugins | must | `ModeChip` options | `cli` `listAgentModes✓`. |
| plugin-created-threads-side-threads-and-steering | should | `PromptOrigin.Plugin`, `Fleet.children` | `cli`. |
| ship-prompt-augmentation | should | Ship sends the prompt; augmentation happens executor-side | inferred. |
| plugin-thread-labels-and-ai-suggested-labels | should | Labels via `getThreadLabels✓`; suggestions unknown | `cli`. |
| global-plugins-personal-and-workspace-management | should | Read-only `ExtensionsGlance.pluginRepos`; manage → web view | `cli` `listGlobalPluginRepositories✓`. |
| skills-listing-sources-reload | should | `ExtensionsGlance.skillRepos` | `cli` `listGlobalSkillRepositories✓`. |
| global-agentsmd-personal-and-workspace-instructions | should | web view (settings) | web-remote. |
| remote-mcp-server-definitions-integrations-mcp-settings | should | Read-only `ExtensionsGlance.mcpServers`; edit → web view `Integrations` | `cli` `mcpListAccountServers✓`. |
| mcp-oauth-sign-in | must | web view `AccountWebPage.Integrations` in the account's WebView profile; [Open in browser] when the provider refuses a WebView | `connectMCPServer` is web-remote. Runner `localhost` paste-back → `ElicitationGate.Form`. |
| per-tool-mcp-policies-and-ask-first-approvals | must | Ask First → `ToolApprovalGate(source = GateSource.Mcp)`; policy editing → web view | Approvals `cli`. Policies web-remote. |
| mcp-privacy-in-multiplayer-threads | should | `GenericToolCard` renders redacted results | Server-side. |
| mcp-elicitation-questions-from-remote-servers | must | `ElicitationGate.Form/OpenUrl` (`SafeElicitUrl`) | Delivery channel NOT VERIFIED (`customize-enterprise.md:362`) → `UnsupportedGate` + [Answer on web]. |
| repository-mcp-server-approval-in-orbs | must | `McpServerApprovalGate` | `cli` `listMCPServerApprovals✓/approveMCPServer✓`. |
| mcp-apps-and-structured-tool-results | should | web view `WebSurface.McpApp` | Inherently HTML. Bridge to `cli` `mcpAppCallTool✓/mcpAppReadResource✓`. |
| model-routing-connections-personal-and-workspace | should | web view `AccountWebPage.ModelRouting` | `product.md` lists model routing under "Shown in a web view, not rebuilt". |
| allowed-models-enterprise-admin-policy-and-member-effects | must | Mode/model lists come filtered from the server; errors map to `EngineError.Server` | `cli` `listAgentModes✓`, `getAgentModelCatalog✓`. |
| workspace-entitlements-member-quota-display | must | `CreditsGlance.Known.workspaceQuota` | `cli` `userDisplayBalanceInfo✓{workspace}` (inferred field). |
| workspace-thread-visibility-controls | must | Visibility picker disables options per policy; server rejection shown | `cli` `updateThread✓`. |
| mcp-registry-allowlist-enterprise | should | Server-enforced; render rejection | Server-side. |
| minimal-data-retention-disclosure | should | Settings → About banner when the account flag is set | `getUserInfo✓` (inferred). |
| report-a-bug | must | `ThreadIntent.ReportBug`; the engine attaches its logs and the `AgentNet` log | `cli` ✓ methods. |
| feedback-and-feature-requests-via-puck | should | Puck | `cli`. |
| help-status-and-supported-platforms | should | Settings → About links (Custom Tab) | Static. |

## news

| id | rel | Served by | Contract / fallback |
|---|---|---|---|
| native-and-multi-surface-clients-where-amp-runs-as-a-ui | must | The whole app | n/a |
| thread-sidebar-and-fleet-management | must | `SidebarPane`, `RunningPane`, `Fleet` | `cli` S2, S14. |
| remote-control-of-cli-and-runner-threads | must | `ThreadHandle` | S4, S5 VERIFIED. |
| composer-steering-queueing-interrupt | must | `Composer` | S5 VERIFIED for steer and interrupt; queue read, not observed. |
| the-mode-dial-model-and-effort-selection | must | `ModeChip` | `cli`. |
| changes-pane-diff-review-and-staging | must | `ChangesPane`, `Changes.stage` | `cli` git ops, S7 VERIFIED (stage ops not spiked). |
| intelligent-file-ordering-in-the-changes-pane | must | `FileOrder` | See review-changes. |
| restack | should | `ChangesAction.Restack` | Prompt send (`cli`). |
| ship-review-and-sync-actions | must | `ChangesAction.Ship/Review`; Sync shows the copyable `amp sync` command | Ship is a prompt (`private-protocol.md:230`). `startThreadShipping` (web-remote) is not used. |
| code-review-agent-and-checks | should | Review action; check output as tool cards | CI status calls are web-remote; shown only when they appear in the transcript. |
| orbs-remote-agent-machines | must | `ExecutorKind.Orb`, `ExecutorState` | `cli` events. |
| orb-terminal-and-the-retired-editor-terminal | should | `:feature:terminal` | S8 VERIFIED (runner). Orb relay is the same path (inferred). |
| portals | must | web view `MachineTab.Portal` | S13 INCONCLUSIVE; Custom Tab fallback. |
| desktop-orb-and-runner | must | `:feature:desktop` | See runner-desktop-sharing. |
| attachments | must | Composer, `AmpHttp.uploadAttachment` | S12 VERIFIED. |
| runners-your-own-machines-as-executors | must | `Catalog.runners`, `ExecutorKind.Runner` | S3, S4 VERIFIED. |
| self-hosted-orbs | should | `OrbProvider.SelfHosted` | See self-hosted-orbs-user-facing-parts. |
| projects-multiple-repositories-and-setup-scripts | should | `ProjectInfo.repos`, `RepoChanges` | `cli` `getProject✓`. Script editing → web view. |
| multiplayer-and-mentions | must | `Collaboration`, `Presence`, `Unread.Mention`, `ActivityItem.Mentioned` | `cli` user actor mention actions/events. |
| space-voice-video-screen-share-per-thread | should | `SpaceTab` | See space row. |
| puck-meta-agent-voice-conversations-mcp | must | `:feature:puck` | `cli`. Puck MCP settings → web view. |
| usage-explanation | should | `CostGlance` detail sheet | `cli` `threadDisplayCostInfo✓{details}`. |
| agent-to-agent-and-custom-agents | must | See agent-to-agent | `cli`. |
| schedules-automations | must | See automations-schedules | `cli`. |
| github-listeners | should | Created by agent tool (card). List/toggle tries internal `listGitHubAutomations/updateGitHubAutomation` | Internal but not CLI-called (unverified) → web view. |
| linear-agent | should | Origin badge | Server-side. |
| plugin-ui-and-built-in-interactive-tools | must | `GateDock` (`ChoiceGate`, `PluginGate`, `UnsupportedGate`) | See plugin dialogs. `ask_user_choice` via approval response `askAnswers` (`cli`, S15). |
| transcript-rendering-collapsed-steps-images-diagrams | must | `TranscriptList`, `Step.Tools` + `StepSummary`, `AuthedImage`; diagrams (mermaid) in a sandboxed `WebSurface.AmpView`-style card | Transcript `cli`. JS-rendered diagrams are inherently web. |
| painter-image-generation | should | `MediaCard` | Transcript. |
| referencing-reading-and-finding-threads | must | Thread chips, `Fleet.search`, `ThreadToolCard` | `cli`. |
| compaction-handoff-and-fork-history | should | `Step.Compaction`, `MarkerKind.Handoff/Forked`, `ThreadSummary.parent` | `cli` events. |
| thread-labels | should | `ThreadIntent.SetLabels`, `Fleet.labels` | S10 VERIFIED. |
| command-palette | should | `CommandPalette` | Client-side. |
| thread-visibility-and-sharing | must | See share-a-thread | `cli`. |
| recap-experimental-from-the-screencast | should | `Recap`, `ThreadIntent.DismissRecap` | `cli` `thread_recap`, `client_dismiss_recap`. |

## projects-orbs

| id | rel | Served by | Contract / fallback |
|---|---|---|---|
| projects-list-and-project-page | must | `Catalog.projects`; project page = threads (`ThreadQuery project:`) + settings summary; repo browser → web view `ProjectSettings` | `cli` `listAccessibleProjects✓`, `getProject✓`. File tree and commits are web-remote. |
| create-a-project | should | web view `AccountWebPage.CreateProject` | `createProject✓` has an unflattened schema; GitHub repo picker needs the web OAuth flow. |
| assign-a-thread-to-a-project | must | `ExecutorChoice.Orb(project)` / `.Runner(project)` | `cli` `resolveProjectByRepositoryURLs✓`, `client_spawn_executor{projectID}`. |
| project-settings-overview-tab | should | `AccountCommand.SetShipBehavior`; the rest → web view | `cli` `updateProject✓`. |
| multi-repository-projects | must | `RepoChanges` grouping, `ProjectInfo.repos` | `cli`. |
| ship-behavior-and-custom-ship-prompt-settings | should | `ShipBehavior`, `AccountCommand.SetShipBehavior` | `cli` `updateProject✓{customShipPrompt, changesWorkflow}`. |
| secrets-and-environment-variables | must | `AccountResource.Secrets` + `AccountCommand.SetSecret` (Ephemeral); in-thread secret prompt = `PluginGate.SecretInput` | `cli` `setEnvVarSecret✓`. In-thread dialog protocol is inferred. |
| project-permissions | must | Edit controls disabled on `EngineError.Unauthorized`; permission flags from `getProject✓` (inferred) | `cli`. |
| manage-projects-with-puck | should | Puck | `cli`. |
| connect-github | should | web view `AccountWebPage.Integrations` | OAuth on the web. |
| commit-pr-and-ci-previews-in-threads | must | PR and commit chips parsed from transcript; [Fix CI] = prompt send | Hover-card and CI calls are web-remote; their details are absent natively (accepted gap). |
| github-event-subscriptions-issues-prs-ci | must | Creation card in transcript; list/toggle as github-listeners | See github-listeners. |
| how-orbs-authenticate-to-git | should | Push-denied errors render from transcript | `getThreadRepositoryPushDenials` is web-remote. |
| other-git-hosts-bitbucket-gitlab-any-https | should | `ProjectInfo.gitHost` hides GitHub-only UI | `cli` `getProject✓`. |
| github-troubleshooting-states | should | `EngineError` mapping + web view link | Server error codes (unknown shapes → `Drift`). |
| start-an-orb-thread | must | `NewThreadPane` + `ExecutorChoice.Orb` | See spawn-an-orb-thread-ox. |
| orb-spawn-pacing | must | `ExecutorState.WaitingForCapacity` | From `executor_status.details` (inferred). |
| orb-lifecycle-pausewake-and-status | must | `ExecutorState.Asleep/Provisioning/Connected`, `ThreadIntent.WakeExecutor`, `RestartOrbProcesses`; metrics sheet | `cli` events, `restartOrbProcesses✓`, `getOrbSystemMetrics✓`. Reboot is web-remote → web view. |
| orb-setup-snapshots-and-setup-failures | should | `ExecutorState.SetupFailed`, `SetupLogRef` viewer, `ThreadIntent.RetryFailedSetup` (`Guarded(SetupFailed)`), `AccountCommand.ClearOrbSnapshots` | `cli` `client_spawn_executor{retryFailedSetup}`, `deleteProjectSandboxSnapshots✓`. Setup log endpoint tier unknown. |
| changes-pane-review-the-diff | must | `ChangesPane`, `DiffView` | S7 VERIFIED live git. Orb captures NOT VERIFIED → explicit wake for live git. |
| ship-push-the-changes-pane-primary-action | must | `ChangesAction.Ship/PushBranch` with confirm | Prompt send (`cli`). |
| restack | should | `ChangesAction.Restack` | Prompt send. |
| other-changes-pane-quick-actions-review-rebase-custom-buttons | should | `ChangesAction.Review/Rebase` | Built-ins native. User-defined buttons come from web-remote `getChangesPrompts`, so the app does not show them (see saved prompts). |
| files-pane-browse-open-upload | must | `FilesTab`, `Machine.files` | `cli` `client_filesystem_read_directory_v2/read_file_v2/write_file_v3` VERIFIED (S19). `range` reads rejected, so reads are whole-file, one at a time, refused over 4 MiB. Direct writes go up to `Files.maxDirectWriteBytes`; larger uploads use attachment upload plus `amp-exec-v1`. |
| terminal | must | `:feature:terminal` | S8 VERIFIED. |
| thread-attachments | must | Composer, Files attachment gallery | S12 VERIFIED. |
| share-generated-files | should | Download via `Files.read` over the socket → SAF or share sheet | `cli` S19 VERIFIED reads. `getThreadFileURLs` answered `permission-denied` on a runner thread (S19), so it is not used. |
| orb-sizes-and-costs | must | `Catalog.orbSizes` in picker with per-hour cost; `CostGlance` | Size list: static table from docs, validated by the server on spawn. `getCurrentAvailableOrbResources` is web-remote. Cost via `threadDisplayCostInfo✓`. |
| portals-tab | must | `MachineTab.Portal` (`PortalTab`, `:feature:web`); `Portals.list` | See portals. Public-access toggles are web-remote → web view `PortalPolicy`. |
| runner-worktrees-branches-per-thread-on-your-own-machine | should | `WorktreeChoice` | See runner-create-worktree. |

## Not listed

`could` and `n/a` rows are outside this table, as the brief asks. Each `could` row in the `cli` tier (for example `setThreadPortalHostname✓` custom portal hostnames, `listCustomDomains✓`) already has a home as a `ThreadIntent`, an `AccountCommand`, or an `AccountResource` key.

## Unverified paths and their fallbacks

Every path below is used by the design and not fully measured, or was measured to be unavailable. Each has a named fallback, so a failed path degrades one surface and never blocks the core loop. Rows marked "Not spiked" come from arena candidate B's table, rewritten for this design's types. Spike 2 rows (S15-S20, `spikes/protocol/RESULTS.md` "Spike 2") are measured unless the verdict says otherwise.

| Spike row | Path | Fallback in this design |
|---|---|---|
| S1 exchange not observed | Device-code token exchange and refresh | Loopback PKCE flow (`private-protocol.md:56-64`). |
| S7 HTTP captures NOT VERIFIED | `diff-captures/latest`, blob raw (orbs) | `ChangesView.Unavailable` with [Wake orb to load live changes]; live git over the socket (S7 measured) once awake. |
| S13 INCONCLUSIVE | Portal data plane | Custom Tab with the portal URL; the browser's ampcode session completes portal-auth. |
| S15 INCONCLUSIVE | Tool approvals: `tool_approval_queue` entries, `client_tool_approval_response`, `approval_required` push tag, the user-actor indicator for approvals | A queue entry that fails to decode becomes `UnsupportedGate` with [Answer on web]; the notification posts [Answer on web] + [Open]. The ledger uses a synthesized approval tag. `FleetStatus.NeedsHuman` for approvals is [INFERENCE] from the plugin-dialog indicator. |
| S18 NOT VERIFIED (rejected) | Anything but `client_append_user_msg` over `/request/rpc` | Every other command goes over the socket, a one-shot socket when no lease holds one (`BackgroundAccess.answer`, `markRead`, push upkeep). `StatelessThreadRpc` has one method. |
| S15c push NOT VERIFIED | Web Push for a pending plugin dialog | `FleetStatus.NeedsHuman` from the user-actor indicator (measured) triggers `GateFetchWorker` on the watch and poll paths. |
| Not spiked | MCP elicitation wire | `UnsupportedGate`. [Answer on web] opens `AccountWebPage.ThreadOnWeb`. |
| S19 NOT VERIFIED | `range` reads | Whole-file reads over the socket, one at a time per thread, refused over 4 MiB. Downloads write the read bytes through SAF. `getThreadFileURLs` answered `permission-denied` and is not used. |
| Not decoded | Waymote pointer messages, desktop relay service names | `DesktopTab` renders [Open on web]. |

Resolved by spike 2 and folded into the design: S16 (`Send` is `Replayable`, server dedupes by `messageId`), S17 (resume cursor is `thread.data.v`), S15b and S15c (plugin dialog wire, re-listing on reconnect, indicator), S19 (list, read, v3 write), S20 (`AgentNet` one-line JSON log format, `sync/internal/Transport.kt` `NetworkLog`).

Read from the web client, not observed, and used by the design: queued sends without `steer` (M3 gate adds one), snooze values other than `one_hour` and `off`, `plugin.ui.request_removed`, `ui.select`, dedupe by `editId`, and `amp-exec-v1` file staging (fallback: the attachment URL).
