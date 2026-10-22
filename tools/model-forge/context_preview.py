#!/usr/bin/env python3
"""Render shipped models in offline hand, dropped-item and item-frame contexts."""
import argparse
import base64
import io
import json
import subprocess
from pathlib import Path
from PIL import Image, ImageDraw
from raster import faces, element_points, default_uv
from recipe import ROOT, load_model, texture_map

VIEWS = ['firstperson_righthand', 'firstperson_lefthand', 'thirdperson_righthand',
         'thirdperson_lefthand', 'thirdperson_righthand_side', 'ground', 'ground_spin', 'fixed', 'gui']

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', required=True, type=Path)
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
        for element in model['elements']:
            for face, (points, _) in faces(element).items():
                if face not in element['faces']:
                    continue
                f = element['faces'][face]
                u, v, U, V = f.get('uv', default_uv(element, face))
                uv = [[u, 16-v], [U, 16-v], [U, 16-V], [u, 16-V]]
                shift = f.get('rotation', 0)//90
                if shift:
                    uv = uv[-shift:]+uv[:-shift]
                polygons.append({'p': (element_points(element, points)-8).tolist(),
                                 'uv': [[a/16, b/16] for a, b in uv], 't': f['texture'][1:]})
        textures = {}
        for key, pixels in texture_map(model).items():
            buffer = io.BytesIO()
            Image.fromarray(pixels).save(buffer, format='PNG')
            textures[key[1:]] = 'data:image/png;base64,'+base64.b64encode(buffer.getvalue()).decode()
        items[name] = {'faces': polygons, 'textures': textures, 'display': model['display']}
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    data = out/'data.json'
    data.write_text(json.dumps(items))
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
