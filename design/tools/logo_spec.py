"""Single source of truth for the 岐黄课表 · AHUCM Schedule logo.

The mark combines a calendar binding, open pages and a negative-space leaf.
All geometry lives in a 512 x 512 design space and works in a single color.

Everything downstream is generated from these constants:

* ``design/*.svg``                     - vector masters
* ``design/png/*.png``, ``design/logo.ico`` - raster exports
* ``androidApp/src/main/res/**``       - adaptive icon layers, mipmaps
* ``composeApp/src/desktopMain/resources/icon.*`` - Windows executable icon
"""
from __future__ import annotations

from pathlib import Path
from typing import Iterable
import re

from logo_kit import svg_document

DESIGN = 512.0

# Brand palette -------------------------------------------------------------
BG_FROM = "#124F46"
BG_TO = "#267363"
GLYPH = "#FFF7E2"
INK = "#124F46"
INK_SOFT = "#4E7F6C"
SQUIRCLE_RADIUS = 112.0

# Background gradient endpoints (design space, ~135 degrees)
BG_GRADIENT = dict(x1=40.0, y1=16.0, x2=472.0, y2=496.0)

# Calendar, open pages and botanical negative space. Only absolute Cartesian
# commands are used so SVG, Android and Compose consume identical contours.
GLYPH_PATHS = (
    "M174,109C174,101.82 179.82,96 187,96C194.18,96 200,101.82 200,109"
    "L200,141C200,148.18 194.18,154 187,154C179.82,154 174,148.18 174,141Z",
    "M312,109C312,101.82 317.82,96 325,96C332.18,96 338,101.82 338,109"
    "L338,141C338,148.18 332.18,154 325,154C317.82,154 312,148.18 312,141Z",
    "M112,180C110,147 134,124 160,124L164,124L164,145"
    "C164,174 208,174 208,145L208,124L304,124L304,145"
    "C304,174 348,174 348,145L348,124L358,124"
    "C382,124 400,141 400,166L400,330"
    "C400,354 385,370 361,371C310,374 277,382 250,416L250,281"
    "C250,256 267,236 295,224C321,212 347,199 362,170"
    "C331,184 300,184 280,196C264,202 251,215 242,232"
    "C225,199 199,190 171,183C146,176 124,170 112,180Z",
    "M112,204C112,190 124,185 138,190C172,199 212,211 231,235"
    "C240,248 242,268 242,288L242,408"
    "C228,392 215,384 195,381L139,370C121,366 112,354 112,336Z",
    "M254,284C260,258 277,243 298,231C314,222 329,210 336,199"
    "C323,212 306,219 289,231C272,243 260,262 254,284Z",
)

# Foreground layer is scaled down so the glyph fits the 66dp adaptive safe
# circle (the same artwork is used for the monochrome/themed layer).
ADAPTIVE_SCALE = 0.82


def glyph_paths(scale: float = 1.0) -> list[str]:
    def coordinate(match):
        value = DESIGN / 2 + (float(match.group()) - DESIGN / 2) * scale
        return f'{value:.3f}'.rstrip('0').rstrip('.')
    return [re.sub(r'-?\d+(?:\.\d+)?', coordinate, path) for path in GLYPH_PATHS]


def bg_gradient_defs(gradient_id: str = "bg", user_space: bool = True) -> str:
    units = ' gradientUnits="userSpaceOnUse"' if user_space else ""
    return (
        f'<linearGradient id="{gradient_id}"{units} '
        f'x1="{BG_GRADIENT["x1"]}" y1="{BG_GRADIENT["y1"]}" '
        f'x2="{BG_GRADIENT["x2"]}" y2="{BG_GRADIENT["y2"]}">'
        f'<stop offset="0" stop-color="{BG_FROM}"/>'
        f'<stop offset="1" stop-color="{BG_TO}"/>'
        "</linearGradient>"
    )


# ---------------------------------------------------------------------------
# SVG masters
# ---------------------------------------------------------------------------
def icon_svg(scale: float = 1.0, rounded: bool = True, with_background: bool = True) -> str:
    body = ""
    if with_background:
        rx = f' rx="{SQUIRCLE_RADIUS:g}"' if rounded else ""
        body += f'<rect x="0" y="0" width="512" height="512"{rx} fill="url(#bg)"/>'
    if scale > 0:
        for path in glyph_paths(scale):
            body += f'<path d="{path}" fill="{GLYPH}"/>'
    return svg_document(body, bg_gradient_defs(), 1024)


def adaptive_background_svg() -> str:
    return icon_svg(scale=0.0, rounded=False, with_background=True)


def adaptive_foreground_svg(scale: float = ADAPTIVE_SCALE) -> str:
    return icon_svg(scale=scale, rounded=False, with_background=False)


def mark_svg(scale: float = 1.0, color: str = GLYPH) -> str:
    body = "".join(f'<path d="{path}" fill="{color}"/>' for path in glyph_paths(scale))
    return svg_document(body, "", 1024)


# ---------------------------------------------------------------------------
# Android vector drawables
# ---------------------------------------------------------------------------
VECTOR_HEADER = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    xmlns:aapt="http://schemas.android.com/aapt"\n'
    '    android:width="108dp"\n'
    '    android:height="108dp"\n'
    '    android:viewportWidth="512"\n'
    '    android:viewportHeight="512">\n'
)


def android_background_drawable() -> str:
    return (
        VECTOR_HEADER
        + '    <path android:pathData="M0,0H512V512H0Z">\n'
        + '        <aapt:attr name="android:fillColor">\n'
        + "            <gradient\n"
        + '                android:type="linear"\n'
        + f'                android:startX="{BG_GRADIENT["x1"]:g}"\n'
        + f'                android:startY="{BG_GRADIENT["y1"]:g}"\n'
        + f'                android:endX="{BG_GRADIENT["x2"]:g}"\n'
        + f'                android:endY="{BG_GRADIENT["y2"]:g}"\n'
        + '                android:startColor="@color/ic_launcher_background_start"\n'
        + '                android:endColor="@color/ic_launcher_background_end" />\n'
        + "        </aapt:attr>\n"
        + "    </path>\n"
        + "</vector>\n"
    )


def android_glyph_drawable(
    scale: float = ADAPTIVE_SCALE, color: str = "@color/ic_launcher_glyph"
) -> str:
    paths = "\n".join(
        f'    <path android:fillColor="{color}" android:pathData="{path}" />'
        for path in glyph_paths(scale)
    )
    return VECTOR_HEADER + paths + "\n</vector>\n"


ADAPTIVE_ICON_XML = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@drawable/ic_launcher_background" />\n'
    '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
    "</adaptive-icon>\n"
)

ADAPTIVE_ICON_V33_XML = ADAPTIVE_ICON_XML.replace(
    "</adaptive-icon>",
    '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
    "</adaptive-icon>",
)


def android_icon_colors(start: str, end: str, glyph: str) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        f'    <color name="ic_launcher_background_start">{start}</color>\n'
        f'    <color name="ic_launcher_background_end">{end}</color>\n'
        f'    <color name="ic_launcher_glyph">{glyph}</color>\n'
        '</resources>\n'
    )


# ---------------------------------------------------------------------------
# file plan
# ---------------------------------------------------------------------------
REPO = Path(__file__).resolve().parents[2]
DESIGN_DIR = REPO / "design"
RENDER_DIR = REPO / ".local" / "logo-render"
ANDROID_RES = REPO / "androidApp" / "src" / "main" / "res"
DESKTOP_RES = REPO / "composeApp" / "src" / "desktopMain" / "resources"

SVG_MASTERS = {
    "logo.svg": icon_svg(),
    "logo-adaptive-background.svg": adaptive_background_svg(),
    "logo-adaptive-foreground.svg": adaptive_foreground_svg(),
    "logo-monochrome.svg": mark_svg(scale=ADAPTIVE_SCALE, color="#FFFFFF"),
    "logo-mark.svg": mark_svg(),
}

RENDER_PAGES = {
    "icon.png": icon_svg(),
    "adaptive-background.png": adaptive_background_svg(),
    "adaptive-foreground.png": adaptive_foreground_svg(),
    "monochrome.png": mark_svg(scale=ADAPTIVE_SCALE, color="#FFFFFF"),
}


def write_android_assets() -> list[Path]:
    written: list[Path] = []
    drawable = ANDROID_RES / "drawable"
    mipmap_v26 = ANDROID_RES / "mipmap-anydpi-v26"
    drawable.mkdir(parents=True, exist_ok=True)
    mipmap_v26.mkdir(parents=True, exist_ok=True)

    files = {
        drawable / "ic_launcher_background.xml": android_background_drawable(),
        drawable / "ic_launcher_foreground.xml": android_glyph_drawable(),
        # A white alpha mask; launcher owns both themed-icon colors on API 33+.
        drawable / "ic_launcher_monochrome.xml": android_glyph_drawable(color="#FFFFFFFF"),
        mipmap_v26 / "ic_launcher.xml": ADAPTIVE_ICON_XML,
        mipmap_v26 / "ic_launcher_round.xml": ADAPTIVE_ICON_XML,
        ANDROID_RES / "mipmap-anydpi-v33" / "ic_launcher.xml": ADAPTIVE_ICON_V33_XML,
        ANDROID_RES / "mipmap-anydpi-v33" / "ic_launcher_round.xml": ADAPTIVE_ICON_V33_XML,
        ANDROID_RES / "values" / "ic_launcher_colors.xml": android_icon_colors(
            BG_FROM, BG_TO, GLYPH
        ),
        ANDROID_RES / "values-v31" / "ic_launcher_colors.xml": android_icon_colors(
            "@android:color/system_accent1_100",
            "@android:color/system_accent1_200",
            "@android:color/system_neutral1_900",
        ),
        ANDROID_RES / "values-night-v31" / "ic_launcher_colors.xml": android_icon_colors(
            "@android:color/system_accent1_800",
            "@android:color/system_accent1_700",
            "@android:color/system_neutral1_100",
        ),
    }
    for path, content in files.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        written.append(path)
    return written


def write_masters() -> list[Path]:
    written: list[Path] = []
    for name, content in SVG_MASTERS.items():
        path = DESIGN_DIR / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        written.append(path)
    harmony = REPO / 'harmonyApp/AppScope/resources/base/media/app_icon.svg'
    harmony.write_text(icon_svg(), encoding='utf-8')
    written.append(harmony)
    compose = REPO / 'composeApp/src/jvmSharedMain/kotlin/cn/edu/ahtcm/schedule/ui/BrandMarkPaths.kt'
    paths = ',\n'.join(f'    "{path}"' for path in glyph_paths())
    compose.write_text(
        '// Generated by design/tools/build_logo.py spec; edit logo_spec.py.\n'
        'package cn.edu.ahtcm.schedule.ui\n\n'
        f'internal val BrandMarkPaths = listOf(\n{paths},\n)\n', encoding='utf-8')
    written.append(compose)
    return written


def write_render_pages() -> Path:
    pages = RENDER_DIR / "pages"
    pages.mkdir(parents=True, exist_ok=True)
    lines: list[str] = []
    for name, content in RENDER_PAGES.items():
        html = pages / (Path(name).stem + ".html")
        html.write_text(
            '<!doctype html><html><head><meta charset="utf-8">'
            "<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}"
            "svg{display:block}</style></head><body>"
            + content
            + "</body></html>",
            encoding="utf-8",
        )
        lines.append(f"pages/{html.name}|rendered/{name}|1024")
    manifest = RENDER_DIR / "manifest.txt"
    manifest.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return manifest


def iter_mipmap_sizes() -> Iterable[tuple[str, int]]:
    return (
        ("mdpi", 48),
        ("hdpi", 72),
        ("xhdpi", 96),
        ("xxhdpi", 144),
        ("xxxhdpi", 192),
    )
