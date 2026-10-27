#!/usr/bin/env python3
"""Checks the sketch: README citations, brace balance in every .kt file, and banned leftovers.

README citations are written as `path:LINE` `Symbol` (or `:LINE` `Symbol` for the same path as the
citation before it in the cell). A citation passes when Symbol appears on LINE. With --fix, each LINE
is rewritten to the line that declares Symbol, or else the first line that contains it.

Usage: python3 docs/design/sketch/check-sketch.py [--fix]
Exit code 1 when any check fails.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent
README = ROOT / "README.md"
CITATION = re.compile(r"`(?P<path>[\w./-]+\.(?:kt|kts|md))?:(?P<line>\d+)` `(?P<symbol>[^`]+)`")
BANNED = [
    r"@RendersTool", r"@Pane\b", r"@MachineTab\b", r"@WireEvent", r":codegen", r"ksp\(", r"dataSync",
    r"graceMs", r"2_000L?\b.*(settle|grace)", r"2000 ms", r"SendMode\s*\{[^}]*InterruptAndSend",
]


def resolve(lines, symbol):
    name = re.escape(symbol.split(".")[-1].split("(")[0])
    decl = re.compile(rf"\b(class|interface|object|fun|val|var|typealias|enum class)\s+(<[^>]+>\s*)?{name}\b")
    for i, text in enumerate(lines, 1):
        if decl.search(text):
            return i
    for i, text in enumerate(lines, 1):
        if symbol in text:
            return i
    return None


def check_citations(fix):
    failures = []
    out = []
    for row in README.read_text().splitlines():
        current = None

        def repl(m):
            nonlocal current
            current = m.group("path") or current
            if current is None:
                failures.append(f"README: citation {m.group(0)} has no path")
                return m.group(0)
            lines = (ROOT / current).read_text().splitlines()
            line = int(m.group("line"))
            symbol = m.group("symbol")
            ok = 1 <= line <= len(lines) and symbol in lines[line - 1]
            if fix and not ok:
                found = resolve(lines, symbol)
                if found is None:
                    failures.append(f"README: {current}: `{symbol}` not found")
                    return m.group(0)
                line, ok = found, True
            if not ok:
                failures.append(f"README: {current}:{line} does not contain `{symbol}`")
            prefix = m.group("path") or ""
            return f"`{prefix}:{line}` `{symbol}`"

        out.append(CITATION.sub(repl, row))
    if fix:
        README.write_text("\n".join(out) + "\n")
    return failures


def code_only(src):
    """Kotlin source with comments, strings and char literals blanked out."""
    out, i, n, depth = [], 0, len(src), 0
    while i < n:
        if src.startswith("/*", i):
            depth, i = 1, i + 2
            while i < n and depth:
                if src.startswith("/*", i):
                    depth, i = depth + 1, i + 2
                elif src.startswith("*/", i):
                    depth, i = depth - 1, i + 2
                else:
                    i += 1
        elif src.startswith("//", i):
            while i < n and src[i] != "\n":
                i += 1
        elif src.startswith('"""', i):
            end = src.find('"""', i + 3)
            i = n if end < 0 else end + 3
        elif src[i] == '"':
            i += 1
            while i < n and src[i] != '"':
                i += 2 if src[i] == "\\" else 1
            i += 1
        elif src[i] == "'" and re.match(r"'(\\.|[^\\'])'", src[i:i + 4]):
            i += src[i:i + 4].index("'", 1) + 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def check_braces():
    failures = []
    pairs = {")": "(", "]": "[", "}": "{"}
    for path in sorted(ROOT.rglob("*.kt*")):
        stack = []
        for ch in code_only(path.read_text()):
            if ch in "([{":
                stack.append(ch)
            elif ch in pairs:
                if not stack or stack.pop() != pairs[ch]:
                    failures.append(f"{path.relative_to(ROOT)}: unbalanced '{ch}'")
                    break
        else:
            if stack:
                failures.append(f"{path.relative_to(ROOT)}: {len(stack)} unclosed {''.join(stack)}")
    return failures


def check_banned():
    failures = []
    for path in sorted(ROOT.rglob("*")):
        if path.suffix not in {".kt", ".kts", ".md"}:
            continue
        text = path.read_text()
        for pattern in BANNED:
            for m in re.finditer(pattern, text):
                line = text.count("\n", 0, m.start()) + 1
                failures.append(f"{path.relative_to(ROOT)}:{line}: banned `{pattern}`")
    return failures


if __name__ == "__main__":
    problems = check_citations("--fix" in sys.argv) + check_braces() + check_banned()
    for p in problems:
        print(p)
    print(f"{len(problems)} problem(s)")
    sys.exit(1 if problems else 0)
