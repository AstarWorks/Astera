#!/usr/bin/env python3
"""bastion-s1 sprite art (bastion-greatsword S1 pilot). Writes the hand-made RGBA strips that
`content/textures/sources/sprites.yaml` lists as `raw: true` sources:

    content/textures/sources/images/bastion-s1-<name>.png

`./gradlew :tools:item-art:run` then copies them (unchanged) to textures/generated/sprites and writes the
font + metadata. Everything is drawn analytically at 4x, premultiplied, downsampled with LANCZOS, so edges are
anti-aliased (alpha ramps over ~2-3 px) and the output is deterministic.

    python3 tools/item-art/scripts/bastion_s1_sprites.py [name ...]
"""
import math
import os
import sys

import numpy as np
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "content", "textures", "sources", "images")
SS = 4


def hexc(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4)])


def smooth(x):
    x = np.clip(x, 0, 1)
    return x * x * (3 - 2 * x)


def soft(d, f):
    """Alpha of a signed distance d (>0 inside, in final px) with half-feather f final px."""
    return smooth((d + f) / (2 * f))


def over(dst, rgb, a):
    """Composite colour rgb (..,3) with alpha a (..) over dst premultiplied rgba (..,4)."""
    a = np.clip(a, 0, 1)[..., None]
    out = dst.copy()
    out[..., :3] = rgb * a + dst[..., :3] * (1 - a)
    out[..., 3:] = a + dst[..., 3:] * (1 - a)
    return out


def blank(n):
    return np.zeros((n, n, 4))


def downsample(img, size):
    """Premultiplied float (N,N,4) -> straight RGBA uint8 (size,size)."""
    chans = []
    for c in range(4):
        im = Image.fromarray(img[..., c].astype(np.float32), mode="F")
        chans.append(np.asarray(im.resize((size, size), Image.LANCZOS)))
    pm = np.stack(chans, -1)
    a = np.clip(pm[..., 3], 0, 1)
    rgb = np.where(a[..., None] > 1e-4, pm[..., :3] / np.maximum(a[..., None], 1e-4), 0)
    out = np.concatenate([np.clip(rgb, 0, 1), a[..., None]], -1)
    out[a < 1.5 / 255, :] = 0
    return (out * 255 + 0.5).astype(np.uint8)


def grid(size):
    n = size * SS
    ys, xs = np.mgrid[0:n, 0:n]
    return (xs + 0.5) / SS, (ys + 0.5) / SS  # final-px units


def value_noise(n, cells, rng):
    g = rng.random((cells + 3, cells + 3)).astype(np.float32)
    im = Image.fromarray(g, mode="F").resize((n + n * 3 // cells, n + n * 3 // cells), Image.BICUBIC)
    a = np.asarray(im)
    off = n // cells
    return a[off:off + n, off:off + n]


def fbm(n, rng, base=4, octaves=4):
    out = np.zeros((n, n), np.float32)
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        out += amp * value_noise(n, base * 2 ** o, rng)
        tot += amp
        amp *= 0.5
    out /= tot
    return (out - out.min()) / (out.max() - out.min() + 1e-6)


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


def save_strip(name, frames, size):
    strip = np.concatenate(frames, axis=1)
    os.makedirs(OUT, exist_ok=True)
    Image.fromarray(strip, "RGBA").save(os.path.join(OUT, f"bastion-s1-{name}.png"))
    print(f"bastion-s1-{name}: {len(frames)} frames, {size}px")


# ---------------------------------------------------------------------------------------------------------
# crescent: needle-tipped gold crescent, dark outer rim, white-hot inner edge
# ---------------------------------------------------------------------------------------------------------
GOLD = hexc("#FFD23A")
BODY = hexc("#E8A010")
HOT = hexc("#FFFBE0")
RIM = hexc("#3A2A06")


def crescent_frame(size, ext, tail, wscale, seed):
    """ext = how much of the arc is drawn from the leading (right) tip, tail = trimmed from the left tip."""
    X, Y = grid(size)
    rng = np.random.default_rng(seed)
    R, wmax = 138.0, 40.0 * wscale
    cx, cy = size / 2, size * 0.5 + 76  # arc spans the top of the circle, vertically centred in the frame
    half = math.radians(65)
    dx, dy = X - cx, cy - Y  # y up
    r = np.hypot(dx, dy)
    th = np.arctan2(dx, dy)  # 0 at the top, + toward the right
    # visible span in s = th/half (-1 left tip .. +1 right tip); the sweep grows from the right tip
    s_hi = 1.0
    s_lo = 1.0 - 2.0 * ext + 2.0 * tail
    s_lo = max(-1.0, s_lo)
    if s_hi - s_lo < 0.08:
        s_lo = s_hi - 0.08
    s = th / half
    u = np.clip((s - s_lo) / (s_hi - s_lo), 0, 1)  # 0..1 along the visible span
    prof = np.cos(np.pi * (u - 0.5)) ** 2.0  # needle at both ends (1.2 px at 12% from the tip)
    prof = np.clip(prof, 0, 1)
    w = wmax * prof
    in_span = ((s >= s_lo) & (s <= s_hi)).astype(float)
    d_out = R - r  # >0 inside the outer edge
    d_in = r - (R - w)  # >0 outside the inner edge
    d = np.minimum(d_out, d_in)
    alpha = soft(d, 1.0) * in_span
    # pixels at the span ends are cut by the profile reaching zero; soften the angular cut as well
    cut = np.minimum(s - s_lo, s_hi - s) * half * R
    alpha *= soft(cut + 0.5, 1.0)
    # colour: depth from the outer edge (dark rim) to the inner edge (white hot)
    rim_w = np.clip(0.2 * w, 1.0, 3.0)
    hot_w = np.clip(0.12 * w, 0.8, 2.0)
    t = np.clip(d_out / np.maximum(w, 1e-3), 0, 1)  # 0 outer .. 1 inner
    streak = np.interp(np.clip(th * 6.0, -9, 9), np.linspace(-9, 9, 4000), noise1d(4000, rng, 40))
    body = BODY[None, None] * (1 - t[..., None] ** 0.8) + GOLD[None, None] * t[..., None] ** 0.8
    body = body * (0.94 + 0.12 * (streak - 0.5)[..., None] * 2)
    col = body
    rim_a = soft(rim_w - d_out, 0.9)
    col = col * (1 - rim_a[..., None]) + RIM[None, None] * rim_a[..., None]
    hot_a = soft(hot_w - d_in, 0.7) * (1 - rim_a)
    col = col * (1 - hot_a[..., None]) + HOT[None, None] * hot_a[..., None]
    img = blank(size * SS)
    return over(img, np.clip(col, 0, 1), alpha)


def sliver_arc(img, size, X, Y, R, r_off, s0, s1, w, rgb):
    """Thin extra arc outside/near the tip (the reference has 2-3 hairline slivers by the left tip)."""
    cx, cy = size / 2, size * 0.5 + 76
    dx, dy = X - cx, cy - Y
    r = np.hypot(dx, dy)
    s = np.arctan2(dx, dy) / math.radians(65)
    u = np.clip((s - s0) / (s1 - s0), 0, 1)
    ww = w * np.sin(np.pi * u) ** 1.6
    d = np.minimum(ww / 2 - np.abs(r - (R + r_off)), 0.0 * r + 9)
    a = soft(d, 0.8) * ((s >= s0) & (s <= s1))
    return over(img, rgb[None, None] * np.ones((1, 1, 3)), a * 0.9)


def make_crescent():
    size = 256
    specs = [  # ext, tail, width scale
        (0.42, 0.0, 0.62), (0.75, 0.0, 0.88), (1.0, 0.0, 1.0),
        (1.0, 0.12, 0.78), (1.0, 0.30, 0.52), (1.0, 0.50, 0.30),
    ]
    frames = []
    for i, (ext, tail, ws) in enumerate(specs):
        img = crescent_frame(size, ext, tail, ws, 11)
        if i >= 1:
            X, Y = grid(size)
            span = (1.0 - 2.0 * ext + 2.0 * tail)
            lo = max(-1.0, span)
            img = sliver_arc(img, size, X, Y, 138.0, 7.0, lo, min(1.0, lo + 0.55), 3.4 * ws, GOLD)
            img = sliver_arc(img, size, X, Y, 138.0, 14.0, lo + 0.06, min(1.0, lo + 0.34), 2.4 * ws, HOT)
        frames.append(downsample(img, size))
    save_strip("crescent", frames, size)


# ---------------------------------------------------------------------------------------------------------
# ink: torn yellow brush flash, 3 stacked ragged bands, tone bands, dry-brush streaks
# ---------------------------------------------------------------------------------------------------------
Y_FLAT = hexc("#FFE45A")
Y_SHADE = hexc("#E8B810")
Y_HOT = hexc("#FFF6B0")


def ink_frame(size, grow, seed=5):
    n = size * SS
    X, Y = grid(size)
    rng = np.random.default_rng(seed)
    img = blank(n)
    bands = [  # centre y, half height, left raggedness seed, right end factor
        (62, 17, 1.00), (128, 23, 0.93), (195, 16, 0.97),
    ]
    xs_idx = np.clip((X * SS).astype(int), 0, n - 1)
    ys_idx = np.clip((Y * SS).astype(int), 0, n - 1)
    for bi, (cy, hh, endf) in enumerate(bands):
        brng = np.random.default_rng(seed * 31 + bi)
        # per-row (bristle) left/right ends, in final px; rows of ~1.6 px
        rows = noise1d(n, brng, 24, 4)
        rows2 = noise1d(n, brng, 60, 3)
        rows3 = noise1d(n, brng, 16, 3)
        front = 8 + grow * (size - 24) * endf
        right = front + (rows[ys_idx] - 0.5) * 26 + (rows2[ys_idx] - 0.5) * 22
        left = 5 + rows3[ys_idx] * 20 + (rows2[ys_idx] - 0.5) * 10
        # leading edge: before full width the front is a torn point cloud, so taper the band height with growth
        prof_x = np.clip((X - left) / 60.0, 0, 1) ** 0.5 * np.clip((right - X) / 40.0, 0, 1) ** 0.6
        edge = (noise1d(n, brng, 36, 4)[xs_idx] - 0.5) * 9 + (noise1d(n, brng, 110, 2)[xs_idx] - 0.5) * 5
        hgt = hh * (0.55 + 0.45 * np.clip(prof_x * 1.6, 0, 1)) * (0.45 + 0.55 * min(1.0, grow * 2.0)) + edge
        dy = np.abs(Y - cy)
        d = np.minimum(np.minimum(hgt - dy, X - left), right - X)
        alpha = soft(d, 0.6)
        # tones: shadow band under, hot band over, dry-brush streaks along x
        rel = (Y - cy) / np.maximum(hh, 1)
        brist = noise1d(n, brng, 70, 2)[ys_idx]
        tone = np.zeros_like(X)  # 0 flat, +1 hot, -1 shade
        tone = np.where(rel > 0.35 + 0.25 * (brist - 0.5), -1.0, tone)
        tone = np.where((rel < -0.25 + 0.2 * (brist - 0.5)) & (rel > -0.62), 1.0, tone)
        # streak lines
        for _ in range(26):
            sy = cy + brng.uniform(-hh, hh)
            sx0 = brng.uniform(10, size * 0.7)
            sl = brng.uniform(30, 120)
            sv = brng.choice([-1.0, 1.0, 0.0])
            m = (np.abs(Y - sy) < brng.uniform(0.5, 1.4)) & (X > sx0) & (X < sx0 + sl)
            tone = np.where(m, sv, tone)
        col = Y_FLAT[None, None] * np.ones((n, n, 1))
        col = np.where((tone > 0.5)[..., None], Y_HOT[None, None], col)
        col = np.where((tone < -0.5)[..., None], Y_SHADE[None, None], col)
        # thin dark-ish hairline at the ragged edges (reads on bright sky)
        rimk = soft(1.1 - d, 0.5) * 0.85
        col = col * (1 - rimk[..., None]) + hexc("#8A6A06")[None, None] * rimk[..., None]
        img = over(img, col, alpha)
    return img


def make_ink():
    size = 256
    frames = []
    for i, g in enumerate([0.12, 0.3, 0.52, 0.74, 0.9, 1.0]):
        frames.append(downsample(ink_frame(size, g, seed=5 + (i % 3)), size))
    save_strip("ink", frames, size)


# ---------------------------------------------------------------------------------------------------------
# streak: white-hot needle line with glow halo
# ---------------------------------------------------------------------------------------------------------
def make_streak():
    size = 256
    X, Y = grid(size)
    t = X / size
    # left: hard needle (rises over the first 40%), right: long soft needle
    left = np.clip(t / 0.4, 0, 1) ** 1.35
    right = np.clip((1 - t) / 0.62, 0, 1) ** 1.15
    env = left * right
    yc = size / 2
    dy = np.abs(Y - yc)
    img = blank(size * SS)
    # outer orange glow: band up to 40 px tall, alpha 0.35
    ho = 20.0 * env
    a_out = smooth(1 - dy / np.maximum(ho, 1e-3)) ** 1.4 * 0.35 * (env > 0.01)
    img = over(img, hexc("#FFB020")[None, None] * np.ones((1, 1, 3)), a_out)
    hg = 12.0 * env
    a_gl = smooth(1 - dy / np.maximum(hg, 1e-3)) ** 1.2 * 0.85 * (env > 0.01)
    img = over(img, hexc("#FFE070")[None, None] * np.ones((1, 1, 3)), a_gl)
    hc = 4.6 * env ** 1.1
    a_core = soft(hc - dy, 0.9) * (env > 0.005)
    img = over(img, hexc("#FFFFF0")[None, None] * np.ones((1, 1, 3)), a_core)
    # fine bright spine so the needle ends survive the downsample
    hs = 1.4 * env
    img = over(img, np.ones((1, 1, 3)), soft(hs - dy, 0.6) * (env > 0.002))
    save_strip("streak", [downsample(img, size)], size)


# ---------------------------------------------------------------------------------------------------------
# smoke: floor swirl, dark and light pockets
# ---------------------------------------------------------------------------------------------------------
def smoke_frame(size, turn, grow, dissolve, seed):
    """Lazy spiral band: nearest-point distance to a sampled spiral, width swelling in the middle."""
    n = size * 2
    SSm = 2
    ys, xs = np.mgrid[0:n, 0:n]
    X, Y = (xs + 0.5) / SSm, (ys + 0.5) / SSm
    rng = np.random.default_rng(seed)
    phimax = 1.65 * np.pi * grow + 0.3
    m = 220
    phi = np.linspace(0, phimax, m)
    rr = 18.0 + 66.0 * phi / (1.65 * np.pi)
    ang = turn + phi
    px = size / 2 + rr * np.cos(ang)
    py = size / 2 + rr * np.sin(ang) * 0.82  # a little foreshortened: it lies on the floor
    u = phi / phimax
    wid = 7.0 + 25.0 * np.sin(np.pi * np.clip(u, 0, 1)) ** 0.7
    best = np.full((n, n), 1e9, np.float32)
    bw = np.ones((n, n), np.float32)
    bu = np.zeros((n, n), np.float32)
    for i in range(m):
        d = np.hypot(X - px[i], Y - py[i]).astype(np.float32) / wid[i]
        upd = d < best
        best = np.where(upd, d, best)
        bu = np.where(upd, u[i], bu)
    band = np.exp(-(best ** 2) * 1.5)
    band *= 0.4 + 0.6 * np.clip(np.sin(np.pi * np.clip(bu, 0, 1)), 0, 1) ** 0.5
    n1 = fbm(n, rng, 5, 5)
    n2 = fbm(n, rng, 3, 4)
    dens = band * (0.3 + 1.0 * n1)
    dens = np.clip((dens - dissolve) / max(1 - dissolve, 1e-3), 0, 1)
    dens = smooth(dens * 1.5)
    light, dark = hexc("#B8B8C0"), hexc("#4A4A52")
    mix = smooth((n2 - 0.38) * 2.4)[..., None]
    col = dark[None, None] * (1 - mix) + light[None, None] * mix
    alpha = np.clip(0.2 + 0.5 * dens, 0, 0.7) * smooth(dens * 7)
    return over(blank(n), col, alpha)


def make_smoke():
    size = 256
    params = [  # turn, grow, dissolve
        (0.0, 0.55, 0.0), (0.9, 0.95, 0.0), (1.8, 1.0, 0.18), (2.6, 0.9, 0.42),
    ]
    frames = [downsample(smoke_frame(size, t, g, d, 21 + i), size) for i, (t, g, d) in enumerate(params)]
    save_strip("smoke", frames, size)


# ---------------------------------------------------------------------------------------------------------
# glint: 4 point star with a long horizontal and a short vertical spike
# ---------------------------------------------------------------------------------------------------------
def glint_frame(size, scale, glow):
    X, Y = grid(size)
    c = size / 2
    dx, dy = np.abs(X - c), np.abs(Y - c)
    img = blank(size * SS)
    Lh, Lv = 60.0 * scale, 20.0 * scale
    # spikes: half-thickness shrinks as (1 - d/L)^2.2
    hh = 3.2 * scale * np.clip(1 - dx / Lh, 0, 1) ** 2.2
    hv = 3.2 * scale * np.clip(1 - dy / Lv, 0, 1) ** 2.2
    r = np.hypot(dx, dy)
    orange, amber, white = hexc("#FF7A12"), hexc("#FFC040"), hexc("#FFFFFF")
    for k, col, a in ((3.0, orange, 0.55 * glow), (1.9, amber, 0.95), (1.0, white, 1.0)):
        sh = np.where(dx < Lh, np.clip(hh * k - dy, -3, 9), -3.0)
        sv = np.where(dy < Lv, np.clip(hv * k - dx, -3, 9), -3.0)
        d = np.maximum(sh, sv)
        img = over(img, col[None, None] * np.ones((1, 1, 3)), soft(d, 0.7) * a * (d > -3))
    halo = np.exp(-(r / (7.0 * scale)) ** 2) * 0.8 * glow
    img = over(img, orange[None, None] * np.ones((1, 1, 3)), halo * 0.5)
    img = over(img, amber[None, None] * np.ones((1, 1, 3)), np.exp(-(r / (4.2 * scale)) ** 2) * 0.9)
    img = over(img, white[None, None] * np.ones((1, 1, 3)), soft(2.6 * scale - r, 0.8))
    return img


def make_glint():
    size = 128
    frames = [downsample(glint_frame(size, 0.86, 0.8), size), downsample(glint_frame(size, 1.0, 1.3), size)]
    save_strip("glint", frames, size)


# ---------------------------------------------------------------------------------------------------------
# sliver: thin slanted shards, 3 variants
# ---------------------------------------------------------------------------------------------------------
def sliver_frame(size, ang_deg, length, wid, peak, bend):
    X, Y = grid(size)
    c = size / 2
    a = math.radians(ang_deg)
    ca, sa = math.cos(a), math.sin(a)
    px, py = X - c, c - Y
    s = (px * ca + py * sa) / length + 0.5  # 0..1 along the shard
    u = -px * sa + py * ca + bend * length * (s - 0.5) ** 2 * 4 - bend * length  # across, with a slight bend
    sc = np.clip(s, 0, 1)
    hw = wid / 2 * (sc / peak) ** 0.75 * (np.where(sc < peak, 1, ((1 - sc) / (1 - peak)) ** 1.05))
    hw = np.where((s > 0) & (s < 1), hw, 0)
    d = hw - np.abs(u)
    alpha = soft(d, 0.8) * ((s > 0) & (s < 1))
    body, edge, rim = hexc("#FFD23A"), hexc("#FFFBE0"), hexc("#3A2A06")
    t = np.clip((u + hw) / np.maximum(2 * hw, 1e-3), 0, 1)  # 0 rim side .. 1 bright side
    col = body[None, None] * (0.85 + 0.15 * t[..., None]) * np.ones((1, 1, 3))
    hot = soft(1.5 - (hw - u), 0.5)
    dark = soft(1.0 - (hw + u), 0.5)
    col = col * (1 - hot[..., None]) + edge[None, None] * hot[..., None]
    col = col * (1 - dark[..., None] * 0.95) + rim[None, None] * dark[..., None] * 0.95
    img = blank(size * SS)
    return over(img, np.clip(col, 0, 1), alpha)


def make_sliver():
    size = 128
    variants = [(32, 118, 12.0, 0.32, 0.0), (-24, 104, 9.5, 0.4, 0.015), (48, 124, 13.0, 0.26, -0.015)]
    frames = [downsample(sliver_frame(size, *v), size) for v in variants]
    save_strip("sliver", frames, size)


MAKERS = {"crescent": make_crescent, "ink": make_ink, "streak": make_streak, "smoke": make_smoke,
          "glint": make_glint, "sliver": make_sliver}

if __name__ == "__main__":
    for nm in (sys.argv[1:] or MAKERS):
        MAKERS[nm]()
