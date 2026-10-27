#!/usr/bin/env bash
# Counts raw `N.dp` literals in feature and app main sources and fails when the count exceeds the budget in
# scripts/dp-budget.txt. A size that has a name belongs in AmpTheme.metrics, spacing, or shapes.
# Usage: scripts/dp-budget.sh [--list]
set -euo pipefail
cd "$(dirname "$0")/.."

budget_file=scripts/dp-budget.txt
# The boundary groups keep `8.dp` from matching inside `x8.dp` or `1.8.dpi`; -o prints one line per literal.
pattern='(^|[^A-Za-z0-9_.])[0-9]+(\.[0-9]+)?\.dp([^A-Za-z0-9_]|$)'

literals() {
  find feature/*/src/main app/src/main -name '*.kt' -print0 | xargs -0 grep -EHno "$pattern" || true
}

if [[ "${1:-}" == --list ]]; then
  literals | awk -F: '{ print $1 }' | sort | uniq -c | sort -rn
  exit 0
fi

count="$(literals | wc -l | tr -d ' ')"
budget="$(tr -d '[:space:]' <"$budget_file")"
if (( count > budget )); then
  echo "dp budget exceeded: $count raw N.dp literals in feature/ and app/ main sources, budget $budget ($budget_file)." >&2
  echo "Use a token from AmpTheme.metrics, spacing, or shapes. scripts/dp-budget.sh --list shows where they are." >&2
  exit 1
fi
echo "dp budget: $count raw N.dp literals, budget $budget"
