#!/usr/bin/env python3
"""Build the heavy melee models through model-forge. usage: build.py <weapon> [--out content/models/items/<id>.model.json]"""
import json, sys, shutil
from pathlib import Path
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE)); sys.path.insert(0, str(HERE.parents[1]))
from core import Project, ROOT
import weapons as W
from geom import display

WORK = Path('/tmp/claude-1000/skill-engine/models/proj')
ITEMS = {'cleaver': W.cleaver, 'scythe': W.scythe, 'club': W.club, 'mace': W.mace, 'war-axe': W.war_axe, 'halberd': W.halberd, 'shield-bulwark': W.shield}
SIZE = {'cleaver': 0.9, 'club': 1.05, 'mace': 1.1, 'war-axe': 1.3, 'scythe': 1.75, 'halberd': 1.95, 'shield-bulwark': 1.0}
TP_ROT = {'shield-bulwark': (0, 0, 0)}
SHIFT = {'scythe': -4.0, 'halberd': -1.2, 'war-axe': -3.0}


def shift(m, spec, dy):
    for e in m.els:
        e['f'][1] += dy; e['t'][1] += dy
        if e['rot']: e['rot']['origin'][1] += dy
    def sp(p): return (p[0], p[1] + dy, p[2])
    spec = dict(spec, **{k: sp(spec[k]) for k in ('grip', 'root', 'tip')}, points={k: sp(v) for k, v in spec['points'].items()})
    return m, spec


FACES = ['north', 'south', 'east', 'west', 'up', 'down']


def build(name, write=True):
    m, spec = ITEMS[name]()
    dy = SHIFT.get(name, 0)
    if dy: m, spec = shift(m, spec, dy)
    lo, hi = m.bbox()
    els = []
    for e in m.els:
        d = {'name': e['name'], 'from': e['f'], 'to': e['t'], 'faces': {f: {'texture': '#particle', 'uv': [0, 0, 16, 16]} for f in FACES}}
        if e['rot']: d['rotation'] = e['rot']
        els.append(d)
    disp = display(lo, hi, spec['grip'], SIZE[name], TP_ROT.get(name, (35, -90, 0)))
    seed = {'textures': {'particle': 'minecraft:block/iron_block'}, 'elements': els, 'display': disp, 'gui_light': 'front', 'ambientocclusion': False}
    proj = WORK / name
    if proj.exists(): shutil.rmtree(proj)
    proj.mkdir(parents=True)
    seedp = proj.parent / f'{name}.seed.json'
    seedp.write_text(json.dumps(seed))
    p = Project(proj)
    p.call('import', {'path': str(seedp)})
    groups = {}
    for e, src in zip(p.model['elements'], m.els):
        groups.setdefault(src['mat'], []).append(e['id'])
    ops = []
    for (mat, tint), ids in groups.items():
        a = {'ids': ids, 'material': mat, 'seed': 20261030}
        if tint: a['tint'] = tint
        ops.append({'op': 'material.apply', 'args': a})
    r = p.call('batch', {'ops': ops})
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k: v for k, v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/iron_block'
    model['credit'] = 'Astera / heavy-models (model-forge, vanilla patterns + tints)'
    out = ROOT / f'content/models/items/{name}.model.json'
    if write: out.write_text(json.dumps(model, indent=2) + '\n')
    (proj.parent / f'{name}.spec.json').write_text(json.dumps({'spec': spec, 'display': disp, 'n': len(els), 'bbox': [lo.tolist(), hi.tolist()]}, default=list))
    return p, model, spec, disp


if __name__ == '__main__':
    for n in sys.argv[1:]:
        p, model, spec, disp = build(n)
        print(n, len(model['elements']), 'tints', model.get('astera_tints'))
