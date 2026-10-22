"""Code-native orthographic construction drawings, independent of model elements.
Polygon silhouettes are authored from the source sheet, not extracted from a render.
"""
import hashlib
import json
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
import yaml
from raster import VIEWS, CENTER, SPAN, display_matrix

KEY=(255,0,255)
ROOT=Path(__file__).resolve().parents[2]


def rect(x,y,X,Y): return [[x,y],[X,y],[X,Y],[x,Y]]


def design(item):
    parts=[]
    def p(poly,depth,color,z=8): parts.append({'polygon':poly,'depth':depth,'z':z,'color':color})
    if item=='verdict-hammer':
        p(rect(7.25,-1,8.75,23),1.5,'#283c44')
        p(rect(7,1,9,10),2,'#26313d')
        for y in (0,10,19.5): p(rect(6.5,y,9.5,y+.8),3,'#d8ab50')
        p([[0,21],[1,20],[15,20],[16,21],[16,25],[15,26],[1,26],[0,25]],6,'#273541')
        for x in (2,12.75): p(rect(x,19.75,x+1.25,26.25),6.5,'#e4b759')
        p([[8,20.8],[10.2,23],[8,25.2],[5.8,23]],.5,'#efc46c',11.25)
        p([[8,21.45],[9.55,23],[8,24.55],[6.45,23]],.5,'#35cabd',11.65)
    elif item=='couture-rapier':
        p(rect(7.4,0,8.6,7.5),1.5,'#233e53')
        p([[7.4,8],[8.6,8],[8.5,26],[8,29],[7.5,26]],.75,'#92f6fa')
        p([[4.5,7],[5,8.3],[8,9],[11.2,8],[11.5,6.8],[8,7.6]],2,'#d4e4ee')
        # Open knuckle bow, no filled disk hiding the grip.
        p([[4.5,7.3],[3.5,5.5],[3.5,2.5],[5.5,.3],[7.8,.3],[7.8,1.1],[5.8,1.1],[4.3,2.8],[4.3,5.3],[5.2,6.9]],1,'#c2d9e8')
        p([[8,6.3],[9.1,7.4],[8,8.5],[6.9,7.4]],2.8,'#46cedf')
        p(rect(7,-.6,9,.5),2,'#8cdef0')
    elif item=='fire-staff':
        p(rect(7.15,-1,8.85,20),1.7,'#70422b')
        p(rect(6.7,-1,9.3,.1),2.6,'#d5a14a')
        for y in (3,9,15): p(rect(6.95,y,9.05,y+.7),2.1,'#b7853f')
        p([[7.4,18],[5.1,20],[4.4,23],[5.4,22.7],[6.1,20.8],[8,19.5],[9.9,20.8],[10.8,24],[11.6,23.8],[11,20],[8.6,18]],2.3,'#b1833d')
        p([[7.8,20],[5.8,23],[6.8,26],[8.5,29],[8.8,26],[10,25],[9.8,22.5]],3,'#f17428')
        p([[8,21],[7,23],[8.3,26],[9,24],[9.1,22.8]],.3,'#ffe495',9.7)
    elif item=='bastion-greatsword':
        p(rect(7,0,9,7),2,'#302f34')
        p(rect(6.5,-1,9.5,.5),3,'#e9ac48')
        p([[4.6,9],[11.4,9],[11.4,24],[10,27],[8,29],[6,27],[4.6,24]],2.5,'#ffe194')
        p([[7.4,10],[8.6,10],[8.6,26],[8,27],[7.4,26]],2.7,'#d88d32')
        p([[2.5,6.5],[2.5,8],[5,9],[11,9],[13.5,8],[13.5,6.5],[10.5,7.5],[5.5,7.5]],3,'#d8943b')
        p([[8,7],[9.3,8.3],[8,9.6],[6.7,8.3]],3.3,'#ffca5b')
    else:
        raise ValueError('No authored turnaround for this id; supply --design JSON with polygon/depth/color parts.')
    return {'parts':parts}


def draw_view(spec,view,size=256):
    R=display_matrix(VIEWS[view]); surfaces=[]
    for part in spec['parts']:
        xy=np.array(part['polygon']);z=part.get('z',8);h=part['depth']/2
        back=np.column_stack([xy,np.full(len(xy),z-h)])
        front=np.column_stack([xy,np.full(len(xy),z+h)])
        faces=[back,front]+[np.array([back[i],back[(i+1)%len(xy)],front[(i+1)%len(xy)],front[i]]) for i in range(len(xy))]
        rgb=np.array(Image.new('RGB',(1,1),part['color']).getpixel((0,0)))
        for f in faces:
            q=(f-CENTER)@R.T
            surfaces.append((q[:,2].mean(),q,rgb))
    im=Image.new('RGB',(size,size),KEY);d=ImageDraw.Draw(im)
    for _,q,rgb in sorted(surfaces,key=lambda a:a[0]):
        pts=q[:,:2]*[size/SPAN,-size/SPAN]+size/2
        d.polygon([tuple(v) for v in pts],fill=tuple(rgb))
    return im


def generate(item,brief,out,design_path=None):
    source=ROOT/'content/items'/f'{item}.yaml'
    metadata=yaml.safe_load(source.read_text())
    if metadata['id']!=item: raise ValueError('Item id mismatch')
    spec=json.loads(Path(design_path).read_text()) if design_path else design(item)
    spec.update(item=item,brief=brief,source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                provenance='Codex-authored polygon construction drawing from existing AI sheet; no model render input',
                camera={'center':CENTER.tolist(),'span':SPAN,'views':VIEWS},key=list(KEY))
    out.mkdir(parents=True,exist_ok=True)
    target=out/'concept.json'
    if target.exists(): raise ValueError('Concept already locked; choose another --out to revise explicitly')
    target.write_text(json.dumps(spec,indent=2)+'\n')
    sheet=Image.new('RGB',(1280,310),'#171f2b');d=ImageDraw.Draw(sheet)
    for i,v in enumerate(VIEWS):
        im=draw_view(spec,v);im.save(out/f'concept-{v}.png');sheet.paste(im,(i*256,28));d.text((i*256+8,8),v,fill='white')
    icon=Image.open(ROOT/'content/textures/generated/items'/f'{item}.png').convert('RGBA')
    sheet.paste(icon.resize((128,128),Image.Resampling.NEAREST),(1056,36),icon.resize((128,128),Image.Resampling.NEAREST))
    # Exact cell layout from tools/item-art/sheets/weapons-v1.yaml.
    manifest=yaml.safe_load((ROOT/'tools/item-art/sheets/weapons-v1.yaml').read_text())
    idx=list(manifest['cells']).index(item);src=Image.open(ROOT/'content/textures/sources/sheets/weapons-v1.png')
    w,h=src.size;col,row=idx%5,idx//5
    crop=src.crop((round(col*w/5),round(row*h/5),round((col+1)*w/5),round((row+1)*h/5)))
    sheet.paste(crop.resize((112,112),Image.Resampling.NEAREST),(1056,180))
    d.text((1032,8),'existing icon / AI source',fill='white');d.text((8,292),item+' | '+metadata['material']+' | independent construction target',fill='white')
    sheet.save(out/'concept-sheet.png')
    return spec
