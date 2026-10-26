#!/usr/bin/env python3
"""Assemble only couture-rapier's real-client capture inputs; no build/server/vanilla pixels."""
import argparse
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[4]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    a = parser.parse_args()
    if a.out.exists(): raise ValueError('Use a fresh resource-pack directory')
    files = {
        'pack.mcmeta':{'pack':{'description':'Open-angle capture','min_format':[97,0],'max_format':[97,1]}},
        'assets/minecraft/atlases/blocks.json':{'sources':[{'type':'directory','source':'blocks','prefix':'blocks/'}]},
    }
    for name in ['couture-rapier']+[f'couture-rapier-open-{t}' for t in (15,30,45,60)]:
        model = json.loads((ROOT/f'content/models/items/{name}.model.json').read_text())
        tints = model.pop('astera_tints')
        files[f'assets/astera/items/{name}.json'] = {'model':{'type':'minecraft:model','model':f'astera:items/{name}',
                  'tints':[{'type':'minecraft:constant','value':rgb} for rgb in tints]}}
        files[f'assets/astera/models/items/{name}.json'] = model
    for name, data in files.items():
        path = a.out/name; path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(json.dumps(data,indent=2)+'\n')
    for ref in set(model['textures'].values()):
        if not ref.startswith('astera:'): continue
        rel = ref.split(':',1)[1]+'.png'
        path = a.out/'assets/astera/textures'/rel; path.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(ROOT/'content/textures'/rel,path)
    print(a.out.resolve())

if __name__=='__main__': main()
