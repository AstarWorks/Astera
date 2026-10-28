"""Pattern functions. All take pixel-centre grids X, Y (core.grid) and return bool masks (or level maps).

Periodic patterns use `period` in px and tile seamlessly along x (strips) or both axes (fills).
Formulas are documented in docs/art/geometric-patterns.md.
"""
import math
import numpy as np
from .core import (TAU, seg_dist, polyline_dist, periodic_polyline_dist, circle_dist, polygon_inside, ngon,
                   star_polygon, p4m_fold, stroke, dither)

__all__ = ["checker", "dot_lattice", "concentric", "moire", "meander", "rose", "spirograph", "lissajous",
           "star_outline", "star_fill", "islamic_star_lattice", "asanoha", "shippo", "seigaiha", "kagome",
           "seigaiha_levels", "braid", "diamond_chain", "ring_chain", "scallops", "stair", "ticks",
           "rosette_levels"]


def checker(X, Y, cell):
    """Chessboard: True where (floor(x/cell) + floor(y/cell)) is even."""
    return ((np.floor(X / cell) + np.floor(Y / cell)) % 2) == 0


def dot_lattice(X, Y, period, r, offset=0.0):
    """Disc of radius r at every lattice point (square lattice, spacing `period`)."""
    u = np.mod(X - offset, period) - period / 2
    v = np.mod(Y - offset, period) - period / 2
    return np.hypot(u, v) <= r


def concentric(X, Y, cx, cy, pitch, width=1.0, phase=0.0):
    """Rings: |frac(r/pitch) - 0.5| band. Ring k at r = pitch*(k+0.5)+phase."""
    r = np.hypot(X - cx, Y - cy) - phase
    d = np.abs(np.mod(r, pitch) - pitch / 2)
    return d <= width / 2


def moire(X, Y, c1, c2, pitch, width=1.0):
    """Two concentric families with different centres, XOR-ed: interference fringes."""
    return concentric(X, Y, *c1, pitch, width) ^ concentric(X, Y, *c2, pitch, width)


def meander(X, Y, period, top, width=1.0):
    """Greek key (meander / fret) band repeating every `period` px along x, occupying y in [top, top + period - 4].

    Baseline at y = top + h (h = period - 4) with a square spiral hook (2 px pitch: 1 px line, 1 px gap).
    """
    w = h = period - 4
    hook = [(0, h), (0, 0), (w, 0), (w, h - 2), (2, h - 2), (2, 2), (w - 2, 2), (w - 2, h - 4)]
    hook = [(x + 0.5, y + top + 0.5) for x, y in hook]  # +0.5: 1 px lines sit on pixel centres
    d = periodic_polyline_dist(X, Y, hook, period)
    base = seg_dist(X, Y, (-period, top + h + 0.5), (X.max() + period, top + h + 0.5))
    return stroke(np.minimum(d, base), width)


def rose(X, Y, cx, cy, k, R, width=1.0, n=360):
    """Rose curve r = R*cos(k*theta) (k petals if odd, 2k if even)."""
    th = np.linspace(0, TAU if k % 2 == 0 else math.pi, n)
    r = R * np.cos(k * th)
    pts = list(zip(cx + r * np.cos(th), cy + r * np.sin(th)))
    return stroke(polyline_dist(X, Y, pts), width)


def spirograph(X, Y, cx, cy, R, r, d, fit=20.0, width=1.0, n=720):
    """Hypotrochoid: x=(R-r)cos t + d cos((R-r)/r t), y=(R-r) sin t - d sin((R-r)/r t); closes after r/gcd turns."""
    g = math.gcd(int(R), int(r))
    t = np.linspace(0, TAU * (int(r) // g), n * (int(r) // g))
    x = (R - r) * np.cos(t) + d * np.cos((R - r) / r * t)
    y = (R - r) * np.sin(t) - d * np.sin((R - r) / r * t)
    k = fit / (R - r + d)  # scale the curve so its extent is `fit` px
    x, y = x * k, y * k
    return stroke(polyline_dist(X, Y, list(zip(cx + x, cy + y))), width)


def lissajous(X, Y, cx, cy, ax, ay, a, b, delta=math.pi / 2, width=1.0, n=720):
    """x = ax sin(a t + delta), y = ay sin(b t)."""
    t = np.linspace(0, TAU, n)
    pts = list(zip(cx + ax * np.sin(a * t + delta), cy + ay * np.sin(b * t)))
    return stroke(polyline_dist(X, Y, pts), width)


def star_outline(X, Y, n, k, r, cx, cy, width=1.0, rot=-math.pi / 2):
    """Outline of the star polygon {n/k}."""
    return stroke(polyline_dist(X, Y, star_polygon(n, k, r, cx, cy, rot), closed=True), width)


def star_fill(X, Y, n, k, r, cx, cy, rot=-math.pi / 2):
    """Even-odd fill of {n/k} (centre of {5/2}-type stars is hollow only with non-zero-winding; here solid centre
    is obtained by OR-ing the inner n-gon)."""
    path = star_polygon(n, k, r, cx, cy, rot)
    inner = r * math.cos(math.pi * k / n) / math.cos(math.pi * (k - 1) / n) if k > 1 else r
    return polygon_inside(X, Y, path) | polygon_inside(X, Y, ngon(n, inner * 1.0, cx, cy, rot))


def islamic_star_lattice(X, Y, period, k=3, width=1.0):
    """8-fold star-and-cross tiling cell: an {8/k} star centred in every square cell plus the cell's edge midlines."""
    u = np.mod(X, period) - period / 2
    v = np.mod(Y, period) - period / 2
    r = period * 0.5
    star = polyline_dist(u, v, star_polygon(8, k, r * 0.98), closed=True)
    # the plane is tiled, so evaluate on the folded cell directly
    return stroke(star, width)


def asanoha(X, Y, period, width=1.0):
    """Hemp-leaf (asanoha) cell approximation: diagonals, midlines and the midpoint diamond of every square cell."""
    u, v = p4m_fold(X, Y, period)  # u >= v, 0..P/2 from the cell centre
    h = period / 2
    d = np.minimum.reduce([
        np.abs(v),                       # midlines
        np.abs(u - v),                   # diagonals
        np.abs(u + v - h) / math.sqrt(2),  # midpoint diamond
    ])
    return stroke(d, width)


def shippo(X, Y, period, width=1.0):
    """Seven-treasures: circles of radius period/sqrt2 on a square lattice (overlap into 4-petal flowers)."""
    r = period / math.sqrt(2)
    u, v = np.mod(X, period), np.mod(Y, period)
    d = np.full(X.shape, 1e9)
    for cx in (0, period):
        for cy in (0, period):
            d = np.minimum(d, np.abs(np.hypot(u - cx, v - cy) - r))
    return stroke(d, width)


def seigaiha_levels(X, Y, R, rings=3):
    """Blue-ocean-waves: discs of radius R on a half-offset grid (row pitch R/2, column pitch 2R), each with
    `rings` nested bands; lower rows paint over upper ones. Returns the band index (1 = centre .. rings = rim)."""
    out = np.zeros(X.shape, np.int8)
    j = 0
    y = -R
    while y < Y.max() + R:
        off = R if j % 2 else 0
        for cx in np.arange(-2 * R + off, X.max() + 2 * R, 2 * R):
            d = np.hypot(X - cx, Y - y)
            m = d <= R
            out[m] = 1 + np.minimum((d[m] / R * rings).astype(int), rings - 1)
        y += R / 2
        j += 1
    return out


def seigaiha(X, Y, R, width=1.0, rings=3):
    """Outline mask of the seigaiha scales (edges where the ring index changes)."""
    lv = seigaiha_levels(X, Y, R, rings)
    e = np.zeros(lv.shape, bool)
    e[:, 1:] |= lv[:, 1:] != lv[:, :-1]
    e[1:, :] |= lv[1:, :] != lv[:-1, :]
    return e


def kagome(X, Y, period, width=1.0, offset=(0.0, 0.5, 0.0)):
    """Basket-weave (kagome): three families of parallel lines at 0/60/120 degrees, spacing `period`,
    per-family offsets in units of the spacing (0.5 on one family pulls the triple crossings apart)."""
    d = np.full(X.shape, 1e9)
    for i, ang in enumerate((0, math.pi / 3, 2 * math.pi / 3)):
        s = -X * math.sin(ang) + Y * math.cos(ang) - offset[i] * period
        m = np.mod(s, period)
        d = np.minimum(d, np.minimum(m, period - m))
    return stroke(d, width)


def braid(X, Y, period, cy, amp, width=2.0):
    """Celtic-style two-strand braid along x. Returns int map: 0 none, 1 outline, 2 strand A, 3 strand B.
    Strands y = cy +/- amp*sin(2*pi*x/period); they cross every period/2 and swap over/under at each crossing."""
    ph = TAU * X / period
    dist = {2: np.abs(Y - (cy + amp * np.sin(ph))), 3: np.abs(Y - (cy - amp * np.sin(ph)))}
    k = np.floor(X / (period / 2) + 0.5).astype(int)  # nearest crossing
    a_over = (k % 2) == 0
    res = np.zeros(X.shape, np.int8)
    for order, region in (((3, 2), a_over), ((2, 3), ~a_over)):  # painted first -> last (last is on top)
        lay = np.zeros(X.shape, np.int8)
        for sid in order:
            d = dist[sid]
            lay[d <= width / 2 + 1.0] = 1
            lay[d <= width / 2] = sid
        res[region] = lay[region]
    return res


def diamond_chain(X, Y, period, cy, half_w, half_h):
    """Row of rhombi |dx|/half_w + |dy|/half_h <= 1 centred every `period` px (touching when half_w = period/2)."""
    u = np.mod(X, period) - period / 2
    return np.abs(u) / half_w + np.abs(Y - cy) / half_h <= 1.0


def ring_chain(X, Y, period, cy, r):
    """Guilloche chain: circles of radius r (> period/2) centred every `period`, neighbours overlapping and
    interlacing over/under. Returns int map: 0 none, 1 = even rings, 2 = odd rings."""
    out = np.zeros(X.shape, np.int8)
    rings, k = {}, -1
    while period * (k + 0.5) < X.max() + period:
        cx = period * (k + 0.5)
        rings[k] = np.abs(np.hypot(X - cx, Y - cy) - r) <= 0.5
        k += 1
    for kk, m in rings.items():  # base: even then odd on top
        if kk % 2 == 0:
            out[m] = 1
    for kk, m in rings.items():
        if kk % 2:
            out[m] = 2
    dy = math.sqrt(max(r * r - (period / 2) ** 2, 0))
    for kk in rings:  # crossing between ring kk and kk+1 at x = period*(kk+1)
        if kk + 1 not in rings:
            continue
        for sgn in (-1, 1):  # upper (y<cy) crossing: ring kk over; lower: ring kk+1 over
            over = kk if sgn < 0 else kk + 1
            near = np.hypot(X - period * (kk + 1), Y - (cy + sgn * dy)) <= 1.8
            out[near & rings[over]] = 1 if over % 2 == 0 else 2
    return out


def scallops(X, Y, period, top, r, width=1.0):
    """Row of circular arcs (bumps) of radius r repeating every `period`, apex at y=top."""
    u = np.mod(X, period) - period / 2
    d = np.abs(np.hypot(u, Y - (top + r)) - r)
    return stroke(d, width) & (Y <= top + r)


def stair(X, Y, steps, step, width=1.0):
    """Stepped corner staircase from (0, steps*step) to (steps*step, 0) (top-left origin)."""
    pts = []
    for i in range(steps):
        pts += [(i * step, (steps - i) * step), (i * step, (steps - i - 1) * step)]
    pts.append((steps * step, 0))
    return stroke(polyline_dist(X, Y, pts), width)


def ticks(X, Y, period, y0, y1, width=1.0, phase=0.0):
    """Short vertical dashes every `period` px between y0 and y1."""
    u = np.abs(np.mod(X - phase, period) - period / 2)
    return (u <= width / 2) & (Y >= y0) & (Y <= y1)


def rosette_levels(X, Y, cx, cy, petals, R, rings=3):
    """Radial petal rosette via D_n fold: levels 0 none, 1 outer petal edge, 2 petal fill, 3 core.
    Petal envelope r(a) = R*(0.45 + 0.55*cos^2(n*a/2))."""
    a = np.arctan2(Y - cy, X - cx)
    r = np.hypot(X - cx, Y - cy)
    env = R * (0.5 + 0.5 * np.cos(petals * a))
    out = np.zeros(X.shape, np.int8)
    out[r <= env] = 2
    out[(r <= env) & (r >= env - 1.0)] = 1
    out[r <= R * 0.3] = 3
    return out
