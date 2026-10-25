"""Composite chosen frames of a strip over sky blue (client-like: alpha < 0.1 discarded), crop, upscale nearest."""
import sys, numpy as np
from PIL import Image
src, out = sys.argv[1], sys.argv[2]
frames = [int(x) for x in sys.argv[3].split(",")]
crop = [int(x) for x in sys.argv[4].split(",")]  # x0,y0,x1,y1 in frame px
k = int(sys.argv[5]) if len(sys.argv) > 5 else 4
im = np.asarray(Image.open(src).convert("RGBA")).astype(float)
S = min(im.shape[:2]); vert = im.shape[0] > im.shape[1]
sky = np.array([130, 170, 255.])
tiles = []
for f in frames:
    fr = im[f*S:(f+1)*S] if vert else im[:, f*S:(f+1)*S]
    a = fr[..., 3:] / 255
    a = np.where(a < 0.1, 0, a)
    c = fr[..., :3] * a + sky * (1 - a)
    c = c[crop[1]:crop[3], crop[0]:crop[2]]
    tiles.append(Image.fromarray(c.astype(np.uint8)).resize(((crop[2]-crop[0])*k, (crop[3]-crop[1])*k), Image.NEAREST))
W = sum(t.width for t in tiles) + 4 * (len(tiles) - 1)
sheet = Image.new("RGB", (W, tiles[0].height), (0, 0, 0))
x = 0
for t in tiles:
    sheet.paste(t, (x, 0)); x += t.width + 4
sheet.save(out)
