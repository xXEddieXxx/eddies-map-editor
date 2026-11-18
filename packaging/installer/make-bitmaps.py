"""Draws the installer's two pictures in the editor's colors, from packaging/icon.ico.

  python packaging/installer/make-bitmaps.py

dialog.bmp (493 x 312) is the Welcome and Finish page: Windows Installer writes its black text
on the right of it, so only the left 164 px carry the picture. banner.bmp (493 x 58) is the strip
on top of the other pages, the text on its left. Run it again after a change of icon or colors.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
DARK = (0x1E, 0x1F, 0x22)      # the dark theme's background
PANEL = (0x2B, 0x2D, 0x30)     # its component background
ACCENT = (0x2F, 0x6F, 0xEB)    # the accent of links and chosen things
LIGHT = (0xF3, 0xF4, 0xF6)     # the light theme's background
TEXT = (0xDF, 0xE1, 0xE5)
SEA = (0x3E, 0x6A, 0x8A)       # the map icons' sea and land
LAND = (0x6E, 0x8B, 0x3D)
S = 4                          # drawn four times as big, then shrunk: smooth edges


def font(name, size):
    return ImageFont.truetype("C:/Windows/Fonts/" + name, size * S)


def icon(size):
    return Image.open(HERE.parent / "icon.ico").convert("RGBA").resize(
        (size * S, size * S), Image.LANCZOS)


def territories(draw, width, top, bottom):
    """A strip of map: sea with a few territories, as the editor's own icons draw them."""
    draw.rectangle([0, top * S, width * S, bottom * S], fill=SEA)
    for points in (
        [(0, 0.35), (0.22, 0.2), (0.4, 0.38), (0.34, 0.72), (0.12, 0.85), (0, 0.7)],
        [(0.48, 0.12), (0.8, 0.05), (1, 0.2), (1, 0.55), (0.72, 0.62), (0.52, 0.42)],
        [(0.42, 0.78), (0.66, 0.72), (0.9, 0.82), (1, 0.78), (1, 1), (0.36, 1)],
    ):
        draw.polygon(
            [(x * width * S, (top + y * (bottom - top)) * S) for x, y in points],
            fill=LAND, outline=DARK, width=S)


def dialog():
    w, h, side = 493, 312, 164
    image = Image.new("RGB", (w * S, h * S), "white")
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, 0, side * S, h * S], fill=DARK)
    territories(draw, side, 196, h)
    draw.rectangle([0, 193 * S, side * S, 196 * S], fill=ACCENT)
    draw.rectangle([side * S, 0, (side + 1) * S, h * S], fill=(0xD0, 0xD3, 0xD9))
    # the icon on a light card, as its shadow is drawn for a light ground
    draw.rounded_rectangle([34 * S, 28 * S, 130 * S, 124 * S], radius=14 * S, fill=LIGHT)
    image.paste(icon(84), (40 * S, 34 * S), icon(84))
    draw.text((82 * S, 146 * S), "Eddie's", font=font("segoeui.ttf", 12), fill=TEXT, anchor="mm")
    draw.text((82 * S, 164 * S), "Map Editor", font=font("seguisb.ttf", 16), fill="white",
              anchor="mm")
    image.resize((w, h), Image.LANCZOS).save(HERE / "dialog.bmp")


def banner():
    w, h = 493, 58
    image = Image.new("RGB", (w * S, h * S), "white")
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, (h - 3) * S, w * S, h * S], fill=ACCENT)
    image.paste(icon(46), ((w - 58) * S, 5 * S), icon(46))
    image.resize((w, h), Image.LANCZOS).save(HERE / "banner.bmp")


dialog()
banner()
