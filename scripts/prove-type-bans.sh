#!/usr/bin/env bash
# Plants one banned text-style or raw text-field line at a time in :feature:space and shows that its ampSourceBans fails on it, after
# checking that the module passes clean. The planted file is removed on exit.
# Usage: scripts/prove-type-bans.sh
set -euo pipefail
cd "$(dirname "$0")/.."
unset ANDROID_SDK_ROOT
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

plant=feature/space/src/main/kotlin/com/agentifiers/amp/space/BanProof.kt
trap 'rm -f "$plant"' EXIT
bans() { ./gradlew -q --console=plain :feature:space:ampSourceBans 2>&1; }

rm -f "$plant"
bans >/dev/null || { echo "clean :feature:space fails ampSourceBans; fix that first" >&2; exit 1; }
echo "clean: :feature:space ampSourceBans passes"

failed=0
while IFS= read -r line; do
  printf 'package com.agentifiers.amp.space\n\n%s\n' "$line" >"$plant"
  if out="$(bans)"; then
    echo "NOT BANNED: $line"
    failed=1
  else
    echo "banned: $line"
    echo "  $(grep -m1 'BanProof.kt' <<<"$out" | sed 's|.*/BanProof.kt|BanProof.kt|')"
  fi
done <<'EOF'
val a = 12.sp
val b = Mono.copy(fontSize = size)
    lineHeight = height,
val d = style.copy(letterSpacing = tracking)
val e = TextStyle(color = ink)
val f = MaterialTheme.typography.bodyMedium
    OutlinedTextField(value = query, onValueChange = {})
    TextField(state = draft)
    BasicTextField(value, onValueChange)
    OutlinedSecureTextField(state = token)
EOF
exit "$failed"
