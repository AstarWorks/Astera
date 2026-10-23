#!/usr/bin/env python3
"""Reproduce the ember-gauntlet through Forge operations (no test runner/server/build)."""
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
    parser.add_argument('--before', type=Path, required=True, help='Original model, e.g. git show HEAD:content/models/items/ember-gauntlet.model.json')
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
    glow = []
    for e in p.model['elements']:
        name = e.get('name','')
        call('scene.element', {'id':e['id']})
        if any(word in name for word in ('hot', 'furnace face', 'heat slit', 'furnace vent', 'furnace window', 'side furnace', 'crest core', 'yellow core', 'front core')):
            glow.append(e)
            continue
        original_uv = next(iter(e['faces'].values()))['uv'][:2]
        if name == 'octagonal cuff plate':
            material = {(12.0,0.0):'metal.anvil',(8.0,0.0):'metal.copper_block',(8.0,8.0):'metal.iron_block'}[tuple(original_uv)]
        elif any(word in name for word in ('bronze', 'border', 'rivet', 'bezel')): material = 'metal.copper_block'
        elif any(word in name for word in ('bevel', 'shield rim', 'highlight', 'seam')): material = 'metal.iron_block'
        else: material = 'metal.anvil'
        groups.setdefault(material, []).append(e['id'])
    for material, ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':material,'seed':20261023}})
    # Only the existing furnace/glow pieces use painted pixels: orange heat and yellow cores.
    ops.extend([
        {'op':'texture.create','args':{'key':'embers','size':[16,16],'color':'#f2670d'}},
        {'op':'texture.paint','args':{'key':'embers','ops':[
            {'type':'rect','box':[8,0,15,15],'color':'#fff6bd'},
        ]}}
    ])
    for e in glow:
        core = any(word in e['name'] for word in ('yellow core','front core','hot core'))
        uv = [8,0,12,4] if core else [0,0,4,4]
        for face in e['faces']:
            ops.append({'op':'uv.set','args':{'id':e['id'],'face':face,'value':{'texture':'#embers','uv':uv}}})
    call('batch', {'ops':ops})
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/anvil_top'
    ember_path = ROOT/'content/textures/blocks/ember-gauntlet-embers.png'
    ember_path.parent.mkdir(parents=True, exist_ok=True)
    p.image('embers').save(ember_path)
    model['textures']['embers'] = 'astera:blocks/ember-gauntlet-embers'
    target = ROOT/'content/models/items/ember-gauntlet.model.json'
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
