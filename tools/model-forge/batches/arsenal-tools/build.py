"""Arsenal working tools; invoke round 1, inspect, then round 2 and inspect.

Uses the shared Forge history/material pipeline. Evidence stays outside the repo.
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'tools/model-forge'))
from core import Project

TOOLS = [f'{tier}-{kind}' for tier in ('stone', 'iron', 'diamond')
         for kind in ('pickaxe', 'axe', 'shovel')]
ICONS = ['ore-iron', 'ore-gold', 'ore-diamond', 'ore-emerald',
         'reinforced-planks-block', 'stone-bulwark-block', 'obsidian-plate-block',
         'packed-clay-block', 'tempered-glass-block', 'hardened-soil-block', 'wool-block']
DISPLAY = {
    'gui': {'rotation': [15, -25, -40], 'translation': [0, -2, 0], 'scale': [.62]*3},
    'firstperson_righthand': {'rotation': [0, -75, 12], 'translation': [0, 1, -1], 'scale': [.65]*3},
    'firstperson_lefthand': {'rotation': [0, -75, 12], 'translation': [0, 1, -1], 'scale': [.65]*3},
    'thirdperson_righthand': {'rotation': [0, 90, 0], 'translation': [0, 3, 0], 'scale': [.6]*3},
    'thirdperson_lefthand': {'rotation': [0, 90, 0], 'translation': [0, 3, 0], 'scale': [.6]*3},
    'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [.3]*3},
    'fixed': {'rotation': [0, 180, 0], 'translation': [0, -2, 0], 'scale': [.65]*3},
}


def install_icons():
    # Keep raster generation in item-art; only author display transforms here.
    display = {
        'firstperson_righthand': {'rotation': [0,-45,0], 'translation': [-3,3,-3], 'scale': [.4]*3},
        'firstperson_lefthand': {'rotation': [0,-45,0], 'translation': [-3,3,-3], 'scale': [.4]*3},
        'thirdperson_righthand': {'rotation': [0,0,0], 'translation': [0,3,1], 'scale': [.4]*3},
        'thirdperson_lefthand': {'rotation': [0,0,0], 'translation': [0,3,1], 'scale': [.4]*3},
    }
    directory = ROOT/'content/models/items/arsenal'
    directory.mkdir(exist_ok=True)
    for item in ICONS:
        (directory/f'{item}.model.json').write_text(json.dumps({
            'parent': 'minecraft:item/generated',
            'textures': {'layer0': f'astera:generated/items/{item}'},
            'display': display,
        }, indent=2)+'\n')


def parts(kind, detailed):
    result = []

    def box(name, lo, hi, material='head'):
        result.append((name, lo, hi, material))

    box('oak shaft', [7.2, 0, 7.2], [8.8, 17, 8.8], 'wood')
    box('leather grip', [7, 1, 7], [9, 6, 9], 'grip')
    box('brass ferrule', [6.8, 14, 6.8], [9.2, 15, 9.2], 'brass')
    if kind == 'pickaxe':
        box('pick socket', [6, 15, 6.5], [10, 18.5, 9.5])
        for side in (-1, 1):
            for i in range(4 if detailed else 2):
                x = 8 + side*(3 + i*1.5)
                box(f'pick wing {side} {i}', [x-.85, 16-i*.65, 7+i*.12],
                    [x+.85, 18-i*.9, 9-i*.12])
    elif kind == 'axe':
        box('axe socket', [6.5, 13, 6.5], [10, 19, 9.5])
        box('rear poll', [4.5, 15, 7], [6.5, 18, 9])
        box('blade core', [10, 12.5, 7], [13, 19.5, 9])
        box('cutting bevel', [13, 12, 7.4], [14.5, 19, 8.6])
        if detailed:
            box('blade toe', [11, 11.5, 7.2], [13, 12.5, 8.8])
            box('blade crown', [10, 19.5, 7.25], [12.5, 20, 8.75])
    else:
        box('spade neck', [7, 14, 7], [9, 17, 9])
        box('spade shoulders', [4.5, 17, 7.3], [11.5, 21, 8.7])
        box('spade cutting edge', [5.5, 21, 7.55], [10.5, 22.5, 8.45])
        if detailed:
            box('raised spine', [7.4, 17, 8.7], [8.6, 20.5, 9.2])
            for x in (4.5, 10.8):
                box(f'spade shoulder lip {x}', [x, 16.5, 7], [x+.7, 17, 9])
    if detailed:
        box('brass heel', [6.9, 0, 6.9], [9.1, 1, 9.1], 'brass')
        for y in (1.5, 3, 4.5):
            box(f'grip binding {y}', [6.9, y, 6.9], [9.1, y+.35, 9.1], 'binding')
        # Small exposed fasteners, outside the socket; each has real depth.
        for z in ((6.7, 9) if kind == 'shovel' else (6.2, 9.5)):
            box(f'head rivet {z}', [7.5, 16, z], [8.5, 17, z+.3], 'brass')
    return result


def build(item, round_number, out):
    project = Project(out/item)
    operations = []

    def call(op, args=None):
        response = project.call(op, args or {})
        operations.append({'op': op, 'args': args or {}, 'result': response})
        return response

    tier, kind = item.split('-')
    sheet = ROOT/'content/textures/sources/sheets/arsenal-tools-v1.png'
    if round_number == 1:
        if not sheet.exists():
            raise SystemExit('Generate and inspect the locked concept sheet first')
        source = out/f'{item}-seed.json'
        source.write_text(json.dumps({'textures': {'0': 'minecraft:block/iron_block'},
                                     'elements': [], 'display': DISPLAY}))
        call('import', {'path': str(source)})
        (project.path/'concept.json').write_text(json.dumps({
            'source': str(sheet.relative_to(ROOT)), 'sha256': hashlib.sha256(sheet.read_bytes()).hexdigest(),
            'brief': f'{tier} {kind}: dark oak shaft, leather grip, brass collar; tier material on head. '
                     'Preserve the generated sheet category silhouette and palette. '
                     'Simple working tools rather than ornamented hero weapons.',
            'head_width': {'pickaxe': 16, 'axe': 10, 'shovel': 7}[kind],
            'head_depth': 3, 'shaft_length': 17, 'grip_y': [1, 6],
        }, indent=2))
    call('scene.get')
    call('measure')
    call('validate')
    call('history.snapshot', {'name': f'round-{round_number}-before'})
    if round_number == 5:
        # Java mirrors the left-hand Y/Z angles itself; do not mirror twice.
        model = project.vanilla()
        model['display'] = DISPLAY
        source = project.path/'display-polish.json'
        source.write_text(json.dumps(model))
        call('import', {'path': str(source)})
        finish(project, item, round_number, call, operations)
        return
    if round_number >= 3:
        # Round-two top/side review found the shovel pins separated from its narrower neck.
        ops = []
        for e in project.model['elements']:
            if round_number == 3 and e['name'].startswith('head rivet') and kind == 'shovel':
                z = 6.7 if e['from'][2] < 8 else 9
                ops.append({'op': 'element.update', 'args': {'id': e['id'], 'changes': {
                    'from': [7.5, 16, z], 'to': [8.5, 17, z+.3]}}})
            if round_number == 4:
                remove = []
                if e['name'] == 'oak shaft' and e['from'][1] == 0:
                    remove.append('down')  # Fully covered by the opaque brass heel.
                if kind == 'shovel':
                    if e['name'] == 'oak shaft' and e['to'][1] == 17:
                        remove.append('up')  # Fully inside the spade neck.
                    if e['name'] == 'spade neck':
                        remove.append('down')  # Fully covered by the ferrule.
                if remove:
                    ops.append({'op': 'element.update', 'args': {'id': e['id'], 'changes': {
                        'faces': {f: v for f, v in e['faces'].items() if f not in remove}}}})
        if ops:
            call('batch', {'ops': ops})
        finish(project, item, round_number, call, operations)
        return
    ops = [{'op': 'element.delete', 'args': {'id': e['id']}}
           for e in project.model['elements']]
    groups = {}
    for n, (name, lo, hi, material) in enumerate(parts(kind, round_number >= 2)):
        eid = f'part_{n:03}'
        groups.setdefault(material, []).append(eid)
        element = {'name': name, 'from': lo, 'to': hi,
                   'faces': {f: {'texture': '#0', 'uv': [0, 0, 1, 1]}
                             for f in ('north', 'south', 'east', 'west', 'up', 'down')}}
        ops.append({'op': 'element.add', 'args': {'id': eid, 'element': element}})
    materials = {'wood': 'wood.stripped_dark_oak_log', 'grip': 'cloth.brown_wool',
                 'binding': 'wood.dark_oak_planks', 'brass': 'metal.gold_block',
                 'head': {'stone': 'stone.smooth_stone', 'iron': 'metal.iron_block',
                          'diamond': 'gem.diamond_block'}[tier]}
    for group, ids in groups.items():
        ops.append({'op': 'material.apply', 'args': {'ids': ids, 'material': materials[group], 'seed': 28}})
    call('batch', {'ops': ops})
    finish(project, item, round_number, call, operations)


def finish(project, item, round_number, call, operations):
    call('history.diff', {'a': f'round-{round_number}-before', 'b': 'current'})
    call('measure')
    call('validate')
    call('board', {'name': f'round-{round_number}', 'resolution': 128})
    call('board', {'name': f'contexts-{round_number}', 'resolution': 128,
                   'views': ['gui', 'firstperson_righthand', 'thirdperson_righthand', 'ground', 'fixed']})
    call('export', {'path': str(project.path/'export')})
    target = ROOT/f'content/models/items/{item}.model.json'
    target.write_bytes((project.path/'export/model.json').read_bytes())
    (project.path/f'operations-{round_number}.json').write_text(json.dumps(operations, indent=2))
    print(item, len(project.model['elements']), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('round', type=int, choices=(1, 2, 3, 4, 5))
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    for item in TOOLS:
        build(item, args.round, args.out)
    install_icons()
