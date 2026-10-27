#!/usr/bin/env python3
"""couture-s2 coin-2 burst + ground shadow sprites (256 px, drawn 8x, box-filter downsample = anti-aliased).
Writes content/textures/sources/images/couture-s2-burst-{star,plume}.png and couture-s2-shadow-spikes.png,
then `./gradlew -q :tools:item-art:run`.
 star  : hot white core + 26 pointed rays (long/short alternating, uneven), amber fringe. Centre (128,128).
 plume : fan of 11 pointed rays from the centre to +x (base at the display centre (128,128), rays only to +x (up to 124 px)), amber fringe.
 shadow: lying on the ground (rotate x 90): ragged dark spikes + core, near-black violet.
"""
import math, os, random
import numpy as np
from PIL import Image, ImageDraw

N, SS = 256, 8
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "content", "textures", "sources", "images")


def canvas():
    return Image.new("L", (N * SS, N * SS), 0)


def tri(d, cx, cy, ang, length, half, r0=0.0, grow=1.0):
    """pointed spike: base at distance r0 (half width `half`), tip at `length`; slightly concave sides."""
    ca, sa = math.cos(ang), math.sin(ang)
    def P(a, b):
        return ((cx + a * ca - b * sa) * SS, (cy + a * sa + b * ca) * SS)
    pts = [P(r0, -half), P(r0 + (length - r0) * 0.45, -half * 0.42 * grow), P(length, 0),
           P(r0 + (length - r0) * 0.45, half * 0.42 * grow), P(r0, half)]
    d.polygon(pts, fill=255)


def disc(d, cx, cy, r):
    d.ellipse([(cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS], fill=255)


def down(im):
    return np.asarray(im.resize((N, N), Image.BOX)).astype(np.float32) / 255


def rgba(cover, rim, col_core, col_rim):
    """cover: white shape mask; rim: slightly larger mask (fringe). premultiplied-safe compose."""
    out = np.zeros((N, N, 4), np.float32)
    a_r = rim
    out[..., :3] = np.array(col_rim) / 255.0
    out[..., 3] = a_r
    core = cover[..., None]
    out[..., :3] = out[..., :3] * (1 - core) + np.array(col_core) / 255.0 * core
    out[..., 3] = np.maximum(a_r, cover)
    return Image.fromarray((np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA")


def star():
    rnd = random.Random(7)
    ca, cb = canvas(), canvas()
    da, db = ImageDraw.Draw(ca), ImageDraw.Draw(cb)
    n = 26
    for i in range(n):
        ang = (i + rnd.uniform(-0.25, 0.25)) * 2 * math.pi / n
        long_ = i % 2 == 0
        L = rnd.uniform(108, 126) if long_ else rnd.uniform(56, 86)
        hw = rnd.uniform(5.5, 8) if long_ else rnd.uniform(4, 6)
        tri(da, 128, 128, ang, L, hw)
        tri(db, 128, 128, ang, L + 5, hw + 2.2)
    disc(da, 128, 128, 22); disc(db, 128, 128, 27)
    return rgba(down(ca), down(cb) * 0.9, (255, 255, 252), (255, 176, 70))


def plume():
    rnd = random.Random(11)
    ca, cb = canvas(), canvas()
    da, db = ImageDraw.Draw(ca), ImageDraw.Draw(cb)
    n = 17
    for i in range(n):
        ang = math.radians(-26 + 52 * i / (n - 1) + rnd.uniform(-1.2, 1.2))
        mid = 1 - abs(i - (n - 1) / 2) / ((n - 1) / 2)
        L = 84 + 40 * mid * rnd.uniform(0.85, 1.0) - (26 if i % 2 else 0)
        hw = rnd.uniform(3.2, 5.0)
        tri(da, 128, 128, ang, L, hw, r0=0, grow=0.8)
        tri(db, 128, 128, ang, L + 4, hw + 1.6, r0=0, grow=0.8)
    disc(da, 128, 128, 8); disc(db, 128, 128, 11)
    return rgba(down(ca), down(cb) * 0.9, (255, 255, 252), (255, 176, 70))


def shadow():
    rnd = random.Random(23)
    ca = canvas(); da = ImageDraw.Draw(ca)
    n = 11
    base = 0.0
    for i in range(n):
        base += (2 * math.pi / n) * rnd.uniform(0.7, 1.3)
        L = rnd.choice([58, 76, 96, 114, 124]) * rnd.uniform(0.92, 1.04)
        hw = rnd.uniform(5, 8.5) * (L / 100) ** 0.5
        tri(da, 128, 128, base, L, hw, r0=0, grow=0.9)
    disc(da, 128, 128, 15)
    cov = down(ca)
    out = np.zeros((N, N, 4), np.float32)
    # near-black violet, a hair lighter toward the tips
    yy, xx = np.mgrid[0:N, 0:N]
    r = np.hypot(xx - 127.5, yy - 127.5) / 128
    for c, (lo, hi) in enumerate(((10, 30), (6, 18), (18, 44))):
        out[..., c] = (lo + (hi - lo) * np.clip(r, 0, 1)) / 255
    out[..., 3] = cov * 0.96
    return Image.fromarray((out * 255 + 0.5).astype(np.uint8), "RGBA")


if __name__ == "__main__":
    for name, im in (("burst-star", star()), ("burst-plume", plume()), ("shadow-spikes", shadow())):
        im.save(os.path.join(OUT, f"couture-s2-{name}.png"))
        print(name, im.size)
