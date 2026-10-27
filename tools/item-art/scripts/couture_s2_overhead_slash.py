"""couture-s2-overhead-slash: thin 165 deg arc, white core, dark rim, tapered ends. 256 px, 4x supersampled."""
import numpy as np
from PIL import Image

S = 4
N = 256 * S
y, x = np.mgrid[0:N, 0:N].astype(np.float64)
x = (x + .5) / S - 128
y = (y + .5) / S - 128
r = np.hypot(x, y)
th = np.degrees(np.arctan2(-y, x)) % 360
R = 112.0
A0, A1 = 0.0, 120.0
u = np.clip((th - A0) / (A1 - A0), 0, 1)
inarc = (th >= A0) & (th <= A1)
taper = np.sin(np.pi * u) ** 0.45
w = (2.0 + 4.6 * taper) * (0.8 + 0.4 * u)  # white core half width px
d = np.abs(r - R)


def sm(v):
    return np.clip(v * S / 2 + .5, 0, 1)


core = sm(w - d) * inarc
rim = sm(w + 1.6 - d) * inarc * 0.55            # faint 1-2 px grey falloff
glow = np.exp(-(d / (w * 2.6 + 5)) ** 2) * inarc * 0.40  # wide soft halo
# thin inner gradient band (<= 15% of R, <= 20% alpha) just inside the rim: glossy blade-edge ribbon
inner = np.clip(1 - (R - w - r) / (0.15 * R), 0, 1)
fill = inarc * (r < R - w) * inner ** 1.5 * 0.20 * np.sin(np.pi * u) ** 0.5
grey = np.array([150, 148, 156.])
white = np.array([255, 255, 255.])
halo = np.array([255, 240, 236.])
a = np.maximum(np.maximum(np.maximum(rim, glow), core), fill)
rgb = np.where(core[..., None] > 0.02, white,
               np.where(rim[..., None] > glow[..., None], grey, halo))
pm = (rgb * a[..., None]).reshape(256, S, 256, S, 3).mean((1, 3))
aa = a.reshape(256, S, 256, S).mean((1, 3))
out = np.where(aa[..., None] > 0, pm / np.maximum(aa[..., None], 1e-6), 0)
img = np.dstack([out, aa * 255]).clip(0, 255).astype(np.uint8)
Image.fromarray(img, 'RGBA').save('content/textures/sources/images/couture-s2-overhead-slash.png')
