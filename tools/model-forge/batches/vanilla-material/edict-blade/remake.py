#!/usr/bin/env python3
"""Reproduce the edict-blade through Forge operations (no test runner/server/build)."""
import argparse
import copy
import json
from pathlib import Path
import shutil
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from core import Project, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--before', type=Path, required=True, help='Original model, e.g. git show 36743b70:content/models/items/edict-blade.model.json')
    parser.add_argument('--evidence', type=Path, default=Path(__file__).parent)
    a = parser.parse_args()
    if (a.project/'project.json').exists(): raise ValueError('Use a fresh project directory')
    a.evidence.mkdir(parents=True, exist_ok=True)
    target = ROOT/'content/models/items/edict-blade.model.json'
    current = json.loads(target.read_text())
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
    call('import', {'path':str(target)})
    original = copy.deepcopy(p.model)
    ops = []
    groups = {}
    for e in p.model['elements']:
        name = e.get('name','')
        if name.startswith('white-glove-'): material, tint = 'cloth.white_wool', '#f2f6fc'
        elif name.startswith('finger-'): material, tint = 'cloth.white_wool', '#d6deec'
        elif name.startswith('brass-cuff-'): material, tint = 'metal.iron_block', '#aba175'
        else: material, tint = 'metal.iron_block', '#e5dcae'
        groups.setdefault((material, tint), []).append(e['id'])
    for (material, tint), ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':material,'tint':tint,'seed':20261024}})
    for e in p.model['elements']:
        call('scene.element', {'id':e['id']})
    call('batch', {'ops':ops})
    # Match coplanar fingertip faces to the glove beneath, retaining 1 texel/unit.
    seam_ops = []
    for e in p.model['elements']:
        if not e.get('name', '').startswith('finger-'): continue
        glove = next(g for g in p.model['elements'] if g.get('name') == 'white-glove-'+e['name'].split('-')[1])
        for face in ('up', 'north'):
            u, v, _, _ = glove['faces'][face]['uv']
            x, y, z = e['from']; X, Y, Z = e['to']
            if face == 'up':
                u += x-glove['from'][0]; v += z-glove['from'][2]
                uv = [u, v, u+X-x, v+Z-z]
            else:
                u += glove['to'][0]-X; v += glove['to'][1]-Y
                uv = [u, v, u+X-x, v+Y-y]
            seam_ops.append({'op':'uv.set','args':{'id':e['id'],'face':face,'value':{'texture':glove['faces'][face]['texture'],'tintindex':glove['faces'][face]['tintindex'],'uv':uv}}})
    call('batch', {'ops':seam_ops})
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/iron_block'
    assert model['display'] == current['display']
    geometry = lambda m: [{k:v for k,v in e.items() if k != 'faces'} for e in m['elements']]
    assert geometry(model) == geometry(current)
    assert geometry(p.model) == geometry(original)
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
    (a.evidence/'verification.json').write_text(json.dumps({'geometryUnchanged':True,'displayUnchanged':True,'exportImportIdentical':True,'palette':p.model['astera_tints'],'validation':p.call('validate')},indent=2)+'\n')
    (a.evidence/'operations.json').write_text(json.dumps(log,indent=2)+'\n')

if __name__=='__main__': main()
