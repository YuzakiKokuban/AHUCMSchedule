"""Geometry helpers and Edge-based rasterizer for the AHUCM Schedule logo.

All geometry is expressed in a 512 x 512 design space.  Paths are emitted as
plain SVG path data, which is also valid Android ``<vector>`` pathData, so the
same string feeds the SVG masters and the Android vector drawables.
"""
from __future__ import annotations

import math
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

DESIGN = 512.0
EDGE_CANDIDATES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]


def _f(v: float) -> str:
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("", "-0") else s


def edge_path() -> str:
    for candidate in EDGE_CANDIDATES:
        if Path(candidate).exists():
            return candidate
    raise SystemExit("Microsoft Edge is required to rasterize the SVG masters")


# ---------------------------------------------------------------------------
# primitives
# ---------------------------------------------------------------------------
def rounded_rect(x: float, y: float, w: float, h: float, r: float) -> str:
    r = min(r, w / 2, h / 2)
    return (
        f"M{_f(x + r)},{_f(y)}"
        f"H{_f(x + w - r)}"
        f"A{_f(r)},{_f(r)} 0 0 1 {_f(x + w)},{_f(y + r)}"
        f"V{_f(y + h - r)}"
        f"A{_f(r)},{_f(r)} 0 0 1 {_f(x + w - r)},{_f(y + h)}"
        f"H{_f(x + r)}"
        f"A{_f(r)},{_f(r)} 0 0 1 {_f(x)},{_f(y + h - r)}"
        f"V{_f(y + r)}"
        f"A{_f(r)},{_f(r)} 0 0 1 {_f(x + r)},{_f(y)}"
        "Z"
    )


def circle(cx: float, cy: float, r: float) -> str:
    return (
        f"M{_f(cx - r)},{_f(cy)}"
        f"a{_f(r)},{_f(r)} 0 1 0 {_f(2 * r)},0"
        f"a{_f(r)},{_f(r)} 0 1 0 {_f(-2 * r)},0"
        "Z"
    )


def _rotate(px: float, py: float, cx: float, cy: float, degrees: float) -> tuple[float, float]:
    a = math.radians(degrees)
    ca, sa = math.cos(a), math.sin(a)
    dx, dy = px - cx, py - cy
    return cx + dx * ca - dy * sa, cy + dx * sa + dy * ca


def leaf_path(cx: float, cy: float, length: float, width: float, angle: float) -> str:
    """Pointed leaf pointing up in local space, rotated by ``angle`` degrees."""
    w = width / 2.0
    L = length
    local = [
        (0.0, 0.0),
        (-w, -0.20 * L),
        (-w, -0.64 * L),
        (0.0, -L),
        (w, -0.64 * L),
        (w, -0.20 * L),
        (0.0, 0.0),
    ]
    pts = [_rotate(cx + x, cy + y, cx, cy, angle) for x, y in local]
    return (
        f"M{_f(pts[0][0])},{_f(pts[0][1])}"
        f"C{_f(pts[1][0])},{_f(pts[1][1])} {_f(pts[2][0])},{_f(pts[2][1])} {_f(pts[3][0])},{_f(pts[3][1])}"
        f"C{_f(pts[4][0])},{_f(pts[4][1])} {_f(pts[5][0])},{_f(pts[5][1])} {_f(pts[6][0])},{_f(pts[6][1])}"
        "Z"
    )


def leaf_veins(cx: float, cy: float, length: float, width: float, angle: float,
               pairs: int = 3) -> list[tuple[float, float, float, float]]:
    """Return centre line plus side vein segments in global coordinates."""
    w = width / 2.0
    L = length

    def t(px: float, py: float) -> tuple[float, float]:
        return _rotate(cx + px, cy + py, cx, cy, angle)

    segments: list[tuple[float, float, float, float]] = []
    a = t(0.0, -0.06 * L)
    b = t(0.0, -0.94 * L)
    segments.append((a[0], a[1], b[0], b[1]))
    steps = [0.26, 0.46, 0.66, 0.86][:pairs]
    for i, p in enumerate(steps):
        reach = w * (1.0 - p) * 0.72
        rise = L * 0.09
        for sign in (-1.0, 1.0):
            s = t(sign * 0.02 * w, -p * L)
            e = t(sign * reach, (-p - rise / L) * L)
            segments.append((s[0], s[1], e[0], e[1]))
    return segments


# ---------------------------------------------------------------------------
# SVG assembly
# ---------------------------------------------------------------------------
def svg_document(body: str, extra_defs: str = "", size: int = 1024) -> str:
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
        f'viewBox="0 0 512 512">'
        f"<defs>{extra_defs}</defs>"
        f"{body}"
        "</svg>"
    )


BG_GRADIENT = (
    '<linearGradient id="bg" x1="0.12" y1="0" x2="0.88" y2="1">'
    '<stop offset="0" stop-color="#0B5E46"/>'
    '<stop offset="0.55" stop-color="#128A64"/>'
    '<stop offset="1" stop-color="#25B98A"/>'
    "</linearGradient>"
)
GOLD_GRADIENT = (
    '<linearGradient id="gold" x1="0.1" y1="0.1" x2="0.9" y2="0.9">'
    '<stop offset="0" stop-color="#FFE2A0"/>'
    '<stop offset="0.5" stop-color="#F6C25C"/>'
    '<stop offset="1" stop-color="#E09A32"/>'
    "</linearGradient>"
)
WHITE_GRADIENT = (
    '<linearGradient id="white" x1="0" y1="0" x2="0.35" y2="1">'
    '<stop offset="0" stop-color="#FFFFFF"/>'
    '<stop offset="1" stop-color="#EAF7F1"/>'
    "</linearGradient>"
)


def write_svg(path: Path, body: str, extra_defs: str, size: int = 1024) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(svg_document(body, extra_defs, size), encoding="utf-8")


# ---------------------------------------------------------------------------
# rasterization input (rendering itself is driven by render.ps1)
# ---------------------------------------------------------------------------
def write_html(svg_text: str, html_path: Path) -> None:
    """Wrap SVG markup in a transparent page for headless Edge screenshots."""
    html_path.parent.mkdir(parents=True, exist_ok=True)
    html_path.write_text(
        '<!doctype html><html><head><meta charset="utf-8">'
        "<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}"
        "svg{display:block}</style></head><body>"
        + svg_text
        + "</body></html>",
        encoding="utf-8",
    )


def write_body_html(body: str, defs: str, html_path: Path, size: int = 1024) -> None:
    write_html(svg_document(body, defs, size), html_path)


# ---------------------------------------------------------------------------
# icon masking helpers
# ---------------------------------------------------------------------------
def mask_squircle(img, radius_ratio: float = 0.219):
    """Apply a rounded-square alpha mask in place (design ratio 112/512)."""
    from PIL import Image, ImageDraw

    w, h = img.size
    mask = Image.new("L", (w * 4, h * 4), 0)
    d = ImageDraw.Draw(mask)
    r = int(min(w, h) * radius_ratio * 4)
    d.rounded_rectangle((0, 0, w * 4 - 1, h * 4 - 1), radius=r, fill=255)
    mask = mask.resize((w, h), Image.LANCZOS)
    img.putalpha(Image.composite(img.getchannel("A"), Image.new("L", (w, h), 0), mask))
    return img


def mask_circle(img):
    from PIL import Image, ImageDraw

    w, h = img.size
    mask = Image.new("L", (w * 4, h * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, w * 4 - 1, h * 4 - 1), fill=255)
    mask = mask.resize((w, h), Image.LANCZOS)
    img.putalpha(Image.composite(img.getchannel("A"), Image.new("L", (w, h), 0), mask))
    return img
