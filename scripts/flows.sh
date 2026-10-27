#!/usr/bin/env bash
# Runs the agent-device flows on one explicitly named device. See flows/README.md.
# Usage: scripts/flows.sh <serial> [glob-or-path...]
# AMP_SCENARIO picks the :fake-amp scenario (default fleet-basic).
set -euo pipefail

if [[ $# -lt 1 || -z "$1" ]]; then
  echo "usage: $0 <serial> [glob-or-path...]" >&2
  exit 2
fi
serial="$1"
source "$(dirname "$0")/device-guard.sh"
guard_device "$serial" "run flows"

cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export AGENT_DEVICE_ANDROID_APP_EVENT_URL_TEMPLATE='amp-debug://event/{event}?json={payload}'

focused_window() {
  adb -s "$serial" shell dumpsys window 2>/dev/null | awk '/mCurrentFocus=/ { print; exit }'
}

# A cold-booted read-only AVD often raises "System UI isn't responding" a minute after boot, and the dialog
# covers every flow. Close system dialogs until the focus has stayed off an ANR dialog for 10 seconds.
settle_device() {
  adb -s "$serial" wait-for-device
  until [[ "$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do sleep 2; done
  local quiet=0 deadline=$((SECONDS + 180))
  while (( quiet < 5 )); do
    if (( SECONDS >= deadline )); then
      echo "an ANR dialog kept returning on $serial for 180s: $(focused_window)" >&2
      exit 1
    fi
    if focused_window | grep -q "Application Not Responding"; then
      echo "closing an ANR dialog on $serial" >&2
      adb -s "$serial" shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null
      quiet=0
    else
      quiet=$((quiet + 1))
    fi
    sleep 2
  done
  # A picker a failed run left open stays in the app's task, so a relaunch would bring it back over the app.
  adb -s "$serial" shell am force-stop com.google.android.photopicker
  adb -s "$serial" shell am force-stop com.google.android.documentsui
}

settle_device

# The flows' swipes and on-screen waits are written for the default font scale.
font_scale="$(adb -s "$serial" shell settings get system font_scale | tr -d '\r')"
if [[ "$font_scale" != null ]] && ! awk -v s="$font_scale" 'BEGIN { exit !(s == 1) }'; then
  echo "$serial has font scale $font_scale; the flows need 1.0. Reset it with:" >&2
  echo "  adb -s $serial shell settings put system font_scale 1.0" >&2
  exit 2
fi

# A tap lands where one snapshot put its target, and a sheet or menu still animating in moves the target first: in a full
# run e2e-day's tap on Photos opened Files (U27). The suite runs with animations off and puts the three scales back after.
scales=(window_animation_scale transition_animation_scale animator_duration_scale)
saved_scales=()
for key in "${scales[@]}"; do
  saved_scales+=("$(adb -s "$serial" shell settings get global "$key" | tr -d '\r')")
  adb -s "$serial" shell settings put global "$key" 0
done
restore_scales() {
  local i
  for i in "${!scales[@]}"; do
    if [[ "${saved_scales[i]}" == null ]]; then
      adb -s "$serial" shell settings delete global "${scales[i]}" >/dev/null
    else
      adb -s "$serial" shell settings put global "${scales[i]}" "${saved_scales[i]}"
    fi
  done
}
trap restore_scales EXIT

scripts/seed-fixtures.sh "$serial" >/dev/null
# The app asks for notifications once after sign-in, and that dialog would cover every flow. Flows run as a user who
# already allowed them; flows/notify-permission*.ad reset the permission to drive the ask itself, and grant it back.
adb -s "$serial" shell pm grant com.agentifiers.amp android.permission.POST_NOTIFICATIONS

env_args=()
if [[ -n "${AMP_SCENARIO:-}" ]]; then env_args+=(-e "AMP_SCENARIO=$AMP_SCENARIO"); fi

paths=("${@:2}")
if (( ! ${#paths[@]} )); then
  # The notify flows pull the notification shade down, and one that fails leaves it over the app. They run last, so a
  # failure there can cover no other flow, and the shade is collapsed when the suite ends, pass or fail.
  for flow in flows/*.ad; do [[ "$flow" == flows/notify-* ]] || paths+=("$flow"); done
  paths+=(flows/notify-*.ad)
fi

status=0
agent-device test "${paths[@]}" --platform android --serial "$serial" \
  ${env_args[@]+"${env_args[@]}"} \
  --reporter default --reporter junit:build/flows/junit.xml --artifacts-dir build/flows || status=$?
adb -s "$serial" shell cmd statusbar collapse
exit "$status"
