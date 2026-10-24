#!/usr/bin/env python3
"""Render held item vs. S1 rig sword (final key) in the same world frame, vanilla-faithful chain.

Panels per camera: held item (display under test) | rig parts (deliver/rig-parts.yaml) | 50/50 overlay.
Cameras: third-person right side, front, first-person (viewmodel chain, FOV 70, 16:9).
Usage: handover_render.py <out-dir> [label=path/to/model.json ...]   (default label 'now' = shipped model)"""
import base64, io, json, subprocess, sys
from pathlib import Path
import numpy as np
import yaml
from PIL import Image, ImageDraw
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1]))
import handover_s1 as h
from raster import faces, element_points, default_uv
from recipe import texture_map
from materials import vanilla_path
ROOT = h.ROOT
RIG_PARTS = ROOT/'tools/skill-compare/data/bastion-greatsword-s1/deliver/rig-parts.yaml'
BLOCK_TEX = {'smooth_quartz': 'quartz_block_bottom', 'dried_kelp_block': 'dried_kelp_side'}

def png_uri(pixels):
    b = io.BytesIO(); Image.fromarray(pixels).save(b, format='PNG')
    return 'data:image/png;base64,'+base64.b64encode(b.getvalue()).decode()

def model_quads(model, mat, textures):
    tex = texture_map(model); out = []
    for e in model['elements']:
        for face, (pts, _) in faces(e).items():
            if face not in e['faces']: continue
            f = e['faces'][face]
            u, v, U, V = f.get('uv', default_uv(e, face))
            uv = [[u, 16-v], [U, 16-v], [U, 16-V], [u, 16-V]]
            sh = f.get('rotation', 0)//90
            if sh: uv = uv[-sh:]+uv[:-sh]
            key = f['texture'][1:]
            if key not in textures: textures[key] = png_uri(tex[f['texture']])
            P = [(mat@np.append(p, 1))[:3].tolist() for p in element_points(e, pts)]
            out.append({'t': key, 'p': P, 'uv': [[a/16, b/16] for a, b in uv]})
    return out

def box_quads(lo, hi, xf, key, color=None):
    e = {'from': lo, 'to': hi}; out = []
    for face, (pts, _) in faces(e).items():
        q = {'p': [xf(np.array(p)).tolist() for p in pts], 'uv': [[0, 1], [1, 1], [1, 0], [0, 0]]}
        if key: q['t'] = key
        else: q['c'] = color
        out.append(q)
    return out

def rig_quads(textures):
    P, R, rig = h.rig_final()
    parts = yaml.safe_load(RIG_PARTS.read_text())['parts']; out = []
    for part in parts:
        b = part['block']; key = 'blk_'+b
        if key not in textures:
            textures[key] = png_uri(np.array(Image.open(vanilla_path('minecraft:block/'+BLOCK_TEX.get(b, b))).convert('RGBA')))
        o = np.array(part['offset']); s = np.array(part['size'])
        out += box_quads((o-s/2).tolist(), (o+s/2).tolist(), lambda p: P+R@p, key)
    return out, len(parts)

def player_quads(left=False):
    """Slim player, ITEM arm pose, mean bob. Built in model px space then the entity chain."""
    root = h.M(h.Ry(180)) @ h.S(-1,-1,1) @ h.S(.9375,.9375,.9375) @ h.M(t=(0,-1.501,0))
    px = lambda m: (lambda p: (m@np.append(np.asarray(p)/16, 1))[:3])
    out = []
    out += box_quads([-4,-8,-4], [4,0,4], px(root), None, '#c08a5a')            # head
    out += box_quads([-4,0,-2], [4,12,2], px(root), None, '#3d8f6a')            # body
    out += box_quads([-4,12,-2], [0,24,2], px(root@h.M(t=(0,0,0))), None, '#40507a')
    out += box_quads([0,12,-2], [4,24,2], px(root), None, '#40507a')
    for side in (False, True):
        sign = -1 if side else 1
        x = (5 if side else -5)
        holding = side == left
        xr = np.degrees(-np.pi/10) if holding else 0.0
        zr = np.degrees(0.05)*(-1 if side else 1)
        arm = root@h.M(h.Rz(zr)@h.Rx(xr), (x/16, 2/16, 0))
        lo, hi = ([-1,-2,-2], [2,10,2]) if side else ([-2,-2,-2], [1,10,2])
        out += box_quads(lo, hi, px(arm), None, '#c08a5a')
    return out

def main():
    outdir = Path(sys.argv[1]); outdir.mkdir(parents=True, exist_ok=True)
    labels = dict(a.split('=', 1) for a in sys.argv[2:]) or {'now': str(h.MODEL)}
    textures = {}; views = []
    rig, nparts = rig_quads(textures)
    body = player_quads()
    cams = {'side': {'pos': [-4.2, 1.3, 0.9], 'target': [0, 1.3, 0.9], 'fov': 40},
            'front': {'pos': [0.3, 1.5, 5.2], 'target': [0, 1.3, 0.5], 'fov': 40},
            'hilt': {'pos': [-1.9, 0.95, 0.55], 'target': [-0.348, 0.85, 0.35], 'fov': 30},
            'reverse': {'pos': [3.6, 2.2, 1.2], 'target': [-0.3, 1.9, 1.3], 'fov': 40}}
    for label, path in labels.items():
        model = json.loads(Path(path).read_text())
        A = h.hand_chain(False)
        held = model_quads(model, A@h.display_mat(model['display']['thirdperson_righthand']), textures)
        for cam, c in cams.items():
            views.append({'name': f'{label}-{cam}-held', 'camera': c, 'quads': body+held})
            views.append({'name': f'{label}-{cam}-rig', 'camera': c, 'quads': body+rig})
        fp = h.M(t=(0.56, -0.52, -0.72))@h.display_mat(model['display']['firstperson_righthand'])
        views.append({'name': f'{label}-firstperson-held', 'camera': {'pos': [0,0,0], 'target': [0,0,-1], 'fov': 70}, 'quads': model_quads(model, fp, textures)})
    eye = {'pos': [0, 1.62, 0], 'target': [0, 1.62, 1], 'fov': 70}
    views.append({'name': 'rig-firstperson-eye', 'camera': eye, 'quads': rig})
    (outdir/'scene.json').write_text(json.dumps({'textures': textures, 'views': views}))
    subprocess.run(['bun', str(HERE/'handover_render.mjs'), str(outdir/'scene.json'), str(outdir)], check=True)
    # boards
    for label in labels:
        rows = []
        for cam in cams:
            a = Image.open(outdir/f'{label}-{cam}-held.png').convert('RGB'); b = Image.open(outdir/f'{label}-{cam}-rig.png').convert('RGB')
            rows.append((cam, [a, b, Image.blend(a, b, .5)]))
        W, H = rows[0][1][0].size
        board = Image.new('RGB', (W*3, (H+18)*len(rows)+18+H), '#202830'); d = ImageDraw.Draw(board)
        for r, (cam, ims) in enumerate(rows):
            for c, (im, t) in enumerate(zip(ims, ['held item', f'rig parts ({nparts})', 'overlay 50/50'])):
                board.paste(im, (c*W, r*(H+18)+18)); d.text((c*W+4, r*(H+18)+3), f'{label} {cam}: {t}', fill='white')
        y = len(rows)*(H+18)
        board.paste(Image.open(outdir/f'{label}-firstperson-held.png').convert('RGB'), (0, y+18)); d.text((4, y+3), f'{label} first person R (FOV70, viewmodel)', fill='white')
        board.paste(Image.open(outdir/'rig-firstperson-eye.png').convert('RGB'), (W, y+18)); d.text((W+4, y+3), 'rig k52 from the eye (FOV70)', fill='white')
        board.save(outdir/f'{label}-handover-board.png')
        print(outdir/f'{label}-handover-board.png')

if __name__ == '__main__':
    main()
