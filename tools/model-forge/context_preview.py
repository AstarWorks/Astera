#!/usr/bin/env python3
"""Render shipped models in offline hand, dropped-item and item-frame contexts."""
import argparse
import base64
import io
import hashlib
import json
import subprocess
from pathlib import Path
from PIL import Image, ImageDraw
from raster import faces, element_points, default_uv
from recipe import ROOT, load_model, texture_map
from surfaces import surface_pairs
import numpy as np

VIEWS = ['firstperson_righthand', 'firstperson_lefthand', 'thirdperson_righthand',
         'thirdperson_lefthand', 'thirdperson_righthand_side', 'ground', 'ground_spin', 'fixed', 'gui']

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--viewer', action='store_true', help='Build a standalone interactive HTML instead of fixed boards')
    parser.add_argument('items', nargs='*', help='Item IDs; default: all authored cuboid items')
    args = parser.parse_args()
    paths = sorted((ROOT/'content/models/items').glob('*.model.json'))
    available = {p.stem.removesuffix('.model'): p for p in paths}
    if set(args.items)-available.keys():
        parser.error('Unknown item ID(s): '+', '.join(sorted(set(args.items)-available.keys())))
    items = {}
    for name, path in available.items():
        if args.items and name not in args.items:
            continue
        model = load_model(path)
        polygons = []
        source_textures = texture_map(model)
        textures = {}
        for i, element in enumerate(model['elements']):
            for face, (points, _) in faces(element).items():
                if face not in element['faces']:
                    continue
                f = element['faces'][face]
                u, v, U, V = f.get('uv', default_uv(element, face))
                uv = [[u, 16-v], [U, 16-v], [U, 16-V], [u, 16-V]]
                shift = f.get('rotation', 0)//90
                if shift:
                    uv = uv[-shift:]+uv[:-shift]
                index = f.get('tintindex', -1)
                rgb = model['astera_tints'][index] if index >= 0 else 0xffffff
                key = f['texture'][1:] + '_' + format(rgb, '06x')
                if key not in textures:
                    pixels = source_textures[f['texture']].copy()
                    pixels[:,:,:3] = np.rint(pixels[:,:,:3] * np.array([rgb>>16, (rgb>>8)&255, rgb&255]) / 255).astype(np.uint8)
                    buffer = io.BytesIO()
                    Image.fromarray(pixels).save(buffer, format='PNG')
                    textures[key] = 'data:image/png;base64,'+base64.b64encode(buffer.getvalue()).decode()
                polygons.append({'id': f'e{i+1:04d}', 'name': element.get('name', f'element {i+1}'), 'face': face, 'p': (element_points(element, points)-8).tolist(),
                                 'uv': [[a/16, b/16] for a, b in uv], 't': key})
        items[name] = {'faces': polygons, 'textures': textures, 'display': model['display'],
                       'source': str(path.relative_to(ROOT)),
                       'sha256': hashlib.sha256(json.dumps({'model': model, 'textures': textures}, sort_keys=True).encode()).hexdigest(),
                       'pairs': surface_pairs(model) if args.viewer else []}
        print(f'{name}: {len(model["elements"])} elements', flush=True)
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    data = out/'data.json'
    data.write_text(json.dumps(items))
    if args.viewer:
        subprocess.run(['bun', str(Path(__file__).with_name('build-reviewer.mjs')), str(data), str(out/'viewer.html')], check=True)
        print(out/'viewer.html')
        return
    subprocess.run(['bun', str(Path(__file__).with_name('context-preview.mjs')), str(data), str(out)], check=True)
    for name in items:
        board = Image.new('RGB', (960, 696), '#293441')
        draw = ImageDraw.Draw(board)
        for i, view in enumerate(VIEWS):
            x, y = i%3*320, i//3*232
            draw.text((x+3, y+3), view, fill='white')
            with Image.open(out/f'{name}-{view}.png') as im:
                board.paste(im.resize((320, 213)), (x, y+19))
        board.save(out/f'{name}-board.jpg')

if __name__ == '__main__':
    main()
