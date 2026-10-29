#!/usr/bin/env python3
"""Build the light-melee item models (katana, rapier, iron-blade, spear, twin-daggers) through Forge.
Usage: build.py <item> [--proj DIR]   Writes content/models/items/<item>.model.json and content/frames/<item>.yaml."""
import argparse, json, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from lib import *

IRON = 'metal.iron_block'; GOLD = 'metal.gold_block'; WOOL = 'cloth.white_wool'; COPPER = 'metal.copper_block'
STEEL = '#e6ecf3'; DSTEEL = '#aab3bf'

def blade(s, name, segs, cx, zc, th, mat=IRON, tint=STEEL, spine_tint=None, edge_strip=None):
    """segs: (y0, y1, left, right) around cx; th = thickness (z) around zc. Optional dark spine half and bright edge strip on +x."""
    for i, (y0, y1, l, r) in enumerate(segs):
        if spine_tint:
            s.box(f'{name} spine {i}', [cx - l, y0, zc - th / 2], [cx - 0.1, y1, zc + th / 2], mat, spine_tint)
            s.box(f'{name} body {i}', [cx - 0.1, y0, zc - th / 2 + 0.1], [cx + r, y1, zc + th / 2 - 0.1], mat, tint)
        else:
            s.box(f'{name} body {i}', [cx - l, y0, zc - th / 2], [cx + r, y1, zc + th / 2], mat, tint)
        if edge_strip:
            s.box(f'{name} edge {i}', [cx + r, y0, zc - edge_strip / 2], [cx + r + 0.25, y1, zc + edge_strip / 2], mat, '#ffffff')

def katana():
    s = Spec('katana')
    NAVY = '#26396b'; ITO = '#c7d3ec'
    s.box('kashira cap', [7.0, -0.7, 7.2], [9.0, 0.4, 8.8], GOLD)
    s.box('tsuka core', [7.1, 0.4, 7.3], [8.9, 7.6, 8.7], WOOL, NAVY)
    for i in range(8):
        y = 0.8 + i * 0.85
        s.box(f'ito wrap {i}', [6.95, y, 7.15], [9.05, y + 0.5, 8.85], WOOL, ITO, ['z', 22.5 if i % 2 == 0 else -22.5, [8, y + .25, 8]])
    s.box('menuki front', [7.6, 3.6, 8.7], [8.4, 4.4, 8.95], GOLD)
    s.box('menuki back', [7.6, 3.6, 7.05], [8.4, 4.4, 7.3], GOLD)
    s.box('fuchi collar', [6.9, 7.3, 7.1], [9.1, 8.0, 8.9], GOLD)
    s.box('tsuba plate', [5.6, 8.0, 6.2], [10.4, 8.5, 9.8], IRON, '#4a4f5a')
    s.box('tsuba wide', [5.0, 8.1, 7.0], [11.0, 8.4, 9.0], IRON, '#4a4f5a')
    s.box('tsuba deep', [6.2, 8.05, 5.8], [9.8, 8.35, 10.2], IRON, '#4a4f5a')
    s.box('tsuba rim gold', [5.0, 8.4, 7.4], [11.0, 8.55, 8.6], GOLD)
    s.box('habaki', [7.0, 8.5, 7.35], [9.0, 10.0, 8.65], GOLD)
    # (y0, y1, left, right) around cx; cx shifts toward the spine (-x) = sori
    for i in range(7):
        y0 = 10.0 + i * 2.5; y1 = y0 + 2.5
        cx = 8 - 0.006 * (y0 - 10.0) ** 2
        r = 0.95 if i < 4 else (0.8 if i == 4 else (0.6 if i == 5 else 0.3))
        l = 0.95 if i < 5 else (0.75 if i == 5 else 0.5)
        s.box(f'blade spine {i}', [cx - l, y0, 7.5], [cx - 0.1, y1, 8.5], IRON, DSTEEL)
        s.box(f'blade hamon {i}', [cx - 0.1, y0, 7.62], [cx + r, y1, 8.38], IRON, '#f4f8fc')
        s.box(f'blade edge {i}', [cx + r, y0, 7.85], [cx + r + 0.25, y1, 8.15], IRON, '#ffffff')
    s.box('shinogi ridge', [7.3, 11.0, 8.5], [7.5, 24.0, 8.6], IRON, '#ffffff')
    s.box('shinogi ridge back', [7.3, 11.0, 7.4], [7.5, 24.0, 7.5], IRON, '#ffffff')
    return s, dict(grip=[8, 4, 8], centre=[7.6, 15, 8], root=[8.0, 10.0, 8], tip=[round(8 - 0.006 * 15 ** 2 - 0.1, 3), 27.5, 8], bottom=[8, -0.7, 8])

def rapier():
    s = Spec('rapier')
    LEATH = '#5b2b24'
    s.box('pommel cap', [7.0, -1.4, 7.0], [9.0, -0.4, 9.0], GOLD)
    s.box('pommel ball', [6.6, -0.4, 6.6], [9.4, 1.0, 9.4], GOLD)
    s.box('pommel neck', [7.1, 1.0, 7.1], [8.9, 1.6, 8.9], GOLD)
    s.box('grip core', [7.2, 1.6, 7.2], [8.8, 7.4, 8.8], WOOL, LEATH)
    for i in range(6):
        y = 2.0 + i * 0.95
        s.box(f'grip wire {i}', [7.1, y, 7.1], [8.9, y + 0.3, 8.9], IRON, '#d9dde3')
    s.box('cup base', [6.2, 7.4, 6.2], [9.8, 7.8, 9.8], GOLD)
    s.box('cup mid', [5.4, 7.8, 5.4], [10.6, 8.3, 10.6], GOLD)
    s.box('cup rim', [5.0, 8.3, 5.0], [11.0, 8.8, 11.0], GOLD)
    s.box('cup inlay', [5.6, 8.8, 5.6], [10.4, 8.9, 10.4], IRON, '#8a2a2a')
    s.box('quillon bar', [2.4, 8.35, 7.5], [13.6, 9.0, 8.5], GOLD)
    s.box('quillon finial L', [1.6, 8.1, 7.3], [2.4, 9.2, 8.7], GOLD)
    s.box('quillon finial R', [13.6, 8.1, 7.3], [14.4, 9.2, 8.7], GOLD)
    s.box('quillon curl L', [1.9, 9.0, 7.5], [2.5, 10.0, 8.5], GOLD, None, ['z', 22.5, [2.2, 9.0, 8]])
    s.box('quillon curl R', [13.5, 9.0, 7.5], [14.1, 10.0, 8.5], GOLD, None, ['z', -22.5, [13.8, 9.0, 8]])
    s.box('quillon stud L', [4.4, 8.4, 8.5], [5.0, 8.9, 8.8], GOLD)
    s.box('quillon stud R', [11.0, 8.4, 8.5], [11.6, 8.9, 8.8], GOLD)
    s.box('bow post', [7.5, 1.3, 4.7], [8.5, 8.4, 5.5], GOLD)
    s.box('bow top link', [7.5, 7.85, 5.5], [8.5, 8.6, 7.4], GOLD)
    s.box('bow low link', [7.5, 1.05, 5.5], [8.5, 1.85, 7.4], GOLD)
    s.box('bow bead', [7.3, 4.2, 4.5], [8.7, 5.0, 5.7], GOLD)
    cuts = [(8.8, 13, 1.2), (13, 18, 1.05), (18, 23, 0.9), (23, 26.5, 0.7), (26.5, 28.0, 0.42), (28.0, 28.8, 0.16)]
    for i, (y0, y1, w) in enumerate(cuts):
        s.box(f'blade body {i}', [8 - w / 2 - 0.0, y0, 7.7], [8 + w / 2 + 0.0, y1, 8.3], IRON, STEEL)
    s.box('blade ridge', [7.85, 9.2, 8.3], [8.15, 25.0, 8.45], IRON, '#ffffff')
    s.box('blade ridge back', [7.85, 9.2, 7.55], [8.15, 25.0, 7.7], IRON, '#ffffff')
    s.box('ricasso', [7.3, 8.85, 7.6], [8.7, 10.6, 8.4], IRON, DSTEEL)
    s.box('edge hone L', [7.35, 12.0, 7.85], [7.45, 26.0, 8.15], IRON, '#ffffff')
    s.box('edge hone R', [8.55, 12.0, 7.85], [8.65, 26.0, 8.15], IRON, '#ffffff')
    return s, dict(grip=[8, 4.5, 8], centre=[8, 14, 8], root=[8, 9.5, 8], tip=[8, 28.8, 8], bottom=[8, -1.4, 8])

def ironblade():
    s = Spec('iron-blade')
    s.box('pommel plug', [7.0, 0.0, 7.0], [9.0, 1.0, 9.0], IRON, '#8e949e')
    s.box('pommel knob', [6.7, 1.0, 6.7], [9.3, 2.0, 9.3], IRON, '#8e949e')
    s.box('grip core', [7.3, 2.0, 7.3], [8.7, 7.0, 8.7], 'wood.stripped_dark_oak_log')
    for i in range(4):
        y = 2.4 + i * 1.15
        s.box(f'grip leather {i}', [7.2, y, 7.2], [8.8, y + 0.55, 8.8], 'leather.brown_mushroom_block', None)
    s.box('guard bar', [4.2, 7.0, 7.1], [11.8, 8.0, 8.9], IRON, '#8f96a1')
    s.box('guard tip L', [3.6, 6.4, 7.3], [4.2, 8.0, 8.7], IRON, '#8f96a1')
    s.box('guard tip R', [11.8, 6.4, 7.3], [12.4, 8.0, 8.7], IRON, '#8f96a1')
    s.box('guard boss', [7.0, 8.0, 7.0], [9.0, 8.8, 9.0], IRON, '#8f96a1')
    s.box('guard rivet L', [5.4, 7.4, 8.9], [5.9, 7.9, 9.1], GOLD); s.box('guard rivet R', [10.1, 7.4, 8.9], [10.6, 7.9, 9.1], GOLD)
    s.box('guard rivet L b', [5.4, 7.4, 6.9], [5.9, 7.9, 7.1], GOLD); s.box('guard rivet R b', [10.1, 7.4, 6.9], [10.6, 7.9, 7.1], GOLD)
    cuts = [(8.8, 18, 1.5, 1.5), (18, 21.5, 1.3, 1.3), (21.5, 23.5, 0.9, 0.9), (23.5, 24.8, 0.55, 0.45), (24.8, 25.5, 0.2, 0.15)]
    for i, (y0, y1, l, r) in enumerate(cuts):
        s.box(f'blade body {i}', [8 - l, y0, 7.55], [8 + r, y1, 8.45], IRON, '#d4dbe4')
        s.box(f'blade edge L {i}', [8 - l - 0.2, y0, 7.8], [8 - l, y1, 8.2], IRON, '#ffffff')
        s.box(f'blade edge R {i}', [8 + r, y0, 7.8], [8 + r + 0.2, y1, 8.2], IRON, '#ffffff')
    s.box('fuller front', [7.75, 9.4, 8.45], [8.25, 20.5, 8.55], IRON, '#9aa3ad')
    s.box('fuller back', [7.75, 9.4, 7.45], [8.25, 20.5, 7.55], IRON, '#9aa3ad')
    return s, dict(grip=[8, 4.5, 8], centre=[8, 12, 8], root=[8, 9.0, 8], tip=[8, 25.5, 8], bottom=[8, 0.0, 8])

def spear():
    s = Spec('spear')
    WOOD = 'wood.stripped_oak_log'
    s.box('butt cap', [7.0, -1.4, 7.0], [9.0, -0.4, 9.0], IRON, '#7e848e')
    s.box('shaft', [7.1, -0.4, 7.1], [8.9, 22.0, 8.9], WOOD)
    for i in range(6):
        y = 5.0 + i * 1.1
        s.box(f'grip wrap {i}', [6.95, y, 6.95], [9.05, y + 0.6, 9.05], WOOL, '#6b3f26')
    s.box('shaft ring low', [6.9, 1.0, 6.9], [9.1, 1.6, 9.1], GOLD)
    s.box('shaft ring mid', [6.9, 14.0, 6.9], [9.1, 14.6, 9.1], GOLD)
    s.box('tassel top', [6.8, 21.0, 6.8], [9.2, 21.95, 9.2], WOOL, '#b3252b')
    s.box('tassel mid', [6.4, 19.8, 6.4], [9.6, 21.0, 9.6], WOOL, '#b3252b')
    s.box('tassel low', [6.8, 18.6, 6.8], [9.2, 19.8, 9.2], WOOL, '#8f1c22')
    s.box('collar', [6.7, 22.0, 6.7], [9.3, 23.0, 9.3], GOLD)
    s.box('socket', [7.2, 23.0, 7.2], [8.8, 24.0, 8.8], IRON, '#9aa1ab')
    cuts = [(24.0, 25.5, 1.2, 1.2), (25.5, 27.5, 1.8, 1.8), (27.5, 29.0, 1.5, 1.5), (29.0, 30.4, 0.95, 0.95), (30.4, 31.4, 0.45, 0.45), (31.4, 31.9, 0.15, 0.15)]
    for i, (y0, y1, l, r) in enumerate(cuts):
        s.box(f'head body {i}', [8 - l, y0, 7.55], [8 + r, y1, 8.45], IRON, '#e9eef4')
        s.box(f'head edge L {i}', [8 - l - 0.2, y0, 7.8], [8 - l, y1, 8.2], IRON, '#ffffff')
        s.box(f'head edge R {i}', [8 + r, y0, 7.8], [8 + r + 0.2, y1, 8.2], IRON, '#ffffff')
    s.box('head ridge front', [7.8, 24.5, 8.45], [8.2, 30.2, 8.6], IRON, '#aab3bf')
    s.box('head ridge back', [7.8, 24.5, 7.4], [8.2, 30.2, 7.55], IRON, '#aab3bf')
    s.box('head gem', [7.7, 24.8, 8.6], [8.3, 25.4, 8.7], 'gem.redstone_block')
    return s, dict(grip=[8, 8, 8], centre=[8, 15, 8], root=[8, 24.0, 8], tip=[8, 31.9, 8], bottom=[8, -1.4, 8])

def twin():
    s = Spec('twin-daggers')
    for k, z in (('a', 6.3), ('b', 9.7)):
        s.box(f'{k} pommel ring', [7.1, -0.2, z - .9], [8.9, 0.7, z + .9], GOLD)
        s.box(f'{k} grip core', [7.3, 0.7, z - .7], [8.7, 6.2, z + .7], WOOL, '#8c1d26')
        for i in range(4):
            y = 1.1 + i * 1.2
            s.box(f'{k} grip band {i}', [7.2, y, z - .8], [8.8, y + 0.4, z + .8], WOOL, '#1c1c24')
        s.box(f'{k} guard', [5.2, 6.2, z - .6], [10.8, 6.9, z + .6], IRON, '#5d6470')
        s.box(f'{k} guard hook L', [4.7, 5.7, z - .45], [5.3, 6.85, z + .45], IRON, '#5d6470')
        s.box(f'{k} guard hook R', [10.7, 6.3, z - .45], [11.3, 7.6, z + .45], IRON, '#5d6470')
        s.box(f'{k} ricasso', [7.3, 6.9, z - .4], [8.7, 7.8, z + .4], GOLD)
        cuts = [(7.8, 10.5, 0.85, 0.95), (10.5, 13.0, 0.9, 1.15), (13.0, 15.2, 0.75, 1.0), (15.2, 16.6, 0.45, 0.6), (16.6, 17.6, 0.15, 0.2)]
        for i, (y0, y1, l, r) in enumerate(cuts):
            s.box(f'{k} blade body {i}', [8 - l, y0, z - .35], [8 + r, y1, z + .35], IRON, '#c7cfda')
            s.box(f'{k} blade edge {i}', [8 + r, y0, z - .15], [8 + r + 0.2, y1, z + .15], IRON, '#ffffff')
        s.box(f'{k} blade fuller', [7.9, 8.2, z + .35], [8.1, 13.8, z + .45], IRON, '#8a93a0')
    return s, dict(grip=[8, 3.5, 8], centre=[8, 9, 8], root=[8, 7.8, 8], tip=[8.1, 17.6, 8], bottom=[8, -0.2, 8])

BUILDERS = {'katana': katana, 'rapier': rapier, 'iron-blade': ironblade, 'spear': spear, 'twin-daggers': twin}
HEADS = {
 'katana': 'Single-edged tachi blade with sori; model +X = cutting edge (hamon side), -X = spine. Grip wrapped navy ito.',
 'rapier': 'Slender thrusting blade with swept cup/knuckle-bow hilt (bow on -Z so it never crosses the edge side); +X = blade edge.',
 'iron-blade': 'Plain iron broadsword: wide straight blade, fuller, bar guard, wooden grip.',
 'spear': 'Leaf-headed spear on a long shaft; root = head base (socket top), tip = point; +X = leaf edge.',
 'twin-daggers': 'Pair of parallel curved-tip daggers modelled as one held item (blades at z=6.3 and z=9.7, same Y span); no off-hand model.',
}

def main():
    ap = argparse.ArgumentParser(); ap.add_argument('item'); ap.add_argument('--proj'); a = ap.parse_args()
    s, k = BUILDERS[a.item]()
    proj = a.proj or f'/tmp/claude-1000/skill-engine/models/proj-{a.item}'
    if (Path(proj) / 'project.json').exists(): raise SystemExit('fresh project dir required: ' + proj)
    p, m = build(s, proj, k['grip'], k['centre'])
    ys = [e['from'][1] for e in m['elements']] + [e['to'][1] for e in m['elements']]
    L = max(ys) - min(ys)
    disp = make_display(L, k['grip'], k['centre'])
    out = {'credit': 'Astera / Claude (light-models, model-forge)', 'textures': m['textures'], 'elements': m['elements'], 'display': disp,
           'gui_light': 'front', 'ambientocclusion': False}
    if m.get('astera_tints'): out['astera_tints'] = m['astera_tints']
    path = ROOT / 'content/models/items' / f'{a.item}.model.json'
    path.write_text(json.dumps(out, indent=2) + '\n')
    parts = frame_parts(a.item, disp, k['grip'], k['root'], k['tip'], k['bottom'])
    hdr = [f'# Point table for the {a.item} (frame and chain as content/frames/dagger.yaml).',
           f'# content/models/items/{a.item}.model.json ({len(m["elements"])} elements, model +Y = blade direction, +X = blade edge side): {HEADS[a.item]}',
           f'# grip = hold point {k["grip"]} = pivot; root = blade base {[round(v, 3) for v in k["root"]]}; tip = blade tip {[round(v, 3) for v in k["tip"]]}; long = root->tip; edge = model +X; length = {k["bottom"][1]} (lowest point) to tip.',
           '# held_hand = the same elements under the model\'s thirdperson_righthand display, the transform the rig\'s main-hand mesh draws with; hand_anchor = model centre (8,8,8).']
    (ROOT / 'content/frames' / f'{a.item}.yaml').write_text(frame_yaml(a.item, parts, hdr))
    print(a.item, len(m['elements']), 'elements', 'L', L, json.dumps(parts['held_hand']['points']))

if __name__ == "__main__": main()
