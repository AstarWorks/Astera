#!/usr/bin/env python3
"""Adapt generated inventory sprites to the existing Model Forge hand/GUI renderer.

Reads display transforms from an already-installed vanilla client jar (preview only).
Extrudes alpha boundaries by the vanilla generated-item thickness of one model unit.
"""
import argparse
import base64
import json
import subprocess
import sys
from pathlib import Path
from zipfile import ZipFile

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tools/model-forge'))
from raster import faces


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--client-jar', required=True, type=Path)
    ap.add_argument('--out', required=True, type=Path)
    ap.add_argument('items', nargs='+')
    args = ap.parse_args()
    with ZipFile(args.client_jar) as archive:
        display = json.loads(archive.read('assets/minecraft/models/item/generated.json'))['display']
    for slot in ['firstperson', 'thirdperson']:
        display[slot+'_lefthand'] = display[slot+'_righthand']
    # Shared context renderer expects every transform member explicitly.
    for d in display.values():
        for k, fallback in [('rotation',[0,0,0]), ('translation',[0,0,0]), ('scale',[1,1,1])]:
            d.setdefault(k, fallback)
    data = {}
    for item in args.items:
        path = ROOT/f'content/textures/generated/items/{item}.png'
        image = Image.open(path).convert('RGBA')
        w,h = image.size
        polygons = []
        def emit(bounds, side, uv):
            points = faces({'from':bounds[:3], 'to':bounds[3:]})[side][0]
            u,v,U,V = uv
            polygons.append({'id':item, 'name':item, 'face':side,
                'p':[[x-8,y-8,z-8] for x,y,z in points],
                'uv':[[u,1-v],[U,1-v],[U,1-V],[u,1-V]], 't':'sprite'})
        emit([0,0,7.5,16,16,8.5], 'south', [0,0,1,1])
        emit([0,0,7.5,16,16,8.5], 'north', [1,0,0,1])
        def opaque(x,y):
            return 0 <= x < w and 0 <= y < h and image.getpixel((x,y))[3] >= 128
        for y in range(h):
            for x in range(w):
                if not opaque(x,y): continue
                for dx,dy,side in [(-1,0,'west'),(1,0,'east'),(0,-1,'up'),(0,1,'down')]:
                    if not opaque(x+dx,y+dy):
                        emit([x*16/w,16-(y+1)*16/h,7.5,(x+1)*16/w,16-y*16/h,8.5],
                             side, [(x+.25)/w,(y+.25)/h,(x+.75)/w,(y+.75)/h])
        data[item] = {'faces':polygons, 'textures':{'sprite':'data:image/png;base64,'+base64.b64encode(path.read_bytes()).decode()},
                      'display':display, 'source':str(path.relative_to(ROOT))}
    args.out.mkdir(parents=True, exist_ok=True)
    source = args.out/'data.json'
    source.write_text(json.dumps(data))
    subprocess.run(['bun', str(ROOT/'tools/model-forge/context-preview.mjs'), str(source), str(args.out)], check=True)
    views = ['gui','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand']
    sheet = Image.new('RGB',(1200, len(args.items)*184+30),'#293441')
    draw = ImageDraw.Draw(sheet)
    draw.text((5,5),'OFFLINE GENERATED ITEM CONTEXTS | shared Model Forge renderer | no live client animation', fill='white')
    for row,item in enumerate(args.items):
        y = row*184+30
        draw.text((4,y), item, fill='white')
        for col,view in enumerate(views):
            with Image.open(args.out/f'{item}-{view}.png') as frame:
                sheet.paste(frame.resize((240,160)), (col*240,y+20))
            draw.text((col*240+4,y+165), view, fill='white')
    sheet.save(args.out/'armor-held-contact-sheet.png')


if __name__ == '__main__':
    main()
