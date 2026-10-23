"""Vanilla material references; client pixels exist only in the ignored preview cache."""
import copy
import hashlib
import io
import itertools
import json
import math
import os
import re
from pathlib import Path
from zipfile import ZipFile
import numpy as np
from PIL import Image
from raster import faces, default_uv

CATALOG = json.loads(Path(__file__).with_name('materials.json').read_text())
CACHE = Path(__file__).with_name('.cache')
ANIMATED = set(json.loads(Path(__file__).with_name('animated-textures.json').read_text()))


def vanilla_ref(ref):
    if not re.fullmatch(r'minecraft:(block|item)/[a-z0-9_/-]+', ref) or '..' in ref:
        raise ValueError('Expected minecraft:block/... or minecraft:item/...: '+ref)
    if ref in ANIMATED: raise ValueError('Animated material is excluded until client-verified: '+ref)
    name = ref.split('/')[-1]
    if (any(s in name for s in ('leaves', 'grass', 'fern', 'water', 'vine', 'lily_pad', 'redstone_dust'))
            or name.startswith(('leather_', 'potion', 'tipped_arrow', 'spawn_egg', 'filled_map', 'firework_star', 'wolf_armor'))
            or name in ('sugar_cane', 'melon_stem', 'pumpkin_stem', 'attached_melon_stem', 'attached_pumpkin_stem')):
        raise ValueError('Tint-dependent material is excluded: '+ref)
    return ref


def texture_ref(model, ref, seen=()):
    if ref in seen: raise ValueError('Texture alias cycle: '+ref)
    if ref.startswith('#'):
        return texture_ref(model, model['textures'][ref[1:]], seen+(ref,))
    if ref.startswith('minecraft:'): vanilla_ref(ref)
    return ref


def vanilla_path(ref):
    vanilla_ref(ref)
    jar = Path(os.environ.get('MODEL_FORGE_CLIENT_JAR', '/tmp/astera-hud-client-26.3.jar')).expanduser()
    if not jar.is_file():
        raise ValueError(f'Vanilla preview requires a local client jar: {jar}. Set MODEL_FORGE_CLIENT_JAR; no PNGs are downloaded or shipped.')
    stat = jar.stat()
    identity = hashlib.sha256(f'{jar.resolve()}:{stat.st_size}:{stat.st_mtime_ns}'.encode()).hexdigest()[:16]
    entry = 'assets/minecraft/textures/'+ref.split(':', 1)[1]+'.png'
    out = CACHE/identity/(ref.split(':', 1)[1]+'.png')
    if not out.exists():
        with ZipFile(jar) as archive:
            if entry not in archive.namelist(): raise ValueError(f'{ref} is absent from client jar {jar}')
            if entry+'.mcmeta' in archive.namelist() and 'animation' in json.loads(archive.read(entry+'.mcmeta')):
                raise ValueError('Animated material is excluded until client-verified: '+ref)
            data = archive.read(entry)
        with Image.open(io.BytesIO(data)) as im:
            if im.size != (16, 16): raise ValueError('Material must be a static 16x16 vanilla texture: '+ref)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(data)
    return out


def apply(model, ids, material, selected_faces=None, seed=0):
    """Split oversized cuboids, preserve external faces, and crop one texel/model unit."""
    if material not in CATALOG: raise ValueError('Unknown material; choose a name from materials.json: '+material)
    if not ids or len(set(ids)) != len(ids): raise ValueError('Supply distinct element ids')
    if set(ids)-{e['id'] for e in model['elements']}: raise ValueError('Unknown material element id')
    selected = set(selected_faces) if selected_faces is not None else set(faces({'from':[0]*3,'to':[1]*3}))
    if not selected or selected-set(('up','down','north','south','east','west')): raise ValueError('Invalid material faces')
    spec = CATALOG[material]
    added = []
    used_ids = {e['id'] for e in model['elements']}
    def key_for(ref):
        vanilla_ref(ref)
        for key, value in model['textures'].items():
            if value == ref: return key
        key = 'material_'+ref.split('/')[-1]
        while key in model['textures']: key += '_'
        model['textures'][key] = ref
        return key
    for e in list(model['elements']):
        if e['id'] not in ids or not selected.intersection(e['faces']): continue
        dims = np.subtract(e['to'], e['from'])
        if np.any(dims<=0): raise ValueError('Material needs positive cuboid dimensions: '+e['id'])
        axis = int(np.argmax(dims))
        cuts = [[float(e['from'][i]+min(n*16, dims[i])) for n in range(math.ceil(dims[i]/16)+1)] for i in range(3)]
        pieces = []
        for index, cell in enumerate(itertools.product(*(range(len(c)-1) for c in cuts))):
            part = copy.deepcopy(e)
            part['from'] = [cuts[i][cell[i]] for i in range(3)]
            part['to'] = [cuts[i][cell[i]+1] for i in range(3)]
            if index:
                part['id'] = e['id']+'_tile'+str(index)
                if part['id'] in used_ids: raise ValueError('Split id collision: '+part['id'])
                used_ids.add(part['id'])
            part['faces'] = {}
            for face, f in e['faces'].items():
                points, normal = faces(e)[face]; j = next(i for i,v in enumerate(normal) if v)
                if part['to' if normal[j]>0 else 'from'][j] != e['to' if normal[j]>0 else 'from'][j]: continue
                value = copy.deepcopy(f)
                p = np.array(faces(part)[face][0]); q = np.array(points)
                edge_u, edge_v = q[1]-q[0], q[3]-q[0]
                if face in selected:
                    ref = spec['texture']
                    rotation = 0
                    if spec.get('grain') in ('u', 'v'):
                        if j == axis and 'end' in spec: ref = spec['end']
                        elif (spec['grain']=='v' and abs(edge_u[axis])>0) or (spec['grain']=='u' and abs(edge_v[axis])>0): rotation = 90
                    w, h = float(np.linalg.norm(p[1]-p[0])), float(np.linalg.norm(p[3]-p[0]))
                    if rotation: w, h = h, w
                    digest = hashlib.sha256(f'{seed}:{e["id"]}:{index}:{face}'.encode()).digest()
                    u, v = digest[0] % (math.floor(16-w+1e-8)+1), digest[1] % (math.floor(16-h+1e-8)+1)
                    value.update(texture='#'+key_for(ref), uv=[u,v,u+w,v+h], rotation=rotation)
                else:
                    # Interpolate existing (possibly mirrored/rotated) UVs onto each external patch.
                    u,v,U,V = f.get('uv', default_uv(e,face))
                    corners = np.roll(np.array([[u,v],[U,v],[U,V],[u,V]]), f.get('rotation',0)//90, axis=0)
                    fractions = np.stack(((p-q[0])@edge_u/(edge_u@edge_u), (p-q[0])@edge_v/(edge_v@edge_v)), axis=1)
                    mapped = corners[0]+fractions[:,0,None]*(corners[1]-corners[0])+fractions[:,1,None]*(corners[3]-corners[0])
                    rotation = f.get('rotation',0)
                    unrolled = np.roll(mapped, -rotation//90, axis=0)
                    value['uv'] = np.concatenate((unrolled[0],unrolled[2])).tolist()
                part['faces'][face] = value
            pieces.append(part)
        position = model['elements'].index(e)
        model['elements'][position:position+1] = pieces
        added.extend(p['id'] for p in pieces)
    return {'ids':added, 'material':material}
