import sys, numpy as np
from PIL import Image
im = np.asarray(Image.open(sys.argv[1]).convert("RGBA")).astype(float)
S = min(im.shape[:2])
vert = im.shape[0] > im.shape[1]
n = max(im.shape[:2]) // S
sky = np.array([130, 170, 255.])
for f in range(n):
    fr = im[f*S:(f+1)*S] if vert else im[:, f*S:(f+1)*S]
    a = fr[..., 3] / 255
    vis = a >= 0.1
    nv = vis.sum()
    if nv == 0:
        print(f, "empty"); continue
    faint = vis & (a < 0.5)
    lum = fr[..., :3].mean(-1)
    dark = vis & (lum < 120)
    comp = fr[..., :3] * a[..., None] + sky * (1 - a[..., None])
    sat = comp.max(-1) - comp.min(-1)
    grey = vis & (sat < 70)
    print(f"f{f}: size {S} vis {nv} faint<0.5 {faint.sum()/nv:.0%} dark(lum<120) {dark.sum()/nv:.0%} "
          f"grey-over-sky {grey.sum()/nv:.0%} darkRGB {fr[dark][:, :3].mean(0).round() if dark.any() else '-'} "
          f"darkA {a[dark].mean() if dark.any() else 0:.2f} greyRGB {comp[grey].mean(0).round() if grey.any() else '-'}")
