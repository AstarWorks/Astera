#!/usr/bin/env python3
"""Reproduce the shotgun through Forge operations (no test runner/server/build)."""
import argparse
import json
import copy
import subprocess
from pathlib import Path
import shutil
import sys
from PIL import Image, ImageDraw
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from core import Project, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--before', type=Path, required=True, help='Palette reference from git show 36743b70:content/models/items/shotgun.model.json')
    parser.add_argument('--evidence', type=Path, default=Path(__file__).parent)
    a = parser.parse_args()
    if (a.project/'project.json').exists(): raise ValueError('Use a fresh project directory')
    a.evidence.mkdir(parents=True, exist_ok=True)
    p = Project(a.project)
    log = []
    def call(op, args=None):
        result = p.call(op, args)
        validation = result if op == 'validate' else result.get('validation')
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
    # The palette reference is historical; geometry and display come from current HEAD.
    head = json.loads(subprocess.check_output(['git','show','HEAD:content/models/items/shotgun.model.json']))
    source = a.project/'head.json'
    source.write_text(json.dumps(head))
    call('import', {'path':str(source)})
    original = copy.deepcopy(p.vanilla())
    call('history.snapshot', {'name':'head'})
    for e in p.model['elements']: call('scene.element', {'id':e['id']})
    ops = []
    groups = {}
    for e in p.model['elements']:
        name = e.get('name','')
        if any(word in name for word in ('wood', 'stock core', 'stock sloping', 'stock neck')):
            material, tint = 'wood.stripped_oak_log', '#b58d6b'
        elif name == 'cheek comb': material, tint = 'wood.stripped_oak_log', '#ffdfaa'
        elif any(word in name for word in ('buttpad', 'buttplate')):
            material, tint = 'cloth.white_wool', '#3f4c5d'
        elif 'shell bright seam' in name or name == 'selector':
            material, tint = 'concrete.white_concrete', '#ff9caa'
        elif 'red shell' in name or 'identity stripe' in name:
            material, tint = 'concrete.white_concrete', '#ec4164'
        elif 'brass' in name: material, tint = 'metal.iron_block', '#fff0a5'
        elif any(word in name for word in ('fastener', 'screw', 'highlight rail', 'front bead', 'trigger', 'muzzle rim', 'tube cap', 'upper bevel', 'lower bevel', 'side plate', 'long stepped barrel')):
            material, tint = 'metal.iron_block', '#c5dbe6'
        elif 'top rail tooth' in name: material, tint = 'metal.iron_block', '#a2b4bf'
        elif 'bore' in name: material, tint = 'concrete.white_concrete', '#151d28'
        elif 'ferrule' in name or name == 'guard front': material, tint = 'metal.iron_block', '#3b4858'
        else: material, tint = 'metal.iron_block', '#242d3a'
        groups.setdefault((material, tint), []).append(e['id'])
    for (material, tint), ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':material,'tint':tint,'seed':20261024}})
    call('batch', {'ops':ops})
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/iron_block'
    target = ROOT/'content/models/items/shotgun.model.json'
    geometry = lambda m: [{k:v for k,v in e.items() if k != 'faces'} for e in m['elements']]
    assert geometry(model) == geometry(original), 'HEAD geometry changed'
    assert model['display'] == original['display'], 'HEAD display changed'
    assert [set(e['faces']) for e in model['elements']] == [set(e['faces']) for e in original['elements']]
    target.write_text(json.dumps(model,indent=2)+'\n')
    call('import', {'path':str(target)})
    call('history.snapshot', {'name':'after'})
    after = call('board', {**board_args,'name':'after'})
    shutil.copyfile(after['path'], a.evidence/'after.png')
    contexts = call('board', {'views':['gui','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand','ground','fixed'],'resolution':256,'name':'contexts'})
    shutil.copyfile(contexts['path'], a.evidence/'contexts.png')
    inventory = Image.new('RGB',(384,160),'#202c3a')
    draw = ImageDraw.Draw(inventory)
    for i, size in enumerate((16,32,64)):
        tile = call('render', {'views':['gui'],'resolution':max(32,size),'name':'inventory-'+str(size)})
        image = Image.open(tile['images'][0]['path']).resize((size,size),Image.Resampling.NEAREST)
        inventory.paste(image.resize((128,128),Image.Resampling.NEAREST),(i*128,24))
        draw.text((i*128+4,4),str(size)+' px',fill='white')
    inventory.save(a.evidence/'inventory.png')
    call('measure'); call('validate')
    call('history.diff', {'a':'head','b':'after'})
    exported = call('export', {'path':str(a.project/'export')})
    roundtrip = Project(a.project/'roundtrip')
    roundtrip.call('import', {'path':exported['path']})
    assert roundtrip.vanilla() == p.vanilla()
    measurement = call('measure')
    assert all(abs(n-1)<1e-10 for d in measurement['density'] for n in d['texels_per_unit'])
    validation = call('validate')
    counts = {}
    for warning in validation['warnings']: counts[warning['kind']] = counts.get(warning['kind'],0)+1
    validation['warnings'] = counts
    (a.evidence/'verification.json').write_text(json.dumps({'geometryUnchanged':True,'displayUnchanged':True,'elements':len(model['elements']),'exportImportIdentical':True,'allFaceTexelsPerLocalUnit':1,'palette':model['astera_tints'],'validation':validation},indent=2)+'\n')
    (a.evidence/'operations.json').write_text(json.dumps(log,indent=2)+'\n')

if __name__=='__main__': main()
