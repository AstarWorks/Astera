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


# crescent-core
body = small(mask(crescent(10)))
core = small(mask(crescent(10, 0)).filter(ImageFilter.MinFilter(15)))  # inner white band
glow = blur(small(mask(crescent(14))), 9)
rim = np.clip(small(mask(crescent(6))) - body, 0, 1)
rim = np.clip(blur(rim, 0.8) * 3, 0, 1)
a = np.clip(np.maximum(np.maximum(body, glow * 0.9), rim * 0.55), 0, 1)
white = np.clip(core * 1.0 + body * 0.35, 0, 1)
rgb = np.zeros((N, N, 3), np.float32)
glow_col = np.array([255, 214, 170], np.float32)
body_col = np.array([255, 246, 232], np.float32)
rim_col = np.array([60, 30, 70], np.float32)
for i in range(3):
    c = glow_col[i] * (1 - body) + body_col[i] * body
    c = c * (1 - white) + 255 * white
    c = c * (1 - rim * (1 - body)) + rim_col[i] * rim * (1 - body)
    rgb[..., i] = c
save("couture-s2-crescent-core", rgb, a)

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
