#!/usr/bin/env python3
"""Checks docs/design/*.md for broken references and banned punctuation.

- `doc.md#anchor` references must name an existing heading in docs/design/.
- Backticked repo paths under spikes/, docs/, scripts/, research/, prototypes/, or arena/ must exist. A `path:N`
  or `path:N-M` suffix must fall inside the file's lines.
- No em dash or en dash characters.
Exit 1 on any problem.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DESIGN = ROOT / "docs" / "design"


def slug(text: str) -> str:
    text = re.sub(r"[`*_]", "", text.lower())
    text = re.sub(r"[^a-z0-9\s-]", "", text)
    return re.sub(r"[\s-]+", "-", text).strip("-")


def anchors(doc: Path) -> set[str]:
    return {slug(line.lstrip("#").strip()) for line in doc.read_text().splitlines() if line.startswith("#")}


PATH_RE = re.compile(r"`((?:research|spikes|prototypes|docs|scripts|arena)/[^`\s:]+)(?::(\d+)(?:-(\d+))?)?`")
ANCHOR_RE = re.compile(r"\b([\w-]+\.md)#([\w-]+)")


def resolve(rel: str) -> Path | None:
    for base in (ROOT, DESIGN):
        p = base / rel
        if p.exists() or any(p.parent.glob(p.name)):
            return p
    return None


def main() -> int:
    docs = sorted(DESIGN.glob("*.md"))
    heading_index = {p.name: anchors(p) for p in docs}
    problems = []
    for doc in docs:
        for n, line in enumerate(doc.read_text().splitlines(), 1):
            where = f"{doc.relative_to(ROOT)}:{n}"
            if "\u2014" in line or "\u2013" in line:
                problems.append(f"{where}: dash character")
            for name, anchor in ANCHOR_RE.findall(line):
                if name in heading_index and anchor not in heading_index[name]:
                    problems.append(f"{where}: no heading #{anchor} in {name}")
            for rel, start, end in PATH_RE.findall(line):
                if any(ch in rel for ch in "*<>{}"):
                    continue
                path = resolve(rel)
                if path is None:
                    problems.append(f"{where}: missing path {rel}")
                elif start and path.is_file():
                    lines = sum(1 for _ in path.open(errors="replace"))
                    if int(end or start) > lines:
                        problems.append(f"{where}: {rel}:{start}{'-' + end if end else ''} past end ({lines} lines)")
    print(f"{len(docs)} docs checked, {len(problems)} problems")
    for p in problems:
        print(p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
