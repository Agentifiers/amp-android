#!/usr/bin/env python3
"""Generates AmpIcons, the app's Lucide icons as Compose ImageVectors, from one pinned lucide-static release.

Downloads the npm tarball once into $TMPDIR, checks it against the registry's sha512, and reads the
icon geometry from its icon-nodes.json. Circles, lines, polylines, and rects become SVG path data so
every icon is a list of paths that Compose parses with addPathNodes. Strokes stay strokes: width 2,
round caps and joins, on Lucide's 24 grid. Writes
core/design/src/main/kotlin/com/agentifiers/amp/design/icons/AmpIcons.kt and
core/design/LICENSES.md. Rerunning writes identical files.

Needs python3, and curl with network access on the first run.
"""
import base64
import hashlib
import io
import json
import os
import subprocess
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "core/design/src/main/kotlin/com/agentifiers/amp/design/icons/AmpIcons.kt"
LICENSES = ROOT / "core/design/LICENSES.md"

VERSION = "1.61.0"
TARBALL = f"https://registry.npmjs.org/lucide-static/-/lucide-static-{VERSION}.tgz"
INTEGRITY = "sha512-yLAiQB3MWUJEstsEDAwenSA3m7vr1tdLvgnC16Q/XMxRRma0DWwMs8M2bWlvgShrIs1m2mWMJ+0AiahqQO4GrA=="
CACHE = Path(os.environ.get("TMPDIR", "/tmp")) / f"lucide-static-{VERSION}.tgz"

ICONS = sorted({
    "refresh-cw", "panel-left-close", "panel-left-open", "log-out", "user", "users", "paperclip", "send",
    "arrow-up", "square", "plus", "search", "settings", "ellipsis-vertical", "x", "check", "chevron-left",
    "chevron-right", "chevron-down", "chevron-up", "git-branch", "git-compare", "file", "file-text", "folder",
    "folder-open", "terminal", "globe", "monitor", "mic", "maximize-2", "minimize-2", "app-window", "copy",
    "archive", "pin", "tag", "palette", "bell", "eye", "clock", "circle-alert", "triangle-alert", "info",
    "external-link", "rotate-ccw", "play", "list-filter", "message-square", "sparkles",
    # UX-02 sidebar header: New Thread, Activity.
    "square-pen", "activity",
    # UX-18 window and pane controls: Command Palette, Focus.
    "command", "focus",
    # UX-12 title bar: Share, and Multiplayer's joystick (screens.md, "Title bar").
    "share-2", "joystick",
    # UX-14 composer: Queue, Steer.
    "list-plus", "corner-down-right",
    # UX-04 diff toolbar: split, wrap.
    "columns-2", "text-wrap",
    # UX-21 and UX-37: Back, Copy Link.
    "arrow-left", "link",
    # UX-16 executor empty state, and AmpButton's loading state.
    "server", "loader-circle",
    # UX-35 cover bottom bar: Threads.
    "messages-square",
    # A2 transcript tool rows: the status glyph of a finished call, and of a cancelled or failed one.
    "circle-check", "circle-x",
    # U32 Changes file rows: Unstage.
    "minus",
})


def tarball() -> bytes:
    if not CACHE.exists():
        subprocess.run(["curl", "-fsSL", "-o", str(CACHE), TARBALL], check=True)
    data = CACHE.read_bytes()
    digest = "sha512-" + base64.b64encode(hashlib.sha512(data).digest()).decode()
    if digest != INTEGRITY:
        CACHE.unlink()
        raise SystemExit(f"{TARBALL} does not match the pinned integrity {INTEGRITY}")
    return data


def member(data: bytes, name: str) -> str:
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        return tar.extractfile(f"package/{name}").read().decode()


def num(value: float) -> str:
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


def path_data(tag: str, a: dict) -> str:
    f = {k: float(v) for k, v in a.items() if k != "fill" and k != "d"}
    if tag == "path":
        return a["d"]
    if tag == "line":
        return f"M{num(f['x1'])} {num(f['y1'])}L{num(f['x2'])} {num(f['y2'])}"
    if tag == "circle":
        cx, cy, r = f["cx"], f["cy"], f["r"]
        return f"M{num(cx - r)} {num(cy)}a{num(r)} {num(r)} 0 1 0 {num(2 * r)} 0a{num(r)} {num(r)} 0 1 0 {num(-2 * r)} 0"
    if tag == "rect":
        x, y, w, h = f.get("x", 0.0), f.get("y", 0.0), f["width"], f["height"]
        rx = f.get("rx", f.get("ry", 0.0))
        ry = f.get("ry", rx)
        if rx == 0:
            return f"M{num(x)} {num(y)}h{num(w)}v{num(h)}h{num(-w)}z"
        arc = f"a{num(rx)} {num(ry)} 0 0 1"
        return (
            f"M{num(x + rx)} {num(y)}h{num(w - 2 * rx)}{arc} {num(rx)} {num(ry)}v{num(h - 2 * ry)}"
            f"{arc} {num(-rx)} {num(ry)}h{num(-(w - 2 * rx))}{arc} {num(-rx)} {num(-ry)}v{num(-(h - 2 * ry))}"
            f"{arc} {num(rx)} {num(-ry)}z"
        )
    if tag in ("polyline", "polygon"):
        nums = a["points"].replace(",", " ").split()
        pairs = [f"{nums[i]} {nums[i + 1]}" for i in range(0, len(nums), 2)]
        return "M" + "L".join(pairs) + ("z" if tag == "polygon" else "")
    raise SystemExit(f"unsupported element <{tag}>")


def kotlin_name(name: str) -> str:
    return "".join(part.capitalize() for part in name.split("-"))


def icon_source(name: str, nodes: list) -> str:
    strokes = [path_data(tag, a) for tag, a in nodes if a.get("fill") != "currentColor"]
    filled = [path_data(tag, a) for tag, a in nodes if a.get("fill") == "currentColor"]
    args = [f'"{kotlin_name(name)}"'] + [f'"{d}"' for d in strokes]
    if filled:
        args.append("filled = listOf(" + ", ".join(f'"{d}"' for d in filled) + ")")
    lines = "".join(f"            {arg},\n" for arg in args)
    return f"    val {kotlin_name(name)}: ImageVector by lazy {{\n        icon(\n{lines}        )\n    }}\n"


def kotlin(nodes: dict, copyright_line: str) -> str:
    icons = "\n".join(icon_source(name, nodes[name]) for name in ICONS)
    registry = "".join(f'        "{name}" to {kotlin_name(name)},\n' for name in ICONS)
    return f"""// Generated by scripts/make-icons-lucide.py from lucide-static {VERSION}. Do not edit; rerun the script.
// Lucide is ISC-licensed: {copyright_line}. Full text in core/design/LICENSES.md.
package com.agentifiers.amp.design.icons

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.AmpTheme

/** Amp's web app draws Lucide icons; these are the ones the Android app uses, named after Lucide's PascalCase names. */
object AmpIcons {{
{icons}
    /** Every icon under its Lucide name, in name order. */
    val all: List<Pair<String, ImageVector>> by lazy {{
        listOf(
{registry}        )
    }}
}}

/** Icon tints the whole vector, so the stroke color only has to be opaque; ink is what an untinted Image draws. */
private fun icon(name: String, vararg strokes: String, filled: List<String> = emptyList()): ImageVector {{
    val ink = SolidColor(AmpTheme.colors.text.ink)
    val builder = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    (strokes.map {{ it to false }} + filled.map {{ it to true }}).forEach {{ (data, isFilled) ->
        builder.addPath(
            pathData = addPathNodes(data),
            fill = if (isFilled) ink else null,
            stroke = ink,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }}
    return builder.build()
}}
"""


def licenses(license_text: str) -> str:
    return f"""# Third-party licenses in :core:design

Generated by `scripts/make-icons-lucide.py`. Do not edit.

## Lucide

The icons in `core/design/src/main/kotlin/com/agentifiers/amp/design/icons/AmpIcons.kt` are converted from `lucide-static` {VERSION} ({TARBALL}, integrity `{INTEGRITY}`). The app ships {len(ICONS)} of them:

{", ".join(f"`{name}`" for name in ICONS)}.

```
{license_text.strip()}
```
"""


def main() -> None:
    data = tarball()
    nodes = json.loads(member(data, "icon-nodes.json"))
    missing = [name for name in ICONS if name not in nodes]
    if missing:
        raise SystemExit(f"not canonical Lucide names in {VERSION}: {', '.join(missing)}")
    license_text = member(data, "LICENSE")
    copyright_line = next(line for line in license_text.splitlines() if line.startswith("Copyright"))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(kotlin(nodes, copyright_line))
    LICENSES.parent.mkdir(parents=True, exist_ok=True)
    LICENSES.write_text(licenses(license_text))
    print(f"wrote {len(ICONS)} icons to {OUT.relative_to(ROOT)} and {LICENSES.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
