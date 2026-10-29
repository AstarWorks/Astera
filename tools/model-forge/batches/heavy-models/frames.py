#!/usr/bin/env python3
"""Write content/frames/<item>.yaml for the heavy melee weapons from the built models (usage: frames.py <item>...)."""
import json, sys, math
from pathlib import Path
import numpy as np
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE)); sys.path.insert(0, str(HERE.parents[1]))
from core import ROOT
from geom import q, qdir, Model
import build as B

TITLES = {
 'cleaver': ('cleaver', 'single-edged rectangular blade, edge on model +X, grip y 4.2'),
 'scythe': ('scythe', 'crescent blade curving to model +X/-Y, cutting edge on the inner (concave) side; long = root->tip chord, haft = pole axis'),
 'club': ('club', 'tapering studded wooden head, strike axis = pole axis, edge = +X by convention (a blunt weapon)'),
 'mace': ('mace', 'flanged head on a wooden shaft, strike axis = pole axis, edge = +X (an axis flange)'),
 'war-axe': ('war-axe', 'single-bit axe, cutting edge on model +X (arc from beard to horn), back spike on -X'),
 'halberd': ('halberd', 'long pole with spear spike, +X axe blade and -X back hook; root/tip on the head axis'),
 'shield-bulwark': ('shield-bulwark', 'heater shield in the XY plane, outer face +Z, grip bar and arm strap on the back'),
 'greatsword': ('greatsword', 'double-edged blade, centreline root->tip, edge = +X by convention'),
}


def f(v, n=4): return [round(float(x) + 0.0, n) + 0.0 for x in v]


def part_text(model, disp_key, display, spec, bbox, scale, hand=False):
    lo, hi = bbox
    d = display[disp_key]
    pts = {'grip': spec['grip'], 'pivot': spec['grip'], 'root': spec['root'], 'tip': spec['tip']}
    pts.update(spec['points'])
    out = {k: f(q(v, d)) for k, v in pts.items()}
    if hand: out['hand_anchor'] = f(q((8, 8, 8), d))
    long = np.array(spec['tip']) - np.array(spec['root'])
    if spec.get('long'): long = np.array(spec['long'], float)
    L = (hi[1] - lo[1]) * d['scale'][1] / 16
    axes = {'long': f(qdir(long, d)), 'edge': f(qdir(spec['edge'], d))}
    if spec.get('haft'): axes['haft'] = f(qdir(spec['haft'], d))
    return out, axes, round(L, 4)


def emit(item, model_id, display, spec, bbox, extra_note):
    if spec['edge'] is None:
        c = np.array(spec['tip'], float) - np.array(spec['root'], float); c[2] = 0; c /= np.linalg.norm(c)
        n = np.array([-c[1], c[0], 0.0])
        if n @ np.array([-1.0, -1.0, 0]) < 0: n = -n
        spec = dict(spec, edge=tuple(round(float(v), 4) for v in n))
    lines = [
        f'# Point table for the {item}: measured from the real 3D model content/models/items/{model_id}.model.json (tools/model-forge/batches/heavy-models), not from a sprite.',
        '# Chain as content/frames/couture-rapier.yaml: q = RotY(180) * ( T + Rxyz * ( S * (p/16 - 0.5) ) ), p = model point in pixels (16 px = 1 block), T/R/S = the model display block of the part display.',
        f'# Model space: {extra_note}.',
        f'# Model points (px): grip={tuple(round(v,2) for v in spec["grip"])} (hand centre, = pivot), root={tuple(round(v,2) for v in spec["root"])}, tip={tuple(round(v,2) for v in spec["tip"])}, edge dir={tuple(spec["edge"])}; root/tip sit on the striking edge/axis so the trail particles run on the steel.',
        '# length = model extent along model +Y (whole item, grip end to far end) * display scale / 16. hand_anchor = q((8,8,8)) under the third-person display (the point the hand frame sits on).',
        'apiVersion: astera/v3', 'kind: ItemFrames', f'item: {item}', 'parts:']
    for name, key, hand in (('held', 'fixed', False), ('held_hand', 'thirdperson_righthand', True)):
        pts, axes, L = part_text(model_id, key, display, spec, bbox, None, hand)
        lines += [f'  {name}:', f'    model: astera:{model_id}', f'    display: {key}', f'    length: {L}',
                  '    points: { ' + ', '.join(f'{k}: {v}' for k, v in pts.items()) + ' }',
                  '    axes: { ' + ', '.join(f'{k}: {v}' for k, v in axes.items()) + ' }']
    (ROOT / f'content/frames/{item}.yaml').write_text('\n'.join(lines).replace('(', '[').replace(')', ']') .replace("], '", "], '") + '\n')


NOTES = {'shield-bulwark': 'plate in the XY plane, up = +Y, outer face +Z, hand-grip bar at z 5.6..7.4 on the back'}

if __name__ == '__main__':
    for item in sys.argv[1:]:
        if item == 'greatsword':
            mj = json.load(open(ROOT / 'content/models/items/greatsword.model.json'))
            m = Model()
            for e in mj['elements']: m.box(e.get('name', ''), *e['from'], *e['to'], None, rot=e.get('rotation'))
            lo, hi = m.bbox(); disp = mj['display']
            spec = dict(grip=(8, 2.2, 8), root=(8, 7.8, 8), tip=(8, 28.2, 8), edge=(1, 0, 0), points={})
        else:
            p, model, spec, disp = B.build(item, write=False)
            data = json.load(open(B.WORK / f'{item}.spec.json')); lo, hi = np.array(data['bbox'][0]), np.array(data['bbox'][1])
        emit(item, item, disp, spec, (lo, hi), NOTES.get(item, 'item upright along model +Y, grip at the bottom, plates thin in Z (centre z = 8), cutting edge toward +X'))
        print(item, 'ok')
