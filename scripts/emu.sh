#!/usr/bin/env bash
# Runs one TriFold_Inner_API36 emulator per slot so parallel units never share a device.
# Slot N (1..13) listens on port 5554+2N, so its serial is emulator-<port>.
# Usage: scripts/emu.sh start|stop|serial <slot>
set -euo pipefail

usage() {
  echo "usage: $0 start|stop|serial <slot 1..13>" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage
command="$1"
slot="$2"
[[ "$slot" =~ ^[1-9][0-9]?$ ]] && (( slot <= 13 )) || usage

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
unset ANDROID_SDK_ROOT
adb="$ANDROID_HOME/platform-tools/adb"
avd=TriFold_Inner_API36
port=$((5554 + 2 * slot))
serial="emulator-$port"
log="${TMPDIR:-/tmp}/amp-emu-$slot.log"
boot_timeout=300
stop_timeout=60

adb_state() {
  "$adb" devices | awk -v s="$serial" '$1 == s { print $2 }'
}

wait_for_boot() {
  local pid="$1" deadline=$((SECONDS + boot_timeout))
  # A bounded poll instead of a bare `adb wait-for-device`, which hangs forever if the emulator dies during launch.
  until [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
    if [[ -n "$pid" ]] && ! kill -0 "$pid" 2>/dev/null; then
      echo "emulator for slot $slot exited before boot; log: $log" >&2
      tail -n 20 "$log" >&2
      exit 1
    fi
    if (( SECONDS >= deadline )); then
      echo "$serial did not finish booting within ${boot_timeout}s; log: $log" >&2
      exit 1
    fi
    sleep 2
  done
}

start() {
  local pid=""
  if [[ -z "$(adb_state)" ]]; then
    nohup "$ANDROID_HOME/emulator/emulator" -avd "$avd" -read-only -no-window -no-audio \
      -no-snapshot-save -port "$port" </dev/null >"$log" 2>&1 &
    pid=$!
  fi
  wait_for_boot "$pid"
  echo "$serial"
}

stop() {
  [[ -n "$(adb_state)" ]] || return 0
  "$adb" -s "$serial" emu kill >/dev/null
  local deadline=$((SECONDS + stop_timeout))
  while [[ -n "$(adb_state)" ]]; do
    if (( SECONDS >= deadline )); then
      echo "$serial still listed by adb after ${stop_timeout}s" >&2
      exit 1
    fi
    sleep 1
  done
}

case "$command" in
  start) start ;;
  stop) stop ;;
  serial) echo "$serial" ;;
  *) usage ;;
esac
