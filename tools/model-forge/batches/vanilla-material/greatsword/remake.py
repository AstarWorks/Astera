#!/usr/bin/env python3
"""Reproduce the greatsword through Forge operations (no test runner/server/build)."""
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
        if name == 'black grip': material = 'wood.stripped_dark_oak_log'
        elif 'grip wrap' in name: material = 'cloth.black_wool'
        elif name in ('dark steel blade', 'stepped spear point', 'deep central channel', 'guard central frame', 'grip collar'): material = 'metal.anvil'
        elif any(word in name for word in ('red guard', 'crimson', 'pommel bevel', 'guard raised boss', 'guard narrow ridge', 'pommel inset')): material = 'metal.copper_block'
        else: material = 'metal.iron_block'
        groups.setdefault(material, []).append(e['id'])
    for material, ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':material,'seed':20261023}})
    call('batch', {'ops':ops})
    # anvil_top has transparent U=0..3 and 13..16 gutters; keep metal crops opaque.
    opaque = []
    for e in p.model['elements']:
        for face, value in e['faces'].items():
            if p.model['textures'][value['texture'][1:]] != 'minecraft:block/anvil_top': continue
            u, v, U, V = value['uv']
            assert abs(U-u) <= 10
            shift = max(0, 3-min(u,U)) + min(0, 13-max(u,U))
            if shift:
                opaque.append({'op':'uv.set', 'args':{'id':e['id'], 'face':face,
                    'value':{**value, 'uv':[u+shift,v,U+shift,V]}}})
    call('batch', {'ops':opaque})
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/anvil_top'
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
    call('export', {'path':str(a.project/'export')})
    (a.evidence/'operations.json').write_text(json.dumps(log,indent=2)+'\n')

if __name__=='__main__': main()
