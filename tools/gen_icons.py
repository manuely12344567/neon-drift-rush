#!/usr/bin/env python3
"""Generates the legacy launcher icons (PNG mipmaps) for NEON DRIFT.

The adaptive icon (API 26+) is a vector drawable; older launchers fall back to
these bitmaps. Run from the project root:

    python3 tools/gen_icons.py
"""

import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

BG_TOP = (42, 7, 72)
BG_BOTTOM = (6, 2, 15)
CYAN = (46, 242, 255)
MAGENTA = (255, 46, 136)
WHITE = (242, 247, 255)


def gradient(size):
    img = Image.new("RGB", (size, size), BG_BOTTOM)
    d = ImageDraw.Draw(img)
    for y in range(size):
        k = y / max(1, size - 1)
        col = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * (k ** 0.85))
                    for i in range(3))
        d.line([(0, y), (size, y)], fill=col)
    return img


def ship_points(size, scale=1.0, dy=0.0):
    """Ship silhouette in a 108x108 space scaled to `size`."""
    pts = [(54, 26), (61, 50), (78, 64), (60, 59), (54, 74),
           (48, 59), (30, 64), (47, 50)]
    out = []
    for x, y in pts:
        sx = 54 + (x - 54) * scale
        sy = 54 + (y - 54) * scale + dy
        out.append((sx * size / 108.0, sy * size / 108.0))
    return out


def make_icon(size, round_mask=False):
    ss = 4  # supersampling
    big = size * ss
    img = gradient(big).convert("RGBA")

    # horizon glow band
    glow = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.rectangle([0, int(big * 0.585), big, int(big * 0.605)], fill=MAGENTA + (190,))
    glow = glow.filter(ImageFilter.GaussianBlur(big * 0.02))
    img = Image.alpha_composite(img, glow)

    # outer neon ring
    ring = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    rd = ImageDraw.Draw(ring)
    rd.ellipse([big * 0.10, big * 0.10, big * 0.90, big * 0.90],
               outline=CYAN + (110,), width=int(big * 0.012))
    ring = ring.filter(ImageFilter.GaussianBlur(big * 0.012))
    img = Image.alpha_composite(img, ring)

    # ship glow
    halo = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    ImageDraw.Draw(halo).polygon(ship_points(big), fill=CYAN + (160,))
    halo = halo.filter(ImageFilter.GaussianBlur(big * 0.055))
    img = Image.alpha_composite(img, halo)
    img = Image.alpha_composite(img, halo)

    # ship body
    body = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    bd = ImageDraw.Draw(body)
    bd.polygon(ship_points(big), fill=WHITE + (255,))
    img = Image.alpha_composite(img, body)

    # outline + cockpit + exhaust
    line = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    ld = ImageDraw.Draw(line)
    ld.polygon(ship_points(big), outline=CYAN + (255,), width=int(big * 0.014))
    # cockpit: slim blade in the upper half of the hull
    cockpit = [(54, 33), (57.5, 52), (54, 57), (50.5, 52)]
    ld.polygon([(x * big / 108.0, y * big / 108.0) for x, y in cockpit],
               fill=CYAN + (255,))
    # exhaust flame under the tail
    flame = [(49.5, 74), (58.5, 74), (54, 88)]
    ld.polygon([(x * big / 108.0, y * big / 108.0) for x, y in flame],
               fill=MAGENTA + (240,))
    img = Image.alpha_composite(img, line)

    img = img.resize((size, size), Image.LANCZOS)

    if round_mask:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(img, (0, 0), mask)
        return out

    # slightly rounded square
    r = int(size * 0.22)
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1],
                                           radius=r * 4, fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def main():
    for folder, size in SIZES.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        make_icon(size).save(os.path.join(d, "ic_launcher.png"))
        make_icon(size, True).save(os.path.join(d, "ic_launcher_round.png"))
        print("  %-16s %dx%d" % (folder, size, size))
    # a large icon used for the store / readme
    os.makedirs(os.path.join(ROOT, "screenshots"), exist_ok=True)
    make_icon(512).save(os.path.join(ROOT, "screenshots", "icon_512.png"))
    print("  screenshots/icon_512.png")


if __name__ == "__main__":
    main()
