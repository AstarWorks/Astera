#!/usr/bin/env python3
"""bastion-s3 ring 1 sprites (r3). Thick multi-layer gold plate ring around the caster's feet, 2 x 2 tiles.

    python3 tools/item-art/scripts/bastion_s3_ring.py        # writes content/textures/sources/images/bastion-s3-ring-q0..3.png
    flock /tmp/claude-1000/item-art.lock ./gradlew -q :tools:item-art:run

Ring = flat plane (tilted TILT deg about the forward axis: left side up) centred on the fx origin, outer radius 2.85,
band 0.55 blocks: dark outer rim (opaque) / orange-gold body / pale gold / white-hot core line, two dark plate separators.
It travels CW seen from above (front -> right -> back -> left, theta clockwise from forward). Frame k (k >= 1) is the state
at fx tick 4 + k (the runtime flipbook shows frame 0 on spawn and spawn + 1 and frame k on spawn + 1 + k; spawn = 3), frame 0
is blank. Head sweeps 360 deg by T8, the tail then erodes front/right first and the back/left last (gone at T15).
Tile (sa, sb) = window of a 2 x 2 split, texel = 3.0 / 256 blocks, overlap 2 texels per edge (no crack).
"""
import math
import os

import numpy as np
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "content", "textures", "sources", "images")
SS = 4
N = 256
HALF = 3.0  # blocks from the full sprite centre to its edge (tile = half of it)
TILT = 8.0
R_OUT, R_IN = 2.85, 2.30
TILES = [(-1, 1), (1, 1), (-1, -1), (1, -1)]
OV = 2


def hexc(h):
    return np.array([int(h[i:i + 2], 16) / 255.0 for i in (1, 3, 5)])


def smooth(x):
    x = np.clip(x, 0, 1)
    return x * x * (3 - 2 * x)


def soft(d, f):
    return smooth((d + f) / (2 * f))


# fx tick -> (tail, head, thin) ; thin = band thickness fraction
def state(T):
    head = {5: 100.0, 6: 215.0, 7: 325.0}.get(T, 345.0)
    tail = -15.0 if T <= 9 else -15.0 + (T - 9) * 65.0
    thin = 1.0 if T <= 11 else 1.0 - 0.1 * (T - 11)
    return tail, head, thin


T_FIRST, T_LAST = 5, 15
FRAMES = 1 + (T_LAST - T_FIRST + 1)


def noise_theta(theta, seed, cells):
    rng = np.random.default_rng(seed)
    pts = rng.random(cells + 2)
    x = np.mod(theta, 360.0) / 360.0 * cells
    i = np.floor(x).astype(int)
    t = x - i
    t = t * t * (3 - 2 * t)
    return pts[i] * (1 - t) + pts[i + 1] * t


def frame(sa, sb, T):
    n = N * SS
    ys, xs = np.mgrid[0:n, 0:n]
    X, Y = (xs + 0.5) / SS, (ys + 0.5) / SS
    h = HALF / 2
    hw = h * (1 + 2.0 * OV / N)
    k = 2 * hw / N  # blocks per final px
    a = sa * h + (X - N / 2) * k
    b = sb * h + (N / 2 - Y) * k
    r = np.hypot(a, b)
    theta = np.degrees(np.arctan2(-a, -b))  # clockwise from forward (forward = image down = -b)
    tail, head, thin = state(T)
    span = head - tail
    q = np.mod(theta - tail, 360.0)
    closed = span >= 359.9
    inside = np.ones_like(q) if closed else (q <= span).astype(float)
    # thickness profile: blunt head (short ramp), long needle toward the tail
    dh = span - q
    f_head = 1.0 if closed else smooth(dh / 6.0)
    if closed:
        f = np.ones_like(q)
    else:
        f = f_head * np.clip(q / 75.0, 0, 1) ** 0.8
    if tail > -14.9 and span >= 359.9 - 1e-6:
        pass
    # when eroding (tail moved) the cut is a needle taper from the tail
    if not closed:
        pass
    rc = 0.5 * (R_OUT + R_IN)
    hwid = 0.5 * (R_OUT - R_IN) * f * thin
    # slight waviness of the band edges (hand-cut brush look), not on the inner core
    wob = (noise_theta(theta, 5, 24) - 0.5) * 0.07
    d_edge = hwid - np.abs(r - rc - wob * 0.5)  # >0 inside the band
    px = k
    alpha = soft(d_edge / px, 1.1) * inside
    # radial coordinate s: 0 inner edge .. 1 outer edge of the current (tapered) band
    s = np.clip((r - (rc - hwid)) / np.maximum(2 * hwid, 1e-4), 0, 1)
    pale = hexc("#FFF2B0")
    gold = hexc("#FFD23A")
    amber = hexc("#E8921A")
    rim = hexc("#5A3306")
    hot = hexc("#FFF0A8")
    col = np.empty(a.shape + (3,))
    base = np.where(s[..., None] < 0.5, gold * (s[..., None] / 0.5) + pale * (1 - s[..., None] / 0.5),
                    gold * (1 - (s[..., None] - 0.5) / 0.5) + amber * ((s[..., None] - 0.5) / 0.5))
    # bright core line at s ~ 0.22 (the blade path)
    core = np.exp(-((s - 0.18) / 0.07) ** 2)[..., None] * np.clip(f, 0, 1)[..., None]
    base = base * (1 - core) + hot * core
    # plate separators (dark, opaque) at s 0.55 and 0.78, broken by noise along the arc
    sep_mask = np.zeros_like(s)
    for s0, wd, seed in ((0.56, 0.035, 11), (0.78, 0.03, 23)):
        sm = soft((wd - np.abs(s - s0)) / (wd * 0.6) * 1.0, 0.6)
        present = (noise_theta(theta, seed, 18) > 0.28).astype(float)
        sep_mask = np.maximum(sep_mask, sm * present)
    base = base * (1 - sep_mask[..., None] * 0.9) + rim * (sep_mask[..., None] * 0.9)
    # dark outer rim: opaque, inside the silhouette (outer 9%)
    rim_m = soft((s - 0.915) / 0.04, 0.5)
    col = base * (1 - rim_m[..., None]) + rim * rim_m[..., None]
    # tail needle darkens toward the end (dark particles mixed in the faded part)
    out = np.zeros(a.shape + (4,))
    out[..., :3] = col * alpha[..., None]
    out[..., 3] = alpha
    return out


def downsample(img):
    chans = []
    for c in range(4):
        im = Image.fromarray(img[..., c].astype(np.float32), mode="F")
        chans.append(np.asarray(im.resize((N, N), Image.LANCZOS)))
    pm = np.stack(chans, -1)
    al = np.clip(pm[..., 3], 0, 1)
    rgb = np.where(al[..., None] > 1e-4, pm[..., :3] / np.maximum(al[..., None], 1e-4), 0)
    o = np.concatenate([np.clip(rgb, 0, 1), al[..., None]], -1)
    o[al < 0.3] = 0  # client discards alpha < 0.1; 0.1-0.3 reads as a grey skirt
    return (o * 255 + 0.5).astype(np.uint8)


def main():
    os.makedirs(OUT, exist_ok=True)
    for qi, (sa, sb) in enumerate(TILES):
        frames = [np.zeros((N, N, 4), np.uint8)]
        for T in range(T_FIRST, T_LAST + 1):
            tail, head, thin = state(T)
            if tail >= head:
                frames.append(np.zeros((N, N, 4), np.uint8))
            else:
                frames.append(downsample(frame(sa, sb, T)))
        strip = np.concatenate(frames, axis=1)
        Image.fromarray(strip, "RGBA").save(os.path.join(OUT, f"bastion-s3-ring-q{qi}.png"))
        print(f"bastion-s3-ring-q{qi}: {len(frames)} frames")


def euler():
    t = math.radians(TILT)
    e1 = np.array([math.cos(t), math.sin(t), 0.0])
    e2 = np.array([0.0, 0.0, -1.0])
    m = np.stack([e1, e2, np.cross(e1, e2)], axis=1)
    bb = math.asin(max(-1.0, min(1.0, -m[1][2])))
    aa = math.atan2(m[0][2], m[2][2])
    cc = math.atan2(m[1][0], m[1][1])
    return e1, e2, [round(-math.degrees(aa), 2) + 0.0, round(math.degrees(bb), 2) + 0.0, round(math.degrees(cc), 2) + 0.0]


if __name__ == "__main__":
    main()
    e1, e2, eu = euler()
    h = HALF / 2
    print("euler", eu, "frames", FRAMES)
    for qi, (sa, sb) in enumerate(TILES):
        c = np.array([0.0, 0.8, 0.0]) + sa * h * e1 + sb * h * e2
        print(qi, [round(float(v), 3) for v in c])
