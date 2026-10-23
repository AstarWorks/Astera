#!/usr/bin/env python3
"""Reproduce the pilot through Forge operations (no test runner/server/build)."""
import argparse
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
    parser.add_argument('--before', type=Path, required=True, help='Original model, e.g. git show HEAD:content/models/items/dagger.model.json')
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
    call('measure'); call('validate')
    call('history.snapshot', {'name':'before'})
    board_args = {'resolution':384, 'name':'before'}
    before = call('board', board_args)
    shutil.copyfile(before['path'], a.evidence/'before.png')
    ops = []
    groups = {}
    for e in p.model['elements']:
        name = e.get('name','')
        if name in ('leather grip', 'leather lap'): material = 'cloth.brown_wool'
        elif name == 'leather stitch': material = 'cloth.gray_wool'
        elif name == 'grip pin': material = 'metal.gold_block'
        elif name.startswith(('guard', 'pommel', 'grip collar')): material = 'metal.anvil'
        else: material = 'metal.iron_block'
        groups.setdefault(material, []).append(e['id'])
    for material, ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':material,'seed':20261023}})
    call('batch', {'ops':ops})
    # Keep the blade's many short segments on a single continuous iron crop.
    iron = next(k for k,v in p.model['textures'].items() if v == 'minecraft:block/iron_block')
    continuous = []
    for e in p.model['elements']:
        if e['name'] not in ('tapered blade section', 'central ridge', 'ground edge bevel', 'subtle grinding mark'): continue
        x,y,z = e['from']; X,Y,Z = e['to']
        for face in ('north','south'):
            continuous.append({'op':'uv.set','args':{'id':e['id'],'face':face,'value':{'texture':'#'+iron,'uv':[20-Y, x-3, 20-y, X-3], 'rotation':90}}})
    call('batch', {'ops':continuous})
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/iron_block'
    target = ROOT/'content/models/items/dagger.model.json'
    target.write_text(json.dumps(model,indent=2)+'\n')
    call('import', {'path':str(target)})
    call('history.snapshot', {'name':'after'})
    after = call('board', {**board_args,'name':'after'})
    shutil.copyfile(after['path'], a.evidence/'after.png')
    contexts = call('board', {'views':['gui','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand','ground','fixed'],'resolution':256,'name':'contexts'})
    shutil.copyfile(contexts['path'], a.evidence/'contexts.png')
    call('measure'); call('validate')
    call('history.diff', {'a':'before','b':'after'})
    call('export', {'path':str(a.project/'export')})
    inventory = Image.new('RGB', (384,160), (28,36,46))
    labels = ImageDraw.Draw(inventory)
    for i, size in enumerate((16,32,64)):
        result = call('render', {'views':['gui'], 'resolution':max(32,size), 'name':f'inventory-{size}'})
        image = Image.open(result['images'][0]['path']).resize((size,size), Image.Resampling.NEAREST)
        inventory.paste(image.resize((128,128), Image.Resampling.NEAREST), (128*i,25))
        labels.text((128*i+8,6), f'{size}px', fill='white')
    inventory.save(a.evidence/'inventory.png')
    subprocess.run([sys.executable, str(ROOT/'tools/model-forge/context_preview.py'),
                    '--out', str(a.project/'contexts'), 'dagger'], check=True)
    shutil.copyfile(a.project/'contexts/dagger-board.jpg', a.evidence/'player-contexts.jpg')
    (a.evidence/'operations.json').write_text(json.dumps(log,indent=2)+'\n')

if __name__=='__main__': main()
