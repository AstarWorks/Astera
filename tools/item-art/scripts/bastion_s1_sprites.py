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
# ink: ONE connected pale-yellow brush mass with horizontal tongues (reference e00-e02), translucent toward the
# tips, dissolving over the last 3 frames (round 8: the r7 slab read as an opaque flat yellow bar and snapped off)
# ---------------------------------------------------------------------------------------------------------
# r9 (real client r6): the r8 ink read pale cream (hot top band + shade bottom + see-through tips washed it over the grey
# ground: measured in-game mean (228, 214, 152), hue 50). Reference e00-e02 ink pixels sampled: a FLAT lemon yellow,
# mean (252, 253, 174) = #FCFDAE, hue 60, s 0.31, v 0.99, with almost no variance. Fill = that colour, nearly opaque,
# no hot band; only a faint same-hue shade on the tongue bottoms and the dark rim (dark mixed with bright).
INK_FLAT = hexc("#FCFDAE")
INK_HOT = hexc("#FFFFD0")
INK_SHADE = hexc("#E6E070")
INK_RIM = hexc("#5E5A14")
# tongue table: centre y offset from the sprite centre (px, + = down), half thickness at the tip end, reach (0..1 of
# the full width), wobble seed. The root (left) spine joins them, the gaps open toward the tips.
INK_TONGUES = [(-62, 11, 0.70, 1), (-30, 17, 1.00, 2), (6, 20, 0.92, 3), (40, 15, 0.80, 4), (66, 9, 0.55, 5)]


def ink_frame(size, grow, diss, boil=0.0, seed=7):
    """grow: 0..1 reach of the tongues; diss: 0 (solid) .. 1 (gone), eroding tips first through 2D noise; boil: a
    small phase shift of the edge noise so consecutive full frames are not identical."""
    n = size * SS
    X, Y = grid(size)
    rng = np.random.default_rng(seed)
    cy = size / 2
    left = 18.0
    width = size - 30.0
    u = np.clip((X - left) / width, 0, 1)  # 0 root .. 1 far tip
    xs_idx = np.clip((X * SS + boil * 40 * SS).astype(int), 0, n - 1)
    field = np.full(X.shape, -9.0)
    tone = np.zeros(X.shape)  # 0 top of the tongue .. 1 bottom, for the hot/shade bands
    for yc, h, reach, ts in INK_TONGUES:
        r2 = np.random.default_rng(seed * 10 + ts)
        wob = noise1d(n, r2, 5, 3)[xs_idx]
        thick = noise1d(n, r2, 9, 2)[xs_idx]
        tipn = noise1d(n, r2, 12, 2)[np.clip((Y * SS).astype(int), 0, n - 1)]
        tip = reach * grow * (0.86 + 0.14 * tipn)  # ragged, slightly torn tip line
        centre = cy + yc * (0.55 + 0.45 * u) + 9.0 * (wob - 0.5)  # tongues fan out from the spine
        hh = h * (0.75 + 0.5 * thick) * (1.0 + 1.4 * (1 - np.clip(u / 0.35, 0, 1)) ** 2)  # fatter at the root
        hh = hh * np.clip((tip - u) / 0.12, 0, 1) ** 0.45  # taper into the tip
        bow = left + 2.0 + 34.0 * ((Y - cy) / 90.0) ** 2 + 8.0 * (tipn - 0.5)  # rounded, ragged back edge
        d = np.minimum(np.minimum(hh - np.abs(Y - centre), (tip - u) * width), X - bow)
        better = d > field
        tone = np.where(better, np.clip((Y - (centre - hh)) / np.maximum(2 * hh, 1e-3), 0, 1), tone)
        field = np.maximum(field, d)
    # root: a ragged ellipse joining every tongue (one connected mass), radius modulated by angle noise
    cx, rx, ry = left + 22.0, 24.0, 74.0 * grow ** 0.5
    ang = np.arctan2(Y - cy, X - cx)
    an = noise1d(720, rng, 16, 3)[np.clip(((ang + np.pi) / (2 * np.pi) * 719).astype(int), 0, 719)]
    rr = np.hypot((X - cx) / rx, (Y - cy) / ry) / (0.72 + 0.5 * an)
    sd = (1.0 - rr) * rx
    field = np.maximum(field, np.minimum(sd, X - 3.0))
    alpha = soft(field, 1.6)
    # translucency gradient: opaque root, see-through tips; dry-brush breaks in the outer half
    streak = fbm(n, np.random.default_rng(seed + 99), base=3, octaves=3)
    streak = np.asarray(Image.fromarray(streak.astype(np.float32), mode="F").resize((n, n // 6), Image.BICUBIC)
                        .resize((n, n), Image.BICUBIC))  # stretched horizontally = brush grain
    alpha *= (1.0 - 0.12 * u ** 2) * (1.0 - 0.6 * np.clip(u - 0.25, 0, 1) / 0.75 * soft(streak - 0.55, 0.06))
    # dissolve: tips first, then holes through the body, then the root
    if diss > 0:
        dn = fbm(n, np.random.default_rng(seed + 7), base=6, octaves=3)
        thr = 0.55 * dn + 0.45 * u
        alpha *= soft((1.0 - diss) - thr, 0.05) * (1.0 - 0.4 * diss)
    col = INK_FLAT[None, None] * np.ones((n, n, 1))
    hot = soft(0.38 - tone, 0.12) * soft(field - 2.0, 1.5)
    shade = soft(tone - 0.72, 0.1)
    col = col * (1 - hot[..., None] * 0.15) + INK_HOT[None, None] * hot[..., None] * 0.15
    col = col * (1 - shade[..., None] * 0.35) + INK_SHADE[None, None] * shade[..., None] * 0.35
    g = soft(streak - 0.62, 0.05)[..., None] * 0.12
    col = col * (1 - g) + INK_SHADE[None, None] * g
    rimk = soft(1.6 - field, 1.0) * 0.45
    col = col * (1 - rimk[..., None]) + INK_RIM[None, None] * rimk[..., None]
    return over(blank(n), np.clip(col, 0, 1), alpha)


def make_ink():
    """6 frames, frame k shows at element tick k + 1 (frame 0 on spawn and spawn + 1): splash (0.8 reach), full,
    full (boiled edge), then a 3-tick dissolve (0.25 / 0.45 / 0.7)."""
    size = 256
    specs = [(0.80, 0.0, 0.0), (1.0, 0.0, 0.0), (1.0, 0.0, 0.5), (1.0, 0.25, 0.7), (1.0, 0.45, 0.9), (1.0, 0.7, 1.0)]
    frames = [downsample(ink_frame(size, g, d, boil=b), size) for g, d, b in specs]
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
    # hit 1 overhead chop: vertical plane containing forward, rolled 22 deg (top toward the caster's right),
    # through the pivot. alpha 0 = forward, 90 = up (pitch 68 at skill tick 5). Tip alpha per skill tick (r7):
    # 155 (3), 137.5 (4), 87 (5), 36 (6), -16.2 contact (7, held 7-8: tip phi 6, pitch -15, y 0.11 ON the chop plane;
    # r6 was -23 = y -0.15). Played in fx-high (skill tick = fx tick + 3).
    "arch": dict(centre=_PIVOT, e1=np.array([0.0, 0.0, 1.0]), e2=np.array([-math.sin(_t1), math.cos(_t1), 0.0]),
                 sign=-1, r_tip=_TIP, t_max=0.36, fan_in=1.15,
                 frames=[(137.5, 87, 0.8, 1.0), (137.5, 36, 0.95, 1.0), (137.5, -16.2, 1.0, 1.0), (137.5, -16.2, 1.0, 1.0),
                         (112, -16.2, 0.85, 0.9), (75, -16.2, 0.7, 0.78), (40, -16.2, 0.52, 0.62), (10, -16.2, 0.34, 0.46),
                         (-8, -16.2, 0.2, 0.32)]),
    # hit 2 low ring: plane tilted RING_TILT (lowest toward phi 60 = left-front), centre on the pivot axis at the
    # cone-centre height (tip pitch RING_CENTRE_PITCH). alpha 0 ~ forward, +90 ~ left; the blade turns +alpha
    # (left-back -> back -> right -> front). Tip alpha per skill tick (r7, in this plane): 134 (26), 163 (27),
    # 210.8 (28), 265 (29), 320.7 (30), 379.1 contact (31, phi 18 y 0.11, held 31-32); follow 60.4 (34), 101.6 (35).
    # Played in fx-low (skill tick = fx tick + 27).
    "ring": dict(centre=_PIVOT + np.array([0.0, _TIP * math.sin(math.radians(RING_CENTRE_PITCH)), 0.0]), e1=_f2,
                 e2=np.cross(_n2, _f2), sign=1, r_tip=round(_TIP * math.cos(math.radians(RING_CENTRE_PITCH)), 3), t_max=0.24, fan_in=1.5, cams=[np.array([0.0, 1.62, 1.7])],  # hit-2 dash stop 1.7
                 frames=[(158, 210.8, 0.75, 1.0), (158, 265, 0.9, 1.0), (158, 320.7, 1.0, 1.0), (158, 379.1, 1.0, 1.0),
                         (158, 379.1, 1.0, 1.0), (205, 379.1, 0.85, 0.9), (250, 379.1, 0.72, 0.8),
                         (290, 379.1, 0.58, 0.68), (322, 379.1, 0.44, 0.54), (348, 379.1, 0.3, 0.4),
                         (364, 379.1, 0.18, 0.28)]),
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
    r = np.minimum.accumulate(r)
    k = max(1, int(SWEEP_SMOOTH_DEG / max(span, 1e-3) * n))
    ker = np.exp(-0.5 * (np.arange(-3 * k, 3 * k + 1) / k) ** 2)
    lim = r
    for _ in range(4):  # smooth, then clamp back under the limit: converges to a smooth curve below it
        rp = np.concatenate([np.full(3 * k, r[0]), r, np.full(3 * k, r[-1])])
        r = np.minimum(np.convolve(rp, ker / ker.sum(), mode="valid"), lim)
    rp = np.concatenate([np.full(3 * k, r[0]), r, np.full(3 * k, r[-1])])
    return qs, np.convolve(rp, ker / ker.sum(), mode="valid") - 0.03


def sweep_frame(size, name, tail, head, tscale, opacity):
    """One smear frame (front view, X = e1): crisp gold band along the tip path (outer edge = blade tip, white-hot
    outer line, dark rim outside), thickest just behind the head, needle toward the tail, plus a faint fan face
    inward toward the pivot (wind-slash fan). Outer radius from sweep_radius."""
    sw = SWEEPS[name]
    X, Y = grid(size)
    px = 2.0 * SWEEP_HALF / size  # blocks per final px
    a = (X - size / 2) * px
    b = (size / 2 - Y) * px
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
    prof = np.clip(dh / 6.0, 0, 1) * np.clip(1.0 - u, 0, 1) ** 0.8
    inside = (dh >= 0) & (dh <= span)
    T = sw["t_max"] * tscale * prof
    d_out = (r_out - r) / px  # px inside the outer edge
    d_in = (r - (r_out - T)) / px  # px outside the inner edge
    band = soft(np.minimum(d_out, d_in), 0.9) * inside
    cut = np.minimum(dh, span - dh) * math.radians(1.0) * r / px  # soften the angular ends
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
    img = over(img, hexc("#FFC83A")[None, None] * np.ones((1, 1, 3)), fan * 0.3 * tscale * floor)
    # dark rim just outside the outer edge (dark mixed with bright)
    rim = soft(1.3 - np.abs(d_out + 0.9), 0.6) * inside * np.clip(prof * 3, 0, 1) * soft(cut + 0.4, 1.0)
    img = over(img, RIM[None, None] * np.ones((1, 1, 3)), rim * 0.75 * floor)
    img = over(img, np.clip(col, 0, 1), band * floor)
    img[..., :4] *= opacity  # premultiplied: fade by frame
    return img


def make_sweep(name):
    size = 256
    frames = [downsample(sweep_frame(size, name, *f), size) for f in SWEEPS[name]["frames"]]
    save_strip(name, frames, size)
    save_strip(name + "-b", [np.ascontiguousarray(f[:, ::-1]) for f in frames], size)


def make_ring():
    make_sweep("ring")


def make_arch():
    make_sweep("arch")


MAKERS = {"crescent": make_crescent, "ink": make_ink, "streak": make_streak, "smoke": make_smoke,
          "glint": make_glint, "sliver": make_sliver, "ring": make_ring, "arch": make_arch}

if __name__ == "__main__":
    for nm in (sys.argv[1:] or MAKERS):
        MAKERS[nm]()
