#!/usr/bin/env python3
"""Render the geometry library's pattern catalogue (docs/assets/geometric-patterns-catalog.png).

  python3 tools/item-art/scripts/geometric-catalog.py [out.png]

One 48x48 swatch per pattern, nearest-neighbour upscaled. Formulas: docs/art/geometric-patterns.md.
"""
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
import geometry as g  # noqa: E402

ROOT = Path(__file__).resolve().parents[3]
N, SCALE = 48, 4
COL = {0: (16, 14, 26), 1: (230, 170, 60), 2: (110, 200, 255), 3: (255, 120, 200), 4: (255, 255, 255)}


def swatch(fn):
    X, Y = g.grid(N, N)
    lv = fn(X, Y)
    if lv.dtype == bool:
        lv = lv.astype(int)
    img = np.zeros((N, N, 3), np.uint8)
    for k, c in COL.items():
        img[lv == k] = c
    return Image.fromarray(img)


def cells():
    c = N / 2
    yield "checker", lambda X, Y: g.checker(X, Y, 6)
    yield "dot_lattice", lambda X, Y: g.dot_lattice(X, Y, 6, 1.6)
    yield "concentric", lambda X, Y: g.concentric(X, Y, c, c, 5)
    yield "moire", lambda X, Y: g.moire(X, Y, (c - 3, c), (c + 3, c), 5)
    yield "meander", lambda X, Y: g.meander(X, Y, 12, 6) | g.meander(X, Y, 16, 28)
    yield "braid", lambda X, Y: g.braid(X, Y, 16, c, 6, 3)
    yield "ring_chain", lambda X, Y: g.ring_chain(X, Y, 14, c, 10)
    yield "diamond_chain", lambda X, Y: g.diamond_chain(X, Y, 12, c, 6, 8)
    yield "scallops", lambda X, Y: g.scallops(X, Y, 12, 14, 6) | g.scallops(X, Y, 12, 26, 6)
    yield "rose k=5", lambda X, Y: g.rose(X, Y, c, c, 5, 22)
    yield "rose k=4", lambda X, Y: g.rose(X, Y, c, c, 4, 22)
    yield "spirograph 7,3", lambda X, Y: g.spirograph(X, Y, c, c, 7, 3, 4.5)
    yield "spirograph 5,2", lambda X, Y: g.spirograph(X, Y, c, c, 5, 2, 3.5)
    yield "lissajous 3:2", lambda X, Y: g.lissajous(X, Y, c, c, 21, 21, 3, 2)
    yield "star {5/2}", lambda X, Y: g.star_outline(X, Y, 5, 2, 22, c, c)
    yield "star {8/3}", lambda X, Y: g.star_outline(X, Y, 8, 3, 22, c, c)
    yield "star {12/5}", lambda X, Y: g.star_outline(X, Y, 12, 5, 22, c, c)
    yield "star fill {8/3}", lambda X, Y: g.star_fill(X, Y, 8, 3, 22, c, c)
    yield "islamic star lattice", lambda X, Y: g.islamic_star_lattice(X, Y, 24, 3)
    yield "asanoha", lambda X, Y: g.asanoha(X, Y, 24)
    yield "shippo", lambda X, Y: g.shippo(X, Y, 16)
    yield "seigaiha", lambda X, Y: g.seigaiha_levels(X, Y, 12, 3)
    yield "kagome", lambda X, Y: g.kagome(X, Y, 12)
    yield "rosette", lambda X, Y: g.rosette_levels(X, Y, c, c, 8, 22)
    yield "stair", lambda X, Y: g.stair(X, Y, 5, 5)
    yield "ticks", lambda X, Y: g.ticks(X, Y, 6, 10, 38)
    yield "dither (Bayer)", lambda X, Y: g.dither(X / N, X, Y)


def main():
    items = list(cells())
    cols = 6
    rows = math.ceil(len(items) / cols)
    cw, ch = N * SCALE + 16, N * SCALE + 36
    sheet = Image.new("RGB", (cols * cw + 16, rows * ch + 16), (40, 40, 52))
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 16)
    except OSError:
        font = ImageFont.load_default()
    for i, (name, fn) in enumerate(items):
        x, y = 16 + (i % cols) * cw, 16 + (i // cols) * ch
        sheet.paste(swatch(fn).resize((N * SCALE, N * SCALE), Image.NEAREST), (x, y + 24))
        d.text((x, y), name, fill=(235, 235, 235), font=font)
    out = ROOT / (sys.argv[1] if len(sys.argv) > 1 else "docs/assets/geometric-patterns-catalog.png")
    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out)


if __name__ == "__main__":
    main()
