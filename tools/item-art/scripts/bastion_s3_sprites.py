#!/usr/bin/env python3
"""bastion-s3 hero sprites (r2). Writes content/textures/sources/images/bastion-s3-<name>.png (raw strips that
sprites.yaml lists), then `./gradlew -q :tools:item-art:run` copies them. 256 px frames, drawn at 4x and
downsampled (anti-aliased edges), deterministic.

    python3 tools/item-art/scripts/bastion_s3_sprites.py [pillar bar spikes plate]

pillar : vertical light pillar column (dark rim, gold body, white core, soft top) - stretch with scale.
bar    : horizontal lens bar (dark rim, gold, white core) - cross bar / thrust beam.
spikes : radial burst of jagged gold lightning shards (2 frames, different shard sets).
plate  : stack of ragged flat-gold brush stripes with a thick black outline (the dash plate).
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "content", "textures", "sources", "images")
SS = 4
N = 256


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def save(name, frames):
    strip = Image.new("RGBA", (N * len(frames), N), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        strip.paste(f, (i * N, 0))
    os.makedirs(OUT, exist_ok=True)
    strip.save(os.path.join(OUT, f"bastion-s3-{name}.png"))
    print(f"bastion-s3-{name}: {len(frames)} frames, {N}px")


def down(img):
    """Premultiplied downsample of an RGBA 4x image."""
    a = np.asarray(img).astype(np.float32) / 255.0
    pm = a.copy()
    pm[..., :3] *= a[..., 3:]
    chans = [np.asarray(Image.fromarray(pm[..., c], mode="F").resize((N, N), Image.LANCZOS)) for c in range(4)]
    pm = np.stack(chans, -1)
    al = np.clip(pm[..., 3], 0, 1)
    rgb = np.where(al[..., None] > 1e-4, pm[..., :3] / np.maximum(al[..., None], 1e-4), 0)
    out = np.concatenate([np.clip(rgb, 0, 1), al[..., None]], -1)
    out[al < 1.5 / 255] = 0
    return Image.fromarray((out * 255 + 0.5).astype(np.uint8), "RGBA")


def smooth(x):
    x = np.clip(x, 0, 1)
    return x * x * (3 - 2 * x)


def soft(d, f):
    return smooth((d + f) / (2 * f))


def comp(dst, rgb, a):
    """dst float (n,n,4) premultiplied; composite colour rgb (3,) with alpha map a."""
    a = np.clip(a, 0, 1)[..., None]
    c = np.array(rgb, np.float32) / 255.0
    dst[..., :3] = c * a + dst[..., :3] * (1 - a)
    dst[..., 3:] = a + dst[..., 3:] * (1 - a)
    return dst


def to_img(dst):
    a = dst[..., 3]
    rgb = np.where(a[..., None] > 1e-4, dst[..., :3] / np.maximum(a[..., None], 1e-4), 0)
    out = np.concatenate([np.clip(rgb, 0, 1), a[..., None]], -1)
    return Image.fromarray((out * 255 + 0.5).astype(np.uint8), "RGBA")


def noise1d(length, rng, cells, octaves=3):
    out = np.zeros(length)
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        c = cells * 2 ** o
        pts = rng.random(c + 3)
        xs = np.linspace(1, c + 1, length)
        i = np.floor(xs).astype(int)
        t = xs - i
        t = t * t * (3 - 2 * t)
        out += amp * (pts[i] * (1 - t) + pts[i + 1] * t)
        tot += amp
        amp *= 0.5
    return out / tot


# ---------------------------------------------------------------------------------------------------------
def make_pillar():
    n = N * SS
    ys, xs = np.mgrid[0:n, 0:n]
    X, Y = (xs + 0.5) / SS, (ys + 0.5) / SS
    rng = np.random.default_rng(7)
    half = 116.0  # half width in px (margin 12 px each side)
    wob = (noise1d(n, rng, 5, 1) - 0.5) * 3.0  # low-frequency ragged edge
    wob_y = wob[np.clip((Y * SS).astype(int), 0, n - 1)]
    u = np.abs(X - N / 2 + wob_y * 0.6) / half  # 0 centre .. 1 edge
    # vertical envelope: hard base, soft top
    top = smooth((Y - 4) / 90.0)
    bot = soft((N - 6) - Y, 1.2)
    env = top * bot
    d = (1.0 - u) * half  # px inside the outer edge
    dst = np.zeros((n, n, 4), np.float32)
    # r5: thin (3 px) dark rim, wide bright body (yellow from d=18, pale from 46, white spine from 72), 4x supersampled
    dst = comp(dst, hexc("#171326"), soft(d, 0.9) * env * 0.92)
    dst = comp(dst, hexc("#F2B31F"), soft(d - 3, 0.9) * env)
    dst = comp(dst, hexc("#FFE45A"), soft(d - 18, 5.0) * env)
    dst = comp(dst, hexc("#FFF6C8"), soft(d - 46, 8.0) * env)
    dst = comp(dst, hexc("#FFFFFF"), soft(d - 72, 8.0) * env * 0.97)
    # faint vertical streaks in the body for the electric look
    g = noise1d(n, np.random.default_rng(3), 22, 2)[np.clip((X * SS).astype(int), 0, n - 1)]
    dst[..., :3] *= (0.97 + 0.06 * (g[..., None] - 0.5) * 2)
    save("pillar", [down(to_img(dst))])


# ---------------------------------------------------------------------------------------------------------
def make_bar():
    n = N * SS
    ys, xs = np.mgrid[0:n, 0:n]
    X, Y = (xs + 0.5) / SS, (ys + 0.5) / SS
    t = (X - N / 2) / (N / 2 - 4)
    env = np.clip(1 - np.abs(t) ** 1.6, 0, 1) ** 0.7  # lens, pointed ends
    hh = 52.0 * env
    dy = np.abs(Y - N / 2)
    d = hh - dy
    dst = np.zeros((n, n, 4), np.float32)
    dst = comp(dst, hexc("#171326"), soft(d, 1.1) * (env > 0.01))
    dst = comp(dst, hexc("#F2B31F"), soft(d - 7, 1.1) * (env > 0.02))
    dst = comp(dst, hexc("#FFE45A"), soft(d - 17, 3.0) * (env > 0.03))
    dst = comp(dst, hexc("#FFF6C8"), soft(d - 28, 4.0) * (env > 0.04))
    dst = comp(dst, hexc("#FFFFFF"), soft(d - 38, 3.0) * (env > 0.05))
    save("bar", [down(to_img(dst))])


# ---------------------------------------------------------------------------------------------------------
def shard(draw_fill, draw_hot, cx, cy, ang, length, w0, rng, kink):
    """One jagged tapered lightning shard from (cx, cy) outward. Polygon points at 4x px."""
    ca, sa = math.cos(ang), math.sin(ang)
    nx, ny = -sa, ca
    steps = 7
    left, right, hot_l, hot_r = [], [], [], []
    for i in range(steps + 1):
        u = i / steps
        along = 10 + (length - 10) * u
        lateral = kink * math.sin(u * math.pi * 1.5) * length * 0.07 + rng.uniform(-1, 1) * 2.0 * (0 < i < steps)
        hw = w0 * (1 - u) ** 0.85 * (1 + 0.35 * rng.uniform(-1, 1) * (0 < i < steps))
        px, py = cx + ca * along + nx * lateral, cy + sa * along + ny * lateral
        left.append((px + nx * hw, py + ny * hw))
        right.append((px - nx * hw, py - ny * hw))
        hot_l.append((px + nx * hw * 0.38, py + ny * hw * 0.38))
        hot_r.append((px - nx * hw * 0.38, py - ny * hw * 0.38))
    poly = left + right[::-1]
    hot = hot_l + hot_r[::-1]
    return [(x * SS, y * SS) for x, y in poly], [(x * SS, y * SS) for x, y in hot]


def spikes_frame(seed, rot):
    rng = np.random.default_rng(seed)
    n = N * SS
    base = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    rim = Image.new("L", (n, n), 0)
    gold = Image.new("L", (n, n), 0)
    hot = Image.new("L", (n, n), 0)
    dg, dr, dh = ImageDraw.Draw(gold), ImageDraw.Draw(rim), ImageDraw.Draw(hot)
    cx, cy = N / 2, N / 2
    # (angle deg from +x, y down, length px, base half width)
    specs = [(8, 118, 11), (32, 100, 12), (58, 112, 13), (84, 92, 10), (108, 118, 14), (136, 96, 12), (160, 112, 11),
             (184, 120, 10), (208, 70, 9), (236, 62, 9), (302, 66, 9), (332, 74, 9), (352, 108, 10), (20, 70, 8), (122, 78, 8)]
    for a, L, w in specs:
        ang = math.radians(a + rot + rng.uniform(-4, 4))
        poly, hp = shard(None, None, cx, cy, ang, L * rng.uniform(0.9, 1.05), w, rng, rng.uniform(-1.3, 1.3))
        dg.polygon(poly, fill=255)
        dh.polygon(hp, fill=255)
    gold_a = np.asarray(gold).astype(np.float32) / 255
    hot_a = np.asarray(hot.filter(ImageFilter.GaussianBlur(2.2))).astype(np.float32) / 255
    rim_a = np.asarray(gold.filter(ImageFilter.MaxFilter(13))).astype(np.float32) / 255
    rim_a = np.asarray(Image.fromarray((rim_a * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.2))).astype(np.float32) / 255
    gold_a = np.asarray(Image.fromarray((gold_a * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.0))).astype(np.float32) / 255
    dst = np.zeros((n, n, 4), np.float32)
    dst = comp(dst, hexc("#171326"), rim_a * 0.8)
    dst = comp(dst, hexc("#F2B31F"), gold_a)
    dst = comp(dst, hexc("#FFE45A"), np.clip(gold_a * 0.9, 0, 1) * 0.0 + hot_a * 0.0 + gold_a * np.asarray(
        Image.fromarray((gold_a * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(5))).astype(np.float32) / 255 * 0.8)
    dst = comp(dst, hexc("#FFF6C8"), hot_a * gold_a)
    # white-hot core where the shards meet
    ys, xs = np.mgrid[0:n, 0:n]
    r = np.hypot((xs + 0.5) / SS - cx, (ys + 0.5) / SS - cy)
    dst = comp(dst, hexc("#FFFFFF"), soft(14 - r, 5.0))
    return down(to_img(dst))


def make_spikes():
    save("spikes", [spikes_frame(5, 0), spikes_frame(23, 11)])


# ---------------------------------------------------------------------------------------------------------
def make_plate():
    n = N * SS
    rng = np.random.default_rng(11)
    gold = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(gold)
    # (centre y, half thickness, left reach, right reach) in px of the 256 frame, x from 8..248
    bands = [(54, 15, 70, 232), (86, 20, 22, 246), (124, 24, 10, 244), (162, 21, 30, 238), (198, 15, 62, 226)]
    for yc, h, x0, x1 in bands:
        m = 24
        xsamp = np.linspace(x0, x1, m)
        wob = (noise1d(m, rng, 3, 2) - 0.5) * 7
        top, bot = [], []
        for i, x in enumerate(xsamp):
            u = i / (m - 1)
            hh = h * (math.sin(math.pi * u) ** 0.55) * (0.85 + 0.3 * rng.random())
            top.append((x * SS, (yc + wob[i] - hh) * SS))
            bot.append((x * SS, (yc + wob[i] + hh) * SS))
        d.polygon(top + bot[::-1], fill=255)
    g = np.asarray(gold.filter(ImageFilter.GaussianBlur(1.2))).astype(np.float32) / 255
    rimm = np.asarray(gold.filter(ImageFilter.MaxFilter(25)).filter(ImageFilter.GaussianBlur(1.4))).astype(np.float32) / 255
    inner = np.asarray(gold.filter(ImageFilter.MinFilter(29)).filter(ImageFilter.GaussianBlur(5))).astype(np.float32) / 255
    dst = np.zeros((n, n, 4), np.float32)
    dst = comp(dst, hexc("#0E0B14"), rimm * 0.95)
    dst = comp(dst, hexc("#FFD43A"), g)
    dst = comp(dst, hexc("#FFF0A0"), inner * g * 0.9)
    save("plate", [down(to_img(dst))])


MAKERS = {"pillar": make_pillar, "bar": make_bar, "spikes": make_spikes, "plate": make_plate}

if __name__ == "__main__":
    for nm in (sys.argv[1:] or MAKERS):
        MAKERS[nm]()
