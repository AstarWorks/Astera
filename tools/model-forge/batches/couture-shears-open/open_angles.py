#!/usr/bin/env python3
"""Bake open-angle full models of the Couturier's Shears from the hinged halves.

Half A (a_*) rotates +theta/2 (counter-clockwise seen from +z, tip toward -x), half B (b_*) -theta/2,
both about the hinge (8,8) on the Z axis. theta = TOTAL extra opening on top of the rest pose.
Each element already carries an optional single-axis z rotation (angle alpha, origin o). The composition
R_phi(p) o R_alpha(o) is again a z rotation by alpha+phi about a new origin o' (solved below), so only the
rotation block changes; from/to/faces/UVs are untouched. The 26.3 client accepts any angle (no 22.5 step,
no +-45 limit; CuboidRotation.SingleAxisRotation takes a float); model-forge validate warns about it.
If alpha+phi == 0 the element would need a pure translation: the box is shifted instead.

usage: open_angles.py [repo_root]   -> content/models/items/couture-rapier-open-<theta>.model.json
"""
import copy, json, math, sys
from pathlib import Path

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[4]
items = root/'content/models/items'
PIVOT = (8.0, 8.0)
ANGLES = (15, 30, 45, 60)


def rot(a, v):
    c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
    return (c*v[0]-s*v[1], s*v[0]+c*v[1])


def compose(el, phi):
    el = copy.deepcopy(el)
    r = el.get('rotation')
    if r and r['axis'] != 'z': raise ValueError(el['name'])
    alpha = r['angle'] if r else 0.0
    o = (r['origin'][0], r['origin'][1]) if r else PIVOT
    oz = r['origin'][2] if r else (el['from'][2]+el['to'][2])/2
    d = rot(phi, (o[0]-PIVOT[0], o[1]-PIVOT[1]))
    c = (d[0]+PIVOT[0], d[1]+PIVOT[1])  # image of o under the global rotation
    total = alpha+phi
    if abs(total) < 1e-9:
        dx, dy = c[0]-o[0], c[1]-o[1]
        el['from'][0] += dx; el['to'][0] += dx; el['from'][1] += dy; el['to'][1] += dy
        el.pop('rotation', None)
        return el
    # (I-R) o' = c - R o
    ro = rot(total, o); rhs = (c[0]-ro[0], c[1]-ro[1])
    cs, sn = math.cos(math.radians(total)), math.sin(math.radians(total))
    a, b, cc, dd = 1-cs, sn, -sn, 1-cs
    det = a*dd-b*cc
    ox = (rhs[0]*dd-b*rhs[1])/det
    oy = (a*rhs[1]-cc*rhs[0])/det
    el['rotation'] = {'origin': [round(ox, 6), round(oy, 6), oz], 'axis': 'z', 'angle': round(total, 6)}
    return el


full = json.loads((items/'couture-rapier.model.json').read_text())
A = json.loads((items/'couture-rapier-blade-a.model.json').read_text())['elements']
B = json.loads((items/'couture-rapier-blade-b.model.json').read_text())['elements']
assert [e['name'] for e in full['elements']] == [e['name'] for e in A+B]
for theta in ANGLES:
    m = copy.deepcopy(full)
    m['elements'] = [compose(e, theta/2) for e in A]+[compose(e, -theta/2) for e in B]
    (items/f'couture-rapier-open-{theta}.model.json').write_text(json.dumps(m, indent=2)+'\n')
    print(theta, len(m['elements']))
