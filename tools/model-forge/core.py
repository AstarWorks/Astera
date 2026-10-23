"""Project operations shared by CLI, HTTP and MCP. No transport-specific state."""
import base64
import fcntl
import copy
import hashlib
import io
import json
import math
import re
import uuid
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageColor
from raster import faces, element_points, default_uv
from validate import schema_errors
from materials import texture_ref, vanilla_path, apply as apply_material

ROOT = Path(__file__).resolve().parents[2]
READS = {'scene.get', 'scene.element', 'texture.get', 'texture.palette', 'texture.regions',
         'measure', 'validate', 'history.list', 'history.diff', 'render', 'board', 'compare', 'export'}
MUTATIONS = {'import', 'element.add', 'element.update', 'element.delete', 'element.duplicate',
             'element.mirror', 'group.create', 'group.move', 'texture.create', 'texture.paint',
             'material.apply', 'uv.set', 'uv.auto', 'batch', 'history.snapshot', 'history.undo', 'history.restore'}


def encoded(im):
    out = io.BytesIO(); im.save(out, format='PNG')
    return base64.b64encode(out.getvalue()).decode()


def decoded(data):
    return Image.open(io.BytesIO(base64.b64decode(data))).convert('RGBA')


def bbox(e):
    p = np.concatenate([element_points(e, p) for p, _ in faces(e).values()])
    return [p.min(0).tolist(), p.max(0).tolist()]


def difference(a, b):
    result = []
    def walk(x, y, path):
        if isinstance(x, dict) and isinstance(y, dict):
            for k in sorted(x.keys() | y.keys()):
                if k not in x: result.append({'path': path+'/'+k, 'after': y[k]})
                elif k not in y: result.append({'path': path+'/'+k, 'before': x[k]})
                else: walk(x[k], y[k], path+'/'+k)
        elif x != y:
            result.append({'path': path, 'before': x, 'after': y})
    # Texture payloads are represented by digest, keeping diffs small.
    def summarized(s):
        s = copy.deepcopy(s)
        s['images'] = {k: hashlib.sha256(v.encode()).hexdigest() for k,v in s['images'].items()}
        s['model']['elements'] = {e['id']: e for e in s['model']['elements']}
        return s
    walk(summarized(a), summarized(b), '')
    return result


class Project:
    def __init__(self, path):
        self.path = Path(path).resolve()
        self.path.mkdir(parents=True, exist_ok=True)
        self.file = self.path/'project.json'
        self.db = json.loads(self.file.read_text()) if self.file.exists() else {
            'state': {'model': {'textures': {}, 'elements': [], 'display': {}}, 'images': {}, 'groups': {}},
            'undo': [], 'snapshots': {}}

    @property
    def state(self): return self.db['state']
    @property
    def model(self): return self.state['model']
    def element(self, id):
        return next((e for e in self.model['elements'] if e['id'] == id), None) or self.fail('Unknown element: '+id)
    @staticmethod
    def fail(message): raise ValueError(message)
    def image(self, key):
        key = key.lstrip('#')
        ref = texture_ref(self.model, '#'+key)
        if ref.startswith('minecraft:'): return Image.open(vanilla_path(ref)).convert('RGBA')
        return decoded(self.state['images'][key])
    def save_image(self, key, im): self.state['images'][key.lstrip('#')] = encoded(im)
    def vanilla(self):
        m = copy.deepcopy(self.model)
        for e in m['elements']:
            e.pop('id', None); e.pop('group', None)
        return m
    def save(self):
        tmp = self.file.with_suffix('.tmp')
        tmp.write_text(json.dumps(self.db, indent=2, allow_nan=False)+'\n'); tmp.replace(self.file)
        # Canonical project.json contains textures and history atomically; these are convenience mirrors.
        (self.path/'model.json').write_text(json.dumps(self.vanilla(), indent=2)+'\n')
        (self.path/'textures').mkdir(exist_ok=True)
        for key in self.state['images']: self.image(key).save(self.path/'textures'/f'{key}.png')

    def call(self, op, args=None):
        with (self.path/'.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            if self.file.exists(): self.db=json.loads(self.file.read_text())
            return self._call(op,args)

    def _call(self, op, args=None):
        args = {} if args is None else args
        if not isinstance(args,dict): raise ValueError('Arguments must be an object')
        json.dumps(args,allow_nan=False)
        if op not in READS | MUTATIONS: raise ValueError('Unknown operation: '+op)
        previous = copy.deepcopy(self.db)
        before = copy.deepcopy(self.state)
        try:
            result = self.dispatch(op, args)
            if op in MUTATIONS:
                report = self.validate()
                if report['errors']: raise ValueError(json.dumps(report))
                if op not in {'history.snapshot', 'history.undo'}:
                    self.db['undo'].append(before)
                self.save()
                return {'result': result, 'diff': difference(before, self.state), 'validation': report}
            return result
        except Exception:
            self.db = previous
            raise

    def validate(self):
        schema = json.loads(Path(__file__).with_name('model.schema.json').read_text())
        schema['required'] = ['textures', 'elements']
        schema['properties']['elements']['minItems'] = 0
        schema['properties']['display']['required'] = []
        errors = schema_errors(self.vanilla(), schema)
        warnings = []
        if errors: return {'valid': False, 'errors': errors, 'warnings': warnings}
        for key in self.model['textures']:
            try: texture_ref(self.model, '#'+key)
            except (ValueError, KeyError) as ex: errors.append('Invalid texture '+key+': '+str(ex))
        # 26.3 bakes one atlas per cuboid model; native block/item sprites cannot mix.
        atlases = set()
        used = {f['texture'] for e in self.model['elements'] for f in e['faces'].values()}
        if 'particle' in self.model['textures']: used.add('#particle')
        for slot in used:
            try: ref = texture_ref(self.model, slot)
            except (ValueError, KeyError): continue
            if ref.startswith(('minecraft:block/', 'astera:blocks/')): atlases.add('blocks')
            if ref.startswith(('minecraft:item/', 'astera:items/', 'astera:generated/items/')): atlases.add('items')
        if len(atlases)>1:
            warnings.append({'kind':'mixed_texture_atlases', 'message':'26.3 rejects one cuboid model spanning item and block atlases; install custom atlases alongside its vanilla material (blocks/ or items/).'})
        boxes = []
        for e in self.model['elements']:
            if np.any(np.subtract(e['to'],e['from']) <= 0): errors.append(e['id']+': nonpositive volume')
            boxes.append(np.array(bbox(e)))
            for face,f in e['faces'].items():
                key = f['texture'].lstrip('#')
                try: ref = texture_ref(self.model, '#'+key)
                except (ValueError, KeyError) as ex:
                    errors.append(e['id']+': invalid texture '+str(ex)); continue
                if not ref.startswith('minecraft:') and key not in self.state['images']:
                    errors.append(e['id']+': missing texture '+key); continue
                uv = f.get('uv', default_uv(e,face))
                if min(uv)<0 or max(uv)>16: errors.append(e['id']+': UV outside 0..16'); continue
                if ref.startswith('minecraft:'): continue  # Validation/export never require or store client pixels.
                im = self.image(key); u,v,U,V = uv
                crop = im.crop((math.floor(min(u,U)*im.width/16), math.floor(min(v,V)*im.height/16),
                                math.ceil(max(u,U)*im.width/16), math.ceil(max(v,V)*im.height/16)))
                if not crop.getbbox(): warnings.append({'kind':'empty_texture', 'id':e['id'], 'face':face})
                elif np.any(np.array(crop)[:,:,3]==0): warnings.append({'kind':'partially_empty_texture', 'id':e['id'], 'face':face})
        # ponytail: conservative O(n²) broad phase; rotated pairs are explicitly approximate.
        connected = [set() for _ in boxes]
        for i,a in enumerate(boxes):
            for j in range(i):
                overlap = np.minimum(a[1],boxes[j][1])-np.maximum(a[0],boxes[j][0])
                if np.all(overlap >= -1e-6):
                    connected[i].add(j); connected[j].add(i)
                    if np.all(overlap>1e-6): warnings.append({'kind':'overlap_aabb', 'ids':[self.model['elements'][k]['id'] for k in (i,j)]})
                    elif np.count_nonzero(overlap>1e-6)==2:
                        warnings.append({'kind':'coplanar_contact_possible_zfight', 'ids':[self.model['elements'][k]['id'] for k in (i,j)]})
        remaining=set(range(len(boxes))); components=[]
        while remaining:
            todo=[remaining.pop()]; component=[]
            while todo:
                i=todo.pop(); component.append(self.model['elements'][i]['id'])
                more=connected[i]&remaining; remaining-=more; todo.extend(more)
            components.append(component)
        if len(components)>1: warnings.append({'kind':'floating_components_aabb', 'components':components})
        return {'valid':not errors, 'errors':errors, 'warnings':warnings}

    def regions(self):
        return [{'id':e['id'], 'face':face, 'texture':f['texture'], 'uv':f.get('uv',default_uv(e,face))}
                for e in self.model['elements'] for face,f in e['faces'].items()]

    def dispatch(self, op, a):
        if op == 'import':
            path=Path(a['path']).resolve(); m=json.loads(path.read_text())
            if 'parent' in m:
                from recipe import load_model
                m=load_model(path)
            images={}
            def resolve(ref, seen=()):
                if ref in seen: raise ValueError('Texture alias cycle')
                if ref.startswith('#'): return resolve(m['textures'][ref[1:]], seen+(ref,))
                if ref.startswith('minecraft:'):
                    texture_ref(m, ref)
                    return None
                override=a.get('textures',{}).get(ref)
                if override: p=Path(override)
                elif ref.startswith('astera:'): p=ROOT/'content/textures'/(ref.split(':',1)[1]+'.png')
                else: p=path.parent/(ref+'.png')
                return encoded(Image.open(p).convert('RGBA'))
            for key,ref in m.get('textures',{}).items():
                if not re.fullmatch(r'[a-zA-Z0-9_]+',key): raise ValueError('Invalid texture key')
                pixels=resolve(ref)
                if pixels is not None: images[key]=pixels
            for i,e in enumerate(m.get('elements',[])): e['id']=f'e{i+1:04d}'
            self.db['state']={'model':m,'images':images,'groups':{}}
            return {'elements':len(m['elements'])}
        if op == 'scene.element': return copy.deepcopy(self.element(a['id']))
        if op == 'scene.get':
            fields=a.get('fields',['id','name','group','from','to','rotation','bbox'])
            root=a.get('root'); groups={root}
            while True:
                children={k for k,v in self.state['groups'].items() if v.get('parent') in groups}
                if children<=groups: break
                groups |= children
            rows=[]
            for e in self.model['elements']:
                if root and e.get('group') not in groups and e['id']!=root: continue
                row={**e,'bbox':bbox(e)}; rows.append({k:row.get(k) for k in fields})
            return {'elements':rows,'groups':self.state['groups'],'textures':copy.deepcopy(self.model['textures'])}
        if op == 'batch':
            results=[]
            for item in a['ops']:
                if item['op'] not in MUTATIONS-{'batch','import','history.snapshot','history.undo','history.restore'}:
                    raise ValueError('Batch supports geometry/texture edits only')
                results.append(self.dispatch(item['op'],item.get('args',{})))
            return results
        if op.startswith('element.'):
            action=op.split('.')[1]
            if action=='add':
                e=copy.deepcopy(a['element']); e['id']=a.get('id','e'+uuid.uuid4().hex[:10])
                if any(x['id']==e['id'] for x in self.model['elements']): raise ValueError('Duplicate id')
                self.model['elements'].append(e)
            else:
                e=self.element(a['id'])
                if action=='delete': self.model['elements'].remove(e); return {'id':a['id']}
                if action=='update':
                    if 'id' in a['changes']: raise ValueError('Stable id cannot change')
                    e.update(copy.deepcopy(a['changes']))
                if action=='duplicate':
                    e=copy.deepcopy(e); e['id']=a.get('new_id','e'+uuid.uuid4().hex[:10])
                    if any(x['id']==e['id'] for x in self.model['elements']): raise ValueError('Duplicate id')
                    offset=np.array(a.get('offset',[0,0,0])); e['from']=(np.array(e['from'])+offset).tolist(); e['to']=(np.array(e['to'])+offset).tolist()
                    if 'rotation' in e: e['rotation']['origin']=(np.array(e['rotation']['origin'])+offset).tolist()
                    self.model['elements'].append(e)
                if action=='mirror':
                    axis=a['axis']; index='xyz'.index(axis); center=a.get('origin',8)
                    old=copy.deepcopy(e)
                    e['from'][index],e['to'][index]=2*center-e['to'][index],2*center-e['from'][index]
                    if 'rotation' in e:
                        r=e['rotation']; r['origin'][index]=2*center-r['origin'][index]
                        if r['axis']!=axis: r['angle']=-r['angle']
                    pair={'x':('east','west'),'y':('up','down'),'z':('north','south')}[axis]
                    e['faces']={}
                    for face,f in old['faces'].items():
                        target=pair[1-pair.index(face)] if face in pair else face
                        points=np.array(faces(old)[face][0],dtype=float); points[:,index]=2*center-points[:,index]
                        corners=np.array(faces(e)[target][0])
                        u,v,U,V=f.get('uv',default_uv(old,face))
                        old_uv=np.roll(np.array([[u,v],[U,v],[U,V],[u,V]]),f.get('rotation',0)//90,axis=0)
                        mapped=np.array([old_uv[np.argmin(np.linalg.norm(points-point,axis=1))] for point in corners])
                        value=copy.deepcopy(f)
                        for uv in ([u,v,U,V],[U,v,u,V],[u,V,U,v],[U,V,u,v]):
                            A,B,C,D=uv; candidate=np.array([[A,B],[C,B],[C,D],[A,D]])
                            for quarter in range(4):
                                if np.allclose(np.roll(candidate,quarter,axis=0),mapped):
                                    value.update(uv=uv,rotation=quarter*90)
                        if value.get('cullface') in pair: value['cullface']=pair[1-pair.index(value['cullface'])]
                        e['faces'][target]=value
            if e.get('group') and e['group'] not in self.state['groups']: raise ValueError('Unknown group')
            return {'id':e['id']}
        if op=='group.create':
            id=a.get('id','g'+uuid.uuid4().hex[:10])
            if id in self.state['groups']: raise ValueError('Duplicate group')
            if a.get('parent') and a['parent'] not in self.state['groups']: raise ValueError('Unknown parent')
            self.state['groups'][id]={'name':a['name'],'origin':a.get('origin',[8,8,8]),'parent':a.get('parent')}
            return {'id':id}
        if op=='group.move':
            parent=a.get('parent')
            if parent and parent not in self.state['groups']: raise ValueError('Unknown parent')
            for id in a['ids']:
                if id in self.state['groups']:
                    ancestor=parent
                    while ancestor:
                        if ancestor==id: raise ValueError('Group cycle')
                        ancestor=self.state['groups'][ancestor].get('parent')
                    self.state['groups'][id]['parent']=parent
                else: self.element(id)['group']=parent
            return {'ids':a['ids']}
        if op=='texture.create':
            key=a['key']
            if not re.fullmatch(r'[a-zA-Z0-9_]+',key): raise ValueError('Invalid texture key')
            if key in self.model['textures']: raise ValueError('Texture already exists')
            size=a.get('size',[64,64])
            if len(size)!=2 or not all(type(n)==int and 1<=n<=2048 for n in size): raise ValueError('Texture size 1..2048')
            self.save_image(key,Image.new('RGBA',tuple(size),a.get('color','#00000000')))
            self.model['textures'][key]='textures/'+key
            return {'key':key}
        if op=='texture.paint':
            key=a['key']
            if key not in self.state['images']: raise ValueError('Vanilla materials are read-only; create a custom atlas for emblems/runes')
            im=self.image(key); d=ImageDraw.Draw(im)
            for p in a['ops']:
                kind=p['type']; color=p.get('color','#ffffffff')
                coords=([p['box'][:2],p['box'][2:]] if 'box' in p else
                        p['points'] if 'points' in p else [p['point']] if 'point' in p else
                        [pixel[:2] for pixel in p.get('pixels',[])])
                if any(len(point)!=2 or any(type(n)!=int for n in point) or not (0<=point[0]<im.width and 0<=point[1]<im.height) for point in coords):
                    raise ValueError('Paint coordinates must be integer pixels inside the atlas')
                if 'box' in p and (len(p['box'])!=4 or p['box'][0]>p['box'][2] or p['box'][1]>p['box'][3]): raise ValueError('Invalid paint box')
                if 'width' in p and (type(p['width'])!=int or not 1<=p['width']<=2048): raise ValueError('Invalid brush width')
                if 'steps' in p and (type(p['steps'])!=int or not 2<=p['steps']<=256): raise ValueError('Gradient steps must be 2..256')
                if kind=='rect': d.rectangle(p['box'],fill=color)
                elif kind=='line': d.line(p['points'],fill=color,width=p.get('width',1))
                elif kind=='fill': ImageDraw.floodfill(im,tuple(p['point']),ImageColor.getcolor(color,'RGBA'),thresh=0)
                elif kind=='pixels':
                    for x,y,c in p['pixels']: im.putpixel((x,y),ImageColor.getcolor(c,'RGBA'))
                elif kind=='gradient-ramp':
                    x,y,X,Y=p['box']; c0=np.array(ImageColor.getcolor(p['colors'][0],'RGBA')); c1=np.array(ImageColor.getcolor(p['colors'][1],'RGBA'))
                    for xx in range(x,X+1):
                        t=(xx-x)/max(1,X-x); t=round(t*(p.get('steps',8)-1))/max(1,p.get('steps',8)-1)
                        d.line([(xx,y),(xx,Y)],fill=tuple(np.rint(c0*(1-t)+c1*t).astype(int)))
                else: raise ValueError('Unknown paint operation '+kind)
            self.save_image(key,im); return {'key':key}
        if op=='texture.get':
            ref=texture_ref(self.model, '#'+a['key'].lstrip('#'))
            if ref.startswith('minecraft:'): return {'path':str(vanilla_path(ref)), 'read_only':True}
            return self.artifact(self.image(a['key']),a.get('name','texture'),a)
        if op=='texture.palette':
            colors=self.image(a['key']).getcolors(2048*2048)
            return {'colors':[{'rgba':list(c),'count':n} for n,c in sorted(colors,reverse=True)[:a.get('limit',32)]]}
        if op=='texture.regions': return {'regions':self.regions()}
        if op=='material.apply':
            return apply_material(self.model, a['ids'], a['material'], a.get('faces'), a.get('seed',0))
        if op=='uv.set':
            self.element(a['id'])['faces'][a['face']]=copy.deepcopy(a['value']); return {'id':a['id']}
        if op=='uv.auto':
            key=a['key']
            if key not in self.state['images']: raise ValueError('uv.auto requires a custom atlas; use material.apply for vanilla')
            im=self.image(key); density=float(a.get('density',2)); x=y=row=0; packed=[]
            if a.get('ids') and any(r['texture']=='#'+key and r['id'] not in a['ids'] for r in self.regions()):
                raise ValueError('Selected UV packing needs a dedicated texture; otherwise repack all faces')
            if density<=0: raise ValueError('Density must be positive')
            for e in self.model['elements']:
                if a.get('ids') and e['id'] not in a['ids']: continue
                for face,f in e['faces'].items():
                    points=element_points(e,faces(e)[face][0]); w,h=[max(1,math.ceil(np.linalg.norm(points[j+1]-points[j])*density)) for j in (0,1)]
                    if w>im.width: raise ValueError('Atlas full; use a larger texture/lower density')
                    if x+w>im.width: x=0; y+=row+1; row=0
                    if y+h>im.height: raise ValueError('Atlas full; use a larger texture/lower density')
                    f.update(texture='#'+key,uv=[x*16/im.width,y*16/im.height,(x+w)*16/im.width,(y+h)*16/im.height],rotation=0)
                    packed.append({'id':e['id'],'face':face,'uv':f['uv']}); x+=w+1; row=max(row,h)
            return {'packed':packed,'note':'Repacking changes UVs; paint atlas after packing. Selected faces pack from origin.'}
        if op=='validate': return self.validate()
        if op=='measure':
            parts=[]; densities=[]
            for e in self.model['elements']:
                b=bbox(e); dims=np.subtract(e['to'],e['from']); parts.append({'id':e['id'],'bbox':b,'lengths':dims.tolist(),'transformed_edge_lengths':[float(np.linalg.norm(element_points(e,[e['from'],(np.array(e['from'])+np.eye(3)[i]*dims[i]).tolist()])[1]-element_points(e,[e['from']])[0])) for i in range(3)],'ratios_to_longest':(dims/max(dims)).tolist()})
                for face,f in e['faces'].items():
                    p=element_points(e,faces(e)[face][0]); ref=texture_ref(self.model,f['texture']); size=(16,16) if ref.startswith('minecraft:') else self.image(f['texture']).size; uv=f.get('uv',default_uv(e,face)); tex=np.abs(np.subtract(uv[2:],uv[:2]))*np.array(size)/16
                    if f.get('rotation',0) in (90,270): tex=tex[::-1]
                    densities.append({'id':e['id'],'face':face,'texels_per_unit':(tex/[np.linalg.norm(p[1]-p[0]),np.linalg.norm(p[2]-p[1])]).tolist()})
            bounds=np.array([p['bbox'] for p in parts])
            return {'element_count':len(parts),'bbox':[bounds[:,0].min(0).tolist(),bounds[:,1].max(0).tolist()] if parts else None,'parts':parts,'density':densities}
        if op=='history.snapshot':
            if a['name'] in self.db['snapshots']: raise ValueError('Snapshot already exists; choose a new name')
            self.db['snapshots'][a['name']]=copy.deepcopy(self.state); return {'name':a['name']}
        if op=='history.list': return {'snapshots':list(self.db['snapshots']),'undo_depth':len(self.db['undo'])}
        if op=='history.undo':
            if not self.db['undo']: raise ValueError('Nothing to undo')
            self.db['state']=self.db['undo'].pop(); return {'undone':True}
        if op=='history.restore': self.db['state']=copy.deepcopy(self.db['snapshots'][a['name']]); return {'restored':a['name']}
        if op=='history.diff':
            def state(name): return self.state if name=='current' else self.db['snapshots'][name]
            return {'diff':difference(state(a['a']),state(a['b']))}
        if op in {'render','board','compare'}:
            from preview import preview
            return preview(self,op,a)
        if op=='export':
            out=Path(a['path']).resolve(); out.mkdir(parents=True,exist_ok=True)
            m=self.vanilla(); (out/'textures').mkdir(exist_ok=True)
            for key in self.state['images']:
                self.image(key).save(out/'textures'/f'{key}.png'); m['textures'][key]='textures/'+key
            path=out/'model.json'; path.write_text(json.dumps(m,indent=2)+'\n')
            return {'path':str(path),'note':'Place PNGs at your resource-pack texture namespace paths when installing.'}
        raise ValueError('Unsupported operation '+op)

    def artifact(self, im, name, args):
        if not re.fullmatch(r'[a-zA-Z0-9_-]+',name): raise ValueError('Invalid output name')
        out=self.path/'renders'; out.mkdir(exist_ok=True); path=out/(name+'.png'); im.save(path)
        return {'path':str(path), **({'base64':encoded(im)} if args.get('base64') else {})}
