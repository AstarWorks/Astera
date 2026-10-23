#!/usr/bin/env python3
"""Reproduce knight-sword through Forge operations (no test runner/server/build)."""
import argparse
import copy
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
    # Palette reference is historical; geometry and display always come from current source.
    call('import', {'path':str(ROOT/'content/models/items/knight-sword.model.json')})
    original = copy.deepcopy(p.vanilla())
    original_display = copy.deepcopy(p.model['display'])
    call('history.snapshot', {'name':'head'})
    ops = []
    groups = {}
    for e in p.model['elements']:
        name = e.get('name','')
        if name == 'leather grip': material, tint = 'cloth.white_wool', '#602f21'
        elif name == 'leather diagonal binding': material, tint = 'cloth.white_wool', '#301d17'
        elif name == 'dark inset fuller': material, tint = 'metal.iron_block', '#8196a6'
        elif name == 'guard rivet bezel': material, tint = 'metal.iron_block', '#c08532'
        elif name in ('ricasso', 'blade web', 'point taper'): material, tint = 'metal.iron_block', '#b0c7ce'
        elif name in ('long edge bevel', 'fuller silver lip', 'edge nick highlight'): material, tint = 'metal.iron_block', '#e0f5f6'
        elif name in ('guard arm', 'guard center', 'wheel middle', 'collar'): material, tint = 'metal.iron_block', '#e9af38'
        else: material, tint = 'metal.iron_block', '#ffda71'
        groups.setdefault((material, tint), []).append(e['id'])
    for (material, tint), ids in groups.items():
        ops.append({'op':'material.apply', 'args':{'ids':ids,'material':material,'tint':tint,'seed':20261024}})
    call('batch', {'ops':ops})
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/iron_block'
    target = ROOT/'content/models/items/knight-sword.model.json'
    target.write_text(json.dumps(model,indent=2)+'\n')
    if model['display'] != original_display: raise ValueError('Display transforms changed')
    geometry = lambda m: [{k:v for k,v in e.items() if k != 'faces'} for e in m['elements']]
    assert geometry(model) == geometry(original), 'HEAD geometry changed'
    assert [[*e['faces']] for e in model['elements']] == [[*e['faces']] for e in original['elements']]
    assert len(model['astera_tints']) == len(set(model['astera_tints'])) == 8

    call('import', {'path':str(target)})
    call('history.snapshot', {'name':'after'})
    after = call('board', {**board_args,'name':'after'})
    shutil.copyfile(after['path'], a.evidence/'after.png')
    contexts = call('board', {'views':['gui','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand','ground','fixed'],'resolution':256,'name':'contexts'})
    shutil.copyfile(contexts['path'], a.evidence/'contexts.png')
    inventory = call('render', {'views':['gui'],'resolution':64,'name':'inventory'})
    with Image.open(inventory['images'][0]['path']) as im:
        sheet = Image.new('RGB', (450,175), '#202b39')
        draw = ImageDraw.Draw(sheet)
        for i, size in enumerate((16,32,64)):
            thumb = im.resize((size,size), Image.Resampling.NEAREST)
            sheet.paste(thumb.resize((128,128), Image.Resampling.NEAREST), (i*150,25))
            draw.text((i*150,3), f'{size}px', fill='white')
        sheet.save(a.evidence/'inventory.png')
    call('measure'); call('validate')
    call('history.diff', {'a':'before','b':'after'})
    exported = call('export', {'path':str(a.project/'export')})
    roundtrip = Project(a.project/'roundtrip')
    roundtrip.call('import', {'path':exported['path']})
    assert roundtrip.vanilla() == p.vanilla()
    assert not list((a.project/'export/textures').glob('*.png'))
    (a.evidence/'verification.json').write_text(json.dumps({
        'headElements':len(original['elements']), 'finalElements':len(model['elements']),
        'headGeometryExactlyPreserved':True, 'headDisplayExactlyPreserved':True,
        'palette':model['astera_tints'], 'exportImportIdentical':True, 'exportedPngs':0,
        'validationErrors':p.call('validate')['errors']}, indent=2)+'\n')
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
