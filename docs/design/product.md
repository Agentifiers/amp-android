# Product

Amp for Android is a client for Amp threads. The agent never runs on the phone. It runs in an orb, on a runner, or in a CLI session, and the app shows that work and lets you direct it. This matches the official macOS and iOS apps: "Nothing else needs to be installed, because the agent runs in an orb and not on your device" (Amp's docs).

## Who it is for

The first user is a developer with a Samsung Galaxy Z TriFold who runs several agents at once, mostly in orbs, and supervises them away from the desk. Amp's own team works this way. One orb per bug, then triage, read the diff or open the portal, reply, and Ship (Amp's news, using-amp section).

The second user is anyone on an Android phone, tablet, or foldable. The layout scales down to one pane on a 411dp cover screen and up to four columns on a DeX monitor (`layout.md`).

## The core loops

Every feature serves one of these loops. A feature that serves none of them waits.

1. **Triage.** Open the app and see which threads need you: approvals and questions first, then threads that stopped with an error, then finished threads, then threads that are still working. Act on each one without losing your place in the list.
2. **Review and ship.** Read what the agent did, read the diff in the Changes pane, ask for changes on a hunk, and press **Ship** when it is right.
3. **Start work.** Start a thread from the phone: pick the executor (a new orb, a runner, or a shared runner), the project, the directory or a new worktree, and the mode. Describe the task by voice or text, and attach a screenshot, a screen recording, or a file.
4. **Steer.** While the agent works, send a message that steers it at the next step, queue one for after the turn, or interrupt.
5. **Get hands-on.** Open the terminal, browse and edit files, check a portal, or drive the desktop, next to the transcript.

## Scope

The design started from 263 capabilities read from Amp's docs, news, and web app. Each was rated for mobile and TriFold relevance: 118 must, 80 should, 40 could, and 20 not applicable, with 5 unrated. `sketch/COVERAGE.md` maps every `must` and `should` row to a module and type.

### In the first release

- Sign in with the same account as ampcode.com, up to five accounts, and a custom server URL for workspaces with a required domain.
- Sidebar, Activity, search with Amp's query syntax, and thread organization: pin, color, labels, rename, snooze, archive, delete, mark unread.
- The full thread view: transcript with every block type, tool cards, thinking, approvals, plugin prompts, queued messages, and the composer with steer, queue, interrupt, mode picker, executor picker, mentions, attachments, and dictation.
- Changes with intelligent file order, the diff, per-hunk change requests, staging, Ship, Review, Restack, Fix CI, and pull request status.
- Files, Terminal, and Portals.
- Notifications with reply and approve actions (`notifications.md`).
- Share and visibility, multiplayer on and off, and the thread's cost and orb status.
- Thread and pane windows, drag and drop, keyboard shortcuts, and the TriFold arrangements.

### In a later release

- Desktop, because its pointer and keyboard protocol is not decoded yet (`docs/protocol/private-protocol.md:244-253`).
- Space calls through LiveKit, because the token endpoint is `web-plain` tier.
- Puck voice calls as a system call. Puck chat is in the first release as a pane.
- Widgets, Quick Settings tiles, and app shortcuts beyond **New Thread** and **Dictate to Puck**.

### Shown in a web view, not rebuilt

Account, billing, workspace administration, model routing, allowed models, entitlements, MCP registry, and enterprise settings. These run on SvelteKit remote functions whose ids change with each web deploy, so native code cannot call them safely (`docs/protocol/private-protocol.md:329`). They are also rare on a phone.

### Not built

- Features Amp removed: Fork, the handoff command, TODOs, the TUI sidebar, public threads, clickable diagrams, the review panel, and the editor Agents Panel (Amp's news, Timeline).
- Anything that runs the agent or its tools on the phone: local MCP servers, plugins, skills execution, and IDE connection. Plugins run on the executor. The app only renders what they ask (Amp's docs).
- `amp sync` to a local checkout. A phone has no checkout to sync into.
- `amp clone`, which clones an Amp-hosted repository into a local checkout (Amp's docs). A phone has no checkout, as with `amp sync`.
- Execute mode, `amp -x`. It is a CLI process that runs one prompt, prints the final message, and exits (Amp's docs). The app starts and watches threads in the composer and the transcript instead.
- Streaming JSON output and input, `--stream-json` and multi-turn messages over stdin. Both are the standard input and output formats of a local CLI process (Amp's docs). The app reads the same events from the thread actor socket.
- The SDK, `@ampcode/sdk` and `amp-sdk`. It runs the CLI as a subprocess under Node 18 or Python 3.9 (Amp's docs), and Android hosts neither.
- TUI as runner, the setting that lets a desktop TUI accept threads started elsewhere (Amp's docs). Android runs no TUI. A desktop TUI with the setting on is a target the executor picker lists, as `docs/design/sketch/COVERAGE.md` row `tui-as-runner` says.
- CLI updates and version checks, `amp update` and `amp.updates.mode` (Amp's docs). They update the CLI binary. The app updates through Play.
- Keymap customization, `amp.keymap` (Amp's docs). It rebinds TUI keys. The app's shortcuts are fixed, and `screens.md` lists them in one table.

## Product principles

- **The thread is the unit.** Every screen is a view of one or more threads. There is no separate "projects app" or "terminal app".
- **Needs-you beats everything.** An approval or a plugin prompt blocks an agent until someone answers. It goes to the top of the sidebar, into a high-importance notification, and into a banner in any window showing another thread.
- **Wake an orb only for a shown thread.** Opening a thread whose orb is asleep wakes it, as the web's thread page does, and the machine pane says "Waking up the orb" until it connects (`screens.md`, "Wake an orb only for a shown thread"). Only a thread shown in a started window can wake an orb. Background refresh, notifications, and quick replies read thread state from Amp's servers and never wake one.
- **Say what costs money.** The composer shows the mode, the executor, and the orb size before you send. The thread menu shows the thread's cost so far.
- **Same words as Amp.** Labels match ampcode.com: **Ship**, **Review**, **Sync**, **Inactive Last 72h**, **Steer**, **Queue**. A user moving between the web and the app never has to translate.
