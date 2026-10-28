#!/usr/bin/env python3
"""Draw the rarity tooltip sprites (frame + background) pixel by pixel.

  python3 tools/item-art/scripts/tooltip-frames.py [--preview docs/assets/tooltip-frames-preview.png]

Writes content/textures/gui/sprites/tooltip/<rarity>_{frame,background}.png (64x64, nine_slice border 8).
Each frame is one 8x8 corner pattern, mirrored to the four corners; the stretched edges repeat the pattern's
last column / row, so patterns are made diagonal-symmetric (left edge = top edge). Only the top-right half
(x >= y) of a pattern is read.
Pattern key: . clear, G glow, A rarity colour, B dark shade, C highlight, W white spark.
Ornament richness escalates with rarity. Rarity colours = astera.item.tooltip.rarity in en.yaml.
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "content/textures/gui/sprites/tooltip"
SIZE, BORDER = 64, 8

RARITIES = {  # name -> (colour, corner pattern rows)
    "common": ((0xFF, 0xFF, 0xFF), [
        "........",
        "........",
        "...AAAAA",
        "..A.....",
        "..A.....",
        "..A.....",
        "..A.....",
        "..A.....",
    ]),
    "uncommon": ((0x55, 0xFF, 0x55), [
        "........",
        "...AAAAA",
        "..ABBBBB",
        ".AB.....",
        ".AB.....",
        ".AB.....",
        ".AB.....",
        ".AB.....",
    ]),
    "rare": ((0x55, 0xFF, 0xFF), [
        "........",
        ".CAAAAAA",
        ".C......",
        ".A.BBBBB",
        ".A.B....",
        ".A.B....",
        ".A.B....",
        ".A.B....",
    ]),
    "epic": ((0xFF, 0x55, 0xFF), [
        "..CC....",
        ".CAAAAAA",
        ".C.C....",
        ".A.CBBBB",
        ".A.B....",
        ".A.B....",
        ".A.B....",
        ".A.B....",
    ]),
    "legendary": ((0xFF, 0xAA, 0x00), [
        "GGGGGGGG",
        "GWCCCCCC",
        "GCAAAAAA",
        "GCA.....",
        "GCA.CBBB",
        "GCA.B...",
        "GCA.B...",
        "GCA.B...",
    ]),
}


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def palette(c):
    return {
        "G": (*c, 70),
        "A": (*c, 255),
        "B": (*mix(c, (0, 0, 0), 0.35), 255),
        "C": (*mix(c, (255, 255, 255), 0.55), 255),
        "W": (255, 255, 255, 255),
    }


def frame(c, pat):
    pal = palette(c)
    grid = [list(r) for r in pat]
    for y in range(8):
        for x in range(y):
            grid[y][x] = grid[x][y]
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()

    def put(x, y, ch):
        if ch in pal:
            px[x, y] = pal[ch]

    for y in range(8):
        for x in range(8):
            ch = grid[y][x]
            put(x, y, ch)
            put(SIZE - 1 - x, y, ch)
            put(x, SIZE - 1 - y, ch)
            put(SIZE - 1 - x, SIZE - 1 - y, ch)
    for y in range(8):  # edges repeat the last column / row
        ch = grid[y][7]
        for x in range(8, SIZE - 8):
            put(x, y, ch)
            put(x, SIZE - 1 - y, ch)
            put(y, x, ch)
            put(SIZE - 1 - y, x, ch)
    return img


def background(c):
    base = mix((14, 12, 22), c, 0.05)
    edge = mix((14, 12, 22), c, 0.13)
    img = Image.new("RGBA", (SIZE, SIZE), (*base, 245))
    d = ImageDraw.Draw(img)
    for i in range(6):  # faint rarity tint creeping in from the frame (uniform along each edge)
        d.rectangle([i, i, SIZE - 1 - i, SIZE - 1 - i], outline=(*mix(edge, base, i / 6), 245))
    return img


def nine(src, w, h):
    b = BORDER
    out = Image.new("RGBA", (w, h))
    iw, ih = w - 2 * b, h - 2 * b

    def cut(box, size, dst):
        out.paste(src.crop(box).resize(size, Image.NEAREST), dst)

    cut((0, 0, b, b), (b, b), (0, 0))
    cut((SIZE - b, 0, SIZE, b), (b, b), (w - b, 0))
    cut((0, SIZE - b, b, SIZE), (b, b), (0, h - b))
    cut((SIZE - b, SIZE - b, SIZE, SIZE), (b, b), (w - b, h - b))
    cut((b, 0, SIZE - b, b), (iw, b), (b, 0))
    cut((b, SIZE - b, SIZE - b, SIZE), (iw, b), (b, h - b))
    cut((0, b, b, SIZE - b), (b, ih), (0, b))
    cut((SIZE - b, b, SIZE, SIZE - b), (b, ih), (w - b, b))
    cut((b, b, SIZE - b, SIZE - b), (iw, ih), (b, b))
    return out


def preview(path):
    scale, w, h = 3, 150, 64
    names = list(RARITIES)
    sheet = Image.new("RGBA", (w * scale + 24, (h * scale + 16) * len(names) + 8), (90, 130, 90, 255))
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 22)
    except OSError:
        font = ImageFont.load_default()
    d = ImageDraw.Draw(sheet)
    for i, n in enumerate(names):
        c, pat = RARITIES[n]
        tile = nine(background(c), w, h)
        tile.alpha_composite(nine(frame(c, pat), w, h))
        y0 = 8 + i * (h * scale + 16)
        sheet.alpha_composite(tile.resize((w * scale, h * scale), Image.NEAREST), (12, y0))
        tx, ty = 12 + 8 * scale + 6, y0 + 8 * scale
        d.text((tx, ty), n.capitalize() + " Sword", fill=(*c, 255), font=font)
        d.text((tx, ty + 30), "Damage 12  Speed 1.4", fill=(170, 170, 170, 255), font=font)
        d.text((tx, ty + 60), "A sample line of lore text.", fill=(255, 255, 255, 255), font=font)
    path.parent.mkdir(parents=True, exist_ok=True)
    sheet.convert("RGB").save(path)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for n, (c, pat) in RARITIES.items():
        frame(c, pat).save(OUT / f"{n}_frame.png")
        background(c).save(OUT / f"{n}_background.png")
    if "--preview" in sys.argv:
        preview(ROOT / sys.argv[sys.argv.index("--preview") + 1])


if __name__ == "__main__":
    main()
