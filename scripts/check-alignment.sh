#!/usr/bin/env bash
# Checks that the panes line up on the inner display. For each arrangement it reads a uiautomator dump and fails unless
# every visible pane header (tagged pane-header-<kind>) has the same top and bottom within 1 px, and the window bar
# (tagged window-bar) is 40dp tall. Opens the :fake-amp "changes" thread first so the thread and machine headers show.
# Then it opens Activity, New Thread, and Settings in Triage, and in every dump a single-line header title (Activity,
# New thread, Settings and its page, Running) must center on its header's icon buttons, or on the header row when it
# has none, and those titles must share one center line across panes, each within 1dp. Last, on the "fleet-organize"
# Sidebar, every section label with controls (Inactive and its Archive All, Snoozed, Archived) must center on them.
# Usage: scripts/check-alignment.sh <serial>
set -euo pipefail

if [[ $# -ne 1 || -z "$1" ]]; then
  echo "usage: $0 <serial>" >&2
  exit 2
fi
serial="$1"
cd "$(dirname "$0")/.."
source scripts/device-guard.sh
guard_device "$serial" "check pane alignment"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
app=com.agentifiers.amp
arrangements=(Triage Review Terminal Read Puck)

adb_() { adb -s "$serial" shell "$@"; }
event() {
  local url
  url="amp-debug://event/$1?json=$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$2")"
  adb_ "am start -W -a android.intent.action.VIEW -d '$url'" >/dev/null
  sleep 1
}

density="$(adb_ wm density | awk -F': ' '/Override/ { o = $2 } /Physical/ { p = $2 } END { print (o ? o : p) }' | tr -d '\r')"
bar_px=$((40 * density / 160))

dump="$(mktemp)"
trap 'rm -f "$dump"' EXIT
take_dump() {
  adb_ uiautomator dump /sdcard/amp-alignment.xml >/dev/null
  adb -s "$serial" exec-out cat /sdcard/amp-alignment.xml >"$dump"
}

# Taps the center of the node whose resource-id is $1, then waits for a node with resource-id $2.
tap() {
  local xy
  take_dump
  xy="$(python3 - "$1" "$dump" <<'PY'
import re, sys, xml.etree.ElementTree as ET
for node in ET.parse(sys.argv[2]).iter('node'):
    if node.get('resource-id') == sys.argv[1]:
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)"
  [[ -n "$xy" ]] || { echo "no node $1 to tap" >&2; return 1; }
  adb_ input tap $xy
  local deadline=$((SECONDS + 15))
  until take_dump && grep -q "resource-id=\"$2\"" "$dump"; do
    (( SECONDS < deadline )) || { echo "$2 did not show after tapping $1" >&2; return 1; }
    sleep 1
  done
  sleep 1
}

failed=0
check() {
  take_dump
  python3 - "$1" "$bar_px" "$density" "$dump" <<'PY' || failed=1
import re, sys, xml.etree.ElementTree as ET
label, bar_px, density, path = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
px = density / 160
touch = 48 * px
tolerance = max(2, round(px))
single_line = {'activity', 'newthread', 'settings', 'settings-page', 'running'}

def bounds(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    return x1, y1, x2, y2

def center(b):
    return (b[1] + b[3]) / 2

nodes, titles = {}, {}
problems = []
for node in ET.parse(path).iter('node'):
    rid = node.get('resource-id', '')
    if not (rid.startswith('pane-header-') or rid == 'window-bar'):
        continue
    b = bounds(node)
    if b[2] <= b[0] or b[3] <= b[1]:
        continue
    nodes.setdefault(rid, (b[1], b[3]))
    kind = rid.removeprefix('pane-header-')
    if kind not in single_line or kind in titles:
        continue
    inside = list(node.iter('node'))[1:]
    text = next((n for n in inside if n.get('text')), None)
    if text is None:
        problems.append(f'{rid} has no title text')
        continue
    title = center(bounds(text))
    icons = [center(bounds(n)) for n in inside if n.get('clickable') == 'true' and bounds(n)[3] - bounds(n)[1] >= touch - 1]
    targets = icons or [center(b)]
    titles[kind] = title
    worst = max(abs(title - t) for t in targets)
    print(f'{label:<11} {kind:<14} title center {title:7.1f} vs {"icons" if icons else "row"} {", ".join(f"{t:.1f}" for t in targets)} (off {worst / px:.1f}dp)')
    if worst > tolerance:
        problems.append(f'{kind} title is {worst / px:.1f}dp off its {"icons" if icons else "header row"}')

for rid, (top, bottom) in sorted(nodes.items()):
    print(f'{label:<11} {rid:<24} {top:>6} {bottom:>7} {bottom - top:>7}')
bar = nodes.pop('window-bar', None)
if bar is None:
    problems.append('no window-bar')
elif abs((bar[1] - bar[0]) - bar_px) > 0:
    problems.append(f'window bar is {bar[1] - bar[0]} px, not {bar_px}')
if len(nodes) < 2:
    problems.append(f'only {len(nodes)} pane header(s) visible')
tops = [t for t, _ in nodes.values()]
bottoms = [b for _, b in nodes.values()]
if tops and max(tops) - min(tops) > 1:
    problems.append(f'header tops differ by {max(tops) - min(tops)} px')
if bottoms and max(bottoms) - min(bottoms) > 1:
    problems.append(f'header bottoms differ by {max(bottoms) - min(bottoms)} px')
if len(titles) > 1 and max(titles.values()) - min(titles.values()) > tolerance:
    problems.append(f'single-line titles differ by {(max(titles.values()) - min(titles.values())) / px:.1f}dp across panes')
for p in problems:
    print(f'{label:<11} FAIL {p}')
sys.exit(1 if problems else 0)
PY
}

adb_ am force-stop "$app"
adb_ monkey -p "$app" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3
event use-server '{"scenario":"changes"}'
event sign-in-token '{"token":"fake-amp-accepts-any-bearer"}'
event geometry-override '{"widthDp":1080,"creasesDp":[335,717]}'
sleep 3
event open-thread '{"threadId":"T-01a16702-3202-73fb-8f52-e4498ccd3721"}'
sleep 3

printf '%-11s %-24s %6s %7s %7s\n' state node top bottom height
for arrangement in "${arrangements[@]}"; do
  event set-arrangement "{\"name\":\"$arrangement\"}"
  sleep 2
  check "$arrangement"
done

event set-arrangement '{"name":"Triage"}'
sleep 2
tap nav-activity pane-header-activity && check activity || failed=1
tap activity-back pane-header-sidebar || failed=1
tap new-thread pane-header-newthread && check newthread || failed=1
tap account-switcher account-settings && tap account-settings pane-header-settings-page && check settings || failed=1

# Checks each section label in the dump against its own controls.
check_sections() {
  python3 - "$density" "$dump" <<'PY'
import re, sys, xml.etree.ElementTree as ET
density, path = int(sys.argv[1]), sys.argv[2]
px = density / 160
tolerance = max(2, round(px))
controls = {
    'sidebar-section-inactive': ['inactive-archive-all', 'sidebar-section-inactive-toggle'],
    'sidebar-section-snoozed': ['sidebar-section-snoozed-toggle'],
    'sidebar-section-archived': ['sidebar-section-archived-toggle'],
}
found = {}
for node in ET.parse(path).iter('node'):
    rid = node.get('resource-id', '')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    if y2 > y1:
        found.setdefault(rid, (y1 + y2) / 2)
problems, checked = [], 0
for label, tags in controls.items():
    if label not in found:
        continue
    for tag in tags:
        if tag not in found:
            problems.append(f'{label} shows without {tag}')
            continue
        off = abs(found[label] - found[tag])
        checked += 1
        print(f'sections    {label:<26} center {found[label]:7.1f} vs {tag} {found[tag]:.1f} (off {off / px:.1f}dp)')
        if off > tolerance:
            problems.append(f'{label} is {off / px:.1f}dp off {tag}')
if checked == 0:
    problems.append('no section header with controls was visible')
for p in problems:
    print(f'sections    FAIL {p}')
sys.exit(1 if problems else 0)
PY
}
adb_ am force-stop "$app"
adb_ monkey -p "$app" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3
event use-server '{"scenario":"fleet-organize"}'
event sign-in-token '{"token":"fake-amp-accepts-any-bearer"}'
event geometry-override '{"widthDp":1080,"creasesDp":[335,717]}'
event set-arrangement '{"name":"Triage"}'
sleep 3
# The Archived header is last, so swiping until the list stops moving shows it whole; one half under the list's bottom
# edge reports its label clipped and off center.
swipe_sidebar() { adb_ input swipe $((150 * density / 160)) $((650 * density / 160)) $((150 * density / 160)) $((300 * density / 160)) 400; sleep 1; }
previous=""
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16; do
  take_dump
  current="$(grep -o 'resource-id="sidebar-section-archived"[^>]*' "$dump" || true)"
  [[ -n "$current" && "$current" == "$previous" ]] && break
  previous="$current"
  swipe_sidebar
done
take_dump
check_sections || failed=1

adb_ rm -f /sdcard/amp-alignment.xml
adb_ am force-stop "$app"
if (( failed )); then
  echo "pane headers do not line up" >&2
  exit 1
fi
echo "pane headers line up in ${#arrangements[@]} arrangements, single-line titles center in Activity, New Thread, and Settings, and section labels center on their controls"
