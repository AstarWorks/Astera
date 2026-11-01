#!/usr/bin/env python3
"""Read a stopped map-shot snapshot; compare every saved slot cell to the shipped import.

Usage: python3 tools/map-gen/scan-world.py CAPTURE_DIR
No server connection or world writes. Unallocated chunks are implicit air in the
boot-loaded void preset. Production Ready also verifies the full permitted slot.
"""
import array
import collections
import csv
import importlib.util
import json
from pathlib import Path
import sys
import struct
import time

import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('anvil', ROOT / 'tools/map-import/anvil.py')
anvil = importlib.util.module_from_spec(spec)
spec.loader.exec_module(anvil)


def state(value):
    if isinstance(value, str):
        name, _, props = value.partition('[')
        return name, dict(p.split('=') for p in props.rstrip(']').split(',') if p)
    return (value.get('id', value.get('Name', value.get(''))),
            value.get('properties', value.get('Properties', {})))


def main():
    started = time.monotonic()
    dest = Path(sys.argv[1])
    placement = dict(line.split('=', 1) for line in (dest / 'placement.txt').read_text().splitlines())
    ox, oz = int(placement['origin_x']), int(placement['origin_z'])
    stage = yaml.safe_load((ROOT / 'content/stages' / (placement['stage'] + '.yaml')).read_text())
    data = yaml.safe_load((ROOT / 'content/arenas' / (stage['layout']['import']['map'] + '.yaml')).read_text())
    sx, sy, sz = data['size']
    ix, iy, iz = data['origin']
    palette = [('minecraft:air', {})] + [state(p) for p in data['palette']]
    cells = []
    for layer in data['layers']:
        row = array.array('H')
        for run in layer.split():
            token, count = map(int, run.split(':'))
            row.extend(array.array('H', [token]) * count)
        assert len(row) == sx * sz
        cells.append(row)
    # Production ArenaIslandGenerator overlays iron pedestals at layout landmarks.
    if ('minecraft:iron_block', {}) not in palette:
        palette.append(('minecraft:iron_block', {}))
    iron = palette.index(('minecraft:iron_block', {}))
    for mark in stage['layout']['landmarks']:
        cells[stage['layout']['surface_y'] - iy][(mark['z'] - iz) * sx + mark['x'] - ix] = iron
    expected_count = sum(sum(v != 0 for v in row) for row in cells)
    counts, foreign = collections.Counter(), collections.Counter()
    seen_expected = mismatches = chunks = sections = 0
    unexpected_positions = outside_palette = material_mismatches = 0
    palette_names = {p[0] for p in palette}
    # Defaults represented by a bare name in modern compact palettes. Explicit
    # properties in a compound take precedence. Unknown defaults fail closed.
    defaults = dict(axis='y', facing='north', half='bottom', shape='straight',
                    type='bottom', level='0', up='true', east='none', west='none',
                    north='none', south='none', open='false', lit='false',
                    hanging='false', rotation='0', persistent='false',
                    hinge='left', signal_fire='false', age='0', moisture='0',
                    attachment='floor')
    samples = []
    files = sorted((dest / 'world/dimensions/minecraft/overworld/region').glob('*.mca'))
    if not files:
        raise ValueError('snapshot has no region files')
    with (dest / 'mismatches.csv').open('w') as output:
        writer = csv.writer(output)
        writer.writerow(['x', 'y', 'z', 'expected', 'actual'])
        for path in files:
            raw = path.read_bytes()
            for i in range(1024):
                location = struct.unpack_from('>I', raw, i * 4)[0]
                if location:
                    compression = raw[(location >> 8) * 4096 + 4]
                    if compression not in (1, 2):
                        raise ValueError(f'{path}: unsupported chunk compression {compression}')
            for chunk in anvil.chunks(path):
                cx, cz = chunk['xPos'] * 16, chunk['zPos'] * 16
                if cx > ox + 512 or cx + 15 < ox - 512 or cz > oz + 512 or cz + 15 < oz - 512:
                    continue
                chunks += 1
                for section in chunk.get('sections', []):
                    blocks = section.get('block_states', {})
                    pal = [state(p) for p in blocks.get('palette', [])]
                    if not pal or all(p[0] in ('minecraft:air', 'minecraft:cave_air', 'minecraft:void_air') for p in pal):
                        continue
                    sections += 1
                    yy = section['Y']
                    if yy > 127:
                        yy -= 256
                    bits = max(4, (len(pal) - 1).bit_length())
                    per, mask = 64 // bits, (1 << bits) - 1
                    packed = blocks.get('data')
                    for i in range(4096):
                        actual = pal[(packed[i // per] >> ((i % per) * bits)) & mask] if packed else pal[0]
                        if actual[0] in ('minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'):
                            continue
                        x, y, z = cx + (i & 15), yy * 16 + (i >> 8), cz + ((i >> 4) & 15)
                        if not (ox - 512 <= x <= ox + 512 and oz - 512 <= z <= oz + 512):
                            continue
                        counts[actual[0]] += 1
                        dx, dy, dz = x - ox - ix, y - iy, z - oz - iz
                        token = cells[dy][dz * sx + dx] & 32767 if 0 <= dx < sx and 0 <= dy < sy and 0 <= dz < sz else 0
                        expected = palette[token]
                        if token:
                            seen_expected += 1
                            cells[dy][dz * sx + dx] |= 32768
                        else:
                            unexpected_positions += 1
                        outside_palette += actual[0] not in palette_names
                        default_half = 'lower' if actual[0].endswith('_door') else defaults['half']
                        block_defaults = dict(defaults, half=default_half)
                        if actual[0].endswith(':campfire'):
                            block_defaults['lit'] = 'true'
                        # Defaults confirmed from the shipped 26.3 client constructors.
                        if actual[0].endswith('_banner'):
                            block_defaults['rotation'] = '8'
                        if actual[0] == 'minecraft:rail':
                            block_defaults['shape'] = 'north_south'
                        match = actual[0] == expected[0] and all(
                            actual[1].get(k, block_defaults.get(k)) == v
                            for k, v in expected[1].items())
                        if not match:
                            mismatches += 1
                            material_mismatches += actual[0] != expected[0]
                            foreign[actual[0]] += 1
                            writer.writerow([x, y, z, json.dumps(expected), json.dumps(actual)])
                            if len(samples) < 30:
                                samples.append(f'- ({x},{y},{z}): {expected} -> {actual}')
        for dy, row in enumerate(cells):
            for i, token in enumerate(row):
                if token and not token & 32768:
                    x, y, z = ox + ix + i % sx, iy + dy, oz + iz + i // sx
                    mismatches += 1
                    foreign['minecraft:air'] += 1
                    writer.writerow([x, y, z, json.dumps(palette[token]), 'minecraft:air'])
                    if len(samples) < 30:
                        samples.append(f'- ({x},{y},{z}): {palette[token]} -> minecraft:air')
    missing = expected_count - seen_expected
    elapsed = time.monotonic() - started
    report = [f'# Highmarch capture-world scan', '',
              f'foreign = {mismatches}; missing = {missing}', '',
              f'Slot {placement["slot"]}, origin ({ox},{oz}); x/z +/-512 inclusive; full overworld height -64..319.',
              f'{len(files)} region files, {chunks} saved chunks, {sections} nonempty sections; unallocated void chunks = air.',
              f'Expected {expected_count:,}; actual {sum(counts.values()):,}; elapsed {elapsed:.3f} s.',
              f'Outside import palette = {outside_palette}; unexpected occupied positions = {unexpected_positions}; '
              f'material mismatches = {material_mismatches}; state-only mismatches = {mismatches - missing - material_mismatches}.',
              'Read-only stopped-server snapshot. Exact material/position and explicitly authored state properties; omitted properties use vanilla defaults.',
              'Unspecified block-state properties follow the production BlockData.matches contract.', '',
              '## Foreign counts', '', *[f'- {k}: {v}' for k, v in foreign.most_common()],
              '', '## Coordinates (first 30; all in mismatches.csv)', '', *samples,
              '', '## Every non-air block type', '', '| Block | Count |', '|---|---:|',
              *[f'| {k} | {v} |' for k, v in counts.most_common()]]
    (dest / 'scan.md').write_text('\n'.join(report) + '\n')
    print(report[2], f'({elapsed:.3f}s)')
    return int(bool(mismatches or missing))


if __name__ == '__main__':
    sys.exit(main())
