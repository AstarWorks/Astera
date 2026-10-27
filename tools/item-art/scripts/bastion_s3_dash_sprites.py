#!/usr/bin/env python3
"""bastion-s3 dash / contact-2 sprites (r3). Writes content/textures/sources/images/bastion-s3-dash-<name>.png.
Reuses the helpers of bastion_s3_sprites.py. 256 px frames drawn at 4x, anti-aliased.

    python3 tools/item-art/scripts/bastion_s3_dash_sprites.py [slab sweep flash]

slab  : black-edged jagged pale-gold plate, elongated along image x (travel), ragged serrated edges, rear tail.
sweep : long gold crescent sweep, tapered both ends, white-hot core, rising to the right.
flash : full-frame jagged orange / yellow flash burst (2 frames, different shard sets).
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bastion_s3_sprites as B  # noqa: E402

N, SS = B.N, B.SS
OUT = B.OUT


def save(name, frames):
    strip = Image.new("RGBA", (N * len(frames), N), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        strip.paste(f, (i * N, 0))
    strip.save(os.path.join(OUT, f"bastion-s3-dash-{name}.png"))
    print(f"bastion-s3-dash-{name}: {len(frames)} frames, {N}px")


def blur(mask, r):
    return np.asarray(Image.fromarray((np.clip(mask, 0, 1) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(r))).astype(np.float32) / 255


def saw(m, rng, amp, teeth):
    """Serrated offsets: low-frequency wobble + sharp alternating teeth."""
    base = (B.noise1d(m, rng, 4, 2) - 0.5) * 2 * amp
    t = np.arange(m)
    tooth = ((t * teeth / m) % 1.0)
    tooth = (tooth - 0.5) * 2 * amp * 0.55 * (0.5 + rng.random(m))
    return base + tooth


def make_slab():
    """r5: stacked clean blades (pointed tips, straight facets), 4x supersampled, thin dark rim (3 px at 256)."""
    n = N * SS
    dst = np.zeros((n, n, 4), np.float32)
    # (centre y, half thickness, x tail, x nose, nose lift)
    bands = [(44, 11, 40, 214, 6), (80, 14, 14, 232, 8), (122, 20, 6, 246, 10), (164, 15, 22, 234, 8), (203, 10, 50, 210, 5)]
    for yc, h, x0, x1, lift in bands:
        L = x1 - x0
        xm = x0 + 0.66 * L
        top = [(x0, yc + 2), (xm, yc - h), (x1, yc - lift)]
        bot = [(xm - 0.12 * L, yc + h * 0.75), (x0 + 0.1 * L, yc + h * 0.2)]
        poly = [(x * SS, y * SS) for x, y in top + bot]
        m = Image.new("L", (n, n), 0)
        ImageDraw.Draw(m).polygon(poly, fill=255)
        full = np.asarray(m).astype(np.float32) / 255
        gold_m = np.asarray(m.filter(ImageFilter.MinFilter(2 * 3 * SS + 1))).astype(np.float32) / 255
        core_m = np.asarray(m.filter(ImageFilter.MinFilter(2 * (h // 2 + 2) * SS + 1))).astype(np.float32) / 255
        dst = B.comp(dst, B.hexc("#1C0F04"), blur(full, 1.0))
        dst = B.comp(dst, B.hexc("#F6C426"), blur(gold_m, 1.0))
        dst = B.comp(dst, B.hexc("#FFF29A"), blur(core_m, 3.0) * 0.95)
    save("slab", [B.down(B.to_img(dst))])


def make_sweep():
    n = N * SS
    ys, xs = np.mgrid[0:n, 0:n]
    X, Y = (xs + 0.5) / SS, (ys + 0.5) / SS
    t = (X - 6) / (N - 12)  # 0 tail (left, low) .. 1 tip (right, high curl)
    tc = np.clip(t, 0, 1)
    cy = 214 - 150 * tc ** 2.2 - 18 * np.sin(tc * math.pi)
    env = np.clip(np.sin(math.pi * tc ** 0.8), 0, 1) ** 0.6 * ((t > 0) & (t < 1))
    hh = 40.0 * env
    dy = np.abs(Y - cy)
    dyy = np.where(Y > cy, dy * 0.8, dy * 1.15)
    d = hh - dyy
    dst = np.zeros((n, n, 4), np.float32)
    dst = B.comp(dst, B.hexc("#1A1208"), B.soft(d, 1.1) * (env > 0.01) * 0.95)
    dst = B.comp(dst, B.hexc("#F2B31F"), B.soft(d - 2.6, 1.1) * (env > 0.02))
    dst = B.comp(dst, B.hexc("#FFE45A"), B.soft(d - 13, 3.0) * (env > 0.03))
    dst = B.comp(dst, B.hexc("#FFF6C8"), B.soft(d - 22, 4.0) * (env > 0.04))
    dst = B.comp(dst, B.hexc("#FFFFFF"), B.soft(d - 30, 3.0) * (env > 0.05))
    save("sweep", [B.down(B.to_img(dst))])


def flash_frame(seed):
    rng = np.random.default_rng(seed)
    n = N * SS
    cx = cy = N / 2
    layers = []
    spikes = 17
    ang0 = rng.uniform(0, 6.28)
    for R, w in [(122, 1.0), (98, 0.8), (56, 0.6)]:
        im = Image.new("L", (n, n), 0)
        d_ = ImageDraw.Draw(im)
        pts = []
        for i in range(spikes * 2):
            a = ang0 + i * math.pi / spikes + rng.uniform(-0.05, 0.05)
            if i % 2 == 0:
                r = R * rng.uniform(0.62, 1.0) * (1.0 if rng.random() > 0.3 else 0.8)
            else:
                r = R * rng.uniform(0.28, 0.44) * w
            pts.append(((cx + math.cos(a) * r) * SS, (cy + math.sin(a) * r) * SS))
        d_.polygon(pts, fill=255)
        layers.append(im)
    bg = blur(np.asarray(layers[0]).astype(np.float32) / 255, 1.2)
    mg = blur(np.asarray(layers[1]).astype(np.float32) / 255, 1.2)
    cg = blur(np.asarray(layers[2]).astype(np.float32) / 255, 1.2)
    rim = blur(np.asarray(layers[0].filter(ImageFilter.MaxFilter(15))).astype(np.float32) / 255, 1.2)
    out = np.zeros((n, n, 4), np.float32)
    out = B.comp(out, B.hexc("#4A1606"), rim * 0.85)
    out = B.comp(out, B.hexc("#FF7A18"), bg)
    out = B.comp(out, B.hexc("#FFC21A"), mg)
    out = B.comp(out, B.hexc("#FFF06A"), cg)
    ys, xs = np.mgrid[0:n, 0:n]
    r = np.hypot((xs + 0.5) / SS - cx, (ys + 0.5) / SS - cy)
    out = B.comp(out, B.hexc("#FFFFFF"), B.soft(24 - r, 7.0))
    return B.down(B.to_img(out))


def make_flash():
    save("flash", [flash_frame(41), flash_frame(77)])


MAKERS = {"slab": make_slab, "sweep": make_sweep, "flash": make_flash}

if __name__ == "__main__":
    for nm in (sys.argv[1:] or MAKERS):
        MAKERS[nm]()
