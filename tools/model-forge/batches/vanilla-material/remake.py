#!/usr/bin/env python3
"""Reproduce the pilot through Forge operations (no test runner/server/build)."""
import argparse
import json
from pathlib import Path
import shutil
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from core import Project, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--before', type=Path, required=True, help='Original model, e.g. git show HEAD:content/models/items/verdict-hammer.model.json')
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
        if 'haft' in name: material = 'wood.stripped_dark_oak_log'
        elif 'binding' in name: material = 'cloth.brown_wool'
        elif any(word in name for word in ('teal', 'gem', 'inlay')): material = 'gem.diamond_block'
        elif 'obsidian' in name or 'black striking' in name: material = 'stone.polished_blackstone'
        elif 'socket neck' in name: material = 'metal.iron_block'
        else: material = 'metal.gold_block'
        groups.setdefault(material, []).append(e['id'])
    for material, ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':material,'seed':20261023}})
    ops.append({'op':'texture.create','args':{'key':'runes','size':[16,16],'color':'#143a42'}})
    ops.append({'op':'texture.paint','args':{'key':'runes','ops':[
        {'type':'rect','box':[2,2,13,13],'color':'#20535b'},
        {'type':'line','points':[[4,4],[11,4],[8,7],[8,12]],'color':'#c1fff1','width':1},
        {'type':'line','points':[[4,8],[11,8]],'color':'#5de1ca','width':1},
        {'type':'line','points':[[5,11],[11,11]],'color':'#5de1ca','width':1}
    ]}})
    for e in p.model['elements']:
        if e.get('name')=='gem table':
            face = 'north' if e['from'][2]<8 else 'south'
            ops.append({'op':'uv.set','args':{'id':e['id'],'face':face,'value':{'texture':'#runes','uv':[0,0,16,16]}}})
    call('batch', {'ops':ops})
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/polished_blackstone'
    rune_path = ROOT/'content/textures/blocks/verdict-hammer-runes.png'
    rune_path.parent.mkdir(parents=True, exist_ok=True)
    p.image('runes').save(rune_path)
    model['textures']['runes'] = 'astera:blocks/verdict-hammer-runes'
    target = ROOT/'content/models/items/verdict-hammer.model.json'
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
