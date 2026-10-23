#!/usr/bin/env python3
"""Recompute bastion-greatsword display transforms from the OLD model's accepted placement.

Hand contexts keep the old grip point fixed in the hand (new grip = model (8,-10,8)); gui/ground/fixed
keep the old bbox centre fixed. New scales are chosen for the 44-unit slab. Minecraft applies
translate(T) -> rotationXYZ(R) -> scale(s) about the model centre (8,8,8).
Usage: display_s1.py <old-model.json> [extra-translation JSON {context:[dx,dy,dz]}]"""
import json, sys
import numpy as np
from pathlib import Path
ROOT = Path(__file__).resolve().parents[4]
def rot(r):
    x, y, z = np.radians(r)
    cx, sx, cy, sy, cz, sz = np.cos(x), np.sin(x), np.cos(y), np.sin(y), np.cos(z), np.sin(z)
    Rx = np.array([[1,0,0],[0,cx,-sx],[0,sx,cx]]); Ry = np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]]); Rz = np.array([[cz,-sz,0],[sz,cz,0],[0,0,1]])
    return Rx@Ry@Rz
def bbox_center(elements):
    lo = np.min([e['from'] for e in elements], axis=0); hi = np.max([e['to'] for e in elements], axis=0)
    return (lo+hi)/2
old = json.loads(Path(sys.argv[1]).read_text())
target = ROOT/'content/models/items/bastion-greatsword.model.json'
new = json.loads(target.read_text())
od, nd = old['display'], new['display']
oc, nc = bbox_center(old['elements'])-8, bbox_center(new['elements'])-8
og, ng = np.array([0,3-8,0.]), np.array([0,-10-8,0.])
SCALE = {'gui':0.385,'firstperson_righthand':0.5,'firstperson_lefthand':0.5,'thirdperson_righthand':0.7,'thirdperson_lefthand':0.7,'ground':0.3,'fixed':0.4}
EXTRA = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
for k, s in SCALE.items():
    o = od[k]; R = rot(o['rotation']); so = o['scale'][0]
    a, b = (og, ng) if 'hand' in k else (oc, nc)
    T = np.array(o['translation']) + R@(so*a) - R@(s*b)
    T += np.array(EXTRA.get(k, [0,0,0]))
    nd[k] = {'rotation': o['rotation'], 'translation': [round(float(v),3) for v in T], 'scale': [s,s,s]}
target.write_text(json.dumps(new, indent=2)+'\n')
print(json.dumps(nd))
