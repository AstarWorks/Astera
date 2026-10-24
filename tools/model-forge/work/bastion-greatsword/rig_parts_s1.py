#!/usr/bin/env python3
"""Writes deliver/rig-parts.yaml for the S1 rig from the held model's coordinates (model units, grip (8,-10,8)).

Round r11 (director r10: end overlap showed two swords; rig guard = plain yellow slab, reverse blade face plain).
22 parts (hard cap: rig 22 + fx core 9 + particles 29 = 60 per cast at hit 2). Tricks that keep the count down:
  * one full-width part per symmetric pair whose middle hides inside a thicker part (edges, rims, rivets, caps);
  * every face-detail part goes through the blade (z > core), so BOTH faces show the fuller / inlay;
  * nested parts differ by >= 0.006 block per side (no z-fight at distance).
Usage: rig_parts_s1.py  (writes the yaml; prints the table)"""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[4]
OUT = ROOT/'tools/skill-compare/data/bastion-greatsword-s1/deliver/rig-parts.yaml'
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
P = [
    # blade (core z 0.8 units = 0.05 block)
    box('blade_face', 'iron_block',          xc(2.7),   BLADE_Y, zc(0.8),  'held blade_core'),
    box('edges',      'smooth_quartz',       xc(3.4),   BLADE_Y, zc(0.68), 'held bevel_hi_l/r (middle inside the core)'),
    box('rims',       'polished_blackstone', xc(3.84),  BLADE_Y, zc(0.48), 'held bevel_rim_l/r (middle inside the edges)'),
    box('fuller',     'polished_deepslate',  xc(0.44),  (1.6, 24.4), zc(1.06), 'held fuller_ridge/start/end_f/b (both faces; hi strips + pinstripes cut for the 22-part cap)'),
    # tip (5 held steps -> 2)
    box('tip_1', 'iron_block',          xc(2.8),  (25.12, 27.6), zc(0.8),  'held tip0 + tip1 + tip2 (mean rimmed width)'),
    box('tip_2', 'polished_blackstone', xc(1.2),  (27.6, 30.72), zc(0.48), 'held tip3 + tip_point'),
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
        '# bastion-greatsword rig parts (sword local frame), delivered by model-artist r11 (generator: tools/model-forge/work/bastion-greatsword/rig_parts_s1.py).',
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
    print('\n'.join(hdr[1:2]+body))

if __name__ == '__main__':
    main()
