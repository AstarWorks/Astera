#!/usr/bin/env python3
"""Render a board of an item model with its frame points marked: root yellow, tip red, grip green, edge direction = blue cubes."""
import json, sys, shutil
from pathlib import Path
import numpy as np
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE)); sys.path.insert(0, str(HERE.parents[1]))
from core import Project, ROOT
import build as B

TEX = {'root': 'minecraft:block/yellow_concrete', 'tip': 'minecraft:block/red_concrete', 'grip': 'minecraft:block/lime_concrete', 'edge': 'minecraft:block/blue_concrete'}


def cube(c, s, tex):
    f = {k: {'texture': '#' + tex, 'uv': [0, 0, 16, 16]} for k in ('north', 'south', 'east', 'west', 'up', 'down')}
    return {'name': 'marker ' + tex, 'from': [c[0] - s, c[1] - s, c[2] - s], 'to': [c[0] + s, min(c[1] + s, 32.0), c[2] + s], 'faces': f}


def main(item):
    spec = json.load(open(B.WORK / f'{item}.spec.json'))['spec']
    model = json.load(open(ROOT / f'content/models/items/{item}.model.json'))
    for k, v in TEX.items(): model['textures'][k] = v
    root, tip, grip = (np.array(spec[k], float) for k in ('root', 'tip', 'grip'))
    edge = spec['edge']
    if edge is None:
        c = tip - root; c[2] = 0; c /= np.linalg.norm(c); n = np.array([-c[1], c[0], 0.0])
        edge = n if n @ np.array([-1, -1, 0.0]) > 0 else -n
    edge = np.array(edge, float)
    mid = (root + tip) / 2
    els = model['elements']
    els.append(cube(root, 0.9, 'root')); els.append(cube(tip, 0.9, 'tip')); els.append(cube(grip, 0.9, 'grip'))
    for i in range(1, 6): els.append(cube(mid + edge * (1.6 * i + 1.0), 0.45 + 0.07 * i, 'edge'))
    tmp = B.WORK / f'{item}.marked.json'
    tmp.write_text(json.dumps(model))
    proj = B.WORK / f'{item}-marked'
    if proj.exists(): shutil.rmtree(proj)
    proj.mkdir(parents=True)
    p = Project(proj)
    p.call('import', {'path': str(tmp)})
    r = p.call('board', {'resolution': 384, 'name': 'marked'})
    out = Path('/tmp/claude-1000/skill-engine/models') / f'{item}.png'
    shutil.copyfile(r['path'], out)
    print(out)


if __name__ == '__main__':
    for i in sys.argv[1:]: main(i)
