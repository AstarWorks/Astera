#!/usr/bin/env python3
"""bastion-greatsword S1-pilot model: Zwei-style flat slab greatsword, vanilla block materials only.

Geometry is authored in the SWORD LOCAL frame (origin = grip centre, +Y grip->tip, broad faces = +-Z,
edges = +-X), in model units (16 = one block).  OFF shifts it into the model-space box (-16..32).
Writes a base model (flat '#0' slots), imports it into a fresh Forge project, applies catalog
materials per group via material.apply, writes content/models/items/bastion-greatsword.model.json.
Usage: build_s1.py --project <fresh dir> [--round N]
"""
import argparse, json, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT/'tools/model-forge'))
from core import Project

KY = 0.686           # length scale about the grip centre: 44.3 -> 30.4 units = 1.9 blocks pommel to tip
KXB = 2.1            # blade/ricasso/shoulder width scale (broad greatsword); grip and guard keep their width
KXG = 1.3            # guard width scale
YBLADE = 5.86        # local y where the ricasso starts
OFF = -10.0          # model y = local y + OFF
MAT = {
 'steel':'metal.iron_block', 'edge':'stone.smooth_quartz', 'rim':'stone.polished_blackstone',
 'guard':'stone.polished_deepslate', 'gold':'metal.gold_block', 'ridge':'stone.polished_deepslate',
 'inlay':'concrete.lime_concrete', 'grip':'cloth.black_wool', 'wrap':'leather.dried_kelp_block',
}
els = []

def B(name, mat, x0, x1, y0, y1, z0, z1, rot=None):
    """box in local coords (x0..x1, y0..y1, z0..z1)"""
    kx = KXB if y0 >= YBLADE - 1e-6 else (KXG if y0 >= 3.5 - 1e-6 else 1.0)
    x0, x1, y0, y1 = x0*kx, x1*kx, y0*KY, y1*KY
    f = {k: {'texture': '#0', 'uv': [0, 0, 1, 1]} for k in ('north', 'south', 'east', 'west', 'up', 'down')}
    e = {'name': f'{mat}|{name}', 'from': [8+x0, OFF+y0, 8+z0], 'to': [8+x1, OFF+y1, 8+z1], 'faces': f}
    if rot:
        axis, ang = rot
        e['rotation'] = {'origin': [8+(x0+x1)/2, OFF+(y0+y1)/2, 8+(z0+z1)/2], 'axis': axis, 'angle': ang}
    els.append(e)

def both(name, mat, x0, x1, y0, y1, z0, z1, rot=None):
    """mirror across Z (front/back face layers). z0<z1 given for +Z side."""
    B(name+'_f', mat, x0, x1, y0, y1, z0, z1, rot)
    B(name+'_b', mat, x0, x1, y0, y1, -z1, -z0, rot)

def sym(name, mat, x0, x1, y0, y1, z0, z1, rot=None):
    """mirror across X (left/right). x0<x1 on +X side; rotation sign flips"""
    B(name+'_l', mat, x0, x1, y0, y1, z0, z1, rot)
    r = (rot[0], -rot[1]) if rot else None
    B(name+'_r', mat, -x1, -x0, y0, y1, z0, z1, r)

def build():
    # ---- pommel + grip (y -3.6 .. 3.6; 0.45 block) -------------------------------------------
    B('pommel_cap', 'gold', -1.0, 1.0, -3.6, -3.1, -1.0, 1.0)
    B('pommel_ball', 'rim', -1.25, 1.25, -3.1, -1.6, -1.15, 1.15)
    B('pommel_stud_f', 'gold', -0.4, 0.4, -2.75, -1.95, 1.15, 1.3)
    B('pommel_stud_b', 'gold', -0.4, 0.4, -2.75, -1.95, -1.3, -1.15)
    B('pommel_collar', 'gold', -1.0, 1.0, -1.6, -1.2, -1.0, 1.0)
    B('grip_core', 'grip', -0.75, 0.75, -1.2, 3.0, -0.75, 0.75)
    for i in range(6):
        y = -1.05 + i*0.68
        B(f'grip_wrap_{i}', 'wrap' if i % 2 == 0 else 'rim', -0.9, 0.9, y, y+0.5, -0.9, 0.9)
    B('grip_collar', 'gold', -1.0, 1.0, 3.0, 3.5, -1.0, 1.0)

    # ---- guard (y 3.6 .. 5.52 = 0.12 block, 0.62 across): 3 steps + end caps ---------------
    B('guard_step1', 'guard', -2.3, 2.3, 3.6, 5.52, -0.95, 0.95)
    sym('guard_step2', 'guard', 2.3, 3.7, 3.76, 5.36, -0.8, 0.8)
    sym('guard_step3', 'guard', 3.7, 4.6, 3.95, 5.17, -0.65, 0.65)
    sym('guard_cap', 'gold', 4.6, 4.96, 3.9, 5.5, -0.75, 0.75)
    sym('guard_tipbead', 'rim', 4.96, 5.2, 4.35, 5.05, -0.5, 0.5)
    sym('guard_trim_top2', 'gold', 2.3, 3.7, 5.36, 5.46, -0.5, 0.5)
    sym('guard_trim_bot2', 'gold', 2.3, 3.7, 3.66, 3.76, -0.5, 0.5)
    B('guard_trim_bot1', 'gold', -2.3, 2.3, 3.5, 3.6, -0.55, 0.55)
    both('guard_plate', 'gold', -1.7, 1.7, 3.9, 5.22, 0.95, 1.05)
    both('guard_inset', 'rim', -0.95, 0.95, 4.2, 4.92, 1.05, 1.12)
    both('guard_crest', 'gold', -0.38, 0.38, 4.3, 4.82, 1.12, 1.2, ('z', 45))
    for sx in (-1, 1):
        for i in range(2):
            x = sx*(2.9+i*0.75)
            both(f'guard_rivet_{sx}_{i}', 'edge', x-0.22, x+0.22, 4.25, 4.69, 0.8-i*0.15, 0.9-i*0.15+0.0)
    B('guard_collar', 'gold', -2.15, 2.15, 5.52, 5.86, -0.7, 0.7)
    sym('shoulder_flare', 'gold', 1.9, 2.8, 5.86, 7.2, -0.45, 0.45, ('z', -22.5))
    B('ricasso', 'rim', -1.92, 1.92, 5.86, 7.0, -0.52, 0.52)
    both('ricasso_rivet', 'gold', -0.3, 0.3, 6.1, 6.7, 0.52, 0.6)

    # ---- blade: straight slab y 7.0 .. 35.12, taper 35.12 .. 40.72 (0.35 block) ------------
    YS, YT, YE = 7.0, 35.12, 40.72
    B('blade_core', 'steel', -1.35, 1.35, YS, YT, -0.40, 0.40)
    sym('bevel_hi', 'edge', 1.35, 1.7, YS, YT, -0.34, 0.34)
    sym('bevel_rim', 'rim', 1.7, 1.92, YS, YT, -0.24, 0.24)
    # fuller ridge + highlights on both faces
    both('fuller_ridge', 'ridge', -0.22, 0.22, 12.4, 33.4, 0.40, 0.48)
    both('fuller_start', 'ridge', -0.12, 0.12, 11.6, 12.4, 0.40, 0.45)
    both('fuller_end', 'ridge', -0.12, 0.12, 33.4, 34.4, 0.40, 0.45)
    for s in (-1, 1):
        both(f'fuller_hi_{s}', 'edge', *sorted((s*0.22, s*0.4)), 12.4, 33.4, 0.40, 0.45)
        both(f'pinstripe_{s}', 'rim', *sorted((s*0.9, s*1.04)), 12.0, 34.0, 0.40, 0.44)
        for k in range(7):  # etched dashes between ridge and pinstripe
            y = 13.0+k*3.1
            both(f'dash_{s}_{k}', 'gold', *sorted((s*0.55, s*0.7)), y, y+0.9, 0.40, 0.43)
    # lime window near the guard: 0.09 x 0.20 block = 1.44 x 3.2, 0.16 proud, gold frame
    both('inlay', 'inlay', -0.72, 0.72, 8.0, 11.2, 0.40, 0.56)
    both('inlay_frame_l', 'gold', -0.9, -0.72, 7.8, 11.4, 0.40, 0.50)
    both('inlay_frame_r', 'gold', 0.72, 0.9, 7.8, 11.4, 0.40, 0.50)
    both('inlay_frame_t', 'gold', -0.72, 0.72, 11.2, 11.4, 0.40, 0.50)
    both('inlay_frame_b', 'gold', -0.72, 0.72, 7.8, 8.0, 0.40, 0.50)
    # tip: 5 stepped pieces, half widths 1.60 .. 0.32
    n = 5; h = (YE-YT)/n
    for i in range(n):
        hw = 1.92-0.32*(i+1)
        y0, y1 = YT+i*h, YT+(i+1)*h
        if i < 4:
            B(f'tip{i}_core', 'steel', -(hw-0.28), hw-0.28, y0, y1, -0.40, 0.40)
            sym(f'tip{i}_rim', 'rim', hw-0.28, hw, y0, y1, -0.24, 0.24)
            if i < 3:
                sym(f'tip{i}_hi', 'edge', hw-0.28, hw-0.0, y0, y1, 0.24, 0.30)
                sym(f'tip{i}_hi2', 'edge', hw-0.28, hw-0.0, y0, y1, -0.30, -0.24)
        else:
            B('tip_point', 'rim', -hw, hw, y0, y1, -0.24, 0.24)

import weapon_shading  # noqa: E402  (sibling module)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--project', type=Path, required=True)
    ap.add_argument('--out', type=Path, default=ROOT/'content/models/items/bastion-greatsword.model.json')
    a = ap.parse_args()
    build()
    old = json.loads(a.out.read_text())
    base = {'textures': {'0': 'minecraft:block/iron_block', 'particle': 'minecraft:block/iron_block'},
            'elements': els, 'display': old['display']}
    work = a.project.parent/(a.project.name+'-base.json')
    work.write_text(json.dumps(base))
    p = Project(a.project)
    p.call('import', {'path': str(work)})
    groups = {}
    for i, e in enumerate(p.model['elements']):
        groups.setdefault(e['name'].split('|')[0], []).append(e['id'])
    ops = [{'op': 'material.apply', 'args': {'ids': ids, 'material': MAT[g], 'seed': 20261024}} for g, ids in groups.items()]
    r = p.call('batch', {'ops': ops})
    for e in p.model['elements']:
        e['name'] = e['name'].split('|')[1]
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k: v for k, v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/iron_block'
    weapon_shading.mark(model)  # ADR-0275: same shading rule as the rig parts
    a.out.write_text(json.dumps(model, indent=2)+'\n')
    print(len(model['elements']), 'elements', sorted(model['textures']))

if __name__ == '__main__':
    main()
