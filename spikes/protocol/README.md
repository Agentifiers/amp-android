# Amp private protocol spike

These Bun scripts exercise the private protocol the Amp CLI speaks, one capability group per script. Each script writes redacted frames, in both directions, to `captures/<script>.jsonl`. Verdicts and evidence are in [RESULTS.md](RESULTS.md).

The prototype is throwaway. It exists to settle what the Kotlin client can rely on.

## Before you run

- Install [Bun](https://bun.sh) and the Amp CLI, and sign the CLI in to the account you want to probe.
- Export an Amp API key as `AMP_API_KEY`. The scripts send it as the bearer token. `AMP_URL` names the server and defaults to `https://ampcode.com`.
- The scripts read Rivet's publishable token from the Amp CLI binary, `~/.amp/bin/amp` by default. Set `AMP_CLI_BINARY` when the CLI lives elsewhere. Neither the key nor the token is printed or written.
- S4 to S13 need a runner. Create a scratch git repository with a `calc.py`, start a runner there with the Amp CLI, and leave it running:

	```
	cd /tmp/amp-scratch && amp --no-tui --remote-control-terminal --runner-id android-spike
	```

- `s04-live-thread.ts` creates the spike thread and stores its ID in `captures/state.json`. Later scripts read it from there. `s10-thread-ops.ts` archives the thread last and unarchives it first, so it is safe to rerun.
- Scripts that start a turn spend credits: `s04`, `s05` (two turns), `s06`, `s11`, and `s12`. The eight turns of the recorded run cost $0.052 in total in agent mode `low`.
- Spike 2 (S15 to S19) needs a runner whose settings make the shell tool ask for approval, and a project plugin for S15b and S15c. Create the settings with `amp --settings-file /tmp/amp-spike2-settings.json permissions add ask shell_command` (repeat for `Bash` and `async_shell_command`). Copy `plugins/spike-dialog.ts` to `/tmp/amp-scratch/.amp/plugins/`. Then start the runner with those settings:

	```
	cd /tmp/amp-scratch && amp --settings-file /tmp/amp-spike2-settings.json --no-tui --remote-control-terminal --runner-id android-spike
	```

- `s15-approvals.ts` creates its own labelled thread and stores it as `approvalThread` in `captures/state.json`. S15b to S19 default to that thread, or take a thread ID as the first argument. `teardown2.ts` archives the thread, and archived threads refuse thread-actor credentials (S10). Before rerunning against it, unarchive it with internal `archiveThread{thread, archived:false}`. The spike 2 turns all failed before reaching a model (see RESULTS.md), so spike 2 spent nothing.
- `s11b-relay.ts` and `s23-push-viewers.ts` need a deployed relay from `relay/` in `RELAY_URL` and a Cloudflare API token in `CLOUDFLARE_API_TOKEN`, so `wrangler tail` can read the relay's logs.

## Scripts

Run each from this directory with `bun <script>`.

| Script | Capability | How to rerun |
|---|---|---|
| `s01-auth.ts` | S1 oauth-config, device flow start, one poll | `bun s01-auth.ts` |
| `s02-threads.ts` | S2 `listThreads` and userActor `getRecentThreads` | `bun s02-threads.ts` |
| `s03-runner.ts` | S3 runner in userActor and internal `listRunners` | Start the runner, then `bun s03-runner.ts` |
| `s04-live-thread.ts` | S4 create thread, spawn runner executor, stream one turn | `bun s04-live-thread.ts` (new thread) or `bun s04-live-thread.ts <threadId>` |
| `s05-steer-interrupt.ts` | S5 steer during a turn, then `client_cancel` | `bun s05-steer-interrupt.ts` |
| `s06-rpc.ts` | S6 one message through `POST …/threadActor/request/rpc` | `bun s06-rpc.ts` |
| `s07-changes.ts` | S7 `client_git_command`, `client_git_diff_snapshot`, `diff-captures` | `bun s07-changes.ts` |
| `s08-terminal.ts` | S8 `amp-terminal-v1` over the executor relay | `bun s08-terminal.ts` |
| `s09-transcript.ts` | S9 transcript paging against `client_resume{version:0}` replay | `bun s09-transcript.ts` |
| `s10-thread-ops.ts` | S10 label, pin, rename, unread, snooze, archive | `bun s10-thread-ops.ts` |
| `s11-push.ts` | S11 Web Push to an ntfy `up…` topic, with decryption | `bun s11-push.ts` |
| `s12-attachment.ts` | S12 resumable upload and an image content block | `bun s12-attachment.ts` |
| `s13-portal.ts` | S13 `resolveThreadPortalURL`, `mintPortalLoginURL`, login hops | Start `python3 -m http.server 8765 --bind 127.0.0.1` in `/tmp/amp-scratch`, then `bun s13-portal.ts` |
| `s14-user-actor.ts` | S14 userActor `/connect` socket in JSON encoding | `bun s14-user-actor.ts <seconds>` while another script runs a turn |
| `probe-json-int.ts` | Rivet JSON integer quirk behind the S2 note | `bun probe-json-int.ts` |
| `s15-approvals.ts` | S15 shell-tool approval: queue event, user status, push, answers over socket and `/rpc`, deny | Spike 2 runner, then `bun s15-approvals.ts` (needs working inference) |
| `s15a-routing-blocker.ts` | Evidence for the workspace model routing failure that blocked S15 | `bun s15a-routing-blocker.ts` |
| `s15b-plugin-dialog.ts` | S15b plugin `command.execute` and `plugin.ui.respond` for confirm and input | Spike 2 runner with the plugin, then `bun s15b-plugin-dialog.ts` |
| `s15c-plugin-dialog-detached.ts` | S15c pending plugin dialog with no observer: push, status, snapshot on reconnect | Same as S15b |
| `s16-dedupe.ts` | S16 same `messageId` over socket, `/rpc`, socket | `bun s16-dedupe.ts` |
| `s17-resume-cursor.ts` | S17 `client_resume{version}` at 0 and at `thread.data.v` | `bun s17-resume-cursor.ts [threadId…]` |
| `s18-rpc-breadth.ts` | S18 `client_cancel`, `client_set_thread_title`, `client_upsert_notification_subscription` over `/rpc` | `bun s18-rpc-breadth.ts` |
| `s18b-rpc-allowlist.ts` | S18 which methods `/request/rpc` accepts | `bun s18b-rpc-allowlist.ts` |
| `s19-files.ts` | S19 `client_filesystem_*` v1, v2, v3 and `getThreadFileURLs` | Spike 2 runner, then `bun s19-files.ts` |
| `s20-changes-params.ts` | S20b `client_changes_file_order` with and without `includeUntracked` on a real thread, read-only | Needs an executor already on the thread; it never wakes one. `bun s20-changes-params.ts <thread title>`, or `--trim` to retrim the capture |
| `s21-watch.ts`, `s21-orb-sleep.ts` | S21 a sleeping orb's frames, read-only, then the web's one wake | `bun s21-watch.ts [minutes] [preferred thread title…]` polls `getRecentThreads` every 60 s with no socket until an orb thread reports `executorConnected:false`, records it with `s21-orb-sleep.ts <threadId>`, then sends one wake with `s21-orb-sleep.ts <threadId> --wake`. The wake bills the orb |
| `s22-directive-shape.ts` | S22 the structure of a thread's `:::` directives, read-only, no capture | `bun s22-directive-shape.ts <thread title>` |
| `s24-orb-providers.ts` | S24 where a workspace's self-hosted orb providers can be read, read-only, each source once | `bun s24-orb-providers.ts [orb thread id]` writes the answers to `/tmp/u55-probe` only; `bun s24-orb-providers.ts --scrub` writes the capture with the workspace id and any provider names or ids replaced |
| `s25-thread-schedule.ts` | S25 where a thread's scheduled run can be read, read-only, each source once | `bun s25-thread-schedule.ts` writes the answers to `/tmp/u57-probe` only; `bun s25-thread-schedule.ts --scrub` writes the capture with every id, title, schedule, and prompt replaced and every time moved by one offset |
| `teardown2.ts` | Spike 2 cost check, archive, `listRunners` | Stop the runner, then `bun teardown2.ts` |

`lib.ts` holds the shared pieces: capture and redaction, internal RPC, gateway URLs, Rivet subprotocols, `ThreadSocket` (JSON-RPC requests, notifications, bare replay frames, binary relay frames), and the relay frame codec. `webpush.ts` subscribes an ntfy `up…` topic and decrypts Web Push messages. `s06-rpc.ts` exports `rpcCall` for any method over `/request/rpc`, and `s14-user-actor.ts` exports `connectUserActor`.

## Check the captures for secrets

```
cd captures && grep -c 'eyJ' * ; grep -c 'sgamp_' *
```

Both counts must be zero for every file.
