#!/usr/bin/env python3
"""bastion-s3 slam / pillar r3 sprites. Writes content/textures/sources/images/bastion-s3-<name>.png (raw strips that
sprites.yaml lists), then `./gradlew -q :tools:item-art:run` copies them. 256 px frames, 4x supersampled, deterministic.

    python3 tools/item-art/scripts/bastion_s3_slam_sprites.py [slam-burst slam-dark pillar-spikes pillar-bar pillar-crackle]

slam-burst     : 3 frames, full-frame white-gold radial burst (rays with gaps, white hot centre), thinning per frame.
slam-dark      : 1 frame, dark translucent radial rays + vignette (centre kept clear) with thin gold shards between.
pillar-spikes  : 2 frames, jagged gold lightning shards rising UP/outward from the bottom-centre (origin y = 236 px).
pillar-bar     : 1 frame, thin crisp horizontal bar (white core, gold halo, dark hairline rim, taper to points).
pillar-crackle : 4 frames, gold floor electricity (zigzag lines along the centre row), dense -> sparse.
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import bastion_s3_sprites as b3  # noqa: E402  (helpers only; its main is guarded)

N, SS = b3.N, b3.SS
n = N * SS
comp, hexc, down, to_img, soft, smooth = b3.comp, b3.hexc, b3.down, b3.to_img, b3.soft, b3.smooth


def save(name, frames):
    strip = Image.new("RGBA", (N * len(frames), N), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        strip.paste(f, (i * N, 0))
    strip.save(os.path.join(b3.OUT, f"bastion-s3-{name}.png"))
    print(f"bastion-s3-{name}: {len(frames)} frames, {N}px")


def blur(a, r):
    return np.asarray(Image.fromarray((np.clip(a, 0, 1) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(r * SS))).astype(np.float32) / 255


def mask_poly(polys):
    im = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(im)
    for p in polys:
        d.polygon([(x * SS, y * SS) for x, y in p], fill=255)
    return np.asarray(im).astype(np.float32) / 255


def ray(cx, cy, ang, r0, r1, half_w, rng, jag=0.0):
    """Tapered jagged ray polygon: width half_w (px) at r0 -> 0 at r1."""
    ca, sa = math.cos(ang), math.sin(ang)
    nx, ny = -sa, ca
    L, R = [], []
    steps = 6
    for i in range(steps + 1):
        u = i / steps
        r = r0 + (r1 - r0) * u
        hw = half_w * (1 - u) ** 0.9 * (1 + jag * rng.uniform(-1, 1) * (0 < i < steps))
        kink = jag * rng.uniform(-1, 1) * 3 * (0 < i < steps)
        px, py = cx + ca * r + nx * kink, cy + sa * r + ny * kink
        L.append((px + nx * hw, py + ny * hw))
        R.append((px - nx * hw, py - ny * hw))
    return L + R[::-1]


def radial():
    ys, xs = np.mgrid[0:n, 0:n]
    X, Y = (xs + 0.5) / SS - N / 2, (ys + 0.5) / SS - N / 2
    return np.hypot(X, Y), X, Y


# ---------------------------------------------------------------------------------------------------------
def burst_frame(k):
    rng = np.random.default_rng(40 + k)
    r, _, _ = radial()
    cx = cy = N / 2
    cnt = [22, 18, 14][k]
    hw = [11.0, 6.5, 3.2][k]
    polys = []
    for i in range(cnt):
        a = 2 * math.pi * i / cnt + rng.uniform(-0.07, 0.07) + k * 0.11
        long = 1.0 if i % 2 == 0 else rng.uniform(0.62, 0.8)
        polys.append(ray(cx, cy, a, 6, 126 * long, hw * (1.0 if i % 2 == 0 else 0.7), rng, 0.25))
    m = blur(mask_poly(polys), 0.5)
    dst = np.zeros((n, n, 4), np.float32)
    t = np.clip(r / 126.0, 0, 1)
    # colour: white hot centre -> pale yellow -> gold at the tips
    for col, lo, hi in (("#FFC93A", 0.0, 1.0),):
        dst = comp(dst, hexc(col), m * (1 - 0.55 * smooth(1 - t)) * 0.95)
    dst = comp(dst, hexc("#FFF0A6"), m * smooth(1 - t * 1.25) * 0.95)
    dst = comp(dst, hexc("#FFFFFF"), m * smooth(1 - t * 2.4))
    glow = [1.0, 0.55, 0.25][k]
    dst = comp(dst, hexc("#FFE98A"), soft(70 - r, 55.0) * 0.5 * glow)
    dst = comp(dst, hexc("#FFFFFF"), soft((60 if k == 0 else 38 if k == 1 else 20) - r, 14.0) * glow)
    return down(to_img(dst))


def make_burst():
    save("slam-burst", [burst_frame(k) for k in range(3)])


# ---------------------------------------------------------------------------------------------------------
def make_dark():
    rng = np.random.default_rng(77)
    r, _, _ = radial()
    cx = cy = N / 2
    polys = []
    cnt = 14
    for i in range(cnt):
        a = 2 * math.pi * (i + 0.5) / cnt + rng.uniform(-0.05, 0.05)
        polys.append(ray(cx, cy, a, 34, 128 * rng.uniform(0.8, 1.0), 13 * rng.uniform(0.8, 1.2), rng, 0.3))
    m = blur(mask_poly(polys), 0.5)
    ring_clear = smooth((r - 26) / 40.0)  # centre stays see-through
    dst = np.zeros((n, n, 4), np.float32)
    vig = smooth((r - 50) / 40.0) * smooth((126 - r) / 36.0) * 0.5
    dst = comp(dst, hexc("#0A0716"), vig)
    dst = comp(dst, hexc("#0A0716"), m * ring_clear * 0.8)
    # thin gold shards between the dark rays
    gp = []
    for i in range(7):
        a = 2 * math.pi * (i + 0.15) / 7 + rng.uniform(-0.1, 0.1)
        gp.append(ray(cx, cy, a, 44, rng.uniform(92, 126), 2.4, rng, 0.35))
    g = blur(mask_poly(gp), 0.4)
    dst = comp(dst, hexc("#FFD65A"), g * 0.85)
    save("slam-dark", [down(to_img(dst))])


# ---------------------------------------------------------------------------------------------------------
def spikes_frame(seed, rot):
    rng = np.random.default_rng(seed)
    ox, oy = N / 2, 238.0
    # (angle deg, y up = -90, length px, base half width): all pointing up / outward, none below the floor
    specs = [(-90, 220, 6), (-74, 170, 5), (-106, 170, 5), (-58, 150, 5.5), (-122, 150, 5.5), (-40, 160, 5),
             (-140, 160, 5), (-24, 130, 4.5), (-156, 130, 4.5), (-10, 100, 4), (-170, 100, 4), (-82, 100, 3.5), (-98, 120, 3.5)]
    gold, hot = [], []
    for a, L, w in specs:
        ang = math.radians(a + rot + rng.uniform(-3, 3))
        poly, hp = b3.shard(None, None, ox, oy, ang, min(L, 232) * rng.uniform(0.92, 1.04), w, rng, rng.uniform(-1.4, 1.4))
        gold.append([(x / SS, y / SS) for x, y in poly])
        hot.append([(x / SS, y / SS) for x, y in hp])
    g = blur(mask_poly(gold), 0.4)
    h = blur(mask_poly(hot), 1.6)
    rim = blur(np.asarray(Image.fromarray((g * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(11))).astype(np.float32) / 255, 1.0)
    dst = np.zeros((n, n, 4), np.float32)
    dst = comp(dst, hexc("#171326"), rim * 0.8)
    dst = comp(dst, hexc("#F2B31F"), g)
    dst = comp(dst, hexc("#FFE45A"), g * blur(g, 4) * 0.9)
    dst = comp(dst, hexc("#FFF6C8"), h * g)
    r, X, Y = radial()
    rr = np.hypot(X + 0, Y - (oy - N / 2))
    return down(to_img(dst))


def make_spikes():
    save("pillar-spikes", [spikes_frame(11, 0), spikes_frame(29, 5)])


# ---------------------------------------------------------------------------------------------------------
def make_bar():
    r, X, Y = radial()
    ax = np.abs(X) / 124.0  # 0..1 along the bar
    taper = np.clip(1 - ax, 0, 1) ** 0.7
    env = soft(124 - np.abs(X), 1.2)

    def band(half_h):
        return soft(half_h * taper - np.abs(Y), 0.9) * env

    dst = np.zeros((n, n, 4), np.float32)
    dst = comp(dst, hexc("#171326"), band(14.0) * 0.85)
    dst = comp(dst, hexc("#F2B31F"), band(11.5))
    dst = comp(dst, hexc("#FFE45A"), band(8.5))
    dst = comp(dst, hexc("#FFF6C8"), band(5.5))
    dst = comp(dst, hexc("#FFFFFF"), band(2.8))
    # two thin secondary streaks, like the reference's stacked lines
    for off, hh, ln in ((-19, 2.2, 0.8), (17, 1.8, 0.62)):
        tp = np.clip(1 - np.abs(X) / (124 * ln), 0, 1) ** 0.7
        band2 = soft(hh * tp - np.abs(Y - off), 0.8) * smooth(tp * 12)
        dst = comp(dst, hexc("#FFF0A0"), band2 * 0.95)
    save("pillar-bar", [down(to_img(dst))])


# ---------------------------------------------------------------------------------------------------------
def crackle_frame(k):
    rng = np.random.default_rng(90 + k)
    cnt = [9, 7, 5, 3][k]
    im = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(im)
    for _ in range(cnt):
        side = rng.choice([-1, 1])
        x = N / 2 + rng.uniform(-14, 14)
        y = N / 2 + rng.uniform(-4, 4)
        length = rng.uniform(60, 120) * (1.0 - 0.12 * k)
        pts = [(x, y)]
        steps = 9
        for i in range(steps):
            x += side * length / steps
            y += rng.uniform(-9, 9)
            y = float(np.clip(y, N / 2 - 22, N / 2 + 22))
            pts.append((x, y))
            if rng.random() < 0.3:  # branch
                bx, by = x, y
                for _ in range(2):
                    bx += side * rng.uniform(4, 12)
                    by += rng.uniform(-14, 14)
                    d.line([(x * SS, y * SS), (bx * SS, by * SS)], fill=255, width=int(1.6 * SS))
        d.line([(px * SS, py * SS) for px, py in pts], fill=255, width=int((2.6 - 0.3 * k) * SS), joint="curve")
    m = blur(np.asarray(im).astype(np.float32) / 255, 0.35)
    halo = blur(m, 2.5)
    dst = np.zeros((n, n, 4), np.float32)
    dst = comp(dst, hexc("#FFC21F"), np.clip(halo * 1.6, 0, 1) * 0.5)
    dst = comp(dst, hexc("#FFE45A"), np.clip(m * 1.4, 0, 1))
    dst = comp(dst, hexc("#FFFFFF"), np.clip(blur(m, 0.3) * m * 1.2, 0, 1) ** 2)
    return down(to_img(dst))


def make_crackle():
    save("pillar-crackle", [crackle_frame(k) for k in range(4)])


MAKERS = {"slam-burst": make_burst, "slam-dark": make_dark, "pillar-spikes": make_spikes, "pillar-bar": make_bar,
          "pillar-crackle": make_crackle}

if __name__ == "__main__":
    for nm in (sys.argv[1:] or MAKERS):
        MAKERS[nm]()
