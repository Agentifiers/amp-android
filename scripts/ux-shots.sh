#!/usr/bin/env bash
# Captures one PNG per app surface on an emulator, for the UX audit's before and after shots.
# The capture flows live in flows/ux/. Each phase changes one device setting, runs its flows, and puts the
# setting back, so the emulator ends as it started even when a flow fails or the script is interrupted.
# Usage: scripts/ux-shots.sh [--font-scale <x>] <serial> <outdir>
# --font-scale applies to every phase; a phase that sets its own font scale wins. The baseline is restored after.
set -euo pipefail

usage() {
  echo "usage: $0 [--font-scale <x>] <serial> <outdir>" >&2
  exit 2
}
font_scale=""
if [[ "${1:-}" == --font-scale ]]; then
  [[ "${2:-}" =~ ^[0-9]+(\.[0-9]+)?$ ]] || usage
  font_scale="$2"
  shift 2
fi
[[ $# -eq 2 && -n "$1" && -n "$2" ]] || usage
serial="$1"
cd "$(dirname "$0")/.."
source scripts/device-guard.sh
guard_device "$serial" "capture UX shots"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export AGENT_DEVICE_ANDROID_APP_EVENT_URL_TEMPLATE='amp-debug://event/{event}?json={payload}'
mkdir -p "$2"
outdir="$(cd "$2" && pwd)"

sh_() { adb -s "$serial" shell "$@" | tr -d '\r'; }

# agent-device types through its own keyboardless input method, so the keyboard phase selects Gboard to draw one.
gboard=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME

# phase | flows | setup command run with `adb shell` before the flows (empty for none)
phases=(
  "inner|flows/ux/inner-*.ad flows/ux/thread-*.ad|"
  "shade|flows/ux/shade.ad|"
  "keyboard|flows/ux/keyboard-*.ad|settings put secure show_ime_with_hard_keyboard 1; ime enable $gboard; ime set $gboard"
  "fontscale|flows/ux/fontscale.ad|settings put system font_scale 1.08"
  "portrait|flows/ux/portrait.ad|settings put system accelerometer_rotation 0; settings put system user_rotation 1"
  "cover|flows/ux/cover.ad|wm size 1080x2520; wm density 420"
)

settings_keys=(
  "system font_scale"
  "system user_rotation"
  "system accelerometer_rotation"
  "secure show_ime_with_hard_keyboard"
)

# Indexed in step with settings_keys, because macOS ships bash 3.2, which has no associative arrays.
baseline_settings=()
baseline_size=""
baseline_density=""
baseline_ime=""
wm_override() { sh_ wm "$1" | awk -F': ' '/^Override/ { print $2 }'; }

record_baseline() {
  local i
  for i in "${!settings_keys[@]}"; do baseline_settings[i]="$(sh_ settings get ${settings_keys[i]})"; done
  baseline_size="$(wm_override size)"
  baseline_density="$(wm_override density)"
  baseline_ime="$(sh_ settings get secure default_input_method)"
}

describe_device() {
  local key
  for key in "${settings_keys[@]}"; do printf '%s=%s ' "${key#* }" "$(sh_ settings get $key)"; done
  printf 'ime=%s wm_size=%s wm_density=%s\n' \
    "$(sh_ settings get secure default_input_method)" \
    "$(sh_ wm size | awk -F': ' '{ print $2 }' | paste -sd/ -)" \
    "$(sh_ wm density | awk -F': ' '{ print $2 }' | paste -sd/ -)"
}

restore_device() {
  local i key value
  for i in "${!settings_keys[@]}"; do
    key="${settings_keys[i]}" value="${baseline_settings[i]}"
    if [[ "$value" == null ]]; then sh_ settings delete $key >/dev/null; else sh_ settings put $key "$value"; fi
  done
  if [[ -n "$baseline_size" ]]; then sh_ wm size "$baseline_size"; else sh_ wm size reset; fi
  if [[ -n "$baseline_density" ]]; then sh_ wm density "$baseline_density"; else sh_ wm density reset; fi
  if [[ "$baseline_ime" != null ]]; then sh_ ime set "$baseline_ime" >/dev/null; fi
  # A flow that fails with the notification shade open would leave it over every later capture.
  sh_ cmd statusbar collapse >/dev/null
}

# A cold-booted read-only AVD often raises "System UI isn't responding" a minute after boot, and the dialog
# covers every capture. Close system dialogs until the focus has stayed off an ANR dialog for 10 seconds.
settle_device() {
  local quiet=0 deadline=$((SECONDS + 180))
  until [[ "$(sh_ getprop sys.boot_completed)" == 1 ]]; do sleep 2; done
  while (( quiet < 5 )); do
    if (( SECONDS >= deadline )); then
      echo "an ANR dialog kept returning on $serial for 180s" >&2
      exit 1
    fi
    if sh_ dumpsys window | grep -q 'mCurrentFocus=.*Application Not Responding'; then
      sh_ am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null
      quiet=0
    else
      quiet=$((quiet + 1))
    fi
    sleep 2
  done
}

expected_shots() {
  grep -ho 'screenshot \${UX_OUT}/[a-z0-9-]*\.png' flows/ux/*.ad | sed 's|.*/||' | sort
}

record_baseline
echo "device before: $(describe_device)"
trap 'restore_device; echo "device after:  $(describe_device)"' EXIT
settle_device
# The app asks for notification permission on first launch; that ask would cover every capture, as in flows.sh.
sh_ pm grant com.agentifiers.amp android.permission.POST_NOTIFICATIONS

while read -r name; do rm -f "$outdir/$name"; done < <(expected_shots)

failed=()
for phase in "${phases[@]}"; do
  IFS='|' read -r name glob setup <<<"$phase"
  echo "== $name"
  if [[ -n "$font_scale" ]]; then setup="settings put system font_scale $font_scale${setup:+; $setup}"; fi
  if [[ -n "$setup" ]]; then sh_ "$setup"; sleep 3; fi
  # shellcheck disable=SC2086 # the glob expands here, on purpose
  if ! agent-device test $glob --platform android --serial "$serial" -e "UX_OUT=$outdir" --retries 1 \
    --reporter default --artifacts-dir "build/ux-shots/$name"; then
    failed+=("$name")
  fi
  restore_device
  sleep 3
done

missing=()
while read -r name; do [[ -f "$outdir/$name" ]] || missing+=("$name"); done < <(expected_shots)
echo "$(expected_shots | wc -l | tr -d ' ') surfaces expected, ${#missing[@]} missing, in $outdir"
if (( ${#missing[@]} )); then printf 'missing: %s\n' "${missing[@]}" >&2; fi
if (( ${#failed[@]} || ${#missing[@]} )); then
  echo "failed phases: ${failed[*]:-none}" >&2
  exit 1
fi
