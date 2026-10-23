"""Dependency-free validation of the bundled JSON Schema vocabulary + art checks."""
import json
import math
import re
from pathlib import Path
import numpy as np
from raster import faces, element_points, default_uv
from materials import texture_ref


def schema_errors(value,s,path='$'):
    errors=[]
    typ=s.get('type')
    ok={'object':isinstance(value,dict),'array':isinstance(value,list),'string':isinstance(value,str),
        'integer':type(value) is int, 'number':type(value) in (int,float) and math.isfinite(value),'boolean':type(value) is bool}
    if typ and not ok[typ]: return [f'{path}: expected {typ}']
    if 'enum' in s and value not in s['enum']: errors.append(f'{path}: not in {s["enum"]}')
    if isinstance(value,(int,float)) and not isinstance(value,bool):
        if not math.isfinite(value): errors.append(f'{path}: nonfinite number')
        if 'minimum' in s and value<s['minimum']: errors.append(f'{path}: below minimum')
        if 'maximum' in s and value>s['maximum']: errors.append(f'{path}: above maximum')
    if isinstance(value,str) and 'pattern' in s and not re.search(s['pattern'],value): errors.append(f'{path}: invalid format')
    if isinstance(value,list):
        if len(value)<s.get('minItems',0) or len(value)>s.get('maxItems',float('inf')): errors.append(f'{path}: invalid length')
        for i,v in enumerate(value): errors+=schema_errors(v,s.get('items',{}),f'{path}[{i}]')
    if isinstance(value,dict):
        for key in s.get('required',[]):
            if key not in value: errors.append(f'{path}: missing {key}')
        for key,v in value.items():
            child=s.get('properties',{}).get(key,s.get('additionalProperties',{}))
            if child is False: errors.append(f'{path}: unsupported property {key}')
            elif isinstance(child,dict): errors+=schema_errors(v,child,f'{path}.{key}')
    return errors


def tint_errors(model):
    table = model.get('astera_tints', [])
    if not isinstance(table, list) or any(type(c) is not int or not 0 <= c <= 0xffffff for c in table):
        return ['astera_tints must be an array of integer RGB values (0..16777215)']
    errors = []
    for i, e in enumerate(model.get('elements', [])):
        for face, f in e['faces'].items():
            index = f.get('tintindex', -1)
            if type(index) is not int or index < -1 or index >= len(table):
                errors.append(f'element {i}/{face}: tintindex must be -1 or an index into astera_tints')
    return errors


def tint_warnings(model):
    # Known dark bases only: authoring/validation must work without client pixels.
    warnings = []
    seen = set()
    for e in model.get('elements', []):
        for f in e['faces'].values():
            if f.get('tintindex', -1) < 0: continue
            try: ref = texture_ref(model, f['texture'])
            except (ValueError, KeyError): continue
            if ref in seen: continue
            seen.add(ref)
            if re.search(r'/(dark_|brown_|anvil|black_|blackstone|polished_blackstone|obsidian|crying_obsidian|coal_block|deepslate|polished_deepslate|netherite_block|gray_|stripped_dark_oak)', ref):
                warnings.append({'kind':'dark_tint_base', 'texture':ref,
                    'message':'Multiplicative tint can only darken. Try iron_block, smooth_stone, white_concrete, white_wool, quartz or calcite; keep pattern contrast.'})
    return warnings


def validate(model,textures,budget=None,density=None):
    schema=json.loads(Path(__file__).with_name('model.schema.json').read_text())
    errors=schema_errors(model,schema);warnings=[];densities=[];allpoints=[]
    if not errors: errors += tint_errors(model)
    if errors: return {'valid':False,'errors':errors,'warnings':warnings}
    warnings = tint_warnings(model)
    if budget is not None and len(model['elements'])>budget: errors.append(f'Element budget exceeded: {len(model["elements"])}/{budget}')
    for i,e in enumerate(model['elements']):
        dims=np.subtract(e['to'],e['from'])
        if (dims<=0).any(): errors.append(f'element {i}: require positive cuboid volume')
        for face,(p,_) in faces(e).items():
            allpoints.extend(element_points(e,p).tolist())
            if face not in e['faces']: continue
            f=e['faces'][face];key=f['texture']
            if key not in textures: errors.append(f'element {i}: unresolved texture {key}');continue
            uv=f.get('uv',default_uv(e,face));a=textures[key]
            if min(uv)<0 or max(uv)>16: errors.append(f'element {i}: UV outside 0..16')
            world=element_points(e,p)
            lengths=np.array([np.linalg.norm(world[1]-world[0]),np.linalg.norm(world[2]-world[1])])
            texels=np.abs(np.subtract(uv[2:],uv[:2]))*[a.shape[1]/16,a.shape[0]/16]
            if f.get('rotation',0) in (90,270): texels=texels[::-1]
            if (lengths<=0).any(): continue
            ds=texels/lengths;densities.extend(ds.tolist())
            if density is not None and not np.allclose(ds,density,rtol=.05,atol=.01): errors.append(f'element {i}/{face}: density {ds.tolist()}, expected {density}')
    bounds=np.array(allpoints)
    if (bounds<-16-1e-6).any() or (bounds>32+1e-6).any(): warnings.append('Rotated geometry extends beyond authoring coordinate box')
    for key,a in textures.items():
        if ((a[:,:,3]>0)&(a[:,:,3]<255)).any(): warnings.append(f'{key}: partial alpha preview is cutout only')
    return {'valid':not errors,'elements':len(model['elements']),'budget':budget,'errors':errors,'warnings':warnings,
            'density_range':[min(densities),max(densities)] if densities else None,
            'rotated_bounds':[bounds.min(0).tolist(),bounds.max(0).tolist()]}
