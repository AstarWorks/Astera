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
# r4: three stacked, differently tilted plate rings (sharp slanted plate ends, jagged outer edge, dark outline).
# name, centre height, roll tilt (deg about the forward axis, left up), r_out, r_in, plates, gap deg, slant, phase deg, delay ticks, seed
RINGS = [
    dict(name="bastion-s3-ring", yc=0.50, tilt=8.0, r_out=2.85, r_in=2.25, plates=6, gap=8.0, slant=14.0, phase=0.0, delay=0, seed=3),
    dict(name="bastion-s3-ring2", yc=0.95, tilt=-13.0, r_out=2.65, r_in=2.05, plates=5, gap=10.0, slant=-16.0, phase=110.0, delay=1, seed=7),
    dict(name="bastion-s3-ring3", yc=1.40, tilt=15.0, r_out=2.45, r_in=1.85, plates=7, gap=7.0, slant=12.0, phase=215.0, delay=1, seed=13),
]
RING = RINGS[0]
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
    T = T - RING["delay"]
    if T < 5:
        return 0.0, 0.0, 1.0
    tail, head, thin = _state(T)
    return tail + RING["phase"], head + RING["phase"], thin


def _state(T):
    head = {5: 100.0, 6: 215.0, 7: 325.0}.get(T, 345.0)
    tail = -15.0 if T <= 9 else -15.0 + (T - 9) * 65.0
    thin = 1.0 if T <= 11 else 1.0 - 0.1 * (T - 11)
    return tail, head, thin


T_FIRST, T_LAST = 5, 16
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
    R_OUT, R_IN = RING["r_out"], RING["r_in"]
    seed = RING["seed"]
    th = np.mod(theta - RING["phase"], 360.0)
    rc = 0.5 * (R_OUT + R_IN)
    hw0 = 0.5 * (R_OUT - R_IN) * f * thin
    # jagged edges: triangle-wave teeth (outer, big) and notches (inner, small), phase random per plate
    per = 360.0 / RING["plates"]
    pid = np.floor((th + 0.0) / per)
    rnd = np.random.default_rng(seed).random(RING["plates"] + 2)
    tooth_p = 9.0 + 4.0 * rnd[np.clip(pid.astype(int), 0, RING["plates"] + 1)]
    tri = np.abs(np.mod(th, tooth_p) / tooth_p * 2 - 1)  # 0..1
    jag_o = (tri - 0.5) * 0.16 * f
    jag_i = (np.abs(np.mod(th * 1.7, 14.0) / 14.0 * 2 - 1) - 0.5) * 0.07 * f
    r_o_edge = rc + hw0 + jag_o
    r_i_edge = rc - hw0 + jag_i
    d_rad = np.minimum(r_o_edge - r, r - r_i_edge)  # blocks, >0 inside
    # slanted plate gaps: gap boundary angle shifts with radius (parallelogram plates)
    sl = RING["slant"] * (r - rc) / (0.5 * (R_OUT - R_IN))
    u = np.mod(th + sl, per)
    gap = RING["gap"]
    # arc distance (blocks) to the nearest gap edge; gap sits at u in [0, gap]
    d_gap_deg = np.minimum(np.abs(u - gap), per - u)
    in_gap = u < gap
    d_gap = np.where(in_gap, -np.minimum(u, gap - u), d_gap_deg) * math.pi / 180.0 * np.maximum(r, 0.5)
    d_edge = np.minimum(d_rad, d_gap)
    px = k
    alpha = soft(d_edge / px, 0.9) * inside
    # radial coordinate s: 0 inner .. 1 outer
    s = np.clip((r - r_i_edge) / np.maximum(r_o_edge - r_i_edge, 1e-4), 0, 1)
    pale = hexc("#FFF2B0")
    gold = hexc("#FFD23A")
    amber = hexc("#E8921A")
    rim = hexc("#2A1503")
    hot = hexc("#FFF4B8")
    base = np.where(s[..., None] < 0.5, gold * (s[..., None] / 0.5) + pale * (1 - s[..., None] / 0.5),
                    gold * (1 - (s[..., None] - 0.5) / 0.5) + amber * ((s[..., None] - 0.5) / 0.5))
    core = np.exp(-((s - 0.2) / 0.09) ** 2)[..., None] * np.clip(f, 0, 1)[..., None]
    base = base * (1 - core) + hot * core
    # per-plate brightness (plates read separately) + brighter at the leading (CW-forward = high u) end
    shade = 0.82 + 0.18 * rnd[np.clip(pid.astype(int), 0, RING["plates"] + 1)]
    base = base * shade[..., None]
    # dark outline: 0.075 blocks inside every edge, opaque
    rim_w = 0.075
    rim_m = 1.0 - smooth((d_edge - rim_w) / 0.025 + 0.5)
    # one dark plate groove along the plate (inner third), broken per plate
    groove = soft((0.02 - np.abs(s - 0.62)) / 0.012, 0.6) * (rnd[np.clip(pid.astype(int), 0, RING["plates"] + 1)] > 0.25)
    rim_m = np.maximum(rim_m, groove * 0.85)
    col = base * (1 - rim_m[..., None]) + rim * rim_m[..., None]
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
    global RING
    os.makedirs(OUT, exist_ok=True)
    for RING in RINGS:
        for qi, (sa, sb) in enumerate(TILES):
            frames = [np.zeros((N, N, 4), np.uint8)]
            for T in range(T_FIRST, T_LAST + 1):
                tail, head, thin = state(T)
                if tail >= head or T - RING["delay"] < 5:
                    frames.append(np.zeros((N, N, 4), np.uint8))
                else:
                    frames.append(downsample(frame(sa, sb, T)))
            strip = np.concatenate(frames, axis=1)
            Image.fromarray(strip, "RGBA").save(os.path.join(OUT, f"{RING['name']}-q{qi}.png"))
            print(f"{RING['name']}-q{qi}: {len(frames)} frames")


def euler():
    t = math.radians(RING["tilt"])
    e1 = np.array([math.cos(t), math.sin(t), 0.0])
    e2 = np.array([0.0, 0.0, -1.0])
    m = np.stack([e1, e2, np.cross(e1, e2)], axis=1)
    bb = math.asin(max(-1.0, min(1.0, -m[1][2])))
    aa = math.atan2(m[0][2], m[2][2])
    cc = math.atan2(m[1][0], m[1][1])
    return e1, e2, [round(-math.degrees(aa), 2) + 0.0, round(math.degrees(bb), 2) + 0.0, round(math.degrees(cc), 2) + 0.0]


if __name__ == "__main__":
    main()
    h = HALF / 2
    out = []
    for RING in RINGS:
        e1, e2, eu = euler()
        print(RING["name"], "euler", eu, "frames", FRAMES)
        for qi, (sa, sb) in enumerate(TILES):
            c = np.array([0.0, RING["yc"], 0.0]) + sa * h * e1 + sb * h * e2
            tr = [round(float(v), 4) for v in c]
            out.append(f"""- id: {RING['name'].replace('bastion-s3-', 's3r-')}-q{qi}
  kind: {{type: sprite, sprite: {RING['name']}-q{qi}, frame_ticks: 1, loop: false, see_through: true}}
  billboard: fixed
  brightness: 15
  start_tick: 3
  end_tick: 17
  keyframes:
  - t: 0
    translate: {tr}
    rotate: {eu}
    scale: [0.4761, 0.4761, 1.0]
""")
    open(os.path.join(ROOT, "tools", "item-art", "scripts", "bastion_s3_ring_elements.yaml.txt"), "w").write("".join(out))
