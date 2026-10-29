#!/usr/bin/env python3
"""Render front / right / top views with root, tip, grip and edge direction marked -> /tmp/claude-1000/skill-engine/models/<item>.png"""
import subprocess, sys, json
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from build import BUILDERS
from PIL import Image, ImageDraw
item = sys.argv[1]
s, k = BUILDERS[item]()
proj = f'/tmp/claude-1000/skill-engine/models/proj-{item}'
ys = [e['from'][1] for e in s.els] + [e['to'][1] for e in s.els]
cy = (min(ys) + max(ys)) / 2; ortho = max(ys) - min(ys) + 8
sh = '/tmp/claude-1000/skill-engine/models/fg.sh'
subprocess.run([sh, item, 'render', json.dumps({'views': ['front', 'right', 'top'], 'resolution': 512, 'name': 'ann', 'camera': {'ortho': ortho, 'center': [8, cy, 8]}})], check=True, stdout=subprocess.DEVNULL)
sc = 512 / ortho
def px(view, p):
    x, y, z = p
    if view == 'front': return 256 + (x - 8) * sc, 256 - (y - cy) * sc
    if view == 'right': return 256 - (z - 8) * sc, 256 - (y - cy) * sc
    return 256 + (x - 8) * sc, 256 + (z - 8) * sc
out = Image.new('RGB', (1536, 540), (30, 38, 52))
d = ImageDraw.Draw(out)
for i, v in enumerate(('front', 'right', 'top')):
    im = Image.open(f'{proj}/renders/ann-{v}.png').convert('RGBA')
    out.paste(im, (i * 512, 28), im)
    off = i * 512
    for g in range(0, 41, 4):   # light grid every 4 units
        pass
    def P(p): a, b = px(v, p); return a + off, b + 28
    for name, col in (('root', (60, 230, 90)), ('tip', (255, 70, 70)), ('grip', (80, 200, 255))):
        x, y = P(k[name]); d.ellipse([x - 6, y - 6, x + 6, y + 6], outline=col, width=3); d.text((x + 9, y - 6), name, fill=col)
    r = k['root']; e = [r[0] + 4, r[1], r[2]]
    a, b = P(r), P(e); d.line([a, b], fill=(255, 170, 40), width=3); d.ellipse([b[0] - 4, b[1] - 4, b[0] + 4, b[1] + 4], fill=(255, 170, 40)); d.text((b[0] + 6, b[1] - 6), 'edge +X', fill=(255, 170, 40))
    d.text((off + 6, 6), f'{item} {v}', fill=(255, 255, 255))
out.save(f'/tmp/claude-1000/skill-engine/models/{item}.png')
