"""Shared helpers: cuboid list -> Forge project (vanilla materials + tints) -> model JSON + frame numbers."""
import json, sys
from pathlib import Path
import numpy as np
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1]))
from core import Project, ROOT

class Spec:
    def __init__(self, item):
        self.item = item; self.els = []
    def box(self, name, a, b, mat, tint=None, rot=None):
        e = {'name': name, 'from': [round(v, 4) for v in a], 'to': [round(v, 4) for v in b], 'mat': mat, 'tint': tint}
        if rot: e['rot'] = rot
        self.els.append(e)
    def mirror_x(self, name, a, b, mat, tint=None):
        self.box(name, a, b, mat, tint)
        self.box(name, [16 - b[0], a[1], a[2]], [16 - a[0], b[1], b[2]], mat, tint)

def R(rx, ry, rz):
    rx, ry, rz = np.radians([rx, ry, rz])
    X = np.array([[1, 0, 0], [0, np.cos(rx), -np.sin(rx)], [0, np.sin(rx), np.cos(rx)]])
    Y = np.array([[np.cos(ry), 0, np.sin(ry)], [0, 1, 0], [-np.sin(ry), 0, np.cos(ry)]])
    Z = np.array([[np.cos(rz), -np.sin(rz), 0], [np.sin(rz), np.cos(rz), 0], [0, 0, 1]])
    return X @ Y @ Z

def center_t(d, pt):
    """Translation (display units, 1/16 block) that puts model point pt (pixels) at the display origin."""
    p = np.array(pt) / 16 - .5
    return [round(float(v), 4) for v in -16 * (R(*d['rotation']) @ (np.array(d['scale']) * p))]

def disp_point(d, p, flip=True):
    p = np.array(p) / 16 - .5
    v = np.array(d['translation']) / 16 + R(*d['rotation']) @ (np.array(d['scale']) * p)
    return np.array([-v[0], v[1], -v[2]]) if flip else v

def build(spec, out_dir, grip, bbox_c, seed=20261030):
    """Returns (model dict, projects). grip/bbox_c in model pixels."""
    proj = Path(out_dir)
    p = Project(proj)
    els = []
    for i, e in enumerate(spec.els):
        el = {'name': e['name'], 'from': e['from'], 'to': e['to'],
              'faces': {f: {'texture': '#m', 'uv': [0, 0, 1, 1]} for f in ('north', 'east', 'south', 'west', 'up', 'down')}}
        if 'rot' in e: el['rotation'] = {'axis': e['rot'][0], 'angle': e['rot'][1], 'origin': e['rot'][2]}
        els.append(el)
    base = {'textures': {'m': 'minecraft:block/iron_block', 'particle': 'minecraft:block/iron_block'}, 'elements': els}
    src = proj / 'source.json'
    proj.mkdir(parents=True, exist_ok=True)
    src.write_text(json.dumps(base))
    p.call('import', {'path': str(src)})
    ids = [e['id'] for e in p.model['elements']]
    groups = {}
    for eid, e in zip(ids, spec.els):
        groups.setdefault((e['mat'], e['tint']), []).append(eid)
    ops = []
    for (mat, tint), g in groups.items():
        a = {'ids': g, 'material': mat, 'seed': seed}
        if tint: a['tint'] = tint
        ops.append({'op': 'material.apply', 'args': a})
    p.call('batch', {'ops': ops})
    m = p.vanilla()
    used = {f['texture'][1:] for e in m['elements'] for f in e['faces'].values()}
    m['textures'] = {k: v for k, v in m['textures'].items() if k in used or k == 'particle'}
    return p, m

def make_display(L, grip, centre, ref=None):
    """Display block following dagger/knight-sword conventions; ref = knight-sword values for the first-person offset."""
    d = {}
    k = json.loads((ROOT / 'content/models/items/knight-sword.model.json').read_text())['display']
    kg = [8, 4.5, 8]
    def mk(rot, s, pt):
        dd = {'rotation': rot, 'scale': [round(s, 4)] * 3}
        dd['translation'] = center_t(dd, pt)
        return dd
    d['gui'] = mk([10, -15, -35], 17.0 / L, centre)
    s = min(0.7, 16.7 / L)
    fp = {'rotation': [0, -35, 12], 'scale': [round(s, 4)] * 3, 'translation': [0, 0, 0]}
    kd = k['firstperson_righthand']
    base_k = np.array(center_t(kd, kg)); off = np.array(kd['translation']) - base_k
    fp['translation'] = [round(float(v), 4) for v in np.array(center_t(fp, grip)) + off]
    d['firstperson_righthand'] = fp
    d['firstperson_lefthand'] = json.loads(json.dumps(fp))
    tp = {'rotation': [35, -90, 0], 'scale': [0.75] * 3}
    tp['translation'] = center_t(tp, grip)
    d['thirdperson_righthand'] = tp
    tl = json.loads(json.dumps(tp)); tl['translation'][0] = -tl['translation'][0] or 0.0
    d['thirdperson_lefthand'] = tl
    d['ground'] = mk([75, 0, -90], 11.0 / L, centre)
    d['fixed'] = mk([0, 180, -35], 22.4 / L, centre)
    return d

def frame_parts(item, d, grip, root, tip, bottom, centre=(8, 8, 8)):
    """held (fixed) and held_hand entries as in content/frames/dagger.yaml."""
    out = {}
    for name, key in (('held', 'fixed'), ('held_hand', 'thirdperson_righthand')):
        dd = d[key]
        q = lambda p: disp_point(dd, p)
        g = q(grip)
        pts = {'grip': q(grip), 'pivot': q(grip), 'root': q(root), 'tip': q(tip)}
        if name == 'held_hand':
            pts = {k: v - g for k, v in pts.items()}
            pts['hand_anchor'] = q(centre) - g
        lng = pts['tip'] - pts['root']; lng = lng / np.linalg.norm(lng)
        edge = (q(np.array(root) + [1, 0, 0]) - q(root)); edge = edge / np.linalg.norm(edge)
        L = np.linalg.norm(q(tip) - q(bottom))
        f = lambda v: [round(float(x) + 0.0, 4) for x in v]
        out[name] = {'display': key, 'length': round(float(L), 4), 'points': {k: f(v) for k, v in pts.items()},
                     'axes': {'long': f(lng), 'edge': f(edge)}}
    return out

def frame_yaml(item, parts, header):
    lines = header + ['apiVersion: astera/v3', 'kind: ItemFrames', f'item: {item}', 'parts:']
    for name, v in parts.items():
        pts = ', '.join(f'{k}: {v["points"][k]}' for k in v['points'])
        lines += [f'  {name}:', f'    model: astera:{item}', f'    display: {v["display"]}', f'    length: {v["length"]}',
                  f'    points: {{ {pts} }}', f'    axes: {{ long: {v["axes"]["long"]}, edge: {v["axes"]["edge"]} }}']
    return '\n'.join(lines) + '\n'
