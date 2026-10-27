#!/usr/bin/env python3
"""bastion-s3 pillar r4: BLADE-shaped spikes + thick angular cross bar (straight edges, no curves).
Writes content/textures/sources/images/bastion-s3-pillar-spikes.png (2 frames) and -pillar-bar.png, then
`./gradlew -q :tools:item-art:run`. 256 px frames, drawn 4x, premultiplied downsample (anti-aliased).

    python3 tools/item-art/scripts/bastion_s3_pillar_blades.py [spikes bar]

Sprite scale 1 = 6.4 blocks => 40 px per block. spikes: origin at (128, 236) (ground contact), shards up/outward,
1.5-3 blocks long, base half width 9-14 px (>= 0.45 block wide). bar: 224 x 30 px = 5.6 x 0.75 block at scale 1.
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(__file__))
from bastion_s3_sprites import SS, N, comp, down, hexc, to_img  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "content", "textures", "sources", "images")


def save(name, frames):
    strip = Image.new("RGBA", (N * len(frames), N), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        strip.paste(f, (i * N, 0))
    strip.save(os.path.join(OUT, f"bastion-s3-{name}.png"))
    print(f"bastion-s3-{name}: {len(frames)} frames")


def layer(polys):
    im = Image.new("L", (N * SS, N * SS), 0)
    d = ImageDraw.Draw(im)
    for p in polys:
        d.polygon([(x * SS, y * SS) for x, y in p], fill=255)
    return np.asarray(im).astype(np.float32) / 255


def blade(ox, oy, ang_deg, length, hw):
    """Straight triangle blade from base centre (ox, oy); ang from straight up (+ = right)."""
    a = math.radians(ang_deg)
    dx, dy = math.sin(a), -math.cos(a)
    nx, ny = -dy, dx
    b0 = (ox - dx * 4, oy - dy * 4)
    tip = (ox + dx * length, oy + dy * length)
    return [(b0[0] + nx * hw, b0[1] + ny * hw), tip, (b0[0] - nx * hw, b0[1] - ny * hw)]


def spikes_frame(specs, ox=128.0, oy=236.0):
    dark, gold, pale, white = [], [], [], []
    for sx, ang, L, hw in specs:
        x = ox + sx
        dark.append(blade(x, oy, ang, L, hw))
        gold.append(blade(x, oy - 1, ang, L * 0.95, hw * 0.66))
        pale.append(blade(x, oy - 2, ang, L * 0.8, hw * 0.36))
        white.append(blade(x, oy - 3, ang, L * 0.55, hw * 0.16))
    dst = np.zeros((N * SS, N * SS, 4), np.float32)
    dst = comp(dst, hexc("#120E1C"), layer(dark))
    dst = comp(dst, hexc("#F2B31F"), layer(gold))
    dst = comp(dst, hexc("#FFE45A"), layer(pale))
    dst = comp(dst, hexc("#FFFFFF"), layer(white))
    ys, xs = np.mgrid[0:N * SS, 0:N * SS]
    r = np.hypot((xs + 0.5) / SS - ox, ((ys + 0.5) / SS - oy) * 1.6)
    dst = comp(dst, hexc("#FFF6C8"), np.clip((16 - r) / 4, 0, 1))
    return down(to_img(dst))


# (base x offset, angle from up, length px, base half width px); 40 px = 1 block
A = [(-26, -78, 80, 11), (-14, -52, 112, 13), (-5, -26, 96, 12), (4, -5, 120, 14), (12, 20, 100, 12),
     (20, 46, 116, 13), (28, 70, 76, 11), (-30, -64, 62, 9), (30, 58, 66, 9)]
B = [(-24, -70, 92, 12), (-12, -40, 104, 13), (-3, -14, 120, 14), (6, 12, 98, 12), (14, 36, 112, 13),
     (24, 62, 84, 12), (-28, -84, 64, 9), (32, 80, 60, 9), (0, 28, 70, 9)]


def make_spikes():
    save("pillar-spikes", [spikes_frame(A), spikes_frame(B)])


def make_bar():
    cx, cy = 128.0, 128.0
    L, H = 112.0, 15.0

    def hexa(k, kl=1.0):
        l, h = L * kl, H * k
        return [(cx - l, cy), (cx - l * 0.62, cy - h), (cx + l * 0.62, cy - h), (cx + l, cy),
                (cx + l * 0.62, cy + h), (cx - l * 0.62, cy + h)]

    dst = np.zeros((N * SS, N * SS, 4), np.float32)
    dst = comp(dst, hexc("#120E1C"), layer([hexa(1.0)]))
    dst = comp(dst, hexc("#F2B31F"), layer([hexa(0.74, 0.95)]))
    dst = comp(dst, hexc("#FFE45A"), layer([hexa(0.5, 0.88)]))
    dst = comp(dst, hexc("#FFFFFF"), layer([hexa(0.22, 0.7)]))
    save("pillar-bar", [down(to_img(dst))])


MAKERS = {"spikes": make_spikes, "bar": make_bar}
if __name__ == "__main__":
    for nm in (sys.argv[1:] or MAKERS):
        MAKERS[nm]()
