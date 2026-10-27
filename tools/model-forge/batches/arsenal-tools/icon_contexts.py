"""Feed generated sprite items into the shared context viewer (front/back cutout planes).

Use after context_preview.py has captured the nine tools. This preview omits the
vanilla generated sprite's subpixel edge extrusion; it does not author a model.
"""
import argparse
import base64
import json
import os
import subprocess
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[4]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('contexts', type=Path)
    args = parser.parse_args()
    data = json.loads((args.contexts/'data.json').read_text())
    with ZipFile(os.environ['MODEL_FORGE_CLIENT_JAR']) as jar:
        display = json.loads(jar.read('assets/minecraft/models/item/generated.json'))['display']
    display['firstperson_lefthand'] = {
        **display['firstperson_righthand'], 'rotation': [0, 90, -25]}
    display['thirdperson_lefthand'] = display['thirdperson_righthand']
    display['gui'] = {}
    display = {key: {'rotation': [0,0,0], 'translation': [0,0,0], 'scale': [1,1,1], **value}
               for key, value in display.items()}
    for path in sorted((ROOT/'content/items').glob('*.yaml')):
        item = path.stem
        if item.startswith('ore-') or item in {
            'reinforced-planks-block', 'stone-bulwark-block', 'obsidian-plate-block',
            'packed-clay-block', 'tempered-glass-block', 'hardened-soil-block', 'wool-block',
        }:
            texture = ROOT/f'content/textures/generated/items/{item}.png'
            authored = json.loads((ROOT/f'content/models/items/arsenal/{item}.model.json').read_text())
            polygons = []
            for face, z, points in (
                ('south', .5, [[-8,8], [8,8], [8,-8], [-8,-8]]),
                ('north', -.5, [[8,8], [-8,8], [-8,-8], [8,-8]]),
            ):
                polygons.append({'id': 'sprite', 'name': 'generated cutout', 'face': face,
                                 'p': [[x,y,z] for x,y in points],
                                 'uv': [[0,1], [1,1], [1,0], [0,0]], 't': 'sprite'})
            data[item] = {'faces': polygons, 'textures': {
                'sprite': 'data:image/png;base64,'+base64.b64encode(texture.read_bytes()).decode()},
                'display': {**display, **authored['display']}}
    target = args.contexts/'with-icons.json'
    target.write_text(json.dumps(data))
    subprocess.run(['bun', str(ROOT/'tools/model-forge/context-preview.mjs'),
                    str(target), str(args.contexts)], check=True)


if __name__ == '__main__':
    main()
