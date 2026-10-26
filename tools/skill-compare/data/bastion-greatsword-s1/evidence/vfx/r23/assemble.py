"""Assemble hring tiles q0..q3 (frame f) into one 512 image over sky (alpha<0.1 discarded); argv: imgdir out frame prefix"""
import sys, numpy as np
from PIL import Image
d, out, f = sys.argv[1], sys.argv[2], int(sys.argv[3])
pre = sys.argv[4] if len(sys.argv) > 4 else "q"
sky = np.array([130, 170, 255.])
canvas = np.zeros((512, 512, 3))
for qi, (ox, oy) in enumerate([(0, 0), (256, 0), (0, 256), (256, 256)]):
    im = np.asarray(Image.open(f"{d}/bastion-s1-hring-{pre}{qi}.png").convert("RGBA")).astype(float)
    S = 256
    fr = im[:, f*S:(f+1)*S] if im.shape[1] > im.shape[0] else im[f*S:(f+1)*S]
    if pre == "bq":
        ox = 256 - ox
    a = fr[..., 3:] / 255
    a = np.where(a < 0.1, 0, a)
    canvas[oy:oy+256, ox:ox+256] = fr[..., :3] * a + sky * (1 - a)
Image.fromarray(canvas.astype(np.uint8)).save(out)
