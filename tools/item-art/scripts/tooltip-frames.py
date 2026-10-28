#!/usr/bin/env python3
"""Draw the rarity tooltip sprites (frame + background) from geometry formulas (scripts/geometry).

  python3 tools/item-art/scripts/tooltip-frames.py [--preview docs/assets/tooltip-frames-preview.png]

Writes content/textures/gui/sprites/tooltip/<rarity>_{frame,background}.png (+ .mcmeta.json).

Sprite = 36x36, nine_slice border 12, stretch_inner false. Minecraft (26.x GuiGraphicsExtractor) then TILES the
12 px edge/centre segments instead of stretching them, so every edge is one 12 px-period strip repeated along
the tooltip. The sprite starts 12 px outside the text (TooltipRenderUtil PADDING 3 + MARGIN 9); ornaments use
only the outer 10 px (v 0..9) so the text keeps a 2 px gap.

Per rarity: corner K (12x12, diagonal symmetric, mirrored to the 4 corners) + edge strip E (12 wide x 12 deep,
top edge; left edge = transpose, bottom/right = flips). Level map: 0 clear, 1 glow, 2 dark, 3 rarity colour,
4 light, 5 white, 6 deep. Each tier uses a structurally different motif (not a recolour):
  common line+chamfer, uncommon double line+stair+ticks, rare diamond chain+checker block,
  epic Greek key meander+octagram, legendary interlaced rings+rosette+glow.
"""
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
import geometry as g  # noqa: E402

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "content/textures/gui/sprites/tooltip"
B = 12          # nine_slice border = tile period
SIZE = 3 * B    # 36

COLORS = {
    "common": (0xFF, 0xFF, 0xFF), "uncommon": (0x55, 0xFF, 0x55), "rare": (0x55, 0xFF, 0xFF),
    "epic": (0xFF, 0x55, 0xFF), "legendary": (0xFF, 0xAA, 0x00),
}
GLOW, DARK, BASE, LIGHT, WHITE, DEEP = 1, 2, 3, 4, 5, 6


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def palette(c):
    return {
        GLOW: (*c, 70), DARK: (*mix(c, (0, 0, 0), 0.35), 255), BASE: (*c, 255),
        LIGHT: (*mix(c, (255, 255, 255), 0.55), 255), WHITE: (255, 255, 255, 255),
        DEEP: (*mix(c, (0, 0, 0), 0.7), 255),
    }


def solid(cv, mask, level=BASE, bevel=True):
    """Fill a mask with `level`, lit rim top-left and shaded rim bottom-right (3 shade levels)."""
    cv.paint(mask, level)
    if bevel:
        light, dark = g.bevel(mask)
        cv.paint(light, LIGHT)
        cv.paint(dark, DARK)


def sym(a):
    return g.sym_diag(a)


# ---------------- common: one line, chamfered corner ----------------

def common_corner(X, Y):
    cv = g.Canvas(B, B)
    cv.paint(g.stroke(g.polyline_dist(X, Y, [(2.5, B), (2.5, 6), (6, 2.5), (B, 2.5)])), BASE)
    return sym(cv.a)


def common_edge(X, Y):
    cv = g.Canvas(3 * B, B)
    cv.paint(Y.astype(int) == 2, BASE)
    return cv.a


# ---------------- uncommon: double line, stair, ticks ----------------

def uncommon_corner(X, Y):
    cv = g.Canvas(B, B)
    cv.paint(g.stroke(g.polyline_dist(X, Y, [(0, 8.5), (1.5, 8.5), (1.5, 6.5), (3.5, 6.5), (3.5, 4.5), (5.5, 4.5),
                                              (5.5, 2.5), (8.5, 2.5), (8.5, 1.5), (B, 1.5)])), BASE)
    cv.paint((Y.astype(int) == 1) | (X.astype(int) == 1), 0)  # clear stray rail where the stair takes over
    cv.paint(g.stroke(g.polyline_dist(X, Y, [(3, B), (3, 8.5), (4.5, 7.5), (7.5, 4.5), (8.5, 3), (B, 3)]), 1.0) & (X > 2) & (Y > 2), DARK)
    cv.paint(g.stroke(g.polyline_dist(X, Y, [(3.5, 3.5), (3.5, 3.5)]), 1.0), LIGHT)
    return sym(cv.a)


def uncommon_edge(X, Y):
    cv = g.Canvas(3 * B, B)
    yi = Y.astype(int)
    cv.paint(yi == 1, BASE)
    cv.paint(yi == 4, DARK)
    cv.paint(g.ticks(X, Y, 6, 2, 3.99, width=1.0, phase=0.5), LIGHT)
    cv.paint((yi == 0) & (X.astype(int) % 6 == 3), LIGHT)
    return cv.a


# ---------------- rare: checker block corner, diamond chain edge ----------------

def rare_corner(X, Y):
    cv = g.Canvas(B, B)
    xi, yi = X.astype(int), Y.astype(int)
    box = (xi <= 9) & (yi <= 9)
    ring = box & ((xi <= 0) | (yi <= 0) | (xi >= 9) | (yi >= 9))
    cv.paint(box, DEEP)
    ck = g.checker(X - 2, Y - 2, 2) & (xi >= 2) & (yi >= 2) & (xi <= 7) & (yi <= 7)
    cv.paint((xi >= 2) & (yi >= 2) & (xi <= 7) & (yi <= 7), DARK)
    cv.paint(ck, LIGHT)
    cv.paint(ring, BASE)
    cv.paint((xi == 1) & (yi >= 1) & (yi <= 8) | (yi == 1) & (xi >= 1) & (xi <= 8), DARK)
    cv.paint((xi == 0) | (yi == 0), BASE)
    cv.paint((xi <= 1) & (yi <= 1), LIGHT)
    # rails continue into the edge strip
    cv.paint((yi == 0) & (xi >= 10), BASE)
    cv.paint((yi == 9) & (xi >= 10), DARK)
    cv.paint((xi == 0) & (yi >= 10), BASE)
    cv.paint((xi == 9) & (yi >= 10), DARK)
    return sym(cv.a)


def rare_edge(X, Y):
    cv = g.Canvas(3 * B, B)
    yi = Y.astype(int)
    cv.paint(yi == 0, BASE)
    cv.paint(yi == 9, DARK)
    d = g.diamond_chain(X, Y, B, 4.5, 5.6, 3.9)
    cv.paint(d, BASE)
    inner = g.diamond_chain(X, Y, B, 4.5, 3.6, 2.2)
    cv.paint(inner, DEEP)
    solid(cv, d & ~inner, BASE)
    dot = g.diamond_chain(X, Y, B, 4.5, 1.6, 1.6)
    cv.paint(dot, LIGHT)
    return cv.a


# ---------------- epic: Greek key edge, octagram corner ----------------

def epic_corner(X, Y):
    cv = g.Canvas(B, B)
    xi, yi = X.astype(int), Y.astype(int)
    c = 5.0
    star = g.star_fill(X, Y, 8, 3, 5.9, c, c, rot=-math.pi / 2 + math.pi / 8)  # octagram {8/3}
    cv.paint(g.outline(star), DARK)
    solid(cv, star, BASE)
    cv.paint(np.hypot(X - c, Y - c) <= 2.4, DEEP)
    cv.paint(np.hypot(X - c, Y - c) <= 1.2, WHITE)
    cv.paint((yi == 1) & (xi >= 10), LIGHT)
    cv.paint((xi == 1) & (yi >= 10), LIGHT)
    return sym(cv.a)


def epic_edge(X, Y):
    cv = g.Canvas(3 * B, B)
    m = g.meander(X, Y, B, 1)
    yi = Y.astype(int)
    solid(cv, m, BASE, bevel=False)
    cv.paint(m & (yi == 1), LIGHT)
    cv.paint(m & (yi == 9), DARK)
    return cv.a


# ---------------- legendary: interlaced rings + rosette, glow ----------------

def legendary_corner(X, Y):
    cv = g.Canvas(B, B)
    xi, yi = X.astype(int), Y.astype(int)
    c = 5.0
    rr = np.hypot(X - c, Y - c)
    disc = rr <= 5.4
    cv.paint(g.outline(disc) | g.outline(g.outline(disc)), GLOW)
    solid(cv, disc & (rr > 4.0), BASE)                                  # outer ring, bevelled
    cv.paint(disc & (rr <= 4.0), DEEP)
    star = g.star_fill(X, Y, 8, 3, 4.1, c, c, rot=-math.pi / 2 + math.pi / 8)
    solid(cv, star, LIGHT, bevel=False)                                 # octagram {8/3} inside
    cv.paint(star & (rr <= 2.4), BASE)
    cv.paint(rr <= 1.5, WHITE)
    # eight studs on the ring
    th = np.arctan2(Y - c, X - c)
    cv.paint((rr > 4.0) & (rr <= 5.4) & (np.abs(np.sin(4 * th)) < 0.12), WHITE)
    cv.paint((yi == 0) & (xi >= 10), LIGHT)
    cv.paint((xi == 0) & (yi >= 10), LIGHT)
    cv.paint((yi == 1) & (xi >= 10), BASE)
    cv.paint((xi == 1) & (yi >= 10), BASE)
    return sym(cv.a)


def legendary_edge(X, Y):
    cv = g.Canvas(3 * B, B)
    yi = Y.astype(int)
    cv.paint((yi == 10), GLOW)
    br = g.braid(X, Y, B, 5.0, 3.0, 2.0)
    cv.paint(br == 1, DEEP)
    cv.paint(br == 2, BASE)
    cv.paint(br == 3, LIGHT)
    cv.paint((br == 2) & (yi >= 6), DARK)
    cv.paint(yi == 0, LIGHT)
    cv.paint(yi == 1, BASE)
    for k in range(3 * B // 6 + 1):  # sparks at the crossings
        cv.paint((np.abs(X - 6 * k) < 1) & (np.abs(Y - 5.0) < 1), WHITE)
    return cv.a


MOTIFS = {
    "common": (common_corner, common_edge),
    "uncommon": (uncommon_corner, uncommon_edge),
    "rare": (rare_corner, rare_edge),
    "epic": (epic_corner, epic_edge),
    "legendary": (legendary_corner, legendary_edge),
}


def frame_levels(name):
    corner_fn, edge_fn = MOTIFS[name]
    X, Y = g.grid(B, B)
    K = corner_fn(X, Y)
    X, Y = g.grid(3 * B, B)
    E = edge_fn(X, Y)[:, B:2 * B]  # one tile, phase 0
    S = np.zeros((SIZE, SIZE), np.int8)
    S[:B, :B] = K
    S[:B, SIZE - B:] = K[:, ::-1]
    S[SIZE - B:, :B] = K[::-1, :]
    S[SIZE - B:, SIZE - B:] = K[::-1, ::-1]
    S[:B, B:2 * B] = E
    S[SIZE - B:, B:2 * B] = E[::-1, :]
    S[B:2 * B, :B] = E.T
    S[B:2 * B, SIZE - B:] = E.T[:, ::-1]
    return S


def levels_to_image(S, c):
    pal = palette(c)
    img = Image.new("RGBA", S.shape[::-1], (0, 0, 0, 0))
    px = img.load()
    for y in range(S.shape[0]):
        for x in range(S.shape[1]):
            if S[y, x] in pal:
                px[x, y] = pal[S[y, x]]
    return img


def background(name, c):
    base = mix((14, 12, 22), c, 0.05)
    edge = mix((14, 12, 22), c, 0.2 if name == "legendary" else 0.14)
    img = np.zeros((SIZE, SIZE, 4), np.uint8)
    yy, xx = np.mgrid[0:SIZE, 0:SIZE]
    depth = np.minimum.reduce([xx, yy, SIZE - 1 - xx, SIZE - 1 - yy]).astype(float)
    t = np.clip(depth / B, 0, 1)[..., None]
    img[..., :3] = (np.array(edge) * (1 - t) + np.array(base) * t).round().astype(np.uint8)
    img[..., 3] = 245
    if name in ("epic", "legendary"):  # faint lattice, period 6 (divides the 12 px tile -> seamless), low contrast
        X, Y = g.grid(SIZE, SIZE)
        lat = g.diamond_chain(X, Y, 6, 3.0, 2.0, 2.0) | g.diamond_chain(X, Y, 6, 6.0, 2.0, 2.0)  # offset rows -> woven diamonds
        lift = np.array(mix((0, 0, 0), c, 0.06 if name == "epic" else 0.08))
        img[lat, :3] = np.clip(img[lat, :3].astype(int) + lift, 0, 255).astype(np.uint8)
    return Image.fromarray(img, "RGBA")


def nine(src, w, h):
    """Minecraft nine_slice with stretch_inner=false: corners 1:1, edges and centre TILED from the segment start."""
    out = Image.new("RGBA", (w, h))
    iw, ih = w - 2 * B, h - 2 * B

    def tile(box, x0, y0, tw, th):
        seg = src.crop(box)
        for yy in range(y0, y0 + th, seg.height):
            for xx in range(x0, x0 + tw, seg.width):
                piece = seg.crop((0, 0, min(seg.width, x0 + tw - xx), min(seg.height, y0 + th - yy)))
                out.paste(piece, (xx, yy))

    tile((0, 0, B, B), 0, 0, B, B)
    tile((SIZE - B, 0, SIZE, B), w - B, 0, B, B)
    tile((0, SIZE - B, B, SIZE), 0, h - B, B, B)
    tile((SIZE - B, SIZE - B, SIZE, SIZE), w - B, h - B, B, B)
    tile((B, 0, 2 * B, B), B, 0, iw, B)
    tile((B, SIZE - B, 2 * B, SIZE), B, h - B, iw, B)
    tile((0, B, B, 2 * B), 0, B, B, ih)
    tile((SIZE - B, B, SIZE, 2 * B), w - B, B, B, ih)
    tile((B, B, 2 * B, 2 * B), B, B, iw, ih)
    return out


def preview(path):
    scale = 3
    sizes = [(84, 30), (168, 78)]  # text box (short, long); sprite spans text + 2*12 - 2*... = text + 24
    pad = 8
    names = list(COLORS)
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 21)
    except OSError:
        font = ImageFont.load_default()
    row_h = (sizes[1][1] + 24) * scale + pad
    width = (sizes[0][0] + 24 + sizes[1][0] + 24) * scale + pad * 3
    sheet = Image.new("RGBA", (width, row_h * len(names) + pad), (84, 120, 84, 255))
    d = ImageDraw.Draw(sheet)
    for i, n in enumerate(names):
        c = COLORS[n]
        fr = levels_to_image(frame_levels(n), c)
        bg = background(n, c)
        x = pad
        for si, (tw, th) in enumerate(sizes):
            w, h = tw + 24, th + 24
            tile_img = nine(bg, w, h)
            tile_img.alpha_composite(nine(fr, w, h))
            big = tile_img.resize((w * scale, h * scale), Image.NEAREST)
            y0 = pad + i * row_h
            sheet.alpha_composite(big, (x, y0))
            tx, ty = x + 12 * scale, y0 + 12 * scale
            d.text((tx, ty - 2), f"{n.capitalize()} Sword", fill=(*c, 255), font=font)
            d.text((tx, ty + 24), "Damage 12  Speed 1.4", fill=(170, 170, 170, 255), font=font)
            if si == 1:
                for k, line in enumerate(("A sample line of lore text.", "Another line of the item story.",
                                          "Bound to the owner on pickup.")):
                    d.text((tx, ty + 48 + k * 24), line, fill=(255, 255, 255, 255), font=font)
            x += w * scale + pad
    path.parent.mkdir(parents=True, exist_ok=True)
    sheet.convert("RGB").save(path)


MCMETA = '{"gui":{"scaling":{"type":"nine_slice","width":%d,"height":%d,"border":%d}}}\n' % (SIZE, SIZE, B)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for n, c in COLORS.items():
        levels_to_image(frame_levels(n), c).save(OUT / f"{n}_frame.png")
        background(n, c).save(OUT / f"{n}_background.png")
        for part in ("frame", "background"):
            (OUT / f"{n}_{part}.mcmeta.json").write_text(MCMETA)
    if "--preview" in sys.argv:
        preview(ROOT / sys.argv[sys.argv.index("--preview") + 1])


if __name__ == "__main__":
    main()
