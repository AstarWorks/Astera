#!/usr/bin/env python3
"""Stitch context_preview outputs into an open-sequence strip (0/15/30/45/60): rows = views.
usage: strip.py <context_preview_out_dir> <out.png>"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

src, out = Path(sys.argv[1]), Path(sys.argv[2])
names = [('couture-rapier', '0')] + [(f'couture-rapier-open-{t}', str(t)) for t in (15, 30, 45, 60)]
views = ['thirdperson_righthand', 'thirdperson_righthand_side', 'gui', 'fixed']
tiles = [[Image.open(src/f'{n}-{v}.png').convert('RGB') for n, _ in names] for v in views]
w, h = tiles[0][0].size
sheet = Image.new('RGB', (w*len(names), (h+16)*len(views)), 'white')
d = ImageDraw.Draw(sheet)
for r, v in enumerate(views):
    for c, (_, label) in enumerate(names):
        im = tiles[r][c].resize((w, h))
        sheet.paste(im, (c*w, r*(h+16)+16))
        d.text((c*w+4, r*(h+16)+2), f'{v} total +{label} deg', fill='black')
sheet.save(out)
print(out, sheet.size)
