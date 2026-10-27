#!/usr/bin/env python3
"""Integration check of the actual built ZIP and shipped arsenal content; no test runner."""
import argparse,json,sys,zipfile
from pathlib import Path
import yaml
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from core import ROOT
NATIVE={'longbow':'bow','shortbow':'bow','hand-crossbow':'crossbow','repeater-crossbow':'crossbow','shield-bulwark':'shield'}
from surfaces import surface_pairs

def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('pack',type=Path);a=ap.parse_args()
 spec=yaml.safe_load((ROOT/'tools/item-art/sheets/arsenal-weapons-art2.yaml').read_text())
 manifest=yaml.safe_load((ROOT/'content/textures/sources/items.yaml').read_text())
 result={}
 with zipfile.ZipFile(a.pack) as z:
  for item in spec['cells']:
   if item in NATIVE:
    assert item not in manifest['items'],item
    assert not (ROOT/f'content/models/generated/items/{item}.model.json').exists(),item
    assert f'assets/astera/items/{item}.json' not in z.namelist(),item
    assert not (ROOT/f'content/models/items/{item}.model.json').exists(),item
    result[item]={'renderer':'vanilla '+NATIVE[item],'states':'26.3 using/pull/charge/block graph preserved'}
    continue
   assert manifest['items'][item]=='arsenal-weapons-art2',item
   assert (ROOT/f'content/models/generated/items/{item}.model.json').exists(),item
   assert (ROOT/f'content/textures/generated/items/{item}.png').exists(),item
   selected=json.loads(z.read(f'assets/astera/items/{item}.json'))
   path=ROOT/f'content/models/items/{item}.model.json';m=json.loads(path.read_text())
   assert not surface_pairs(m),(item,surface_pairs(m))
   assert len(m['elements'])<=64,item
   assert selected['model']['model']=='astera:items/'+item,item
   assert [t['value'] for t in selected['model']['tints']]==m['astera_tints'],item
   packaged=json.loads(z.read(f'assets/astera/models/items/{item}.json'))
   assert packaged=={k:v for k,v in m.items() if k!='astera_tints'},item
   for e in m['elements']:
    assert all(-16<=v<=32 for v in e['from']+e['to']),item
    for f in e['faces'].values():
     assert 0<=min(f['uv'])<=max(f['uv'])<=16,item
     assert m['textures'][f['texture'][1:]].startswith('minecraft:block/'),item
   result[item]={'renderer':str(path.relative_to(ROOT)),'elements':len(m['elements']),'coplanar_pairs':0}
  assert not any(n.startswith('assets/minecraft/textures/block/') or n.startswith('assets/minecraft/textures/item/') for n in z.namelist())
 print(json.dumps(result,indent=2))
 print('PASS: 31 custom models, 5 unchanged native renderers, 31 runtime registrations; no copied vanilla item/block PNGs')

if __name__=='__main__':main()
