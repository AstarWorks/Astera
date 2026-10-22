"""Validate batch C's actual content assets with the authorized authoring budget."""
import sys,json,hashlib
from pathlib import Path
F=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(F))
from forge import load_model,texture_map
from validate import validate
R=F.parents[1]
items=['verdict-hammer','pistol','rifle','rtm-blaster','shotgun','sigil-rifle','vendetta-rifle']
failed=False
for item in items:
 modelpath=R/f'content/models/items/{item}.model.json'
 texpath=R/f'content/textures/items/{item}.png'
 model=load_model(modelpath)
 report=validate(model,texture_map(model),budget=250,density=2)
 report['asset_sha256']={str(p.relative_to(R)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (modelpath,texpath)}
 report['texture_shape']=list(texture_map(model)['#0'].shape)
 report['required_hands_present']=all(k in model['display'] for k in ('firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand'))
 report['correct_texture_reference']=set(model['textures'].values())=={f'astera:items/{item}'}
 report['valid']=report['valid'] and report['required_hands_present'] and report['correct_texture_reference']
 (F/'work'/item/'production-validation.json').write_text(json.dumps(report,indent=2)+'\n')
 print(item,report['elements'],'PASS' if report['valid'] else report)
 failed|=not report['valid']
raise SystemExit(1 if failed else 0)
