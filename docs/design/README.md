# Amp for Android: design

Amp for Android is a native client for Amp threads, built to use the full inner display of the Samsung Galaxy Z TriFold and to work on every other Android window size. The agent runs in an orb, on a runner, or in a CLI session. The app shows that work and lets you direct it.

## Read in this order

1. `product.md` says who the app is for, the five core loops, and what is in and out of scope.
2. `layout.md` defines panels, panes, arrangements, and every form factor from the 411dp cover screen to a DeX monitor.
3. `screens.md` specifies every surface, every state, and the keyboard shortcuts.
4. `architecture.md` explains the module structure and the decisions behind it. `sketch/` holds the Kotlin type sketches. Where the two disagree, the docs win and the sketch is fixed to match.
5. `transport.md` is the reference for the private protocol the app speaks.
6. `git-and-diffs.md` covers the Changes pane, the diff engine, and Ship.
7. `terminal.md` covers the Terminal pane and the VT emulator.
8. `notifications.md` covers push, watching, and answering from a notification.
9. `push-relay.md` specifies the Cloudflare Worker that turns Amp's Web Push into Firebase Cloud Messaging for the app.
10. `auth-and-security.md` covers sign-in, accounts, credentials, and the threat model.
11. `verification.md` sets the rules that make every flow drivable with agent-device.
12. `roadmap.md` is the build plan as a stack of verifiable milestones.

## Evidence behind the design

| Evidence | Where |
|---|---|
| The private protocol, mapped from the CLI binary and web bundles | `docs/protocol/private-protocol.md` |
| Two live protocol spikes against ampcode.com, 20 capability rows with verdicts. Rows S1 to S20 are the only source of verdicts in these docs. | `spikes/protocol/RESULTS.md` |
| The TriFold-sized AVD that CI and every flow run on | `scripts/create-trifold-avd.sh` |
| Every `must` and `should` capability mapped to a module and type | `sketch/COVERAGE.md` |
