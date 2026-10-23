#!/usr/bin/env python3
"""Reproduce the rtm-blaster palette restoration through Forge operations (no test runner/server/build)."""
import argparse
import copy
from PIL import Image
import json
from pathlib import Path
import shutil
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from core import Project, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--before', type=Path, required=True, help='Colour target from git show 36743b70:content/models/items/rtm-blaster.model.json')
    parser.add_argument('--current', type=Path, required=True, help='HEAD model with current geometry/display')
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
        if op == 'validate':
            entry['result'] = {'valid':result['valid'], 'errors':result['errors'], 'warnings':{kind:sum(w['kind']==kind for w in result['warnings']) for kind in sorted({w['kind'] for w in result['warnings']})}}
        log.append(entry)
        print(op, flush=True)
        return result
    call('import', {'path':str(a.before)})
    call('scene.get', {'fields':['id','name','faces']})
    call('measure'); call('validate')
    for e in p.model['elements']:
        call('scene.element', {'id':e['id']})
    call('history.snapshot', {'name':'before'})
    board_args = {'resolution':384, 'name':'before'}
    before = call('board', board_args)
    shutil.copyfile(before['path'], a.evidence/'before.png')
    # Render the historical colour target, but author only on the current geometry/display.
    reference = json.loads(a.before.read_text())
    source = ROOT/'content/textures/items/rtm-blaster.png'
    atlas = Image.open(source).convert('RGB')
    baseline = json.loads(a.current.read_text())
    call('import', {'path':str(a.current)})
    original = copy.deepcopy(p.model)
    groups = {}
    palette_map = []
    for element, colour_part in zip(p.model['elements'], reference['elements'], strict=True):
        name = element['name']
        assert name == colour_part['name']
        # The original atlas has region highlights; average the same first-face crop
        # for a stable per-part hue, including subpixel faces.
        u,v,U,V = next(iter(colour_part['faces'].values()))['uv']
        box = (min(u,U)*atlas.width/16, min(v,V)*atlas.height/16,
               max(u,U)*atlas.width/16+.01, max(v,V)*atlas.height/16+.01)
        rgb = atlas.transform((32,32), Image.Transform.EXTENT, box).resize((1,1)).getpixel((0,0))
        if name in ('stock core','stock sloping heel','stock neck','cheek comb'):
            material, gain = 'wood.stripped_oak_log', (1.45,1.7,2.7)
        elif name in ('grip','stock buttpad','buttplate ribs'):
            material, gain = 'cloth.white_wool', 1.25
        elif any(word in name for word in ('pink core','battery window','charge indicator','luminous bore','coil top highlight','identity stripe','forearm edge','violet channel','forearm fluting','lens exposed','lens facet','selector')):
            material, gain = 'concrete.white_concrete', 1.08
        else:
            material, gain = 'metal.iron_block', 1.12
        gains = gain if isinstance(gain,tuple) else (gain,)*3
        tint = '#%02x%02x%02x' % tuple(min(255,round(c*g)) for c,g in zip(rgb,gains))
        groups.setdefault((material,tint), []).append(element['id'])
        palette_map.append({'id':element['id'],'part':name,'originalRGB':list(rgb),'material':material,'tint':tint})
    ops = [{'op':'material.apply','args':{'ids':ids,'material':material,'tint':tint,'seed':20261024}}
           for (material,tint),ids in groups.items()]
    call('batch', {'ops':ops})
    geometry = lambda model: [{k:v for k,v in e.items() if k!='faces'} for e in model['elements']]
    assert geometry(p.model) == geometry(original)
    assert p.model['display'] == original['display']
    assert not p.call('validate')['errors']
    (a.evidence/'palette.json').write_text(json.dumps(palette_map,indent=2)+'\n')
    # Install references, retaining only used slots. Re-import records this packaging step in history.
    model = p.vanilla()
    used = {f['texture'][1:] for e in model['elements'] for f in e['faces'].values()}
    model['textures'] = {k:v for k,v in model['textures'].items() if k in used}
    model['textures']['particle'] = 'minecraft:block/iron_block'
    target = ROOT/'content/models/items/rtm-blaster.model.json'
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
    assert model['display'] == baseline['display']
    (a.evidence/'verification.json').write_text(json.dumps({'geometryUnchanged':True,'displayUnchanged':True,'elements':len(model['elements']),'exportImportIdentical':True,'validationErrors':p.call('validate')['errors']},indent=2)+'\n')
    (a.evidence/'operations.json').write_text(json.dumps(log,indent=2)+'\n')

if __name__=='__main__': main()
