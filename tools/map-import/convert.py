#!/usr/bin/env python3
"""Convert a legacy (pre-1.13 Anvil) map world into `content/arenas/<id>.yaml` (ADR-0281).

usage: python3 -I tools/map-import/convert.py <maps/config.json> <world-dir> <out.yaml>

The world directory is untrusted data: it is only parsed (NBT), never executed. The config carries the arena id, the
column whose floor block must land on surface_y, the stray-component cut-off, optional `fill` boxes (bridges and plinths
added by hand, original coordinates) and the licence/authorship credits.
Every produced block-data string is checked against tools/lobby-gen's block registry (the one the preview and the
server guard use); unknown ids or properties abort the conversion.
"""
import collections
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import anvil  # noqa: E402
import legacy  # noqa: E402

REGISTRY = os.path.join(HERE, '..', 'lobby-gen', 'src', 'main', 'resources', 'block-registry.txt')
SURFACE_Y = 64


def load_registry():
    reg = {}
    for line in open(REGISTRY, encoding='utf-8'):
        if line.strip():
            block_id, props = line.rstrip('\n').split('\t', 1) if '\t' in line else (line.strip(), '')
            reg[block_id] = {}
            for part in props.split(','):
                if '=' in part:
                    k, v = part.split('=', 1)
                    reg[block_id][k] = set(v.split('|'))
    return reg


def validate(state, reg):
    block_id = state.split('[')[0]
    if block_id not in reg:
        raise SystemExit('block id not in registry: ' + state)
    if '[' in state:
        for pair in state[state.index('[') + 1:-1].split(','):
            k, v = pair.split('=')
            if k not in reg[block_id] or v not in reg[block_id][k]:
                raise SystemExit('property not in registry: ' + state)


def components(cells):
    """26-connected components of the occupied cells (a floating prop is its own component)."""
    seen = set()
    sizes = []
    comp_of = {}
    for start in cells:
        if start in seen:
            continue
        stack = [start]
        seen.add(start)
        members = []
        while stack:
            x, y, z = stack.pop()
            members.append((x, y, z))
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for dz in (-1, 0, 1):
                        n = (x + dx, y + dy, z + dz)
                        if n in cells and n not in seen:
                            seen.add(n)
                            stack.append(n)
        sizes.append(len(members))
        for m in members:
            comp_of[m] = len(sizes) - 1
    return sizes, comp_of


def q(text):
    return json.dumps(text, ensure_ascii=False)


def main():
    cfg = json.load(open(sys.argv[1], encoding='utf-8'))
    raw, _tiles = anvil.legacy_blocks(sys.argv[2])
    states = legacy.convert(raw)
    # Hand-authored additions in the world's original coordinates (bridges over void gaps, plinths for star pedestals).
    for fill in cfg.get('fill', []):
        x0, y0, z0, x1, y1, z1 = fill['box']
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    if fill.get('only_air', True) and (x, y, z) in states:
                        continue
                    states[(x, y, z)] = fill['block']
    fx, fz, fy = cfg['floor']['x'], cfg['floor']['z'], cfg['floor']['y']
    dy = SURFACE_Y - fy
    shifted = {(x, y + dy, z): s for (x, y, z), s in states.items()}
    sizes, comp_of = components(shifted.keys())
    keep_min = cfg.get('min_component', 0)
    dropped = [(i, n) for i, n in enumerate(sizes) if n < keep_min]
    print('components', sorted(sizes, reverse=True)[:12], 'dropping', len(dropped), 'of', len(sizes))
    drop_ids = {i for i, _ in dropped}
    for box in cfg.get('crop_out', []):
        for pos in list(shifted):
            if box[0] <= pos[0] <= box[3] and box[1] <= pos[1] <= box[4] and box[2] <= pos[2] <= box[5]:
                del shifted[pos]
    blocks = {p: s for p, s in shifted.items() if comp_of.get(p) not in drop_ids}
    if not blocks:
        raise SystemExit('no blocks left')
    reg = load_registry()
    counts = collections.Counter(blocks.values())
    for state in counts:
        validate(state, reg)
    palette = [s for s, _ in counts.most_common()]
    index = {s: i + 1 for i, s in enumerate(palette)}
    xs = [p[0] for p in blocks]; ys = [p[1] for p in blocks]; zs = [p[2] for p in blocks]
    x0, x1, y0, y1, z0, z1 = min(xs), max(xs), min(ys), max(ys), min(zs), max(zs)
    sx, sy, sz = x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1
    layers = []
    for y in range(y0, y1 + 1):
        runs = []
        last, n = None, 0
        for z in range(z0, z1 + 1):
            for x in range(x0, x1 + 1):
                t = index.get(blocks.get((x, y, z)), 0) if (x, y, z) in blocks else 0
                if t == last:
                    n += 1
                else:
                    if last is not None:
                        runs.append('%d:%d' % (last, n))
                    last, n = t, 1
        runs.append('%d:%d' % (last, n))
        layers.append(' '.join(runs))
    c = cfg['credits']
    out = ['# Derived from "%s" (%s). %s. Modified: %s' % (c['title'], c['source'], c['license'], c['modified']),
           '# Generated by tools/map-import/convert.py; do not edit by hand. See content/stages/NOTICE.md.',
           'id: ' + cfg['id'],
           'credits:',
           '  title: ' + q(c['title']),
           '  authors: [' + ', '.join(q(a) for a in c['authors']) + ']',
           '  source: ' + q(c['source']),
           '  license: ' + q(c['license']),
           '  modified: ' + q(c['modified']),
           'origin: [%d, %d, %d]' % (x0, y0, z0),
           'size: [%d, %d, %d]' % (sx, sy, sz),
           'palette:'] + ['  - ' + q(s) for s in palette] + ['layers:'] + ['  - ' + q(l) for l in layers]
    with open(sys.argv[3], 'w', encoding='utf-8') as f:
        f.write('\n'.join(out) + '\n')
    print('blocks', len(blocks), 'palette', len(palette), 'x', x0, x1, 'y', y0, y1, 'z', z0, z1, 'shift_y', dy)


if __name__ == '__main__':
    main()
