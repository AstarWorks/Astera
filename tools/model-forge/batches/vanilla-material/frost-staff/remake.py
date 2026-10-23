#!/usr/bin/env python3
"""Reproduce the frost staff through Forge operations (no test runner/server/build)."""
import argparse
import json
from pathlib import Path
import shutil
import sys
import subprocess
from PIL import Image
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from core import Project, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--before', type=Path, required=True, help='Original model, e.g. git show HEAD:content/models/items/frost-staff.model.json')
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
    call('scene.element', {'id':'e0001'})
    call('measure'); call('validate')
    call('history.snapshot', {'name':'before'})
    board_args = {'resolution':384, 'name':'before'}
    before = call('board', board_args)
    shutil.copyfile(before['path'], a.evidence/'before.png')
    ops = []
    groups = {}
    for e in p.model['elements']:
        name = e.get('name','')
        if 'wood' in name or 'shaft' in name: material = 'wood.stripped_dark_oak_log'
        elif 'leather' in name: material = 'cloth.brown_wool'
        elif e['faces']['north']['uv'][:2] == [8.0, 8.0] or 'silver' in name or 'pale' in name: material = 'metal.iron_block'
        elif name == 'frost fracture': material = 'metal.iron_block'
        elif name == 'stepped crystal facet':
            material = 'metal.gold_block' if e['faces']['north']['uv'][1] < 4 else 'gem.diamond_block'
        elif any(word in name for word in ('ice', 'gem')): material = 'gem.diamond_block'
        else: material = 'metal.gold_block'
        groups.setdefault(material, []).append(e['id'])
    for material, ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':material,'seed':20261024}})
    call('batch', {'ops':ops})
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/diamond_block'
    target = ROOT/'content/models/items/frost-staff.model.json'
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
    subprocess.run([sys.executable, str(ROOT/'tools/model-forge/context_preview.py'), '--out', str(a.project/'contexts'), 'frost-staff'], check=True)
    with Image.open(a.project/'contexts/frost-staff-board.jpg') as image:
        image.save(a.evidence/'contexts.png')

if __name__=='__main__': main()
