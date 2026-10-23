#!/usr/bin/env python3
"""Reproduce couture-rapier through Forge operations (no test runner/server/build)."""
import argparse
import copy
import json
from pathlib import Path
import shutil
import sys
import subprocess
from PIL import Image, ImageDraw
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from core import Project, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--before', type=Path, required=True, help='Original palette model from git show 36743b70:content/models/items/couture-rapier.model.json')
    parser.add_argument('--current', type=Path, default=ROOT/'content/models/items/couture-rapier.model.json', help='Current geometry and display to preserve')
    parser.add_argument('--evidence', type=Path, default=Path(__file__).parent)
    a = parser.parse_args()
    if (a.project/'project.json').exists(): raise ValueError('Use a fresh project directory')
    a.evidence.mkdir(parents=True, exist_ok=True)
    p = Project(a.project)
    log = []
    def call(op, args=None):
        result = p.call(op, args)
        validation = result.get('validation')
        if validation:
            counts = {}
            for warning in validation['warnings']: counts[warning['kind']] = counts.get(warning['kind'],0)+1
            validation = {**validation,'warnings':counts}
        log.append({'op':op, 'args':args, 'validation':validation})
        print(op, flush=True)
        return result
    call('import', {'path':str(a.before)})
    call('scene.get', {'fields':['id','name','faces']})
    call('scene.element', {'id':'e0001'})
    call('scene.element', {'id':'e0007'})
    call('scene.element', {'id':'e0023'})
    call('measure'); call('validate')
    call('history.snapshot', {'name':'before'})
    board_args = {'resolution':384, 'name':'before'}
    before = call('board', board_args)
    shutil.copyfile(before['path'], a.evidence/'before.png')
    call('import', {'path':str(a.current)})
    current = copy.deepcopy(p.vanilla())
    call('history.snapshot', {'name':'current-display'})
    ops = []
    groups = {}
    for e in p.model['elements']:
        name = e.get('name','')
        if name.startswith('blade'): tint = '#3c4353'  # Original gunmetal (52,58,72).
        elif name.startswith('edge'): tint = '#f5deea'  # Original rose silver (213,193,203).
        elif name.startswith('flower'): tint = '#fff3fa'  # Pearl pink pivot (242,230,236).
        else: tint = '#20242d'  # Blackened metal loops (28,31,39).
        groups.setdefault(tint, []).append(e['id'])
    for tint, ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':'metal.iron_block','tint':tint,'seed':20261024}})
    call('batch', {'ops':ops})
    # Keep thin steel away from the block's heavy border; preserve each crop's density.
    crops = []
    for e in p.model['elements']:
        if not e.get('name', '').startswith(('blade', 'edge', 'flower')): continue
        for face, value in e['faces'].items():
            u, v, U, V = value['uv']
            crop = {**value, 'uv':[4, 4, 4 + U-u, 4 + V-v]}
            if e.get('name', '').startswith(('blade', 'edge')) and face in ('north','south','east','west'):
                crop = {**value, 'uv':[4, 4, 4 + V-v, 4 + U-u], 'rotation':90}
            crops.append({'op':'uv.set', 'args':{'id':e['id'], 'face':face,
                'value':crop}})
    call('batch', {'ops':crops})
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/iron_block'
    geometry = lambda m: [{k:v for k,v in e.items() if k != 'faces'} for e in m['elements']]
    assert geometry(model) == geometry(current), 'Geometry changed'
    assert model['display'] == current['display'], 'Display changed'
    assert all(set(a['faces']) == set(b['faces']) for a,b in zip(model['elements'], current['elements']))
    target = ROOT/'content/models/items/couture-rapier.model.json'
    target.write_text(json.dumps(model,indent=2)+'\n')
    call('import', {'path':str(target)})
    call('history.snapshot', {'name':'after'})
    after = call('board', {**board_args,'name':'after'})
    shutil.copyfile(after['path'], a.evidence/'after.png')
    contexts = call('board', {'views':['gui','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand','ground','fixed'],'resolution':256,'name':'contexts'})
    shutil.copyfile(contexts['path'], a.evidence/'contexts.png')
    # The Java display semantics preview includes both hands and mannequin anchors.
    preview = a.project/'contexts'
    subprocess.run([sys.executable, str(ROOT/'tools/model-forge/context_preview.py'),
                    '--out', str(preview), 'couture-rapier'], check=True)
    shutil.copyfile(a.evidence/'contexts.png', a.evidence/'forge-contexts.png')
    views = ['firstperson_righthand','firstperson_lefthand','thirdperson_righthand',
             'thirdperson_lefthand','thirdperson_righthand_side','ground','ground_spin','fixed','gui']
    board = Image.new('RGB', (960,696), '#293441')
    draw = ImageDraw.Draw(board)
    for i, view in enumerate(views):
        x, y = i%3*320, i//3*232
        draw.text((x+3,y+3), view, fill='white')
        with Image.open(preview/f'couture-rapier-{view}.png') as im:
            board.paste(im.resize((320,213)), (x,y+19))
    board.save(a.evidence/'contexts.png')
    inventory = Image.new('RGB', (384,152), '#202c3a')
    draw = ImageDraw.Draw(inventory)
    for i, size in enumerate([16,32,64]):
        rendered = call('render', {'views':['gui'], 'resolution':max(32,size), 'name':f'inventory-{size}'})
        with Image.open(rendered['images'][0]['path']) as im:
            tile = im.resize((size,size),Image.Resampling.NEAREST).resize((128,128),Image.Resampling.NEAREST)
            inventory.paste(tile, (i*128,24))
        draw.text((i*128+4,4), f'{size}px', fill='white')
    inventory.save(a.evidence/'inventory.png')
    call('measure'); call('validate')
    call('history.diff', {'a':'current-display','b':'after'})
    call('export', {'path':str(a.project/'export')})
    (a.evidence/'operations.json').write_text(json.dumps(log,indent=2)+'\n')

if __name__=='__main__': main()
