# Changelog

## Added (unreleased)

- A thread that an automation runs now ends with one quiet line, as on ampcode.com: "Next run in 5m", or "Automation paused" when it is stopped, with "· Last run 2h ago" once it has run. The line counts down on the minute and shows while the agent is idle. Tap it to pause or resume the automation, or to delete it after a confirmation.

## 0.1.0

The first public release. The app reads and drives Amp threads over Amp's private protocol: the thread list, the transcript, gates and approvals, the Changes, Files, Terminal, and Desktop tabs, Puck, settings, and notifications through FCM, UnifiedPush, or polling. It lays out panes for the Galaxy Z TriFold's inner display and every other Android window size. `docs/design/roadmap.md` lists what each milestone covers and its known gaps.
