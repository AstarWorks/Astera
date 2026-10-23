#!/usr/bin/env python3
"""Compare the original, catalog candidates and final crops using identical cameras."""
import argparse
import json
from pathlib import Path
import sys
from PIL import Image, ImageDraw
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from core import Project, ROOT

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--before', type=Path, required=True)
parser.add_argument('--project', type=Path, required=True)
a = parser.parse_args()
if (a.project/'project.json').exists(): raise ValueError('Use a fresh project')
p = Project(a.project)
log = []
def call(op, args):
    result = p.call(op, args)
    log.append({'op':op, 'args':args})
    return result
call('import', {'path':str(a.before.resolve())})
call('history.snapshot', {'name':'original'})
materials = ['original', 'metal.gold_block', 'metal.raw_gold_block', 'metal.exposed_copper',
             'metal.weathered_copper', 'stone.red_terracotta', 'leather.brown_mushroom_block', 'selected khaki copper crops']
board = Image.new('RGB', (2048, 610), '#20252b')
draw = ImageDraw.Draw(board)
for i, material in enumerate(materials):
    call('history.restore', {'name':'original'})
    if i == 7:
        call('import', {'path':str(ROOT/'content/models/items/edict-blade.model.json')})
    elif material != 'original':
        for cloth in [True, False]:
            ids = [e['id'] for e in p.model['elements'] if (('glove' in e.get('name','') or 'finger' in e.get('name','')) == cloth)]
            call('material.apply', {'ids':ids, 'material':'cloth.white_wool' if cloth else material, 'seed':20261023})
    result = call('board', {'views':['front','iso'], 'resolution':256, 'name':f'candidate-{i}'})
    x, y = (i % 4)*512, (i // 4)*305
    draw.text((x+10, y+8), material, fill='white')
    board.paste(Image.open(result['path']), (x, y+25))
board.save(Path(__file__).parent/'material-comparison.png')
(Path(__file__).parent/'comparison-operations.json').write_text(json.dumps(log, indent=2)+'\n')
