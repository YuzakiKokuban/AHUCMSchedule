"""Single source of truth for the 岐黄课表 · AHUCM Schedule logo.

The mark is a timetable: a rounded header bar plus a 2 x 3 grid of class blocks
on a light jade squircle.  All geometry lives in a 512 x 512 design space.

Everything downstream is generated from these constants:

* ``design/*.svg``                     - vector masters
* ``design/png/*.png``, ``design/logo.ico`` - raster exports
* ``androidApp/src/main/res/**``       - adaptive icon layers, mipmaps
* ``composeApp/src/desktopMain/resources/icon.*`` - Windows executable icon
"""
from __future__ import annotations

from pathlib import Path
from typing import Iterable

from logo_kit import rounded_rect, svg_document

DESIGN = 512.0

# Brand palette -------------------------------------------------------------
BG_FROM = "#5ED0A8"
BG_TO = "#A9EBD3"
GLYPH = "#FFFFFF"
INK = "#0B5E46"
INK_SOFT = "#4E7F6C"
SQUIRCLE_RADIUS = 112.0

# Background gradient endpoints (design space, ~135 degrees)
BG_GRADIENT = dict(x1=40.0, y1=16.0, x2=472.0, y2=496.0)

# Timetable glyph -----------------------------------------------------------
HEADER_BAR = (136.0, 116.0, 240.0, 50.0, 25.0)
BLOCK_WIDTH = 108.0
BLOCK_HEIGHT = 66.0
BLOCK_RADIUS = 18.0
COLUMN_X = (136.0, 268.0)
ROW_Y = (182.0, 264.0, 346.0)

# Foreground layer is scaled down so the glyph fits the 66dp adaptive safe
# circle (the same artwork is used for the monochrome/themed layer).
ADAPTIVE_SCALE = 0.82


def _scaled(rect: tuple[float, float, float, float, float], scale: float):
    x, y, w, h, r = rect
    cx = cy = DESIGN / 2
    return (
        cx + (x - cx) * scale,
        cy + (y - cy) * scale,
        w * scale,
        h * scale,
        r * scale,
    )


def glyph_rects(scale: float = 1.0) -> list[tuple[float, float, float, float, float]]:
    rects = [HEADER_BAR]
    for y in ROW_Y:
        for x in COLUMN_X:
            rects.append((x, y, BLOCK_WIDTH, BLOCK_HEIGHT, BLOCK_RADIUS))
    return [_scaled(rect, scale) for rect in rects]


def glyph_paths(scale: float = 1.0) -> list[str]:
    return [rounded_rect(*rect) for rect in glyph_rects(scale)]


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
    "logo-monochrome.svg": adaptive_foreground_svg(),
    "logo-mark.svg": mark_svg(),
}

RENDER_PAGES = {
    "icon.png": icon_svg(),
    "adaptive-background.png": adaptive_background_svg(),
    "adaptive-foreground.png": adaptive_foreground_svg(),
    "monochrome.png": adaptive_foreground_svg(),
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
