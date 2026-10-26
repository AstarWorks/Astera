"""lumrgb.py <frames dir> <spec>  spec = 'frame:hx,hy,tx,ty;frame:...'  (same masking as r25/lum.py)
Blade pixels within 3 px of the hilt->tip segment, 15..100 % of its length, that differ from the per-pixel median of
all frames (background) by > 18 in some channel. Prints median Rec.709 luma, p25/p75, median R/G/B and B-R per frame."""
import sys, os, glob
import numpy as np
from PIL import Image

d = sys.argv[1]
files = sorted(glob.glob(os.path.join(d, 'frame_*.png')) or glob.glob(os.path.join(d, 'frame_*.webp')))
load = lambda p: np.asarray(Image.open(p).convert('RGB')).astype(np.float32)
stack = np.stack([load(p) for p in files])
bg = np.median(stack, axis=0)
byn = {int(os.path.basename(p)[6:9]): i for i, p in enumerate(files)}
H, W = bg.shape[:2]
yy, xx = np.mgrid[0:H, 0:W]
for item in sys.argv[2].split(';'):
    f, c = item.split(':')
    f = int(f); hx, hy, tx, ty = map(float, c.split(','))
    img = stack[byn[f]]
    dx, dy = tx - hx, ty - hy; L2 = dx * dx + dy * dy
    t = ((xx - hx) * dx + (yy - hy) * dy) / L2
    dist = np.hypot(xx - (hx + t * dx), yy - (hy + t * dy))
    m = (t >= 0.15) & (t <= 1.0) & (dist <= 3.0) & (np.abs(img - bg).max(axis=2) > 18)
    if not m.any():
        print(f'f{f}: no pixels'); continue
    px = img[m]
    y = px @ np.array([0.2126, 0.7152, 0.0722])
    r, g, b = np.median(px, axis=0)
    print(f'f{f}: luma {np.median(y):6.1f}  p25 {np.percentile(y,25):6.1f}  p75 {np.percentile(y,75):6.1f}  '
          f'RGB {r:5.1f} {g:5.1f} {b:5.1f}  B-R {b-r:+5.1f}  n {y.size}')
