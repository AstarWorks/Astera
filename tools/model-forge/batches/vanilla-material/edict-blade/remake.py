#!/usr/bin/env python3
"""Reproduce the edict-blade through Forge operations (no test runner/server/build)."""
import argparse
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
        if 'glove' in name or 'finger' in name: material = 'cloth.white_wool'
        else: material = 'metal.exposed_copper'
        groups.setdefault(material, []).append(e['id'])
    for material, ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':material,'seed':20261023}})
    call('batch', {'ops':ops})
    # Use khaki patina crops, avoiding the broad pink/green patches.
    patina_ops = []
    for e in p.model['elements']:
        if not (e.get('name', '').startswith('brass-cuff-') or e.get('name', '').startswith('chain-')): continue
        call('scene.element', {'id':e['id']})
        for face, f in e['faces'].items():
            u, v, U, V = f['uv']
            origin = (1, 2) if e['name'].startswith('chain-') else ((1, 10) if face not in ('up', 'down') else (8, 6))
            patina_ops.append({'op':'uv.set', 'args':{'id':e['id'], 'face':face, 'value':{**f, 'uv':[origin[0], origin[1], origin[0]+U-u, origin[1]+V-v]}}})
    call('batch', {'ops':patina_ops})
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
            seam_ops.append({'op':'uv.set','args':{'id':e['id'],'face':face,'value':{'texture':glove['faces'][face]['texture'],'uv':uv}}})
    call('batch', {'ops':seam_ops})
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/exposed_copper'
    target = ROOT/'content/models/items/edict-blade.model.json'
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
    (a.evidence/'operations.json').write_text(json.dumps(log,indent=2)+'\n')

if __name__=='__main__': main()
