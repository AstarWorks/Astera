"""Publish only an already visually reviewed round; validate actual output references."""
import sys,json,shutil,hashlib
from forge_local import ROOT,validate
from forge import load_model,texture_map
item=sys.argv[1];stage=int(sys.argv[2]);w=ROOT/f'tools/model-forge/work/{item}';r=w/f'r{stage}'
report=json.loads((r/'review.json').read_text())
if any(d['clipped'] for d in report['views'].values()):raise ValueError('Clipped preview')
model=ROOT/f'content/models/items/{item}.model.json';tex=ROOT/f'content/textures/items/{item}.png'
shutil.copyfile(r/'model.json',model);shutil.copyfile(r/'texture.png',tex)
m=load_model(model);v=validate(m,texture_map(m),budget=250)
if not v['valid']:raise ValueError(v)
shutil.copyfile(r/'comparison.png',w/'final-sheet.png')
report['validation']=v;report['rounds']=[json.loads(p.read_text()) for p in sorted(w.glob('r[0-9]*/review.json'))]
report['sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (model,tex,w/'ai-target/concept.png')}
(w/'final-review.json').write_text(json.dumps(report,indent=2)+'\n');print(item,v['elements'],'valid')
