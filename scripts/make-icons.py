#!/usr/bin/env python3
"""Generates the app's icon resources from Amp's icon, docs/design/brand/ampcode-app-icon.svg.

Writes the adaptive launcher icon layers, the notification small icon, the colors the splash, the
shortcut icons, and the notification accent use, and a 512 px store PNG. Vector drawables reproduce
the SVG's gradient ground, its radial glows, and its wordmark path; the wordmark's drop shadow is a
blur filter, which vector drawables cannot draw, so only the store PNG has it. Rerunning writes
identical files.

Needs python3 and rsvg-convert (`brew install librsvg`) for the PNG.
"""
import math
import re
import subprocess
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SVG = ROOT / "docs" / "design" / "brand" / "ampcode-app-icon.svg"
APP_RES = ROOT / "app" / "src" / "main" / "res"
NOTIFY_RES = ROOT / "notify" / "src" / "main" / "res"
STORE_PNG = ROOT / "docs" / "build" / "ux" / "icon" / "amp-icon-512.png"

NS = {"svg": "http://www.w3.org/2000/svg"}
ANDROID = 'xmlns:android="http://schemas.android.com/apk/res/android"'
AAPT = 'xmlns:aapt="http://schemas.android.com/aapt"'

# An adaptive icon is a 108 dp canvas whose launcher mask shows at most the middle 72 dp, and
# guarantees only a 66 dp circle. The 1024 artboard maps onto the 72 dp so the icon keeps Amp's
# composition; the 18 dp bleed on each side continues the ground.
CANVAS_DP = 108
VISIBLE_DP = 72
SAFE_DP = 66
NOTIFICATION_DP = 24
NOTIFICATION_PADDING_DP = 1


@dataclass(frozen=True)
class Stop:
    offset: float
    argb: str


@dataclass(frozen=True)
class Transform:
    translate: tuple[float, float] = (0.0, 0.0)
    rotate: float = 0.0
    scale: tuple[float, float] = (1.0, 1.0)


@dataclass(frozen=True)
class Glow:
    """A radial gradient on the unit circle, placed by `transform` (translate, rotate, scale, as in the SVG)."""
    transform: Transform
    stops: list[Stop]


@dataclass(frozen=True)
class Icon:
    size: float
    ground: list[Stop]
    ground_from_y: float
    ground_to_y: float
    glows: list[Glow]
    wordmark: str
    wordmark_transform: Transform
    wordmark_argb: str

    @property
    def darkest_ground(self) -> str:
        return self.ground[-1].argb


def argb(hex_rgb: str, opacity: float = 1.0) -> str:
    return f"#{round(opacity * 255):02X}{hex_rgb.lstrip('#').upper()}"


def num(value: float) -> str:
    return f"{value:.4f}".rstrip("0").rstrip(".")


def parse_transform(text: str) -> Transform:
    calls = re.findall(r"(\w+)\(([^)]*)\)", text)
    parts = {name: [float(v) for v in re.split(r"[\s,]+", args.strip())] for name, args in calls}
    assert [name for name, _ in calls] == [n for n in ("translate", "rotate", "scale") if n in parts], f"unsupported transform order: {text}"
    scale = parts.get("scale", [1.0])
    return Transform(
        translate=tuple(parts.get("translate", [0.0, 0.0])),
        rotate=parts.get("rotate", [0.0])[0],
        scale=(scale[0], scale[-1]),
    )


def parse_stops(gradient: ET.Element) -> list[Stop]:
    return [
        Stop(float(s.get("offset", "0")), argb(s.get("stop-color"), float(s.get("stop-opacity", "1"))))
        for s in gradient.findall("svg:stop", NS)
    ]


def parse_icon() -> Icon:
    root = ET.parse(SVG).getroot()
    size = float(root.get("width"))
    background = root.find(".//svg:linearGradient[@id='background']", NS)
    assert background.get("x1") == background.get("x2"), "the ground gradient is vertical"
    fills = [r.get("fill") for r in root.findall("./svg:g/svg:rect", NS)]
    assert fills[0] == "url(#background)", "the first ground rect is the linear gradient"
    glows = []
    for fill in fills[1:]:
        gradient = root.find(f".//svg:radialGradient[@id='{fill[5:-1]}']", NS)
        assert (gradient.get("cx"), gradient.get("cy"), gradient.get("r")) == ("0", "0", "1"), "glows are unit circles placed by gradientTransform"
        glows.append(Glow(parse_transform(gradient.get("gradientTransform")), parse_stops(gradient)))
    paths = root.findall(".//svg:path", NS)
    assert len(paths) == 1, "the icon has one wordmark path"
    path = paths[0]
    return Icon(
        size=size,
        ground=parse_stops(background),
        ground_from_y=float(background.get("y1")),
        ground_to_y=float(background.get("y2")),
        glows=glows,
        wordmark=path.get("d"),
        wordmark_transform=parse_transform(path.get("transform")),
        wordmark_argb=argb(path.get("fill")),
    )


def path_bounds(d: str) -> tuple[float, float, float, float]:
    """Bounds of every coordinate in an absolute M/C/L/H/V/Z path. Bezier control points bound their curves."""
    assert set(re.findall(r"[A-Za-z]", d)) - {"e"} <= set("MCLHVZ"), "only absolute M, C, L, H, V, Z commands"
    xs, ys = [], []
    for command, args in re.findall(r"([MCLHVZ])([^MCLHVZ]*)", d):
        values = [float(v) for v in re.findall(r"-?\d*\.?\d+(?:e-?\d+)?", args)]
        if command == "H":
            xs += values
        elif command == "V":
            ys += values
        else:
            xs += values[0::2]
            ys += values[1::2]
    return min(xs), min(ys), max(xs), max(ys)


def gradient_items(stops: list[Stop], indent: str) -> str:
    return "\n".join(f'{indent}<item android:offset="{num(s.offset)}" android:color="{s.argb}" />' for s in stops)


def group_attrs(t: Transform) -> str:
    attrs = []
    if t.translate != (0.0, 0.0):
        attrs += [f'android:translateX="{num(t.translate[0])}"', f'android:translateY="{num(t.translate[1])}"']
    if t.rotate:
        attrs.append(f'android:rotation="{num(t.rotate)}"')
    if t.scale != (1.0, 1.0):
        attrs += [f'android:scaleX="{num(t.scale[0])}"', f'android:scaleY="{num(t.scale[1])}"']
    return " ".join(attrs)


def layer_vector(icon: Icon, body: str, aapt: bool = False) -> str:
    viewport = icon.size * CANVAS_DP / VISIBLE_DP
    bleed = (viewport - icon.size) / 2
    namespaces = f"{ANDROID}\n    {AAPT}" if aapt else ANDROID
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/make-icons.py from docs/design/brand/ampcode-app-icon.svg. -->
<vector {namespaces}
    android:width="{CANVAS_DP}dp"
    android:height="{CANVAS_DP}dp"
    android:viewportWidth="{num(viewport)}"
    android:viewportHeight="{num(viewport)}">
    <group android:translateX="{num(bleed)}" android:translateY="{num(bleed)}">
{body}
    </group>
</vector>
"""


def background(icon: Icon) -> str:
    viewport = icon.size * CANVAS_DP / VISIBLE_DP
    bleed = (viewport - icon.size) / 2
    full = f"M{num(-bleed)},{num(-bleed)}H{num(icon.size + bleed)}V{num(icon.size + bleed)}H{num(-bleed)}Z"
    center = num(icon.size / 2)
    ground = f"""        <path android:pathData="{full}">
            <aapt:attr name="android:fillColor">
                <gradient android:type="linear" android:startX="{center}" android:startY="{num(icon.ground_from_y)}" android:endX="{center}" android:endY="{num(icon.ground_to_y)}">
{gradient_items(icon.ground, "                    ")}
                </gradient>
            </aapt:attr>
        </path>"""
    glows = [
        f"""        <group {group_attrs(glow.transform)}>
            <path android:pathData="M-1,-1H1V1H-1Z">
                <aapt:attr name="android:fillColor">
                    <gradient android:type="radial" android:centerX="0" android:centerY="0" android:gradientRadius="1">
{gradient_items(glow.stops, "                        ")}
                    </gradient>
                </aapt:attr>
            </path>
        </group>"""
        for glow in icon.glows
    ]
    return layer_vector(icon, "\n".join([ground, *glows]), aapt=True)


def wordmark_group(icon: Icon, fill: str, indent: str) -> str:
    return f"""{indent}<group {group_attrs(icon.wordmark_transform)}>
{indent}    <path android:fillColor="{fill}" android:pathData="{icon.wordmark}" />
{indent}</group>"""


def foreground(icon: Icon) -> str:
    return layer_vector(icon, wordmark_group(icon, icon.wordmark_argb, "        "))


def adaptive_icon() -> str:
    return """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/make-icons.py. The monochrome layer is the foreground; themed icons use only its alpha. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
"""


def notification(icon: Icon) -> str:
    left, top, right, bottom = path_bounds(icon.wordmark)
    width, height = right - left, bottom - top
    viewport = width * NOTIFICATION_DP / (NOTIFICATION_DP - 2 * NOTIFICATION_PADDING_DP)
    t = Transform(translate=((viewport - width) / 2 - left, (viewport - height) / 2 - top))
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/make-icons.py from the wordmark in docs/design/brand/ampcode-app-icon.svg. -->
<vector {ANDROID}
    android:width="{NOTIFICATION_DP}dp"
    android:height="{NOTIFICATION_DP}dp"
    android:viewportWidth="{num(viewport)}"
    android:viewportHeight="{num(viewport)}">
    <group {group_attrs(t)}>
        <path android:fillColor="#FFFFFFFF" android:pathData="{icon.wordmark}" />
    </group>
</vector>
"""


def colors(**named: str) -> str:
    items = "\n".join(f'    <color name="{name}">{value}</color>' for name, value in named.items())
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/make-icons.py from docs/design/brand/ampcode-app-icon.svg. -->
<resources>
{items}
</resources>
"""


def safe_zone_diameter_dp(icon: Icon) -> float:
    """Diameter of the smallest centered circle on the 108 dp canvas that holds the wordmark's bounds."""
    left, top, right, bottom = path_bounds(icon.wordmark)
    t = icon.wordmark_transform
    center = icon.size / 2
    corners = [(t.translate[0] + x * t.scale[0], t.translate[1] + y * t.scale[1]) for x in (left, right) for y in (top, bottom)]
    radius = max(math.hypot(x - center, y - center) for x, y in corners)
    return 2 * radius * VISIBLE_DP / icon.size


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    print(path.relative_to(ROOT))


def main() -> None:
    icon = parse_icon()
    assert icon.wordmark_transform.rotate == 0, "the wordmark is not rotated"
    diameter = safe_zone_diameter_dp(icon)
    assert diameter <= SAFE_DP, f"wordmark needs a {diameter:.1f} dp circle, more than the {SAFE_DP} dp safe zone"
    print(f"wordmark fits a {diameter:.1f} dp circle inside the {SAFE_DP} dp safe zone")

    launcher = adaptive_icon()
    outputs = {
        APP_RES / "drawable" / "ic_launcher_background.xml": background(icon),
        APP_RES / "drawable" / "ic_launcher_foreground.xml": foreground(icon),
        APP_RES / "mipmap-anydpi-v26" / "ic_launcher.xml": launcher,
        APP_RES / "mipmap-anydpi-v26" / "ic_launcher_round.xml": launcher,
        APP_RES / "values" / "colors.xml": colors(amp_ground=icon.darkest_ground, amp_mark=icon.wordmark_argb),
        NOTIFY_RES / "drawable" / "ic_stat_amp.xml": notification(icon),
        NOTIFY_RES / "values" / "colors.xml": colors(notification_accent=icon.wordmark_argb),
    }
    for path, text in outputs.items():
        write(path, text)

    STORE_PNG.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["rsvg-convert", "-w", "512", "-h", "512", "-o", str(STORE_PNG), str(SVG)], check=True)
    print(STORE_PNG.relative_to(ROOT))


if __name__ == "__main__":
    main()
