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
BODY = hexc("#E88A0C")  # round 2: more orange body
HOT = hexc("#FFF6D0")
RIM = hexc("#3A2A06")


def crescent_frame(size, ext, tail, wscale, seed):
    """ext = how much of the arc is drawn from the leading (right) tip, tail = trimmed from the left tip."""
    X, Y = grid(size)
    rng = np.random.default_rng(seed)
    R, wmax = 138.0, 48.0 * wscale  # round 2: thicker (was 40)
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
    hot_w = np.clip(0.07 * w, 0.6, 1.4)
    t = np.clip(d_out / np.maximum(w, 1e-3), 0, 1)  # 0 outer .. 1 inner
    streak = np.interp(np.clip(th * 6.0, -9, 9), np.linspace(-9, 9, 4000), noise1d(4000, rng, 40))
    body = BODY[None, None] * (1 - t[..., None] ** 0.8) + GOLD[None, None] * t[..., None] ** 0.8
    body = body * (0.94 + 0.12 * (streak - 0.5)[..., None] * 2)
    col = body
    rim_a = soft(rim_w - d_out, 0.9)
    col = col * (1 - rim_a[..., None]) + RIM[None, None] * rim_a[..., None]
    hot_a = soft(hot_w - d_in, 1.6) * (1 - rim_a) * 0.8  # round 2: softer inner glow
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
# ink: the hit-2 impact flash as a SHADED horizontal brush band (reference e00-e02: flat saturated yellow ragged
# bands streaming across the contact), centred on a bright core, soft-edged, slightly see-through
# ---------------------------------------------------------------------------------------------------------
# r10: the colour is the perceived beat-sheet yellow #FFE45A (case.json t30), not the reference's sampled #FCFDAE
# (that is light over near-black; over Minecraft's grey daylight it reads cream).
# r17 (director real client r13: the r10-r16 sprite, an opaque #FFE45A fill with a 0.6 px feather, high-frequency
# edge bites and a dark olive rim, read in caster FP and opponent as a FLAT OPAQUE SLAB with a cut-out pixel edge at
# 0.5-0.8 blocks from the eye, where one texel is ~6 screen px): rebuilt as an impact shape.
# - Layout: symmetric about a central core (the contact) so it reads the same from the caster and the opponent (a
#   billboard is mirrored between the two views; the old root-left mass put the root on different sides of the blade).
#   Four horizontal brush bands leave the core both ways, unequal reaches, gaps between them show the scene.
# - Shading: white-hot core #FFFBE0 (anisotropic gaussian, ~18% of the width) -> flat #FFE45A body -> warm gold
#   #F2B926 along the band edges (no dark rim: the rim made the cut-out outline).
# - Alpha: 0.9 in the core falling to ~0.6 at the band tips (bodies show through the outer half), a 3 px soft edge
#   with LOW-frequency raggedness (base 7, +-5 px) instead of 2-5 px bites (texel staircases when magnified); the
#   shader discards alpha < 0.1, so the soft edge ends in a faint 10% step, not a hard outline.
INK_FLAT = hexc("#FFE45A")
INK_HOT = hexc("#FFFBE0")
INK_EDGE = hexc("#F2B926")
# bands: centre y offset (px, + = down), half thickness at the core, reach left / right (0..1 of the half width)
INK_BANDS = [(-36, 9, 0.58, 0.78, 1), (-13, 16, 0.88, 0.82, 2), (12, 15, 0.80, 0.88, 3), (35, 9, 0.66, 0.54, 4)]


def ink_frame(size, diss, core=1.0, seed=11):
    """diss: 0 (full band) .. 1 (gone), tips first then holes; core: weight of the white-hot core."""
    n = size * SS
    X, Y = grid(size)
    cx, cy = size / 2, size / 2
    half = size / 2 - 8.0
    u = (X - cx) / half  # -1 left tip .. +1 right tip
    au = np.abs(u)
    field = np.full(X.shape, -9.0)
    xs_idx = np.clip((X * SS).astype(int), 0, n - 1)
    for yc, h, rl, rr, ts in INK_BANDS:
        r2 = np.random.default_rng(seed * 10 + ts)
        wob = noise1d(n, r2, 4, 2)[xs_idx]
        thick = noise1d(n, r2, 6, 2)[xs_idx]
        reach = np.where(u < 0, rl, rr)
        centre = cy + yc * (0.75 + 0.25 * au) + 6.0 * (wob - 0.5)
        hh = h * (0.8 + 0.4 * thick) * np.clip(1.0 - au / reach, 0, 1) ** 0.55 * (1.0 - 0.45 * diss)
        d = hh - np.abs(Y - centre)
        field = np.maximum(field, d)
    # core: a ragged ellipse joining the bands (one connected mass)
    rng = np.random.default_rng(seed)
    ang = np.arctan2(Y - cy, X - cx)
    an = noise1d(720, rng, 9, 2)[np.clip(((ang + np.pi) / (2 * np.pi) * 719).astype(int), 0, 719)]
    rx, ry = 36.0, 30.0
    rr_ = np.hypot((X - cx) / rx, (Y - cy) / ry) / (0.8 + 0.35 * an)
    field = np.maximum(field, (1.0 - rr_ / (1.0 - 0.5 * diss)) * ry)
    # low-frequency ragged edge (no texel-sized bites)
    rag = fbm(n, np.random.default_rng(seed + 31), base=7, octaves=2) - 0.5
    tear = fbm(n, np.random.default_rng(seed + 47), base=16, octaves=2) - 0.5
    field = field + (10.0 * rag + 5.0 * tear) * soft(10.0 - np.abs(field), 4.0)
    body = 0.97 - 0.2 * np.clip(au, 0, 1) ** 1.5
    alpha = soft(field, 1.8) * body * soft(np.minimum(X, size - X) - 10.0, 5.0)  # tips fade before the frame edge
    if diss > 0:
        dn = fbm(n, np.random.default_rng(seed + 7), base=5, octaves=2)
        thr = 0.6 * dn + 0.4 * au
        alpha *= smooth(((1.0 - 0.6 * diss) - thr) / 0.08 + 0.5)
    # shading: hot core, flat body, warm gold toward the band edges, faint horizontal brush grain
    g = np.exp(-(((X - cx) / 32.0) ** 2 + ((Y - cy) / 17.0) ** 2)) * core
    edge = (1.0 - soft(field - 4.0, 2.5)) * 0.5
    grain = np.asarray(Image.fromarray(fbm(n, np.random.default_rng(seed + 99), base=3, octaves=3).astype(np.float32), mode="F")
                       .resize((n, n // 8), Image.BICUBIC).resize((n, n), Image.BICUBIC))
    col = INK_FLAT[None, None] * np.ones((n, n, 1))
    col = col * (1 - edge[..., None]) + INK_EDGE[None, None] * edge[..., None]
    col = col * (1 - g[..., None]) + INK_HOT[None, None] * g[..., None]
    col = col * (0.96 + 0.08 * (grain[..., None] - 0.5))
    alpha = np.clip(alpha + 0.08 * g, 0, 1)
    return over(blank(n), np.clip(col, 0, 1), alpha)


def make_ink():
    """2 frames (element life 2): frame 0 = full band with the hot core (contact), frame 1 = tips eaten, holes, core
    cooled (reference t31)."""
    size = 256
    specs = [(0.0, 1.0), (0.45, 0.45)]
    frames = [downsample(ink_frame(size, d, core=c), size) for d, c in specs]
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
    light, dark = hexc("#B8B8C0"), hexc("#62626C")
    mix = smooth((n2 - 0.38) * 2.4)[..., None]
    col = dark[None, None] * (1 - mix) + light[None, None] * mix
    alpha = np.clip(0.1 + 0.4 * dens, 0, 0.5) * smooth(dens * 5)
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
# flare (r15): contact flash = soft radial burst + two SLANTED, tapered spikes (no axis-aligned "+")
# ---------------------------------------------------------------------------------------------------------
# reviewer r11: the 128 px `glint` scaled to 1.6 blocks at ~0.8 blocks from the opponent eye read as a big axis-aligned
# cross with stair-stepped rays (texels nearest-neighbour). The flare is 256 px, the spikes are slanted (+14 deg long,
# -58 deg short, unequal arm lengths) and taper to needles with a 1.4 px feather, and the radial part is an opaque white /
# amber core with a translucent orange halo whose alpha is zeroed below 0.12 (the client discards text alpha < 0.1, so a
# fading halo would otherwise end in an unplanned hard contour).
FLARE_SPIKES = [(14.0, 118.0, 74.0, 5.2), (-58.0, 54.0, 38.0, 3.6)]  # angle deg, arm length +, arm length -, half width (px)


def flare_frame(size, k, glow):
    X, Y = grid(size)
    c = size / 2
    dx, dy = X - c, c - Y
    r = np.hypot(dx, dy)
    white, amber, orange = hexc("#FFFFFF"), hexc("#FFC040"), hexc("#FF7A12")
    img = blank(size * SS)
    R = 44.0 * k
    halo = 0.5 * glow * np.clip(1 - (r / R) ** 2, 0, 1) ** 1.6
    halo = np.where(halo < 0.12, 0.0, halo)
    img = over(img, orange[None, None] * np.ones((1, 1, 3)), halo)
    for ang, lp, lm, hw in FLARE_SPIKES:
        a = math.radians(ang)
        u = dx * math.cos(a) + dy * math.sin(a)
        v = -dx * math.sin(a) + dy * math.cos(a)
        L = np.where(u >= 0, lp, lm) * k
        t = np.clip(1 - np.abs(u) / L, 0, 1)
        w = hw * k * t ** 1.8
        for mul, col, al in ((1.9, orange, 0.55 * glow), (1.25, amber, 0.95), (0.6, white, 1.0)):
            img = over(img, col[None, None] * np.ones((1, 1, 3)), soft(w * mul - np.abs(v), 1.4) * (t > 0) * al)
    img = over(img, amber[None, None] * np.ones((1, 1, 3)), soft(17.0 * k - r, 1.5))
    img = over(img, white[None, None] * np.ones((1, 1, 3)), soft(10.0 * k - r, 1.5))
    return img


def make_flare():
    size = 256
    frames = []
    for k, g in ((1.0, 1.0), (0.7, 0.6)):
        fr = downsample(flare_frame(size, k, g), size)
        fr[fr[..., 3] < 0.1 * 255] = 0
        frames.append(fr)
    save_strip("flare", frames, size)


# ---------------------------------------------------------------------------------------------------------
# sliver: hot-headed shards with a charred tail, 3 variants
# ---------------------------------------------------------------------------------------------------------
# r21 (director r20: the r12 umber needles, alpha 0.8 with a 1.4 px feather on a 5-7 px body, read in caster FP t12 and
# compare t11-12 as thin GREY horizontal dashes around the hit-1 spark burst: a thin half-transparent dark lens mixes with
# the sky/floor behind it and loses its hue). Rebuilt as a spark shard: an opaque white-hot -> orange head on the leading
# end and a dark char tail, so the burst reads warm and the dark is a deliberate accent attached to a bright spark, never
# a free-floating smudge. Fully opaque body (alpha 1, 0.7 px feather), thicker (9-11 px of 128); every variant shares
# the 28 deg up-tilt (no per-frame rotation: a rotating flipbook read as a spinning "X" in r8), the emitter's slow
# random-sign spin varies the angle per particle.
SLIVER_TILT = 28.0
SLIVER_HOT, SLIVER_ORANGE, SLIVER_EMBER, SLIVER_CHAR = hexc("#FFF6C8"), hexc("#FF9A28"), hexc("#B8400E"), hexc("#2A1810")


def sliver_frame(size, ang_deg, length, wid, peak, bend, char=0.45):
    """One shard along the tilted axis; s = 0 tail .. 1 head. Head (s > 0.72) white-hot fading to orange, mid ember,
    tail (s < char) charcoal. Width peaks near the head (peak) and tapers to a needle at the tail."""
    X, Y = grid(size)
    c = size / 2
    a = math.radians(ang_deg)
    ca, sa = math.cos(a), math.sin(a)
    px, py = X - c, c - Y
    s = (px * ca + py * sa) / length + 0.5
    u = -px * sa + py * ca + bend * length * ((s - 0.5) ** 2 * 4 - 1)
    sc = np.clip(s, 0, 1)
    hw = wid / 2 * np.where(sc < peak, (sc / peak) ** 0.8, ((1 - sc) / (1 - peak)) ** 0.6)
    inside = (s > 0) & (s < 1)
    hw = np.where(inside, hw, 0)
    alpha = soft(hw - np.abs(u), 0.7) * inside
    # colour along the shard: char tail -> ember -> orange -> white-hot head
    t_ember = smooth((sc - (char - 0.12)) / 0.2)
    t_orange = smooth((sc - (char + 0.1)) / 0.18)
    t_hot = smooth((sc - 0.64) / 0.14)
    col = SLIVER_CHAR[None, None] * (1 - t_ember[..., None]) + SLIVER_EMBER[None, None] * t_ember[..., None]
    col = col * (1 - t_orange[..., None]) + SLIVER_ORANGE[None, None] * t_orange[..., None]
    # hot core only along the centre line of the head (edges stay orange so the head keeps a warm outline)
    core = soft(hw * 0.7 - np.abs(u), 0.6) * t_hot
    col = col * (1 - core[..., None]) + SLIVER_HOT[None, None] * core[..., None]
    img = blank(size * SS)
    return over(img, np.clip(col, 0, 1), alpha)


def make_sliver():
    size = 128
    # same tilt for every variant; they differ in length, thickness, bend and how far the char runs up the tail
    variants = [(SLIVER_TILT, 100, 10.0, 0.7, 0.0, 0.45), (SLIVER_TILT, 84, 9.0, 0.66, 0.015, 0.38),
                (SLIVER_TILT, 108, 11.0, 0.72, -0.012, 0.5)]
    frames = [downsample(sliver_frame(size, *v), size) for v in variants]
    save_strip("sliver", frames, size)


# ---------------------------------------------------------------------------------------------------------
# arch / ring (round 7): swing-plane smears that lie IN the animator's r6 blade planes (fixed billboard).
# Each sprite is drawn by mapping every pixel to its 3D point in the swing plane, so the art and the fx placement
# (bastion_s1_fx.py imports SWEEPS + sweep_euler) share one geometry. Frame: x = caster's left, y up, z forward,
# origin = the caster's feet at the fx anchor. A TextDisplay draws image-right along local +X, image-up along local
# +Y and is only trusted from its +Z side, so every smear ships as a FRONT strip (X = e1, Y = e2) plus a BACK strip
# (X = -e1, Y = e2, the mirrored image) with the opposite facing: the pair shows the same 3D arc from both sides.
# ---------------------------------------------------------------------------------------------------------
PALE = hexc("#FFFBE0")
SWEEP_HALF = 2.85  # blocks from the sprite centre to its edge; fx scale = 2 * SWEEP_HALF / 6.4
SWEEP_ZCAP = 2.3  # outer edge never at/behind the opponent camera (z 2.4)
SWEEP_KEEP_OUT = 0.8  # blocks kept clear around every camera eye (caster, opponent)
SWEEP_SMOOTH_DEG = 16.0  # gaussian sigma of the radius curl (a sharper curl reads as a wiggle)
_CAMS = [np.array([0.0, 1.62, 0.0]), np.array([0.0, 1.62, 2.4])]  # caster eye, hit-1 opponent eye (dash stop 2.4)
_PIVOT = np.array([-0.348, 0.773, 0.293])  # rig pivot = held-item fist (animator r7, animation-notes Round 7); smears are placed relative to it
_TIP = 2.545  # pivot -> blade tip
_t1 = math.radians(22.0)
# hit-2 ring plane (r7b): tip pitch on the plane ~= RING_CENTRE_PITCH - RING_TILT * cos(phi - 60). Least-squares fit to the
# r7 blade keys 24-30 (phi 163 .. 102) with the lowest point held at the floor pitch -15 (tip y 0.11): max off-plane
# 0.12 blocks over the whole arc. The r6 plane (-8 / 14) dipped to y -0.19 at phi 60 (under the floor) while the blade
# stayed at -15; a -1 / 14 plane fixes the floor but sits 0.31 above the r7 wind-up keys 23-27.
RING_CENTRE_PITCH = -5.0
RING_TILT = 10.0
_h60 = np.array([math.sin(math.radians(60)), 0.0, math.cos(math.radians(60))])
_n2 = math.cos(math.radians(RING_TILT)) * np.array([0.0, 1.0, 0.0]) + math.sin(math.radians(RING_TILT)) * _h60
_f2 = np.array([0.0, 0.0, 1.0]) - _n2[2] * _n2
_f2 = _f2 / np.linalg.norm(_f2)

# alpha = in-plane angle atan2(b, a) in degrees (a along e1, b along e2), sign = travel direction of the blade.
# frames: (tail, head, thickness scale, opacity); tail/head in alpha degrees, unwrapped along the travel.
SWEEPS = {
    # hit 1 (r10 rig): tilted horizontal sweep. The tip stays on the plane y = 1.15 + tan(20 deg) z (animator Round 10), so
    # the smear is a RING lying in that plane around the caster at chest (sides) / head (front) height. e1 = caster's left,
    # e2 = forward rising 20 deg. `aniso` = cos(20): pixel (a, b) -> horizontal radius hypot(a, b cos20) and angle
    # alpha = atan2(b cos20, a) about the tip circle centre (-0.35, 1.15, 0) (tip radius 2.44-2.48 at every on-plane key),
    # so alpha 0 = left, 90 = forward, 180 = right, 270 = back. The blade travels right -> front -> left (alpha decreasing).
    # Blade tip alpha per skill tick: 214 (4, raised wind-up extreme), 226 (5), 181.2 (6, on plane), 134.8 (7), 93.5 (8,
    # contact, held 8-10), 45.2 (11), -3.3 (12), -48.4 (13, leaving the plane down). Played in fx-high (skill = fx + 4).
    # Outer edge 2.75 (diameter 5.5 blocks across the sides), band 0.42 thick so the tip runs along the white-hot INNER
    # edge; dark rim outside. Front pulled in by z <= 2.3 and the opponent-eye keep-out, back held >= y 0.22 (the plane
    # meets the floor behind the caster); one global radius curve (no monotone curl: the ring continues past the front).
    "hring": dict(centre=np.array([-0.35, 1.15, 0.0]), e1=np.array([1.0, 0.0, 0.0]),
                  e2=np.array([0.0, math.sin(math.radians(20.0)), math.cos(math.radians(20.0))]), sign=-1, r_tip=2.75,
                  t_max=0.42, fan_in=1.9, half=3.0, aniso=math.cos(math.radians(20.0)), ellipse=(0.0, -0.15, 2.55, 1.75), ymin=0.2,
                  smooth=10.0, hot="inner", fan_alpha=0.0,
                  # r23 (director r22, opponent t9-10: the outer rim read as an ~8 px dark grey band over the sky). Cause:
                  # the r12 dark rim (#3A2A06 at <= 0.6 alpha, centred 1.5 px OUTSIDE the band) plus the 1.5 px outer
                  # feather: 23-27% of the visible texels of the contact frames were that translucent umber (straight
                  # (88, 59, 7), mean alpha 0.52), which over sky blue composites to grey (~(110, 115, 130)); fade frames
                  # (opacity 0.4 / 0.25) were 100% translucent. Fix: no rim outside (`rim_alpha` 0), no fan, a narrow outer
                  # feather (0.7 px), an OPAQUE ember edge line inside the silhouette (`edge` = colour, width px: dark
                  # mixed with bright, but it keeps its hue), alpha < 0.3 zeroed, and fades by thinning (opacity >= 0.8).
                  rim_alpha=0.0, feather_out=0.7, edge=("#C8500A", 1.8), alpha_floor=0.3,
                  # r16 (director r12: in the caster FP the front arc, which passes ~0.1 above the caster's eye at z 1.6,
                  # crossed the target's head / the crosshair as a ~60 px opaque band on t10-14): `fp_thin` = (floor, a0,
                  # a1) scales the band thickness by the horizontal angle from the caster's eye: a hairline (12%) within
                  # 12 deg of straight ahead, full again past 46 deg. The loop stays closed, so compare / side / opponent
                  # still see one ellipse around the body (its front end, at the target, becomes a fine line).
                  fp_thin=(0.12, 12.0, 46.0),
                  # r15 (reviewer r11: ring centre read 1-1.5 blocks BEHIND the caster from side / compare): ellipse centre
                  # z -0.50 -> -0.15 (within 0.3 of the caster), semi z 2.09 -> 1.75: the front stays at z 1.6 (opponent-eye
                  # keep-out unchanged), the back comes in from z -2.59 to -1.9 (y 0.46 there, was 0.2).
                  # r12: ellipse centred on the CASTER (x 0, was the tip-circle centre -0.35) and 2.55 wide (was 2.72): the
                  # right side (compare / side close-ups sit there) is 0.52 farther from those lenses, the left edge stays
                  # inside the 3.0 sprite half. Frames 7+ CLOSE the loop (span 360, thickness floor = 5th value): the
                  # ring reads as one ellipse around the body from every camera, as in the reference, not as a 230 deg arc.
                  # r13 (reviewer r9: from the side camera the ring first showed at t12, contact t8): hit_stop freezes the
                  # displays on skill 8-10, so the contact frame 3 is what every camera sees until t11. Frame 3 is now the
                  # CLOSED loop (head at the contact angle 93.5, floor 0.7 so the step where the head meets the tail stays small) as in reference b00/b01 (the
                  # ring is already nearly whole at t06-07); frames 0-2 lead the blade slightly so the arc grows with it.
                  frames=[(240, 200, 0.6, 1.0), (240, 160, 0.85, 1.0), (250, 110, 1.0, 1.0, 0.2), (453.5, 93.5, 1.0, 1.0, 0.7),
                          (453.5, 93.5, 1.0, 1.0, 0.7), (405.2, 45.2, 1.0, 1.0, 0.6), (356.7, -3.3, 1.0, 1.0, 0.5),
                          (330, -30, 0.9, 1.0, 0.4), (330, -30, 0.7, 0.95, 0.35), (330, -30, 0.55, 0.9, 0.3),
                          (330, -30, 0.4, 0.85, 0.3), (330, -30, 0.3, 0.8, 0.3)]),
    # hit 2 low ring: plane tilted RING_TILT (lowest toward phi 60 = left-front), centre on the pivot axis at the
    # cone-centre height (tip pitch RING_CENTRE_PITCH). alpha 0 ~ forward, +90 ~ left; the blade turns +alpha
    # (left-back -> back -> right -> front). Tip alpha per skill tick (r7, in this plane): 134 (26), 163 (27),
    # 210.8 (28), 265 (29), 320.7 (30), 379.1 contact (31, phi 18 y 0.11, held 31-32); follow 60.4 (34), 101.6 (35).
    # Played in fx-low (skill tick = fx tick + 27).
    "ring": dict(centre=_PIVOT + np.array([0.0, _TIP * math.sin(math.radians(RING_CENTRE_PITCH)), 0.0]), e1=_f2,
                 e2=np.cross(_n2, _f2), sign=1, r_tip=round(_TIP * math.cos(math.radians(RING_CENTRE_PITCH)), 3), fan_in=1.5, cams=[np.array([0.0, 1.62, 1.7])],  # hit-2 dash stop 1.7
                 # r13: shipped as 4 quadrant TILES (front face only, 2x texel density) with a soft edge, a thickness floor
                 # (the tail no longer dies to a needle) and a thicker BACK arc (alpha 120-250): the opponent (z 1.7) only
                 # sees the back half, nearly edge-on (~14 deg), so the r9 0.24-block band read as a hairline.
                 tiles=2, feather=1.2, floor_t=0.55, tail_deg=70.0, boost=(185.0, 70.0, 1.0),
                 # r14 (director r10, compare close-up t32: frame 0 showed a straight head cut and a translucent khaki wash with
                 # a straight top = the fan face, whose alpha-0.1 contour the client's text shader turns into a hard edge;
                 # outer edge still a 1-2 px comb at 2x): no fan (alpha exactly 0 inside the ring), an 18 deg needle head
                 # instead of the 6 deg blunt ramp, a wider OUTER ramp (2.0 px half-feather = ~8.5 tile texels) with a fainter,
                 # softer rim, every alpha < 0.1 zeroed in the art (the client discards it anyway), and tiles that only cover
                 # the ring (tile_half 2.65 instead of 2.85: 7.5% smaller texels at the same 4 displays).
                 fan_alpha=0.0, head_deg=18.0, feather_out=2.0, rim=(0.45, 1.6), tile_half=2.65, alpha_floor=0.1,
                 # r15 (reviewer r11: from the opponent / caster the low ring read as a thin PARTIAL arc; reference e04-e06 is a
                 # thick FULL low ring): t_max 0.24 -> 0.42 (front arc ~0.2 blocks on screen from the caster, seen ~29 deg from
                 # above), the back boost 2.2 -> 1.0 (0.84 blocks in-plane, was 0.77) and the contact frames 3-4 are CLOSED
                 # (tail = head - 360: uniform thickness all round, no head needle / tail taper, see sweep_frame); the fade
                 # frames open the loop again from the tail side.
                 t_max=0.42,
                 frames=[(158, 210.8, 0.75, 1.0), (158, 265, 0.9, 1.0), (130, 320.7, 1.0, 1.0), (19.1, 379.1, 1.0, 1.0),
                         (19.1, 379.1, 1.0, 1.0), (19.1, 379.1, 0.85, 0.9), (110, 379.1, 0.72, 0.8),
                         (200, 379.1, 0.58, 0.68), (270, 379.1, 0.44, 0.54), (320, 379.1, 0.3, 0.4),
                         (355, 379.1, 0.18, 0.28)]),
}


def sweep_basis(name, back=False):
    """World images of the sprite's local X, Y, Z (columns)."""
    sw = SWEEPS[name]
    x = -sw["e1"] if back else sw["e1"]
    y = sw["e2"]
    return np.stack([x, y, np.cross(x, y)], axis=1)


def sweep_euler(name, back=False):
    """[yaw, pitch, roll] for VfxTimeline (Quaternion.fromEulerDegrees: Ry(-yaw) * Rx(pitch) * Rz(roll))."""
    m = sweep_basis(name, back)
    # m = Ry(a) Rx(b) Rz(c):  m[1][2] = -sin b ; m[0][2] = sin a cos b ; m[2][2] = cos a cos b ; m[1][0] = cos b sin c ; m[1][1] = cos b cos c
    b = math.asin(max(-1.0, min(1.0, -m[1][2])))
    a = math.atan2(m[0][2], m[2][2])
    c = math.atan2(m[1][0], m[1][1])
    return [round(-math.degrees(a), 2) + 0.0, round(math.degrees(b), 2) + 0.0, round(math.degrees(c), 2) + 0.0]


def sweep_radius(name, tail, head, n=721):
    """Outer radius along the travel (q = degrees since the tail): the tip radius, pulled in where the arc would pass
    z SWEEP_ZCAP or come within SWEEP_KEEP_OUT of a camera eye; made non-increasing along the travel (the smear curls
    inward toward the head instead of notching) and smoothed (SWEEP_SMOOTH_DEG)."""
    sw = SWEEPS[name]
    span = sw["sign"] * (head - tail)
    qs = np.linspace(0.0, span, n)
    al = np.radians(tail + sw["sign"] * qs)
    d = np.cos(al)[:, None] * sw["e1"][None] + np.sin(al)[:, None] * sw["e2"][None]
    r = np.full(n, sw["r_tip"])
    r = np.minimum(r, np.where(d[:, 2] > 1e-3, (SWEEP_ZCAP - sw["centre"][2]) / np.maximum(d[:, 2], 1e-3), 99.0))
    for cam in _CAMS + sw.get("cams", []):
        w = sw["centre"] - cam
        wd = d @ w
        disc = wd * wd - (w @ w - SWEEP_KEEP_OUT ** 2)
        r1 = -wd - np.sqrt(np.maximum(disc, 0.0))
        tip_in = np.linalg.norm(w[None] + r[:, None] * d, axis=1) < SWEEP_KEEP_OUT  # only where the arc itself is inside
        r = np.minimum(r, np.where(tip_in & (disc > 0) & (r1 > 0), r1, 99.0))
    if span < 359.9:  # r15: a closed loop keeps the per-angle limit (no curl: the ring continues past the front)
        r = np.minimum.accumulate(r)
    k = max(1, int(SWEEP_SMOOTH_DEG / max(span, 1e-3) * n))
    ker = np.exp(-0.5 * (np.arange(-3 * k, 3 * k + 1) / k) ** 2)
    lim = r
    for _ in range(4):  # smooth, then clamp back under the limit: converges to a smooth curve below it
        rp = np.concatenate([np.full(3 * k, r[0]), r, np.full(3 * k, r[-1])])
        r = np.minimum(np.convolve(rp, ker / ker.sum(), mode="valid"), lim)
    rp = np.concatenate([np.full(3 * k, r[0]), r, np.full(3 * k, r[-1])])
    return qs, np.convolve(rp, ker / ker.sum(), mode="valid") - 0.03


def sweep_dir(sw, al):
    """3D offset per unit horizontal radius for in-plane angle(s) al (radians); unit vectors unless aniso."""
    k = sw.get("aniso", 1.0)
    return np.cos(al)[:, None] * sw["e1"][None] + (np.sin(al) / k)[:, None] * sw["e2"][None]


def ring_radius(name):
    """hit-1 ring: outer radius as ONE function of the absolute (unwrapped) alpha, shared by every frame: the polar radius
    (about the tip circle centre) of a horizontal ELLIPSE (`ellipse` = centre x, centre z, semi x, semi z). The ellipse is
    squashed front-to-back and shifted back so its front stays at z <= ~1.85 (0.6 clear of the opponent eye at z 2.4) and
    its back stays above ymin; a dent around the eye read as a wiggle (r10 attempt), a smooth ellipse reads as a ring."""
    sw = SWEEPS[name]
    ex, ez, ax, az = sw["ellipse"]
    al_deg = np.arange(-400.0, 800.0, 0.5)
    al = np.radians(al_deg)
    c = sw["centre"]
    ux, uz = np.cos(al), np.sin(al)  # horizontal ray from the tip circle centre
    ox, oz = c[0] - ex, c[2] - ez
    A = (ux / ax) ** 2 + (uz / az) ** 2
    B = 2 * (ox * ux / ax ** 2 + oz * uz / az ** 2)
    C = (ox / ax) ** 2 + (oz / az) ** 2 - 1
    r = (-B + np.sqrt(B * B - 4 * A * C)) / (2 * A)
    d = sweep_dir(sw, al)
    r = np.minimum(r, np.where(d[:, 1] < -1e-3, (c[1] - sw["ymin"]) / np.maximum(-d[:, 1], 1e-3), 99.0))
    return al_deg, r


def ring_frame(size, name, tail, head, tscale, opacity, floor_t=0.0):
    """hit-1 ring frame (front view, X = e1): gold band whose INNER edge is white-hot (the blade tip runs along it), dark
    rim outside, needle toward the tail, faint fan inward. Radius / angle are horizontal (aniso).
    floor_t: minimum thickness (fraction of t_max) along the whole span; a span >= 360 deg is a CLOSED loop (no end cuts)."""
    sw = SWEEPS[name]
    X, Y = grid(size)
    half = sw["half"]
    px = 2.0 * half / size
    a = (X - size / 2) * px
    b = (size / 2 - Y) * px
    zz = b * sw["aniso"]
    r = np.hypot(a, zz)
    ang = np.degrees(np.arctan2(zz, a))
    sgn = sw["sign"]
    q = np.mod(sgn * (ang - tail), 360.0)
    qh = sgn * (head - tail)
    dh = qh - q
    span = qh
    al_pix = tail + sgn * np.clip(q, 0, span)
    world = (sw["centre"][:, None, None] + a[None] * sw["e1"][:, None, None] + b[None] * sw["e2"][:, None, None])
    als, rs = ring_radius(name)
    r_out = np.interp(al_pix, als, rs)
    u = np.clip(dh / max(span, 1e-3), 0, 1)
    prof = smooth(dh / 26.0) * np.clip(1.0 - u, 0, 1) ** 0.7  # needle head over ~26 deg (no blunt radial cut)
    closed = span >= 359.9
    if floor_t > 0:
        prof = floor_t + (1 - floor_t) * prof
    if sw.get("fp_thin"):  # r16: thin the near arc in front of the caster's eye (x 0) down to a hairline
        lo, a0, a1 = sw["fp_thin"]
        th = np.degrees(np.abs(np.arctan2(world[0], np.maximum(world[2], 1e-3))))
        prof = prof * np.where(world[2] > 0, lo + (1 - lo) * smooth((th - a0) / (a1 - a0)), 1.0)
    inside = (dh >= 0) & (dh <= span) if not closed else np.ones_like(dh, dtype=bool)
    T = sw["t_max"] * tscale * prof
    d_out = (r_out - r) / px
    d_in = (r - (r_out - T)) / px
    # r12: 1.5 px half-feather (was 0.9): the real client draws font texels nearest-neighbour, so at the compare close-up
    # (~16 screen px per texel) a 1-2 texel edge is a hard stair; a 3-texel ramp reads as a soft edge.
    # r23 (director r22: the outer rim read as a dark grey band over the sky, opponent t9-10): a separate, narrower OUTER
    # feather (`feather_out`) so the silhouette has at most ~1 partial texel; the inner (white-hot) edge keeps 1.5.
    fo = sw.get("feather_out", 1.5)
    band = soft(d_out, fo) * soft(d_in, 1.5) * inside
    cut = np.minimum(dh, span - dh) * math.radians(1.0) * r / px if not closed else np.full_like(dh, 99.0)
    band *= soft(cut + 0.4, 1.0)
    floor = soft((world[1] - 0.04) / px, 1.0)
    tt = np.clip(d_out / np.maximum(T / px, 1e-3), 0, 1)  # 0 outer .. 1 inner
    col = BODY[None, None] * (1 - tt[..., None] ** 0.7) + GOLD[None, None] * tt[..., None] ** 0.7
    hot = soft(np.minimum(3.0, 0.2 * T / px) - d_in, 0.8) * (d_in > -0.5)
    col = col * (1 - hot[..., None] * 0.95) + PALE[None, None] * hot[..., None] * 0.95
    if sw.get("edge"):  # r23: crisp warm outer edge INSIDE the silhouette (opaque, keeps its hue over the sky)
        ec, ew = sw["edge"]
        e = soft(ew - d_out, 0.6) * (1 - hot)
        col = col * (1 - e[..., None]) + hexc(ec)[None, None] * e[..., None]
    img = blank(size * SS)
    fan_r0 = sw["fan_in"]
    fr = np.clip((r - fan_r0) / np.maximum(r_out - T - fan_r0, 1e-3), 0, 1)
    fan = (fr ** 1.6) * (r < r_out - T * 0.5) * (r > fan_r0) * inside * np.clip(1 - u, 0, 1) ** 1.6 * np.clip(dh / 10, 0, 1)
    img = over(img, hexc("#FFC83A")[None, None] * np.ones((1, 1, 3)), fan * sw["fan_alpha"] * tscale * floor)
    # dark rim: softer and lighter than r11 (hard 0.85 near-black line = the highest-contrast stair at the close-up).
    # r23: `rim_alpha` 0 for hring: a translucent near-black skirt OUTSIDE the band mixes with the sky into grey.
    rim = soft(1.6 - np.abs(d_out + 1.5), 1.0) * inside * np.clip(prof * 3, 0, 1) * soft(cut + 0.4, 1.0)
    img = over(img, RIM[None, None] * np.ones((1, 1, 3)), rim * sw.get("rim_alpha", 0.6) * floor)
    img = over(img, np.clip(col, 0, 1), band * floor)
    img[..., :4] *= opacity
    return img


def sweep_frame(size, name, tail, head, tscale, opacity, window=None):
    """One smear frame (front view, X = e1): crisp gold band along the tip path (outer edge = blade tip, white-hot
    outer line, dark rim outside), thickest just behind the head, needle toward the tail, plus a faint fan face
    inward toward the pivot (wind-slash fan). Outer radius from sweep_radius.
    window = (sa, sb, n): render tile (sa, sb) (each -1/+1 per axis) of an n x n split of the full sprite square, at
    `size` px per tile (n x texel density). Distances / feathers stay in full-sprite px (px), so the shape is identical
    and only the texels shrink."""
    sw = SWEEPS[name]
    X, Y = grid(size)
    px = 2.0 * SWEEP_HALF / size  # blocks per full-sprite px (shape units)
    if window is None:
        a = (X - size / 2) * px
        b = (size / 2 - Y) * px
    else:
        sa, sb, n = window
        h = sw.get("tile_half", SWEEP_HALF) / n
        a = sa * h + (X - size / 2) * (2 * h / size)
        b = sb * h + (size / 2 - Y) * (2 * h / size)
    r = np.hypot(a, b)
    ang = np.degrees(np.arctan2(b, a))
    sgn = sw["sign"]
    q = np.mod(sgn * (ang - tail), 360.0)  # degrees travelled since the tail
    qh = sgn * (head - tail)
    dh = qh - q  # degrees behind the head (>= 0 inside the span)
    span = qh
    world = (sw["centre"][:, None, None] + a[None] * sw["e1"][:, None, None] + b[None] * sw["e2"][:, None, None])
    qs, rs = sweep_radius(name, tail, head)
    r_out = np.interp(np.clip(q, 0, span), qs, rs)
    # thickness along the span: 6 deg blunt head ramp, then decay to a needle at the tail
    u = np.clip(dh / max(span, 1e-3), 0, 1)
    head = smooth(dh / sw["head_deg"]) if sw.get("head_deg") else np.clip(dh / 6.0, 0, 1)  # r14: needle head, no blunt cut
    prof = head * np.clip(1.0 - u, 0, 1) ** 0.8
    if sw.get("floor_t"):  # r13: hold a minimum thickness, taper only the last tail_deg into a needle
        ft = sw["floor_t"]
        prof = head * (ft + (1 - ft) * np.clip(1.0 - u, 0, 1) ** 0.8) * smooth(q / sw["tail_deg"])
    closed = span >= 359.9
    if closed:  # r15: a closed loop has no head / tail: uniform thickness all round (boost still applies)
        prof = np.ones_like(dh)
    if sw.get("boost"):  # r13: thicker band around alpha c (+- w deg, cosine bump), factor 1 + k
        c, w, k = sw["boost"]
        al = tail + sw["sign"] * np.clip(q, 0, span)
        dd = np.abs((al - c + 180.0) % 360.0 - 180.0)
        prof = prof * (1.0 + k * 0.5 * (1 + np.cos(np.pi * np.clip(dd / w, 0, 1))))
    inside = ((dh >= 0) & (dh <= span)) | closed
    T = sw["t_max"] * tscale * prof
    fe = sw.get("feather", 0.9)
    d_out = (r_out - r) / px  # px inside the outer edge
    d_in = (r - (r_out - T)) / px  # px outside the inner edge
    if sw.get("feather_out"):  # r14: wider ramp on the outer (silhouette) edge only, the inner edge stays crisp
        band = soft(d_out, sw["feather_out"]) * soft(d_in, fe) * inside
    else:
        band = soft(np.minimum(d_out, d_in), fe) * inside
    cut = np.minimum(dh, span - dh) * math.radians(1.0) * r / px  # soften the angular ends
    if closed:
        cut = np.full_like(dh, 99.0)
    band *= soft(cut + 0.4, 1.0)
    floor = soft((world[1] - 0.04) / px, 1.0)  # see_through text would show under the floor
    tt = np.clip(d_out / np.maximum(T / px, 1e-3), 0, 1)  # 0 outer .. 1 inner
    col = GOLD[None, None] * (1 - tt[..., None] ** 1.5) + BODY[None, None] * tt[..., None] ** 1.5
    hot = soft(np.minimum(2.2, 0.45 * T / px) - d_out, 0.8) * (d_out > -0.5)
    col = col * (1 - hot[..., None] * 0.92) + PALE[None, None] * hot[..., None] * 0.92
    img = blank(size * SS)
    # fan face: translucent gold wedge from the band's inner edge toward the pivot, fades with distance behind the head
    fan_r0 = sw["fan_in"]
    fr = np.clip((r - fan_r0) / np.maximum(r_out - T - fan_r0, 1e-3), 0, 1)
    fan = (fr ** 1.6) * (r < r_out - T * 0.5) * (r > fan_r0) * inside * np.clip(1 - u, 0, 1) ** 1.4 * np.clip(dh / 10, 0, 1)
    img = over(img, hexc("#FFC83A")[None, None] * np.ones((1, 1, 3)), fan * sw.get("fan_alpha", 0.3) * tscale * floor)
    # dark rim just outside the outer edge (dark mixed with bright)
    if sw.get("rim"):  # r14: (alpha, feather) of the soft rim, centred just outside the wider outer ramp
        ra, rf = sw["rim"]
        rim = soft(1.6 - np.abs(d_out + 1.5 + 0.5 * (sw.get("feather_out", fe) - fe)), rf) * inside * np.clip(prof * 3, 0, 1) * soft(cut + 0.4, 1.0)
        img = over(img, RIM[None, None] * np.ones((1, 1, 3)), rim * ra * floor)
    elif fe > 1.0:  # r13: soft, lighter rim (font texels are nearest-neighbour: a hard near-black line is the worst stair)
        rim = soft(1.6 - np.abs(d_out + 1.5), 1.0) * inside * np.clip(prof * 3, 0, 1) * soft(cut + 0.4, 1.0)
        img = over(img, RIM[None, None] * np.ones((1, 1, 3)), rim * 0.55 * floor)
    else:
        rim = soft(1.3 - np.abs(d_out + 0.9), 0.6) * inside * np.clip(prof * 3, 0, 1) * soft(cut + 0.4, 1.0)
        img = over(img, RIM[None, None] * np.ones((1, 1, 3)), rim * 0.75 * floor)
    img = over(img, np.clip(col, 0, 1), band * floor)
    img[..., :4] *= opacity  # premultiplied: fade by frame
    return img


TILES = [(-1, 1), (1, 1), (-1, -1), (1, -1)]  # tile q = (sa, sb): sign of the e1 / e2 offset of its centre


def make_sweep(name):
    size = 256
    if SWEEPS[name].get("tiles"):  # r13: front-only tiles <name>-q0..q3 (every camera is on the +Z side of the plane)
        n = SWEEPS[name]["tiles"]
        for qi, (sa, sb) in enumerate(TILES):
            frames = [downsample(sweep_frame(size, name, *f, window=(sa, sb, n)), size) for f in SWEEPS[name]["frames"]]
            af = SWEEPS[name].get("alpha_floor", 0.0)
            for fr in frames:  # r14: the client discards text alpha < 0.1; zero it here so frame 0 is clean outside the arc
                fr[fr[..., 3] < af * 255] = 0
            save_strip(f"{name}-q{qi}", frames, size)
        return
    fn = ring_frame if "aniso" in SWEEPS[name] else sweep_frame
    frames = [downsample(fn(size, name, *f), size) for f in SWEEPS[name]["frames"]]
    af = SWEEPS[name].get("alpha_floor", 0.0)
    for fr in frames:  # r23: no faint texels (the client discards < 0.1; 0.1-0.3 over the sky reads as a grey skirt)
        fr[fr[..., 3] < af * 255] = 0
    save_strip(name, frames, size)
    save_strip(name + "-b", [np.ascontiguousarray(f[:, ::-1]) for f in frames], size)


def make_ring():
    make_sweep("ring")


def make_hring():
    make_sweep("hring")


MAKERS = {"crescent": make_crescent, "ink": make_ink, "streak": make_streak, "smoke": make_smoke,
          "glint": make_glint, "flare": make_flare, "sliver": make_sliver, "ring": make_ring, "hring": make_hring}

if __name__ == "__main__":
    for nm in (sys.argv[1:] or MAKERS):
        MAKERS[nm]()
