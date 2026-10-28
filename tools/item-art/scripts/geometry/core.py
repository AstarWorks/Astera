"""Pixel grid, distance-field primitives, symmetry folds and quantisation helpers."""
import math
import numpy as np

TAU = math.tau


def grid(w, h):
    """Pixel-centre coordinates (X, Y), each shaped (h, w): X = column + 0.5, Y = row + 0.5."""
    return np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)


# ---- distance fields ----

def seg_dist(X, Y, p, q):
    """Distance to the segment p-q."""
    px, py = p
    qx, qy = q
    dx, dy = qx - px, qy - py
    t = np.clip(((X - px) * dx + (Y - py) * dy) / max(dx * dx + dy * dy, 1e-9), 0, 1)
    return np.hypot(X - (px + t * dx), Y - (py + t * dy))


def polyline_dist(X, Y, pts, closed=False):
    pts = list(pts) + ([pts[0]] if closed else [])
    d = np.full(X.shape, 1e9)
    for a, b in zip(pts, pts[1:]):
        d = np.minimum(d, seg_dist(X, Y, a, b))
    return d


def periodic_polyline_dist(X, Y, pts, period, copies=(-1, 0, 1)):
    """polyline_dist of a motif repeated every `period` px along x (wrap-safe)."""
    d = np.full(X.shape, 1e9)
    for k in copies:
        d = np.minimum(d, polyline_dist(X, Y, [(x + k * period, y) for x, y in pts]))
    return d


def circle_dist(X, Y, cx, cy, r):
    """|distance| to the circle outline."""
    return np.abs(np.hypot(X - cx, Y - cy) - r)


def polygon_inside(X, Y, pts):
    """Even-odd fill test of a closed polygon (works for self-crossing star polygons)."""
    inside = np.zeros(X.shape, bool)
    n = len(pts)
    for i in range(n):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % n]
        if y1 == y2:
            continue
        cond = (y1 > Y) != (y2 > Y)
        xint = x1 + (Y - y1) * (x2 - x1) / (y2 - y1)
        inside ^= cond & (X < xint)
    return inside


def ngon(n, r, cx=0.0, cy=0.0, rot=-math.pi / 2):
    return [(cx + r * math.cos(rot + TAU * i / n), cy + r * math.sin(rot + TAU * i / n)) for i in range(n)]


def star_polygon(n, k, r, cx=0.0, cy=0.0, rot=-math.pi / 2):
    """Vertex path of the star polygon {n/k}: every k-th vertex of a regular n-gon (gcd(n,k)=1 -> one path)."""
    base = ngon(n, r, cx, cy, rot)
    g = math.gcd(n, k)
    path, i = [], 0
    for _ in range(n // g):
        path.append(base[i % n])
        i += k
    return path


# ---- symmetry ----

def rot_fold(X, Y, cx, cy, n):
    """Fold the plane into one 1/n wedge (n-fold rotation). Returns polar (radius, angle in [0, 2pi/n))."""
    a = np.mod(np.arctan2(Y - cy, X - cx), TAU / n)
    return np.hypot(X - cx, Y - cy), a


def dihedral_fold(X, Y, cx, cy, n):
    """n-fold rotation AND reflection (D_n). Returns (radius, angle in [0, pi/n])."""
    r, a = rot_fold(X, Y, cx, cy, n)
    return r, np.minimum(a, TAU / n - a)


def p4m_fold(X, Y, period):
    """Wallpaper group p4m: fold onto the fundamental triangle 0 <= v <= u <= period/2 (centre at tile centre)."""
    u = np.abs(np.mod(X, period) - period / 2)
    v = np.abs(np.mod(Y, period) - period / 2)
    return np.maximum(u, v), np.minimum(u, v)


def sym_diag(a):
    """Make a square map symmetric about its main diagonal by mirroring the upper triangle (x >= y)."""
    a = a.copy()
    for y in range(a.shape[0]):
        for x in range(y):
            a[y, x] = a[x, y]
    return a


# ---- quantisation / shading ----

def stroke(d, width=1.0):
    """Mask of a line `width` px thick around a distance field."""
    return d <= width / 2 + 1e-6


BAYER4 = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) / 16.0


def dither(field, X, Y, matrix=BAYER4):
    """Ordered dither: field in [0,1] -> bool mask whose density follows the field."""
    n = matrix.shape[0]
    return field > matrix[Y.astype(int) % n, X.astype(int) % n]


def shift(mask, dx, dy):
    out = np.zeros_like(mask)
    h, w = mask.shape
    ys = slice(max(-dy, 0), h - max(dy, 0))
    yd = slice(max(dy, 0), h - max(-dy, 0))
    xs = slice(max(-dx, 0), w - max(dx, 0))
    xd = slice(max(dx, 0), w - max(-dx, 0))
    out[yd, xd] = mask[ys, xs]
    return out


def bevel(mask):
    """(light, dark) rims inside a filled shape, light from the north-west."""
    light = mask & ~shift(mask, 1, 1)
    dark = mask & ~shift(mask, -1, -1) & ~light
    return light, dark


def outline(mask):
    """Pixels just outside `mask` (4-neighbour dilation minus mask)."""
    d = mask.copy()
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        d |= shift(mask, dx, dy)
    return d & ~mask


class Canvas:
    """Integer level map with painter's-order drawing. Level meaning is up to the caller."""

    def __init__(self, w, h):
        self.a = np.zeros((h, w), np.int8)

    def paint(self, mask, level):
        self.a[mask] = level
        return self
