"""gridcrop.py <out.png> <frames dir> <x0,y0,x1,y1> <scale> <frame> [<frame> ...]
Crops the same box from each frame, scales it (nearest) and draws a labelled 20 px grid in frame coordinates, stacked
vertically, so hilt/tip pixel coordinates for lum.py / lumrgb.py can be read off the picture."""
import sys, os, glob
from PIL import Image, ImageDraw

out, d, box, s = sys.argv[1], sys.argv[2], tuple(map(int, sys.argv[3].split(','))), int(sys.argv[4])
frames = [int(f) for f in sys.argv[5:]]
def path(f):
    for ext in ('png', 'webp'):
        p = os.path.join(d, f'frame_{f:03d}.{ext}')
        if os.path.exists(p):
            return p
x0, y0, x1, y1 = box
w, h = (x1 - x0) * s, (y1 - y0) * s
sheet = Image.new('RGB', (w, h * len(frames)))
for i, f in enumerate(frames):
    im = Image.open(path(f)).convert('RGB').crop(box).resize((w, h), Image.NEAREST)
    dr = ImageDraw.Draw(im)
    for x in range((x0 // 20 + 1) * 20, x1, 20):
        dr.line([((x - x0) * s, 0), ((x - x0) * s, h)], fill=(255, 0, 255) if x % 100 == 0 else (90, 90, 140))
        if x % 100 == 0:
            dr.text(((x - x0) * s + 2, 2), str(x), fill=(255, 255, 0))
    for y in range((y0 // 20 + 1) * 20, y1, 20):
        dr.line([(0, (y - y0) * s), (w, (y - y0) * s)], fill=(255, 0, 255) if y % 100 == 0 else (90, 90, 140))
        if y % 100 == 0:
            dr.text((2, (y - y0) * s + 2), str(y), fill=(255, 255, 0))
    dr.text((w - 40, 2), f'f{f}', fill=(255, 255, 255))
    sheet.paste(im, (0, i * h))
sheet.save(out)
