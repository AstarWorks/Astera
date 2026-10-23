#!/usr/bin/env python3
"""Reproduce knight-sword through Forge operations (no test runner/server/build)."""
import argparse
import json
from pathlib import Path
import shutil
import sys
import subprocess
from PIL import Image, ImageDraw
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from core import Project, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--before', type=Path, required=True, help='Original model, e.g. git show HEAD:content/models/items/knight-sword.model.json')
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
        entry = {'op':op, 'args':args, 'validation':validation}
        if op == 'measure': entry['result'] = result
        if op == 'validate':
            counts = {}
            for warning in result['warnings']: counts[warning['kind']] = counts.get(warning['kind'],0)+1
            entry['result'] = {**result, 'warnings':counts}
        log.append(entry)
        print(op, flush=True)
        return result
    call('import', {'path':str(a.before)})
    call('scene.get', {'fields':['id','name','faces']})
    original_display = p.model['display'].copy()
    for element in p.model['elements']:
        call('scene.element', {'id':element['id']})
    call('measure'); call('validate')
    call('history.snapshot', {'name':'before'})
    board_args = {'resolution':384, 'name':'before'}
    before = call('board', board_args)
    shutil.copyfile(before['path'], a.evidence/'before.png')
    ops = []
    groups = {}
    for e in p.model['elements']:
        name = e.get('name','')
        if name == 'leather grip': material = 'cloth.brown_wool'
        elif name == 'leather diagonal binding': material = 'cloth.black_wool'
        elif name in ('dark inset fuller', 'guard rivet bezel'): material = 'stone.polished_blackstone'
        elif name in ('ricasso', 'blade web', 'long edge bevel', 'point taper', 'fuller silver lip', 'edge nick highlight'): material = 'metal.iron_block'
        else: material = 'metal.gold_block'
        groups.setdefault(material, []).append(e['id'])
    for material, ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':material,'seed':20261023}})
    call('batch', {'ops':ops})
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/iron_block'
    target = ROOT/'content/models/items/knight-sword.model.json'
    target.write_text(json.dumps(model,indent=2)+'\n')
    if model['display'] != original_display: raise ValueError('Display transforms changed')
    call('import', {'path':str(target)})
    call('history.snapshot', {'name':'after'})
    after = call('board', {**board_args,'name':'after'})
    shutil.copyfile(after['path'], a.evidence/'after.png')
    contexts = call('board', {'views':['gui','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand','ground','fixed'],'resolution':256,'name':'contexts'})
    shutil.copyfile(contexts['path'], a.evidence/'contexts.png')
    call('measure'); call('validate')
    call('history.diff', {'a':'before','b':'after'})
    call('export', {'path':str(a.project/'export')})
    # Existing client-context renderer and orbit reviewer; keep embedded vanilla pixels local.
    context = a.project/'contexts'
    viewer = a.project/'viewer'
    orbit = a.project/'orbits'
    subprocess.run([sys.executable, str(ROOT/'tools/model-forge/context_preview.py'), '--out', str(context), 'knight-sword'], check=True)
    shutil.copyfile(context/'knight-sword-board.jpg', a.evidence/'contexts-faithful.jpg')
    subprocess.run([sys.executable, str(ROOT/'tools/model-forge/context_preview.py'), '--viewer', '--out', str(viewer), 'knight-sword'], check=True)
    subprocess.run(['bun', str(ROOT/'tools/model-forge/review.mjs'), str(viewer/'viewer.html'), str(orbit), 'knight-sword'], check=True)
    files = sorted(orbit.glob('knight-sword-orbit*.png')) + sorted(orbit.glob('knight-sword-pole*.png'))
    sheet = Image.new('RGB', (1280,1680), '#222b35')
    draw = ImageDraw.Draw(sheet)
    for i, path in enumerate(files):
        with Image.open(path) as im:
            im.thumbnail((320,215))
            x, y = i%4*320, i//4*240
            sheet.paste(im,(x,y+20))
            draw.text((x+2,y+2), path.stem.removeprefix('knight-sword-'), fill='white')
    sheet.save(a.evidence/'orbits.png')
    shutil.copyfile(orbit/'cameras.json', a.evidence/'cameras.json')
    (a.evidence/'operations.json').write_text(json.dumps(log,indent=2)+'\n')

if __name__=='__main__': main()
