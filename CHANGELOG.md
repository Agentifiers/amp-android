# Changelog

## Added (unreleased)

- A thread that an automation runs now ends with one quiet line, as on ampcode.com: "Next run in 5m", or "Automation paused" when it is stopped, with "· Last run 2h ago" once it has run. The line counts down on the minute and shows while the agent is idle. Tap it to pause or resume the automation, or to delete it after a confirmation.
- `amp.releaseApplicationId` in `local.properties` builds the release under another package name, so a device that already runs the app under that name updates in place and keeps its data, permissions, and push setup. The launcher shortcuts and the Firebase client follow it.
- `scripts/check-private.sh` refuses a commit that adds a string or a path listed in `.git/info/private-patterns`, and runs `gitleaks` on it. `git config core.hooksPath scripts/hooks` runs it on every commit.

## Fixes (unreleased)

- The 15-minute background check now replaces its schedule at each start, so an update that renames its worker class keeps it running.

## 0.1.0

The first public release. The app reads and drives Amp threads over Amp's private protocol: the thread list, the transcript, gates and approvals, the Changes, Files, Terminal, and Desktop tabs, Puck, settings, and notifications through FCM, UnifiedPush, or polling. It lays out panes for the Galaxy Z TriFold's inner display and every other Android window size. `docs/design/roadmap.md` lists what each milestone covers and its known gaps.
