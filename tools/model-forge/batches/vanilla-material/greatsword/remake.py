#!/usr/bin/env python3
"""Reproduce the greatsword through Forge operations (no test runner/server/build)."""
import argparse
import json
import copy
import math
from pathlib import Path
import shutil
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from core import Project, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--before', type=Path, required=True, help='Original model, e.g. git show HEAD:content/models/items/greatsword.model.json')
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
    original = copy.deepcopy(p.model)
    call('history.snapshot', {'name':'before'})
    board_args = {'resolution':384, 'name':'before'}
    before = call('board', board_args)
    shutil.copyfile(before['path'], a.evidence/'before.png')
    for e in p.model['elements']:
        call('scene.element', {'id':e['id']})
    ops = []
    groups = {}
    for e in p.model['elements']:
        name = e.get('name','')
        if name == 'black grip': material, tint = 'wood.stripped_dark_oak_log', None
        elif 'grip wrap' in name: material, tint = 'cloth.black_wool', None
        elif name in ('dark steel blade', 'stepped spear point'): material, tint = 'metal.iron_block', '#557caa'
        elif name == 'deep central channel': material, tint = 'metal.iron_block', '#243c59'
        elif name in ('guard central frame', 'grip collar'): material, tint = 'metal.iron_block', '#354c69'
        elif any(word in name for word in ('red guard', 'crimson', 'pommel bevel', 'guard raised boss', 'guard narrow ridge', 'pommel inset')):
            material, tint = 'metal.iron_block', '#bf293e'
        else: material, tint = 'metal.iron_block', '#b4cee8'
        groups.setdefault((material, tint), []).append(e['id'])
    for (material, tint), ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':material,'tint':tint,'seed':20261024}})
    call('batch', {'ops':ops})
    # Verify the real import -> material/split -> export workflow against original geometry.
    split_count = 0
    for element in original['elements']:
        parts = [e for e in p.model['elements'] if e['id'] == element['id'] or e['id'].startswith(element['id']+'_tile')]
        split_count += len(parts) > 1
        volume = lambda e: math.prod(b-a for a,b in zip(e['from'],e['to']))
        assert math.isclose(sum(map(volume, parts)), volume(element))
        assert all(e.get('rotation') == element.get('rotation') and e.get('name') == element.get('name') for e in parts)
        for axis in range(3):
            assert math.isclose(min(e['from'][axis] for e in parts), element['from'][axis], abs_tol=1e-12)
            assert math.isclose(max(e['to'][axis] for e in parts), element['to'][axis], abs_tol=1e-12)
    assert p.model['display'] == original['display']
    assert len(p.model['astera_tints']) == len(set(p.model['astera_tints'])) == 5
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/iron_block'
    target = ROOT/'content/models/items/greatsword.model.json'
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
        'originalElements':len(original['elements']), 'finalElements':len(p.model['elements']),
        'splitOriginalElements':split_count, 'geometry':'Original envelopes, volumes, names and rotations preserved.',
        'displayUnchanged':True, 'palette':p.model['astera_tints'], 'paletteDeduplicated':True,
        'exportImportIdentical':True, 'exportedPngs':0, 'validation':p.call('validate')['errors'],
        'bbox':measurement['bbox'],
        'texelsPerLocalUnit':[min(n for d in measurement['density'] for n in d['texels_per_unit']),
                              max(n for d in measurement['density'] for n in d['texels_per_unit'])]}, indent=2)+'\n')
    (a.evidence/'operations.json').write_text(json.dumps(log,indent=2)+'\n')

if __name__=='__main__': main()
