#!/usr/bin/env python3
"""couture-s2 coin-1 sprites (256 px, 4x supersample, anti-aliased).
 crescent-core: thick pointed crescent (opens to -x), white-hot core, warm soft glow, thin dark violet rim outside.
 streak       : tapered horizontal speed streak, white-hot centre, orange body (tip at +x).
Run, then `./gradlew -q :tools:item-art:run`."""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

N, SS = 256, 4
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "content", "textures", "sources", "images")


def mask(fn):
    im = Image.new("L", (N * SS, N * SS), 0)
    fn(ImageDraw.Draw(im))
    return im


def small(im):
    return np.asarray(im.resize((N, N), Image.BOX)).astype(np.float32) / 255


def blur(a, r):
    return np.asarray(Image.fromarray((a * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(r))).astype(np.float32) / 255


def crescent(shrink, cx_off=0):
    def f(d):
        R, R2, off = 112 - shrink, 106 - shrink, 36
        d.ellipse([(128 + cx_off - R) * SS, (128 - R) * SS, (128 + cx_off + R) * SS, (128 + R) * SS], fill=255)
        d.ellipse([(128 + cx_off - off - R2) * SS, (128 - R2) * SS, (128 + cx_off - off + R2) * SS, (128 + R2) * SS], fill=0)
    return f


def save(name, rgb, a):
    out = np.zeros((N, N, 4), np.uint8)
    out[..., :3] = np.clip(rgb, 0, 255).astype(np.uint8)
    out[..., 3] = np.clip(a * 255, 0, 255).astype(np.uint8)
    Image.fromarray(out).save(os.path.join(OUT, name + ".png"))


# crescent-core: THIN arc. Leading rim (+x) hot white, trailing gradient fading to full transparency inward.
yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
R, R2, off = 112.0, 100.0, 30.0
r1 = np.hypot(xx - 128, yy - 128)
r2 = np.hypot(xx - 128 + off, yy - 128)
d = R - r1                                   # depth under the outer rim
inside = np.clip(d * 2 + 0.5, 0, 1)          # anti-aliased outer edge
cut = np.clip((r2 - R2) * 2 + 0.5, 0, 1)     # keep only outside the inner circle (pointed tips)
band = np.clip(1 - np.exp(-np.maximum(r2 - R2, 0) / 5.0) * 0 , 0, 1)
grad = np.exp(-np.maximum(d, 0) / 9.0)       # bright at the rim, fading inward
hot = np.clip(1 - d / 3.5, 0, 1)             # 3 px white-hot rim
a = np.clip(inside * cut * grad, 0, 1)
a = np.maximum(a, inside * hot * cut)
a = a * np.clip((xx - 40) / 60, 0.25, 1)     # trailing tips thinner/dimmer
glow = blur(inside * cut, 5) * 0.25 * np.clip(1 - np.maximum(d, 0) / 30, 0, 1) + blur(np.clip(1 - np.abs(d) / 6, 0, 1) * cut, 4) * 0.35
a = np.clip(np.maximum(a, glow), 0, 1)
rgb = np.zeros((N, N, 3), np.float32)
w = np.clip(hot * 1.0 + grad * 0.35, 0, 1)
for i, (g, h) in enumerate(zip((255, 205, 150), (255, 255, 255))):
    rgb[..., i] = g * (1 - w) + h * w
save("couture-s2-crescent-core", rgb, a)

# petal: small pointed teardrop, white with pale pink tip
pm = Image.new("L", (N * SS, N * SS), 0)
dd = ImageDraw.Draw(pm)
import math
pts = []
for k in range(41):
    u = k / 40 * 2 * math.pi
    pts.append(((128 + 70 * math.cos(u) * (1 if math.cos(u) > 0 else 0.7)) * SS, (128 + 30 * math.sin(u) * (1 - 0.5 * max(0, math.cos(u)) ** 3)) * SS))
dd.polygon(pts, fill=255)
cov = small(pm)
xn = np.clip((xx - 58) / 140, 0, 1)
rgb = np.zeros((N, N, 3), np.float32)
rgb[..., 0] = 255
rgb[..., 1] = 255 - 45 * xn
rgb[..., 2] = 255 - 30 * xn
save("couture-s2-petal-spark", rgb, cov)

# streak
yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
t = np.clip((xx - 8) / 240, 0, 1)            # 0 tail .. 1 tip
half = 11 * np.sin(np.clip(t, 0, 1) ** 0.6 * np.pi * 0.5 + 0.0) * (1 - t) ** 0.7 + 0.5
d = np.abs(yy - 128)
body = np.clip((half - d) * 1.2 + 0.5, 0, 1) * (xx > 8) * (xx < 250)
hot = np.clip((half * 0.35 - d) * 1.5 + 0.5, 0, 1) * (xx > 8)
fade = np.clip(t * 4, 0, 1)
a = np.clip(body * fade, 0, 1)
rgb = np.zeros((N, N, 3), np.float32)
for i, (o, w) in enumerate(zip((255, 140, 40), (255, 245, 220))):
    rgb[..., i] = o * (1 - hot) + w * hot
save("couture-s2-streak", rgb, a)
