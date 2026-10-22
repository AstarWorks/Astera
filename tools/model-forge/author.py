"""Four explicit prop recipes, staged for visual review; no text-to-3D service."""
import json
import numpy as np
from PIL import Image
import hammer

PALETTES={
 'dark':['19232e','303e4d','4a5c6c','8296a3'],
 'silver':['526879','8ca8b9','c9dde5','f0fcff'],
 'cyan':['17718d','32b2cf','7ee5ec','d8ffff'],
 'gold':['986029','c98f38','efc862','fff0aa'],
 'orange':['8e3b21','c46a29','eb9c3b','ffd477'],
 'wood':['362328','663d2b','92613a','c08a47'],
 'fire':['b52d22','ec5824','ff9d36','ffedab'],
 'white':['b7cdd0','def7ee','f9f9ce','ffffff']}
REGIONS={name:(i%4*32,i//4*64) for i,name in enumerate(PALETTES)}


def atlas_image(textured,polish=False):
    im=Image.new('RGBA',(128,128))
    for material,(ox,oy) in REGIONS.items():
        colors=[tuple(bytes.fromhex(c))+(255,) for c in PALETTES[material]]
        for y in range(64):
            for x in range(32):
                t=2
                if textured:
                    t=3 if x==0 or y==0 else 0 if x%12==11 else 2 if x%12<5 else 1
                    if material=='dark': t=0 if (y-x//2)%6==5 else 2 if x%6==0 else 1
                    if material=='wood': t=2 if x%5==1 else 1; t=0 if (y+3*x)%29==0 else t
                    if material=='fire':
                        t=(3 if x%12==0 else 2 if x%12<3 else 1 if x%12<6 else 0) if polish else (3 if x%12<3 else 2 if x%12<7 else 1)
                im.putpixel((ox+x,oy+y),colors[t])
    return im


def displays(item):
    # Model-space grip at y~4; translations bring that grip toward display origin.
    scale=.65 if item=='verdict-hammer' else .7
    return {
      'gui':{'rotation':[16,-30,-35],'translation':[-2.35,-3.1,0],'scale':[.48]*3},
      'firstperson_righthand':{'rotation':[0,-35,12],'translation':[1,-1,-3],'scale':[scale]*3},
      'firstperson_lefthand':{'rotation':[0,35,-12],'translation':[1,-1,-3],'scale':[scale]*3},
      'thirdperson_righthand':{'rotation':[0,65,-10],'translation':[0,2.5,0],'scale':[.65]*3},
      'thirdperson_lefthand':{'rotation':[0,-65,10],'translation':[0,2.5,0],'scale':[.65]*3},
      'ground':{'rotation':[0,0,0],'translation':[0,3,0],'scale':[.3]*3},
      'fixed':{'rotation':[0,180,0],'translation':[0,-3,0],'scale':[.6]*3}}


def build(item,stage,out):
    out.mkdir(parents=True,exist_ok=True)
    els=[]
    def cube(name,a,b,mat,angle=0,axis='z',origin=None):
        dims=np.subtract(b,a);ox,oy=REGIONS[mat];f={}
        for face,axes in {'north':(0,1),'south':(0,1),'east':(2,1),'west':(2,1),'up':(0,2),'down':(0,2)}.items():
            u,v=dims[list(axes)]*2
            if u>32 or v>64: raise ValueError((name,'trim overflow',u,v))
            f[face]={'texture':'#0','uv':[ox/8,oy/8,(ox+u)/8,(oy+v)/8]}
        e={'name':name,'from':a,'to':b,'faces':f}
        if angle: e['rotation']={'axis':axis,'angle':angle,'origin':origin or ((np.array(a)+b)/2).tolist()}
        els.append(e)
    def rod(name,x,y,w,h,z,depth,mat,angle=0):
        cube(name,[x-w/2,y,z-depth/2],[x+w/2,y+h,z+depth/2],mat,angle)
    if item=='verdict-hammer':
        m,atlas=hammer.build()
        if stage==1:
            cube('block haft',[7,0,7],[9,21,9],'dark');cube('block head',[1,20,5],[15,25,11],'dark')
            m['elements']=els;atlas=atlas_image(False)
        elif stage==2:
            m['elements']=[e for e in m['elements'] if not any(t in e['name'] for t in ['seal','stone','stud','pin'])]
        if stage<4 and stage!=1:
            atlas=Image.new('RGBA',(64,64),(162,179,190,255))
    else:
        atlas=atlas_image(stage>=4,stage>=5)
        if item=='couture-rapier':
            rod('midnight grip',8,0,1.2,7.5,8,1.5,'dark')
            rod('cyan needle',8,8,1.2 if stage<4 else 1.4,18,8,.75,'cyan')
            rod('needle point',8,26,.5,3,8,.5,'silver')
            rod('silver crossguard',8,7,7,1,8,2,'silver')
            if stage>=2:
                rod('bow upright',3.9,2.5,.7,3.2,8,1,'silver')
                rod('bow upper sweep',4.6,5.3,.7,2,8,1,'silver',-22.5)
                rod('bow lower sweep',4.9,.6,.7,2.8,8,1,'silver',-45 if stage==2 else 45)
                rod('bow heel',6.7,.25,2.8,.75,8,1,'silver')
                rod('guard left swept',5.8,7.7,3.3,.7,8,2,'silver',22.5)
                rod('guard right swept',9.9,7.7,3.3,.7,8,2,'silver',-22.5)
            if stage>=3:
                rod('gem bezel',8,6.55,1.8,1.8,9,1,'silver',45)
                rod('cyan hilt stone',8,6.8,1.3,1.3,9.6,.4,'cyan',45)
                rod('pommel',8,-.6,2,1.1,8,2,'silver')
                rod('pommel stone',8,-.35,1,.6,9.1,.3,'cyan')
                for y in (1.5,3.5,5.5): rod('grip silver stitch',8,y,1.25,.25,8,1.55,'silver')
        elif item=='fire-staff':
            rod('wood shaft',8,-1,1.7 if stage<4 else 1.95,21,8,1.7 if stage<4 else 1.95,'wood')
            rod('ember crystal heart',8,21 if stage<3 else 22,3 if stage<3 else 3.4,5 if stage<3 else 3.4,8,3,'fire',0 if stage==1 else -22.5 if stage==2 else 45)
            if stage>=2:
                rod('crystal rising tip',8.1,25,1.6,3.7,8,1.5 if stage<4 else 2.6,'fire',-22.5)
                rod('crystal left facet',6.7,22,1.3,3,8,2,'orange',22.5)
                rod('fork left root',6.8,18.5,1,3.3,8,2,'wood',45)
                rod('fork right root',9.3,18.5,1,3.3,8,2,'wood',-45)
                rod('fork left tine',5.2,20.6,.85,2.7,8,1.7,'gold',22.5)
                rod('fork right tine',10.6,20.6,.85,3.5,8,1.7,'gold',-22.5)
            if stage>=3:
                rod('crystal bright core',8,21.4 if stage<5 else 22.1,1.2 if stage<5 else .65,3.5 if stage<5 else 2.6,9.55,.35,'white',-22.5)
                rod('crystal amber face',7,22.1,.7,2.8,9.6,.25,'gold' if stage<5 else 'fire',-22.5)
                rod('foot ferrule',8,-1,2.6,1.1,8,2.6,'gold')
                for y in (3,9,15,18): rod('warm binding',8,y,2.1,.7,8,2.1,'gold')
        elif item=='bastion-greatsword':
            rod('dark grip',8,0,2,7,8,2,'dark')
            rod('gold blade body',8,9,5 if stage==1 else 6.8,15,8,2.5,'gold')
            rod('orange crossguard',8,7,11,1.3 if stage==1 else 2,8,3,'orange')
            if stage>=2:
                rod('upper blade',8,24,4 if stage<4 else 4.8,2,8,2.5 if stage<4 else 2.48,'gold')
                rod('spear crown',8,25,2.8 if stage==2 else 3.5,2.8 if stage==2 else 3.5,8,2.5 if stage<4 else 2.46,'gold',45)
                for x,ang in ((4.5,22.5),(11.5,-22.5)):
                    rod('guard swept arm',x,7.2,3.8,1,8,3,'orange',ang)
            if stage>=3:
                rod('left shoulder bevel',5.8,23.7,.85,3.2,8,2.46,'gold',-22.5)
                rod('right shoulder bevel',10.2,23.7,.85,3.2,8,2.46,'gold',22.5)
                for z in (6.65,9.35):
                    rod('orange recessed fuller',8,10,1.2,15.5,z,.15,'orange')
                    rod('blade edge left',4.8,9,.4,15,z,.15,'white')
                    rod('blade edge right',11.2,9,.4,15,z,.15,'white')
                rod('guard central crest',8,7.25,1.8,1.8,9.6,.6,'gold',45)
                rod('pommel',8,-1,3,1.5,8,3,'gold')
                for y in (1,3,5): rod('grip wrap',8,y,2.1,.4,8,2.1,'orange')
        else: raise ValueError('Unknown recipe')
        m={'credit':'Astera / Codex model-forge','textures':{'0':f'astera:items/{item}','particle':f'astera:items/{item}'},'elements':els}
    # Final polish: keep the grip-side face quieter and inset overlapping bevels.
    if stage>=5:
        if item=='verdict-hammer':
            # Seed seal atlas already includes teal; add a small pommel inlay without new texture.
            m['elements'].append({'name':'Pommel teal inlay','from':[7.65,-.8,9.01],'to':[8.35,-.1,9.15],
                'faces':{'south':{'texture':'#0','uv':[4,4,4.35,4.35]}}})
        if item=='couture-rapier':
            # Suppress the flat beam under the swept quillons where fully overlapped.
            for e in m['elements']:
                if e['name']=='silver crossguard':
                    e['from'][0]=5; e['to'][0]=11
                    for f in ('north','south','up','down'):
                        uv=e['faces'][f]['uv']; uv[2]=uv[0]+12/8
    m.pop('parent',None)
    m.update(gui_light='front',ambientocclusion=False,display=displays(item))
    if stage>=4 and item=='bastion-greatsword':
        for view in ('thirdperson_righthand','thirdperson_lefthand'):
            m['display'][view]['scale']=[.6]*3
    (out/'model.json').write_text(json.dumps(m,indent=2)+'\n');atlas.save(out/'texture.png')
    return m,atlas
