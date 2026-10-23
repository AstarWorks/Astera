#!/usr/bin/env python3
"""Reproduce the orrery staff through Forge operations (no test runner/server/build)."""
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
    parser.add_argument('--before', type=Path, required=True, help='Original model, e.g. git show HEAD:content/models/items/orrery-staff.model.json')
    parser.add_argument('--evidence', type=Path, default=Path(__file__).parent)
    a = parser.parse_args()
    if (a.project/'project.json').exists(): raise ValueError('Use a fresh project directory')
    a.evidence.mkdir(parents=True, exist_ok=True)
    p = Project(a.project)
    log = []
    def call(op, args=None):
        result = p.call(op, args)
        validation = result.get('validation')
        if op == 'validate': validation = result
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
        if name == 'shaft': material = 'wood.stripped_dark_oak_log'
        elif 'cable' in name: material = 'cloth.black_wool'
        elif name == 'emitter-housing': material = 'stone.polished_blackstone'
        elif 'inset' in name: material = 'metal.copper_block'
        else: material = 'metal.iron_block'
        groups.setdefault(material, []).append(e['id'])
    for material, ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':material,'seed':20261024}})
    ops.append({'op':'texture.create','args':{'key':'runes','size':[16,16],'color':'#9b411b'}})
    ops.append({'op':'texture.paint','args':{'key':'runes','ops':[
        {'type':'rect','box':[6,0,9,15],'color':'#ffac38'},
        {'type':'rect','box':[7,0,8,15],'color':'#ffe299'},
        {'type':'line','points':[[3,4],[12,4]],'color':'#ffcc66','width':1},
        {'type':'line','points':[[3,11],[12,11]],'color':'#ffcc66','width':1}
    ]}})
    for e in p.model['elements']:
        if 'emitter-inset' in e.get('name',''):
            face = 'north' if 'front' in e['name'] else 'south'
            ops.append({'op':'uv.set','args':{'id':e['id'],'face':face,'value':{'texture':'#runes','uv':[0,0,16,16]}}})
    call('batch', {'ops':ops})
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/polished_blackstone'
    rune_path = ROOT/'content/textures/blocks/orrery-staff-runes.png'
    rune_path.parent.mkdir(parents=True, exist_ok=True)
    p.image('runes').save(rune_path)
    model['textures']['runes'] = 'astera:blocks/orrery-staff-runes'
    target = ROOT/'content/models/items/orrery-staff.model.json'
    target.write_text(json.dumps(model,indent=2)+'\n')
    call('import', {'path':str(target)})
    call('history.snapshot', {'name':'after'})
    after = call('board', {**board_args,'name':'after'})
    shutil.copyfile(after['path'], a.evidence/'after.png')
    contexts = call('board', {'views':['gui','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand','ground','fixed'],'resolution':256,'name':'contexts'})
    shutil.copyfile(contexts['path'], a.evidence/'forge-contexts.png')
    measurement = call('measure'); validation = call('validate')
    (a.evidence/'measure.json').write_text(json.dumps(measurement,indent=2)+'\n')
    (a.evidence/'validation.json').write_text(json.dumps(validation,indent=2)+'\n')
    assert not validation['errors'], validation['errors']
    call('history.diff', {'a':'before','b':'after'})
    call('export', {'path':str(a.project/'export')})
    preview = a.project/'player-contexts'
    subprocess.run([sys.executable,str(ROOT/'tools/model-forge/context_preview.py'),
                    '--out',str(preview),'orrery-staff'],check=True)
    Image.open(preview/'orrery-staff-board.jpg').save(a.evidence/'contexts.png')
    inventory = Image.new('RGB',(384,148),'#293441')
    draw = ImageDraw.Draw(inventory)
    for i, size in enumerate((16,32,64)):
        tile = call('render', {'views':['gui'],'resolution':max(32,size),'name':f'inventory-{size}'})
        image = Image.open(tile['images'][0]['path']).resize((size,size),Image.Resampling.NEAREST)
        inventory.paste(image.resize((128,128),Image.Resampling.NEAREST),(i*128,20))
        draw.text((i*128+4,4),f'{size}px',fill='white')
    inventory.save(a.evidence/'inventory.png')
    (a.evidence/'operations.json').write_text(json.dumps(log,indent=2)+'\n')

if __name__=='__main__': main()
