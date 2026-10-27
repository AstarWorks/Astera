#!/usr/bin/env python3
"""Offline vanilla player armor review using Model Forge's existing depth/UV rasterizer.

Uses the Java armor cube nets, outer dilation 1 and leggings dilation .5.
A small walking pose (limbs +/-3 degrees) exposes the boot/leggings seams.
Does not establish live-client pack loading or armor animation behavior.
"""
import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tools/model-forge'))
from raster import render

SETS = ['leather-scout', 'chain-guard', 'iron-warden', 'diamond-champion']


def cube(name, bounds, uv, size, texture, dilation, mirror=False):
    u, v = uv
    w, h, d = size
    nets = {'east': (u, v+d, u+d, v+d+h),
            'south': (u+d, v+d, u+d+w, v+d+h),
            'west': (u+d+w, v+d, u+2*d+w, v+d+h),
            'north': (u+2*d+w, v+d, u+2*d+2*w, v+d+h),
            'up': (u+d, v, u+d+w, v+d),
            'down': (u+d+w, v, u+d+2*w, v+d)}
    fs = {}
    for face, (a,b,c,e) in nets.items():
        # Forge model UVs use 0..16 regardless of the atlas dimensions (64x32).
        fs[face] = {'texture': texture, 'uv': [a/4,b/2,c/4,e/2]}
    if mirror:
        fs['east'], fs['west'] = fs['west'], fs['east']
        for f in fs.values():
            a,b,c,e = f['uv']; f['uv'] = [c,b,a,e]
    return {'id': name, 'from': [n-dilation for n in bounds[:3]],
            'to': [n+dilation for n in bounds[3:]], 'faces': fs}


def player():
    elements = []
    # Java armor model: helmet/chest/boots at dilation 1, leggings at .5.
    elements.append(cube('helmet', [-4,24,-4,4,32,4], (0,0), (8,8,8), '#outer', 1))
    for tex, dilation in [('#inner', .5), ('#outer', 1)]:
        elements.append(cube(tex+'body', [-4,12,-2,4,24,2], (16,16), (8,12,4), tex, dilation))
        for x, mirrored in [(-4, False), (0, True)]:
            leg = cube(tex+str(x)+'leg', [x,0,-2,x+4,12,2], (0,16), (4,12,4), tex, dilation, mirrored)
            leg['rotation'] = {'axis':'x', 'angle':3 if x < 0 else -3, 'origin':[x+2,12,0]}
            elements.append(leg)
    for x, mirrored in [(-8, False), (4, True)]:
        arm = cube(str(x)+'arm', [x,12,-2,x+4,24,2], (40,16), (4,12,4), '#outer', 1, mirrored)
        arm['rotation'] = {'axis':'x', 'angle':-3 if x < 0 else 3, 'origin':[x+1 if x < 0 else x+3,22,0]}
        elements.append(arm)
    return {'elements': elements}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out', required=True, type=Path)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    views = [('front',0), ('back',180), ('side',90), ('oblique',-30)]
    board = Image.new('RGB', (1536, 4*370+48), '#202935')
    draw = ImageDraw.Draw(board)
    draw.text((12,12), 'OFFLINE JAVA ARMOR UV REVIEW | outer=1, leggings=.5 | front / back / side / oblique | inventory 32px x3', fill='white')
    for row, name in enumerate(SETS):
        textures = {key: np.array(Image.open(ROOT/f'content/textures/equipment/{layer}/{name}.png').convert('RGBA'))
                    for key, layer in [('#outer','humanoid'),('#inner','humanoid_leggings')]}
        y = 48+row*370
        draw.text((12,y), name, fill='white')
        for col, (view,yaw) in enumerate(views):
            frame, _, _ = render(player(), textures, 'front', size=320,
                                 camera={'pitch': 0 if view != 'oblique' else 12, 'yaw': yaw, 'center':[0,16,0], 'ortho':38})
            frame.save(args.out/f'{name}-{view}.png')
            board.paste(frame, (col*320,y+24), frame)
            draw.text((col*320+12,y+340), view, fill='white')
        for i,piece in enumerate(['helmet','chestplate','leggings','boots']):
            icon = Image.open(ROOT/f'content/textures/generated/items/{name}-{piece}.png').convert('RGBA')
            icon = icon.resize((96,96), Image.Resampling.NEAREST)
            x = 1280+(i%2)*120; iy = y+35+(i//2)*145
            board.paste(icon, (x,iy), icon)
            draw.text((x,iy+100), piece, fill='white')
    board.save(args.out/'armor-contact-sheet.png')
    print(args.out/'armor-contact-sheet.png')


if __name__ == '__main__':
    main()
