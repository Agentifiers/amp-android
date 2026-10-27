# Flows

Each file here is an agent-device `.ad` script that drives the debug app on an emulator and asserts on tagged elements. `docs/design/verification.md` explains why the app is built to be driven this way.

## Run the flows

Start an emulator for your slot, then pass its serial to `scripts/flows.sh`:

```sh
serial=$(scripts/emu.sh start 1)
scripts/flows.sh "$serial"
scripts/emu.sh stop 1
```

`scripts/flows.sh <serial> [glob]` runs `flows/*.ad` unless you give a glob. It refuses to run without a serial, and it refuses any device listed in `AMP_PROTECTED_SERIALS` (`scripts/device-guard.sh`). It writes `build/flows/junit.xml` and one artifacts directory per flow attempt under `build/flows/`.

`scripts/emu.sh start <slot>` boots `TriFold_Inner_API36` read-only on port `5554 + 2 * slot` and prints the serial, for example `emulator-5556` for slot 1. Running it again on a booted slot prints the same serial and starts nothing.

## Write a flow

- Put one flow in one file, named `<area>-<what>.ad`, for example `slice1-sidebar.ad`.
- Select elements by `id=<testTag>` only. Text and roles change with copy and layout. Tags do not.
- Start with `open com.agentifiers.amp --relaunch`, then pick a server and sign in with app events, not the UI. Flows run against `:fake-amp`: `trigger-app-event use-server {"scenario":"${AMP_SCENARIO:-fleet-basic}"}` starts a scenario inside the app, and `trigger-app-event sign-in-token {"token":"fake-amp-accepts-any-bearer"}` signs in, because the fake accepts any bearer.
- Never put a real token in a flow or pass one through agent-device. agent-device writes event URLs to its own request logs. Live read-only checks live in `flows/live/`: sign in first with `scripts/sign-in-live.sh <serial>`, which sends the events with adb and prints no secret, then run `scripts/flows.sh <serial> 'flows/live/*.ad'`.
- Save screenshots under `${AD_ARTIFACTS:-build/flows}/`. During `agent-device test`, `AD_ARTIFACTS` is the attempt's artifacts directory. A bare relative path writes into the directory you ran the command from.
- To type into system UI, such as a notification's inline reply, open with `--no-test-ime`. agent-device's test IME commits text into whatever editor the IME is bound to, and SystemUI can focus its field before it binds the IME, so the text goes to the app's window. With the real keyboard, `type` sends key events to the focused window (`flows/notify-inject.ad`).
- Do not wait out an animation. `scripts/flows.sh` sets the device's three animation scales to 0 for the run and puts them back after, because a tap lands where one snapshot put its target, and a sheet or menu still animating in moves the target first. A run of `agent-device test` without `flows.sh` needs `adb shell settings put global animator_duration_scale 0` and the same for `window_animation_scale` and `transition_animation_scale`.
- End with `close`.

## Know the language limits

- `.ad` has no conditionals, so fake and live flows are separate files.
- A JSON payload must contain no spaces, or it must be one JSON-quoted string. Each line splits on whitespace, and a token that starts with `"` is parsed as a JSON string.
- An `id=` selector matches the whole id, ignoring case. To assert that some element has an id with a given prefix, use `find id <prefix> exists`. With the `id` locator, `find` matches any id that contains the query and fails when nothing matches.
