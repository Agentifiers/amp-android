#!/usr/bin/env bash
# Signs the debug app on <serial> in to the Amp server at AMP_URL (default https://ampcode.com) with an API key,
# through the use-server and sign-in-token app events sent with adb. The key is AMP_API_KEY, or else the Amp CLI's
# stored key for that server (apiKey@<AMP_URL> in ~/.local/share/amp/secrets.json). The key never reaches stdout,
# a flow file, or agent-device's logs; Android logs the deep link as amp-debug://event/... without its query.
# Usage: [AMP_URL=<server>] [AMP_API_KEY=<key>] scripts/sign-in-live.sh <serial>
set -euo pipefail

if [[ $# -ne 1 || -z "$1" ]]; then
  echo "usage: $0 <serial>" >&2
  exit 2
fi
serial="$1"
source "$(dirname "$0")/device-guard.sh"
guard_device "$serial" "sign in"

SERIAL="$serial" AMP_URL="${AMP_URL:-https://ampcode.com}" python3 - <<'EOF'
import json, os, subprocess, sys, time, urllib.parse

server = os.environ["AMP_URL"].rstrip("/")
token = os.environ.get("AMP_API_KEY")
if not token:
    try:
        secrets = json.load(open(os.path.expanduser("~/.local/share/amp/secrets.json")))
    except FileNotFoundError:
        secrets = {}
    token = secrets.get("apiKey@" + server)
if not token:
    sys.exit("no API key for %s: set AMP_API_KEY, or sign in to that server with the Amp CLI" % server)

def event(name, payload):
    url = "amp-debug://event/%s?json=%s" % (name, urllib.parse.quote(json.dumps(payload), safe=""))
    subprocess.run(
        ["adb", "-s", os.environ["SERIAL"], "shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "'%s'" % url],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True,
    )

event("use-server", {"url": server})
time.sleep(2)
event("sign-in-token", {"token": token})
EOF
sleep 5
adb -s "$serial" logcat -d -s AmpEvent | grep -E "use-server|sign-in-token" | tail -2
