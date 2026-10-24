"""bladecol.py <dir> <offset> <x0,y0,x1,y1> <t...>: per tick, blade pixels = differ from the floor colour; print median RGB, luma, B-R."""
import sys, numpy as np
from PIL import Image
d, off = sys.argv[1], int(sys.argv[2])
x0, y0, x1, y1 = (int(v) for v in sys.argv[3].split(','))
for t in sys.argv[4:]:
    t = int(t)
    a = np.asarray(Image.open(f'{d}/frame_{t + off:03d}.png').convert('RGB')).astype(int)[y0:y1, x0:x1]
    floor = np.median(a.reshape(-1, 3), axis=0)
    diff = np.abs(a - floor).sum(axis=2)
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    # exclude warm (orange/gold/skin/green) pixels: keep near-neutral or bluish
    neutral = (np.abs(r - g) < 25) & (diff > 40)
    px = a[neutral]
    if len(px) == 0:
        print(t, 'none'); continue
    m = np.median(px, axis=0)
    lum = 0.2126 * m[0] + 0.7152 * m[1] + 0.0722 * m[2]
    dark = px[(px.sum(axis=1) < 300)]
    dm = np.median(dark, axis=0) if len(dark) else [0, 0, 0]
    print(f't{t} n={len(px)} median RGB {m.astype(int).tolist()} luma {lum:.0f} B-R {m[2]-m[0]:.0f} | dark n={len(dark)} RGB {np.array(dm).astype(int).tolist()} B-R {dm[2]-dm[0]:.0f}')
