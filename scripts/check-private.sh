#!/usr/bin/env bash
# usage: check-private.sh --staged | <rev-range>
#
# Refuses a commit or a range of commits that adds a string you never want published, or a file under a path you
# keep private, then runs gitleaks over the same change. The strings and paths are yours, so they live outside the
# repository, in .git/info/private-patterns (or the file $AMP_PRIVATE_PATTERNS names), one per line:
#
#   # a comment
#   some-hostname.local
#   path:notes/
#
# scripts/hooks/pre-commit runs it on every commit once `git config core.hooksPath scripts/hooks` is set.
set -uo pipefail

target="${1:?usage: check-private.sh --staged | <rev-range>}"
patterns="${AMP_PRIVATE_PATTERNS:-$(git rev-parse --git-common-dir)/info/private-patterns}"
if [[ "$target" == --staged ]]; then diff_args=(--cached); else diff_args=("$target"); fi

failed=0
if [[ -f "$patterns" ]]; then
  strings=$(grep -v -E '^[[:space:]]*(#|$)' "$patterns" | grep -v '^path:' || true)
  paths=$(grep '^path:' "$patterns" | cut -c6- || true)
  while read -r file; do
    while read -r prefix; do
      if [[ -n "$prefix" && "$file" == "$prefix"* ]]; then
        echo "check-private: $file is under the private path $prefix" >&2
        failed=1
      fi
    done <<<"$paths"
    if [[ -n "$strings" ]]; then
      found=$(git diff "${diff_args[@]}" -U0 -- "$file" | grep '^+' | grep -v '^+++' | grep -o -F -f <(printf '%s\n' "$strings") | sort -u)
      if [[ -n "$found" ]]; then
        echo "check-private: $file adds $(echo "$found" | paste -sd, -)" >&2
        failed=1
      fi
    fi
  done < <(git diff "${diff_args[@]}" --name-only --diff-filter=ACMR)
fi

if command -v gitleaks >/dev/null; then
  if [[ "$target" == --staged ]]; then
    gitleaks git --pre-commit --staged --redact --no-banner . >&2 || failed=1
  else
    gitleaks git --log-opts="$target" --redact --no-banner . >&2 || failed=1
  fi
else
  echo "check-private: gitleaks is not installed, so secrets were not scanned" >&2
fi

exit "$failed"
