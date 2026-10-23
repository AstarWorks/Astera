#!/usr/bin/env python3
"""Assemble only the knight-sword's real-client capture inputs; no build/server/vanilla pixels."""
import argparse
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[5]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    a = parser.parse_args()
    if a.out.exists(): raise ValueError('Use a fresh resource-pack directory')
    source = ROOT/'content/models/items/knight-sword.model.json'
    model = json.loads(source.read_text())
    palette = model.pop('astera_tints', [])
    definition = {'type':'minecraft:model','model':'astera:items/knight-sword'}
    if palette: definition['tints'] = [{'type':'minecraft:constant','value':rgb} for rgb in palette]
    files = {
        'pack.mcmeta':{'pack':{'description':'Knight sword vanilla material capture','min_format':[97,0],'max_format':[97,1]}},
        'assets/astera/items/knight-sword.json':{'model':definition},
        'assets/astera/models/items/knight-sword.json':model,
        'assets/minecraft/atlases/blocks.json':{'sources':[{'type':'directory','source':'blocks','prefix':'blocks/'}]},
    }
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
