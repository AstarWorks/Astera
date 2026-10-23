#!/usr/bin/env python3
"""Reproduce vendetta-rifle texturing through Forge operations (no test runner/server/build)."""
import argparse
import json
import copy
import subprocess
from pathlib import Path
import shutil
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from core import Project, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--before', type=Path, required=True, help='Original model, e.g. git show HEAD:content/models/items/vendetta-rifle.model.json')
    parser.add_argument('--evidence', type=Path, default=Path(__file__).parent)
    parser.add_argument('--current', type=Path, required=True, help='HEAD model; geometry and display source')
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
    original = json.loads(a.before.read_text())
    current = json.loads(a.current.read_text())
    atlas = a.project/'original.png'
    atlas.parent.mkdir(parents=True, exist_ok=True)
    atlas.write_bytes(subprocess.check_output(['git','show','36743b70:content/textures/items/vendetta-rifle.png']))
    call('import', {'path':str(a.before), 'textures':{'astera:items/vendetta-rifle':str(atlas)}})
    call('scene.get', {'fields':['id','name','faces']})
    call('scene.element', {'id':'e0002'})
    call('scene.element', {'id':'e0132'})
    call('measure'); call('validate')
    call('history.snapshot', {'name':'before'})
    board_args = {'resolution':384, 'name':'before'}
    before = call('board', board_args)
    shutil.copyfile(before['path'], a.evidence/'before.png')
    call('import', {'path':str(a.current)})
    head = copy.deepcopy(p.model)
    assert len(original['elements']) == len(head['elements'])
    ops = []
    groups = {}
    # Original atlas regions carry the actual part palette, including orange optics
    # and blue-gray screws/blade; names alone lost these distinctions in pass one.
    palette = {
        (0, 0): ('metal.iron_block', '#283340'),
        (0, 8): ('metal.iron_block', '#849bb3'),
        (4, 0): ('stone.calcite', '#ff8117'),
        (4, 8): ('metal.iron_block', '#df2b55'),
        (8, 8): ('metal.iron_block', '#ce3ef8'),
        (12, 0): ('wood.stripped_oak_log', '#ad7952'),
    }
    part_map = []
    for e, source in zip(p.model['elements'], original['elements']):
        assert e['name'] == source['name']
        region = tuple(int(v) for v in next(iter(source['faces'].values()))['uv'][:2])
        material, tint = palette[region]
        name = e.get('name', '')
        if 'binding' in name:
            material = 'cloth.white_wool'
        elif 'grip' in name or 'buttpad' in name or 'buttplate ribs' in name:
            material = 'cloth.white_wool'
        elif any(word in name for word in ('recess', 'muzzle port', 'dark muzzle')):
            material = 'concrete.white_concrete'
        groups.setdefault((material, tint), []).append(e['id'])
        part_map.append({'id': e['id'], 'name': name, 'originalRegion': region,
                         'material': material, 'tint': tint})
    for (material, tint), ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':material,'tint':tint,'seed':20261024}})
    call('batch', {'ops':ops})
    assert p.model['display'] == head['display']
    assert [{k:v for k,v in e.items() if k != 'faces'} for e in p.model['elements']] == [
        {k:v for k,v in e.items() if k != 'faces'} for e in head['elements']]
    assert [set(e['faces']) for e in p.model['elements']] == [set(e['faces']) for e in head['elements']]
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/iron_block'
    target = ROOT/'content/models/items/vendetta-rifle.model.json'
    target.write_text(json.dumps(model,indent=2)+'\n')
    call('import', {'path':str(target)})
    call('history.snapshot', {'name':'after'})
    after = call('board', {**board_args,'name':'after'})
    shutil.copyfile(after['path'], a.evidence/'after.png')
    contexts = call('board', {'views':['gui','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand','ground','fixed'],'resolution':256,'name':'contexts'})
    shutil.copyfile(contexts['path'], a.evidence/'contexts.png')
    for size in (32,64):
        call('render', {'views':['gui'],'resolution':size,'name':f'inventory-{size}'})
    call('measure'); call('validate')
    call('history.diff', {'a':'before','b':'after'})
    exported = call('export', {'path':str(a.project/'export')})
    roundtrip = Project(a.project/'roundtrip')
    roundtrip.call('import', {'path':exported['path']})
    assert roundtrip.vanilla() == p.vanilla()
    assert not list((a.project/'export/textures').glob('*.png'))
    validation = p.call('validate')
    validation['warnings'] = {kind: sum(w['kind'] == kind for w in validation['warnings'])
                              for kind in {w['kind'] for w in validation['warnings']}}
    (a.evidence/'verification.json').write_text(json.dumps({'originalRevision':'36743b70', 'geometryAndDisplayMatchHead':True, 'elements':len(head['elements']), 'palette':p.model['astera_tints'], 'exportImportIdentical':True, 'exportedPngs':0, 'validation':validation, 'partMap':part_map},indent=2)+'\n')
    (a.evidence/'operations.json').write_text(json.dumps(log,indent=2)+'\n')

if __name__=='__main__': main()
