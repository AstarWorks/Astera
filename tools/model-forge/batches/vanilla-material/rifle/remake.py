#!/usr/bin/env python3
"""Reproduce the rifle through Forge operations (no test runner/server/build)."""
import argparse
import json
import copy
from pathlib import Path
import shutil
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from core import Project, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--before', type=Path, required=True, help='Original model, e.g. git show HEAD:content/models/items/rifle.model.json')
    parser.add_argument('--current', type=Path, required=True, help='HEAD geometry and display, captured before replay')
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
    call('import', {'path':str(a.current)})
    original = copy.deepcopy(p.model)
    source = json.loads(a.before.read_text())
    assert len(source['elements']) == len(original['elements'])
    groups = {}
    for e, old in zip(p.model['elements'], source['elements']):
        call('scene.element', {'id':e['id']})
        name = e.get('name','')
        # Original atlas quadrants: blue-black steel, walnut, silver-blue steel, lime glass.
        u, v = next(iter(old['faces'].values()))['uv'][:2]
        if u >= 12 and v < 8:
            material, tint = 'wood.stripped_oak_log', '#b98276'
        elif u >= 12:
            material, tint = 'concrete.white_concrete', '#83d852'
        elif v >= 8:
            material, tint = 'metal.iron_block', '#8ba4ba'
        elif name in ('grip', 'stock buttpad', 'buttplate ribs'):
            material, tint = 'cloth.white_wool', '#293442'
        elif any(word in name for word in ('recess', 'port', 'dark muzzle')):
            material, tint = 'concrete.white_concrete', '#18212c'
        else:
            material, tint = 'metal.iron_block', '#293442'
        groups.setdefault((material,tint), []).append(e['id'])
    ops = [{'op':'material.apply', 'args':{'ids':ids,'material':material,'tint':tint,'seed':20261024}}
           for (material,tint),ids in groups.items()]
    call('batch', {'ops':ops})
    assert len(p.model['elements']) == len(original['elements'])
    for old, new in zip(original['elements'], p.model['elements']):
        assert {k:v for k,v in old.items() if k != 'faces'} == {k:v for k,v in new.items() if k != 'faces'}
    assert p.model['display'] == original['display']
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/iron_block'
    target = ROOT/'content/models/items/rifle.model.json'
    target.write_text(json.dumps(model,indent=2)+'\n')
    call('import', {'path':str(target)})
    call('history.snapshot', {'name':'after'})
    after = call('board', {**board_args,'name':'after'})
    shutil.copyfile(after['path'], a.evidence/'after.png')
    contexts = call('board', {'views':['gui','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand','ground','fixed'],'resolution':256,'name':'contexts'})
    shutil.copyfile(contexts['path'], a.evidence/'contexts.png')
    call('measure'); call('validate')
    call('history.diff', {'a':'before','b':'after'})
    exported = call('export', {'path':str(a.project/'export')})
    roundtrip = Project(a.project/'roundtrip')
    roundtrip.call('import', {'path':exported['path']})
    assert roundtrip.vanilla() == p.vanilla()
    assert not list((a.project/'export/textures').glob('*.png'))
    measurement = p.call('measure')
    (a.evidence/'verification.json').write_text(json.dumps({
        'elements':len(original['elements']), 'geometryExactlyUnchanged':True,
        'displayExactlyUnchanged':True, 'exportImportIdentical':True,
        'palette':p.model['astera_tints'], 'exportedPngs':0,
        'validationErrors':p.call('validate')['errors'],
        'densityRange':[min(n for d in measurement['density'] for n in d['texels_per_unit']),
                        max(n for d in measurement['density'] for n in d['texels_per_unit'])]},indent=2)+'\n')
    (a.evidence/'operations.json').write_text(json.dumps(log,indent=2)+'\n')

if __name__=='__main__': main()
