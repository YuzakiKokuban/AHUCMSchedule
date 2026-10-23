"""Build every logo artefact for 安中课表 · AHUCM Schedule.

Two stages, because headless Edge must be driven from PowerShell:

    python design/tools/build_logo.py spec        # SVG / vector XML / HTML pages
    powershell -File design/tools/render.ps1 .local/logo-render/manifest.txt
    python design/tools/build_logo.py export      # PNG / ICO / previews / lockups

``design/tools/build-logo.ps1`` runs the three steps in order.
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import logo_spec as spec

RENDER = spec.RENDER_DIR / "rendered"
DESIGN = spec.DESIGN_DIR
PNG_DIR = DESIGN / "png"

FONT_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"
FONT_REGULAR = r"C:\Windows\Fonts\Deng.ttf"

MONET_PALETTES = [
    ("Jade", "#B8F0DA", "#00382A"),
    ("Sage", "#D9EBD3", "#1F3620"),
    ("Lavender", "#E8DEF8", "#1D192B"),
    ("Peach", "#FFDCC2", "#2E1500"),
    ("Sky", "#D3E4FF", "#001D36"),
    ("Rose", "#FFD9E2", "#3E001D"),
]

ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]

PNG_SIZES = [1024, 512, 256, 192, 128, 96, 64, 48, 32, 16]


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
def load_render(name: str) -> Image.Image:
    path = RENDER / name
    if not path.exists():
        raise SystemExit(f"missing render {path}; run the render stage first")
    return Image.open(path).convert("RGBA")


def resize(img: Image.Image, size: int) -> Image.Image:
    return img.resize((size, size), Image.LANCZOS)


def rounded_mask(size: int, radius_ratio: float) -> Image.Image:
    scale = 4
    mask = Image.new("L", (size * scale, size * scale), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size * scale - 1, size * scale - 1),
        radius=int(size * radius_ratio * scale),
        fill=255,
    )
    return mask.resize((size, size), Image.LANCZOS)


def circle_mask(size: int) -> Image.Image:
    scale = 4
    mask = Image.new("L", (size * scale, size * scale), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * scale - 1, size * scale - 1), fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def rounded_mask_full(width: int, height: int, radius_ratio: float) -> Image.Image:
    scale = 2
    mask = Image.new("L", (width * scale, height * scale), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, width * scale - 1, height * scale - 1),
        radius=int(min(width, height) * radius_ratio * scale),
        fill=255,
    )
    return mask.resize((width, height), Image.LANCZOS)


def apply_mask(img: Image.Image, mask: Image.Image) -> Image.Image:
    out = img.copy()
    out.putalpha(Image.composite(out.getchannel("A"), Image.new("L", img.size, 0), mask))
    return out


def font(path: str, size: int, index: int = 0) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, size, index=index)


# ---------------------------------------------------------------------------
# exports
# ---------------------------------------------------------------------------
def export_png_set(icon: Image.Image) -> list[Path]:
    PNG_DIR.mkdir(parents=True, exist_ok=True)
    written = []
    for size in PNG_SIZES:
        path = PNG_DIR / f"logo-{size}.png"
        resize(icon, size).save(path)
        written.append(path)
    return written


def export_ico(icon: Image.Image) -> list[Path]:
    written = []
    ico = DESIGN / "logo.ico"
    icon.save(ico, format="ICO", sizes=[(s, s) for s in ICO_SIZES])
    written.append(ico)
    for target in (
        spec.DESKTOP_RES / "icon.ico",
        spec.DESKTOP_RES / "icon.png",
    ):
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.suffix == ".ico":
            icon.save(target, format="ICO", sizes=[(s, s) for s in ICO_SIZES])
        else:
            resize(icon, 512).save(target)
        written.append(target)
    return written


def export_android_mipmaps(icon: Image.Image) -> list[Path]:
    written = []
    round_icon = apply_mask(icon, circle_mask(icon.size[0]))
    for density, size in spec.iter_mipmap_sizes():
        directory = spec.ANDROID_RES / f"mipmap-{density}"
        directory.mkdir(parents=True, exist_ok=True)
        square = directory / "ic_launcher.png"
        resize(icon, size).save(square)
        round_path = directory / "ic_launcher_round.png"
        resize(round_icon, size).save(round_path)
        written += [square, round_path]
    return written


def export_monochrome(mono: Image.Image) -> list[Path]:
    path = DESIGN / "logo-monochrome.png"
    resize(mono, 512).save(path)
    return [path]


def export_sizes_sheet(icon: Image.Image) -> Path:
    sizes = [96, 64, 48, 32, 24, 16]
    pad, cell = 18, 132
    sheet = Image.new("RGB", (cell * len(sizes), cell * 2), (240, 243, 242))
    for row, background in enumerate(((246, 248, 247), (30, 33, 32))):
        for col, size in enumerate(sizes):
            tile = Image.new("RGBA", (cell, cell), (*background, 255))
            art = resize(icon, size)
            tile.alpha_composite(art, ((cell - size) // 2, (cell - size) // 2))
            sheet.paste(tile.convert("RGB"), (col * cell, row * cell))
    path = DESIGN / "sizes-preview.png"
    sheet.save(path)
    return path


def export_adaptive_preview(bg: Image.Image, fg: Image.Image) -> Path:
    composed = bg.copy()
    composed.alpha_composite(fg)
    size = 240
    tiles: list[tuple[str, Image.Image]] = []
    for label, mask in (
        ("circle", circle_mask(size)),
        ("rounded", rounded_mask(size, 0.22)),
        ("square", rounded_mask(size, 0.10)),
    ):
        art = apply_mask(resize(composed, size), mask)
        tiles.append((label, art))

    # themed (monochrome) variant, tinted with a Monet palette
    mono = load_render("monochrome.png")
    alpha = resize(mono, size).getchannel("A")
    name, bg_color, fg_color = MONET_PALETTES[0]
    themed_bg = Image.new("RGBA", (size, size), bg_color)
    themed_bg.putalpha(rounded_mask(size, 0.22))
    tinted = Image.new("RGBA", (size, size), fg_color)
    tinted.putalpha(alpha)
    themed_bg.alpha_composite(tinted)
    tiles.append((f"themed/{name}", themed_bg))

    pad, label_h = 18, 34
    sheet = Image.new("RGB", (pad + (size + pad) * len(tiles), size + pad * 2 + label_h), (240, 243, 242))
    draw = ImageDraw.Draw(sheet)
    label_font = font(FONT_REGULAR, 17)
    for index, (label, art) in enumerate(tiles):
        x = pad + index * (size + pad)
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        canvas.alpha_composite(art)
        sheet.paste(canvas, (x, pad), canvas)
        draw.text((x + size // 2, pad + size + 8), label, fill=(70, 84, 79), font=label_font, anchor="ma")
    path = DESIGN / "adaptive-preview.png"
    sheet.save(path)
    return path


def export_monet_sheet(mono: Image.Image) -> Path:
    tile, pad, label_h, title_h = 148, 26, 30, 56
    columns = len(MONET_PALETTES)
    width = pad + columns * (tile + pad)
    height = title_h + tile + label_h + pad
    sheet = Image.new("RGB", (width, height), (250, 251, 250))
    draw = ImageDraw.Draw(sheet)
    title_font = font(FONT_BOLD, 24)
    label_font = font(FONT_REGULAR, 16)
    draw.text((pad, 16), "Monet 取色预览 · themed icon", fill=(38, 58, 50), font=title_font)

    alpha = resize(mono, tile).getchannel("A")
    for index, (name, bg_color, fg_color) in enumerate(MONET_PALETTES):
        x = pad + index * (tile + pad)
        y = title_h
        tile_img = Image.new("RGBA", (tile, tile), bg_color)
        tile_img.putalpha(rounded_mask(tile, 0.22))
        tinted = Image.new("RGBA", (tile, tile), fg_color)
        tinted.putalpha(alpha)
        tile_img.alpha_composite(tinted)
        canvas = Image.new("RGBA", (tile, tile), (0, 0, 0, 0))
        canvas.alpha_composite(tile_img)
        sheet.paste(canvas, (x, y), canvas)
        draw.text((x + tile // 2, y + tile + 8), f"{name} {fg_color}", fill=(92, 106, 100), font=label_font, anchor="ma")
    path = DESIGN / "monet-preview.png"
    sheet.save(path)
    return path


def export_lockups(icon: Image.Image) -> list[Path]:
    icon_size = 176
    gap = 40
    pad = 24
    title_font = font(FONT_BOLD, 92)
    sub_font = font(FONT_REGULAR, 38)
    title = "安中课表"
    subtitle = "AHUCM Schedule"

    probe = ImageDraw.Draw(Image.new("RGBA", (8, 8)))
    title_box = probe.textbbox((0, 0), title, font=title_font)
    sub_box = probe.textbbox((0, 0), subtitle, font=sub_font)
    text_w = max(title_box[2] - title_box[0], sub_box[2] - sub_box[0])
    text_h = (title_box[3] - title_box[1]) + 22 + (sub_box[3] - sub_box[1])
    width = pad * 2 + icon_size + gap + text_w
    height = pad * 2 + max(icon_size, text_h)

    written = []
    for variant, title_color, sub_color, background in (
        ("light", spec.INK, spec.INK_SOFT, None),
        ("dark", "#FFFFFF", "#BFE8D6", "#0B2A22"),
    ):
        canvas = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        if background:
            panel = Image.new("RGBA", (width, height), background)
            panel.putalpha(rounded_mask_full(width, height, 0.125))
            canvas.alpha_composite(panel)
        art = resize(icon, icon_size)
        canvas.alpha_composite(art, (pad, (height - icon_size) // 2))
        draw = ImageDraw.Draw(canvas)
        text_x = pad + icon_size + gap
        baseline_y = (height - text_h) // 2
        draw.text((text_x, baseline_y - title_box[1]), title, fill=title_color, font=title_font)
        sub_y = baseline_y + (title_box[3] - title_box[1]) + 22
        draw.text((text_x, sub_y - sub_box[1]), subtitle, fill=sub_color, font=sub_font)
        path = DESIGN / f"logo-lockup-{variant}.png"
        canvas.save(path)
        written.append(path)
    return written


def export_docs_icon(icon: Image.Image) -> Path:
    path = DESIGN / "logo-512.png"
    resize(icon, 512).save(path)
    return path


# ---------------------------------------------------------------------------
# stages
# ---------------------------------------------------------------------------
def stage_spec() -> None:
    for path in spec.write_masters():
        print("svg  ", path.relative_to(spec.REPO))
    for path in spec.write_android_assets():
        print("xml  ", path.relative_to(spec.REPO))
    manifest = spec.write_render_pages()
    print("pages", manifest.relative_to(spec.REPO))


def stage_export() -> None:
    icon = load_render("icon.png")
    background = load_render("adaptive-background.png")
    foreground = load_render("adaptive-foreground.png")
    monochrome = load_render("monochrome.png")

    written: list[Path] = []
    written += export_png_set(icon)
    written += export_ico(icon)
    written += export_android_mipmaps(icon)
    written += export_monochrome(monochrome)
    written.append(export_sizes_sheet(icon))
    written.append(export_adaptive_preview(background, foreground))
    written.append(export_monet_sheet(monochrome))
    written += export_lockups(icon)
    written.append(export_docs_icon(icon))
    for path in written:
        try:
            print("out  ", path.relative_to(spec.REPO))
        except ValueError:
            print("out  ", path)


def main(argv: list[str]) -> int:
    stage = argv[1] if len(argv) > 1 else "spec"
    if stage == "spec":
        stage_spec()
    elif stage == "export":
        stage_export()
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
