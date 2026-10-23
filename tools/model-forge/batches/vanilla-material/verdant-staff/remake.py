#!/usr/bin/env python3
"""Reproduce the verdant staff through Forge operations (no test runner/server/build)."""
import argparse
import json
from pathlib import Path
import shutil
import sys
import numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from core import Project, ROOT
from raster import render
from preview import tile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--before', type=Path, required=True, help='Original model, e.g. git show HEAD:content/models/items/verdant-staff.model.json')
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
    call('scene.element', {'id':p.model['elements'][0]['id']})
    call('measure'); call('validate')
    call('history.snapshot', {'name':'before'})
    board_args = {'resolution':384, 'name':'before'}
    before = call('board', board_args)
    shutil.copyfile(before['path'], a.evidence/'before.png')
    ops = []
    groups = {}
    for e in p.model['elements']:
        name = e.get('name','')
        if 'crystal' in name: material = 'gem.emerald_block'
        elif 'bright heart' in name or 'leaf vein' in name: material = 'cloth.lime_wool'
        elif 'leaf stepped' in name or 'winding vine' in name: material = 'cloth.green_wool'
        elif 'collar' in name or 'bronze pin' in name: material = 'metal.exposed_copper'
        elif 'raised vein' in name: material = 'wood.stripped_oak_log'
        else: material = 'wood.stripped_spruce_log'
        groups.setdefault(material, []).append(e['id'])
    for material, ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':material,'seed':20261024}})
    call('batch', {'ops':ops})
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/stripped_spruce_log'
    target = ROOT/'content/models/items/verdant-staff.model.json'
    target.write_text(json.dumps(model,indent=2)+'\n')
    call('import', {'path':str(target)})
    call('history.snapshot', {'name':'after'})
    after = call('board', {**board_args,'name':'after'})
    shutil.copyfile(after['path'], a.evidence/'after.png')
    contexts = call('board', {'views':['gui','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand','ground','fixed'],'resolution':256,'name':'contexts'})
    shutil.copyfile(contexts['path'], a.evidence/'contexts.png')
    inventory = Image.new('RGB', (384,160), '#131b26')
    draw = ImageDraw.Draw(inventory)
    textures = {'#'+k:np.array(p.image(k)) for k in p.model['textures']}
    for i, size in enumerate((16,32,64)):
        im, _, _ = render(p.model, textures, 'gui', size=size)
        inventory.paste(tile(im).resize((128,128), Image.Resampling.NEAREST), (i*128,24))
        draw.text((i*128+5,5), f'{size}px inventory', fill='white')
    inventory.save(a.evidence/'inventory.png')
    call('measure'); call('validate')
    call('history.diff', {'a':'before','b':'after'})
    call('export', {'path':str(a.project/'export')})
    (a.evidence/'operations.json').write_text(json.dumps(log,indent=2)+'\n')

if __name__=='__main__': main()
