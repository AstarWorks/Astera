#!/usr/bin/env python3
"""Writes deliver/rig-parts.yaml for the S1 rig from the held model's coordinates (model units, grip (8,-10,8)).

Round r11 (director r10: end overlap showed two swords; rig guard = plain yellow slab, reverse blade face plain).
22 parts (hard cap: rig 22 + fx core 9 + particles 29 = 60 per cast at hit 2). Tricks that keep the count down:
  * one full-width part per symmetric pair whose middle hides inside a thicker part (edges, rims, rivets, caps);
  * every face-detail part goes through the blade (z > core), so BOTH faces show the fuller / inlay;
  * nested parts differ by >= 0.006 block per side (no z-fight at distance).
Usage: rig_parts_s1.py  (writes the yaml; prints the table)"""
import json
from pathlib import Path
ROOT = Path(__file__).resolve().parents[4]
OUT = ROOT/'tools/skill-compare/data/bastion-greatsword-s1/deliver/rig-parts.yaml'
RIG = ROOT/'content/vfx/bastion-s1-rig.yaml'
HELD = ROOT/'content/models/items/bastion-greatsword.model.json'
G = (8.0, -10.0, 8.0)

def box(pid, block, x, y, z, note):
    """x/y/z = (lo, hi) in model units, or a float = rig-local size in blocks centred on the grip axis."""
    c, s = [], []
    for (lo, hi), g in zip((x, y, z), G):
        c.append(round(((lo+hi)/2-g)/16, 5)); s.append(round((hi-lo)/16, 5))
    return dict(id=pid, block=block, offset=c, size=s, note=note)

def zc(w):  # symmetric thickness (model units) about z=8
    return (8-w/2, 8+w/2)
def xc(w):
    return (8-w/2, 8+w/2)

BLADE_Y = (-3.0, 25.12)
TIP_Y = 27.36  # held tip1 / tip2 step
TIP_ROOT = -6.3  # 0.1 above guard_1's bottom (-6.4): inside guard_1, no coplanar bottom face; 0.23 block from the grip pivot
P = [
    # blade (core z 0.8 units = 0.05 block)
    # r22: the core runs on into the tip (held tip0/tip1 cores are the same 0.8-deep iron), so the tip needs no iron part of its own;
    # it stops 0.16 below tip_1's top so no iron top face is coplanar with the rim top (z-fight) and no iron side face is ever outermost.
    box('blade_face', 'iron_block',          xc(2.7),   (BLADE_Y[0], TIP_Y - 0.16), zc(0.8),  'held blade_core + tip0_core/tip1_core'),
    box('edges',      'smooth_quartz',       xc(3.4),   BLADE_Y, zc(0.68), 'held bevel_hi_l/r (middle inside the core)'),
    box('rims',       'polished_blackstone', xc(3.84),  BLADE_Y, zc(0.48), 'held bevel_rim_l/r (middle inside the edges)'),
    box('fuller',     'polished_deepslate',  xc(0.44),  (1.6, 24.4), zc(1.06), 'held fuller_ridge/start/end_f/b (both faces; hi strips + pinstripes cut for the 22-part cap)'),
    # tip (5 held steps -> 2). r22 (director r20: the old tip_1 was a 0.05-deep IRON box, so edge-on its light side face stood out past
    # the 0.03 dark rims as a second object). Now the held profile: dark 0.48 rim outermost (flush with `rims`), 0.8 iron core inside it.
    # ROOTED NEAR THE PIVOT: the planner sends translation = pivot + R*(offset - size/2) (the box corner) and the client LERPS it while it
    # SLERPS the rotation, so mid-chunk a corner at distance d sits d*(1-cos(step/2)) off the arc (old tip: 2.2 block -> 0.2 block off the
    # blade at a 50 deg chunk = the "second object" in r20/r22 opponent t4-5). Both tip parts therefore start at TIP_ROOT inside guard_1 and
    # run hidden through guard/collar/ricasso/blade (tip_1 inside `edges` 0.68, tip_2 inside the 0.8 core), so their corner = the blade's.
    box('tip_1', 'polished_blackstone', xc(3.2),  (TIP_ROOT, TIP_Y), zc(0.48), 'held tip0/tip1 rims (frame round the blade_face core; hidden inside edges below 25.12)'),
    box('tip_2', 'polished_blackstone', xc(1.28), (TIP_ROOT, 30.72), zc(0.6),  'held tip2 + tip3 + tip_point (mean outer width; 0.6 = rim + quartz strips; hidden in the core below 27.2)'),
    # inlay window (through: both faces)
    box('inlay',       'lime_concrete', xc(1.44), (-2.0, 1.2), zc(1.12), 'held inlay_f/b'),
    box('inlay_frame', 'gold_block',    xc(1.8),  (-2.2, 1.4), zc(1.0),  'held inlay_frame_*'),
    # ricasso + collar + shoulder flares
    box('ricasso', 'polished_blackstone', xc(3.84), (-4.14, -3.0), zc(1.04), 'held ricasso'),
    box('collar',  'gold_block',          xc(4.3),  (-4.48, -4.14), zc(1.4), 'held guard_collar'),
    box('flares',  'gold_block',          xc(5.6),  (-4.14, -2.8), zc(0.74), 'held shoulder_flare_l/r (wings beside the ricasso; z < blade core so the middle hides in ricasso + core)'),
    # crossguard: stepped dark body, gold caps/trim, gold face plate with dark inset, white rivets
    box('guard_1', 'polished_deepslate', xc(4.6),  (-6.4, -4.48),  zc(1.9),  'held guard_step1'),
    box('guard_2', 'polished_deepslate', xc(7.4),  (-6.24, -4.64), zc(1.6),  'held guard_step2_l/r'),
    box('guard_3', 'polished_deepslate', xc(9.2),  (-6.05, -4.83), zc(1.3),  'held guard_step3_l/r'),
    box('guard_caps', 'gold_block',      xc(9.92), (-6.1, -4.5),   zc(1.2),  'held guard_cap_l/r + trim_top2 (gold line over step 2/3)'),
    box('guard_plate', 'gold_block',     xc(3.4),  (-6.1, -4.78),  zc(2.1),  'held guard_plate_f/b'),
    box('guard_inset', 'polished_blackstone', xc(1.9), (-5.8, -5.08), zc(2.24), 'held guard_inset_f/b'),
    box('guard_rivets', 'smooth_quartz', xc(5.44), (-5.75, -5.31), zc(1.8),  'held guard_rivet_+-1_0 (studs show on step 2 beside step 1)'),
    # grip + pommel
    box('grip',        'dried_kelp_block', xc(1.8), (-11.2, -7.0), zc(1.8), 'held grip_core + grip_wrap_0..5 (kelp side = wrap bands)'),
    box('grip_collar', 'gold_block',       xc(2.0), (-7.0, -6.4),  zc(2.0), 'held grip_collar + guard_trim_bot1'),
    box('pommel',      'polished_blackstone', xc(2.5), (-13.1, -11.6), zc(2.3), 'held pommel_ball'),
    box('pommel_gold', 'gold_block',       xc(2.0), (-13.6, -11.2), zc(2.0), 'held pommel_cap + pommel_collar (middle inside the ball)'),
]

def main():
    blocks = sorted({p['block'] for p in P})
    hdr = [
        '# bastion-greatsword rig parts (sword local frame), delivered by model-artist r22 (generator: tools/model-forge/work/bastion-greatsword/rig_parts_s1.py).',
        f'# {len(P)} parts, {len(blocks)} distinct blocks: {", ".join(blocks)}.',
        '# AXES (rig-local, pivot = grip centre = origin = held model point (8,-10,8)):',
        '#   blade LENGTH axis  = +Y (grip -> tip). pommel end y=-0.225, tip y=2.545; total 2.770 block.',
        '#   EDGE axis          = +-X (cutting edges on +X / -X; blade width 0.24 block incl. rims).',
        '#   FACE NORMAL axis   = +-Z (broad faces; core thickness 0.05).',
        '#   => size is [x = edge-to-edge, y = along the blade, z = thickness]; every value = held model units / 16.',
        '# Symmetric pairs are single parts whose middle hides inside a thicker part (edges, rims, flares, caps, rivets, pommel gold).',
        '# Face detail (fuller, highlights, pinstripes, inlay) passes through the core, so both faces carry it.',
        '# No rotated parts; no part id is referenced elsewhere.',
        '# name | block | size [x,y,z] | offset [x,y,z] | held source',
    ]
    for p in P:
        hdr.append(f"#  {p['id']:12s} {p['block']:20s} {p['size']} {p['offset']}  {p['note']}")
    body = ['parts:'] + [f"  - {{ id: {p['id']}, block: {p['block']}, offset: {p['offset']}, size: {p['size']} }}" for p in P]
    OUT.write_text('\n'.join(hdr+body)+'\n')
    write_rig(body[1:])
    print('\n'.join(hdr[1:2]+body))
    profile_check()
    corner_check()

def corner_check():
    """Mid-chunk drift of each part vs the arc: |corner| * (1 - cos(step/2)), corner = offset - size/2 (block, from the pivot).
    A part whose drift differs from the blade's by more than ~0.02 block at a 50 deg chunk detaches visibly in the client."""
    import math
    k = 1 - math.cos(math.radians(25))
    print('\nmid-chunk drift at a 50 deg chunk (block): part | |corner| | drift')
    for p in P:
        c = [o - s/2 for o, s in zip(p['offset'], p['size'])]
        d = math.sqrt(sum(v*v for v in c))
        print(f"  {p['id']:12s} {d:6.3f}  {d*k:6.3f}{'  <-- far from pivot' if d > 0.6 else ''}")

def write_rig(lines):
    """Replace ONLY the part lines of the sword rig in content/vfx (swing keys / header belong to the animator)."""
    src = RIG.read_text().split('\n')
    i = src.index('  parts:') + 1
    j = i
    while src[j].startswith('  - { id:'):
        j += 1
    RIG.write_text('\n'.join(src[:i] + lines + src[j:]))

# held texture -> rig block (same vanilla texture)
TEX = {'0': 'iron_block', 'material_quartz_block_bottom': 'smooth_quartz', 'material_black_wool': 'dried_kelp_block',
       'material_dried_kelp_side': 'dried_kelp_block', 'material_dried_kelp_top': 'dried_kelp_block'}

def profile_check():
    """Edge-on read check: per 0.04-unit slice along the blade, the OUTERMOST box in +X (what an edge-on viewer sees first)
    in the held model vs the rig: its material and its depth (z). Prints every run where they differ."""
    held = []
    for e in json.loads(HELD.read_text())['elements']:
        if e.get('rotation'):
            continue  # flares / crests: rotated detail, compared by eye
        t = next(iter(e['faces'].values()))['texture'].lstrip('#')
        held.append((e['from'], e['to'], TEX.get(t, t.replace('material_', '')), e['name']))
    rig = []
    for p in P:
        lo = [G[k] + 16*(p['offset'][k] - p['size'][k]/2) for k in range(3)]
        hi = [G[k] + 16*(p['offset'][k] + p['size'][k]/2) for k in range(3)]
        rig.append((lo, hi, p['block'], p['id']))
    def outer(boxes, y):
        hit = [b for b in boxes if b[0][1] <= y < b[1][1] and b[1][0] > 8]
        if not hit:
            return None
        xmax = max(b[1][0] for b in hit)
        top = [b for b in hit if b[1][0] >= xmax - 1e-6]
        b = max(top, key=lambda b: b[1][2] - b[0][2])
        return round(xmax - 8, 2), round(b[1][2] - b[0][2], 2), b[2], b[3]
    runs, prev = [], None
    y = -13.58
    while y < 30.72:
        h, r = outer(held, y), outer(rig, y)
        bad = h and r and (h[2] != r[2] or abs(h[1] - r[1]) > 0.12 or abs(h[0] - r[0]) > 0.3)
        key = (bad, h and h[3], r and r[3])
        if key != prev:
            runs.append([round(y, 2), h, r, bad])
            prev = key
        y += 0.04
    print('\nedge-on outermost (+X) check: y_from | held (half-width, depth, block, element) | rig (...) | MISMATCH')
    for y0, h, r, bad in runs:
        print(f"  {y0:7.2f}  {h}  {r}  {'MISMATCH' if bad else ''}")

if __name__ == '__main__':
    main()
