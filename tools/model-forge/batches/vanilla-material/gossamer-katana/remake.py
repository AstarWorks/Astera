#!/usr/bin/env python3
"""Reproduce gossamer-katana through Forge operations (no test runner/server/build)."""
import argparse
import json
import copy
from collections import Counter
from pathlib import Path
import shutil
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from core import Project, ROOT
from PIL import Image, ImageDraw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--before', type=Path, required=True, help='Palette reference: git show 36743b70:content/models/items/gossamer-katana.model.json')
    parser.add_argument('--head', type=Path, required=True, help='Saved HEAD model; geometry and display authority')
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
    for e in p.model['elements']:
        call('scene.element', {'id':e['id']})
    call('measure'); call('validate')
    call('history.snapshot', {'name':'before'})
    board_args = {'resolution':384, 'name':'before'}
    before = call('board', board_args)
    shutil.copyfile(before['path'], a.evidence/'before.png')
    # Palette reference is historical; geometry and display come exclusively from HEAD.
    call('import', {'path':str(a.head)})
    original = copy.deepcopy(p.model)
    ops = []
    groups = {}
    for e in p.model['elements']:
        name = e.get('name','')
        if name == 'blue underwrap': material, tint = 'cloth.white_wool', '#1463b5'
        elif 'silk' in name: material, tint = 'cloth.white_wool', '#f6686d'
        elif name == 'curved silver section': material, tint = 'metal.iron_block', '#cce4f4'
        elif name in ('bright cutting edge', 'web spoke'): material, tint = 'metal.iron_block', '#b9d0da'
        elif name == 'dark spine': material, tint = 'metal.iron_block', '#293641'
        elif name == 'tsuba core': material, tint = 'metal.iron_block', '#ffb638'
        else: material, tint = 'metal.iron_block', '#ffdc79'
        groups.setdefault((material, tint), []).append(e['id'])
    for (material, tint), ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':material,'tint':tint,'seed':20261024}})
    call('batch', {'ops':ops})
    # Align tiny blade segments on quiet metal crops so random borders do not form bands.
    aligned = []
    for e in p.model['elements']:
        name = e.get('name', '')
        if name not in ('curved silver section', 'bright cutting edge', 'dark spine'): continue
        u, v = (4, 7) if name == 'curved silver section' else (4, 1) if name == 'bright cutting edge' else (6, 5)
        for face, value in e['faces'].items():
            x, y, X, Y = value['uv']
            aligned.append({'op':'uv.set','args':{'id':e['id'],'face':face,'value':{**value,'uv':[u,v,u+X-x,v+Y-y]}}})
    call('batch', {'ops':aligned})
    assert len(p.model['elements']) == len(original['elements'])
    for before, after in zip(original['elements'], p.model['elements']):
        assert {k:v for k,v in before.items() if k != 'faces'} == {k:v for k,v in after.items() if k != 'faces'}
        assert before['faces'].keys() == after['faces'].keys()
    assert p.model['display'] == original['display']
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/iron_block'
    target = ROOT/'content/models/items/gossamer-katana.model.json'
    target.write_text(json.dumps(model,indent=2)+'\n')
    call('import', {'path':str(target)})
    call('history.snapshot', {'name':'after'})
    after = call('board', {**board_args,'name':'after'})
    shutil.copyfile(after['path'], a.evidence/'after.png')
    contexts = call('board', {'views':['gui','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand','ground','fixed'],'resolution':256,'name':'contexts'})
    shutil.copyfile(contexts['path'], a.evidence/'contexts.png')
    inventory = call('board', {'views':['gui'],'resolution':64,'name':'inventory'})
    tile = Image.open(a.project/'renders/inventory-gui.png').convert('RGB')
    scales = Image.new('RGB', (240, 104), '#202c39')
    labels = ImageDraw.Draw(scales)
    for i, size in enumerate((16, 32, 64)):
        sample = tile.resize((size, size), Image.Resampling.NEAREST)
        scales.paste(sample.resize((64, 64), Image.Resampling.NEAREST), (i*80, 24))
        labels.text((i*80, 4), str(size)+'px', fill='white')
    scales.save(a.evidence/'inventory.png')
    call('measure'); call('validate')
    call('history.diff', {'a':'before','b':'after'})
    exported = call('export', {'path':str(a.project/'export')})
    roundtrip = Project(a.project/'roundtrip')
    roundtrip.call('import', {'path':exported['path']})
    assert roundtrip.vanilla() == p.vanilla()
    assert not list((a.project/'export/textures').glob('*.png'))
    validation = p.call('validate')
    validation['warnings'] = dict(Counter(w['kind'] for w in validation['warnings']))
    (a.evidence/'verification.json').write_text(json.dumps({'geometryUnchanged':True,'displayUnchanged':True,'faceCoverageUnchanged':True,'elements':len(original['elements']),'exportImportIdentical':True,'exportedPngs':0,'validation':validation}, indent=2)+'\n')
    (a.evidence/'operations.json').write_text(json.dumps(log,indent=2)+'\n')

if __name__=='__main__': main()
