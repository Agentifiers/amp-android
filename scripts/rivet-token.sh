#!/usr/bin/env bash
# Copies the Rivet publishable token embedded in the Amp CLI into local.properties (gitignored) as
# amp.rivetPublishableToken, the way spikes/protocol/lib.ts publishableRivetToken() finds it. Never prints it.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cli="${AMP_CLI:-$HOME/.amp/bin/amp}"
props="$root/local.properties"

[ -f "$cli" ] || { echo "Amp CLI not found at $cli (set AMP_CLI)" >&2; exit 1; }

python3 - "$cli" "$props" <<'PY'
import os, re, sys

cli, props = sys.argv[1], sys.argv[2]
data = open(cli, "rb").read()
needle = 'else t.password="pk_'.encode("utf-16-le")
at = data.find(needle)
if at < 0:
    sys.exit("publishable Rivet token not found in the Amp CLI binary")
tail = data[at + len(needle) - 6 : at + len(needle) + 200].decode("utf-16-le", errors="ignore")
match = re.match(r"pk_[A-Za-z0-9]+", tail)
if not match:
    sys.exit("publishable Rivet token has an unexpected shape")

key = "amp.rivetPublishableToken"
lines = open(props).read().splitlines() if os.path.exists(props) else []
lines = [line for line in lines if not line.startswith(key + "=")] + [f"{key}={match.group(0)}"]
with open(props, "w") as f:
    f.write("\n".join(lines) + "\n")
print(f"wrote {key} to {props}")
PY
