"""Perspective model of a billboard-center sprite (screen-parallel quad) for the real-client views.

fx frame: caster at origin (fx spawn), +z forward toward the target, +y up, +x = caster's left.
Camera: eye E, facing +z (caster) or -z (opponent), pitch (deg, + = looking down), vfov 90, 1280x720.
The sprite is drawn as w x h blocks centred on C, spanning camera right/up (MC billboard CENTER = screen
parallel). Inverse mapping: every screen pixel's ray hits the quad plane, sample the sprite alpha (nearest texel,
as the client does), alpha > 0.1 (shader discard). Floor clip: points with y < 0 are hidden (depth-tested sprite).
"""
import math
import numpy as np
from PIL import Image

W, H = 1280, 720
F = (H / 2) / math.tan(math.radians(45))  # vfov 90


def cam_axes(facing, pitch):
    fz = 1.0 if facing == "+z" else -1.0
    p = math.radians(pitch)
    fwd = np.array([0.0, -math.sin(p), fz * math.cos(p)])
    right = np.array([-fz, 0.0, 0.0])
    up = np.cross(right, fwd)
    if up[1] < 0:
        up = -up
    return fwd, right, up


def load_alpha(path, frame, size=256):
    return np.asarray(Image.open(path).convert("RGBA")).astype(float)[:, frame * size:(frame + 1) * size, 3] / 255.0


def project(alpha, C, w, h, eye, facing, pitch, thr=0.1, floor_clip=True, step=2):
    """Returns a boolean mask at (H/step, W/step)."""
    fwd, right, up = cam_axes(facing, pitch)
    eye = np.asarray(eye, float)
    C = np.asarray(C, float)
    zc = (C - eye) @ fwd  # quad plane depth along the view axis (screen-parallel plane)
    hh, ww = H // step, W // step
    if zc <= 0.05:
        return np.zeros((hh, ww), bool)
    xs = (np.arange(ww) + 0.5) * step
    ys = (np.arange(hh) + 0.5) * step
    X, Y = np.meshgrid(xs, ys)
    sx = (X - W / 2) / F * zc  # world offsets on the plane
    sy = (H / 2 - Y) / F * zc
    cr = (C - eye) @ right
    cu = (C - eye) @ up
    u = (sx - cr) / w + 0.5
    v = 0.5 - (sy - cu) / h
    n = alpha.shape[0]
    inside = (u >= 0) & (u < 1) & (v >= 0) & (v < 1)
    ui = np.clip((u * n).astype(int), 0, n - 1)
    vi = np.clip((v * n).astype(int), 0, n - 1)
    m = inside & (alpha[vi, ui] > thr)
    if floor_clip:
        wy = eye[1] + zc * fwd[1] + sx * right[1] + sy * up[1]
        m &= wy > 0.0
    return m


def stats(mask):
    ys, xs = np.nonzero(mask)
    sc = 1280 // mask.shape[1]
    bb = (xs.min() * sc, xs.max() * sc, ys.min() * sc, ys.max() * sc) if len(xs) else None
    return round(mask.mean() * 100, 1), bb
