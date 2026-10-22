"""Seed hammer geometry and pixel atlas, retained and adapted for model-forge."""
import math
import numpy as np
from PIL import Image

def build():
    PALETTES = {
        'black': ['10151e','202a36','354454','536374'],
        'gold': ['694123','a66a2d','d6a448','ffe298'],
        'grip': ['12171c','242a30','384148','515b60'],
        'shaft': ['10171d','202e35','34464c','50676a'],
        'end': ['10151e','202a36','354454','536374'],
        'gem': ['064a50','087f82','25b9b2','a0f7dc'],
    }
    REGIONS = {'black':(0,0,32,16),'gold':(32,0,16,16),'grip':(48,0,8,40),
               'shaft':(56,0,8,48),'end':(0,16,16,16),'gem':(16,16,8,8)}
    atlas = Image.new('RGBA',(64,64),(16,21,30,255))
    for material,(ox,oy,w,h) in REGIONS.items():
        colors=[tuple(bytes.fromhex(c))+(255,) for c in PALETTES[material]]
        for y in range(h):
            for x in range(w):
                tone=1
                if y==0 or x==0: tone=3
                elif y==1 or x==1: tone=2
                elif y==h-1 or x==w-1: tone=0
                elif material=='gold':
                    tone=2 if x< w*.6 else 1
                    if (x+y*2)%19==0: tone=max(0,tone-1)
                elif material=='grip':
                    tone=0 if (y+x//2)%5==4 else (2 if x<3 else 1)
                elif material=='shaft':
                    tone=2 if x in (2,3) else 1
                    if (y*7+x*13)%41==0: tone=0
                elif material=='gem':
                    tone=3 if x+y<5 else 2 if x<y else 1
                    if x+y>11: tone=0
                elif (x*13+y*7)%53==0: tone=2
                atlas.putpixel((ox+x,oy+y),colors[tone])
    # Sparse deliberate bevel glints and strike-face nested engraving.
    for x,y in [(6,3),(7,3),(22,8),(23,7),(24,6)]:
        atlas.putpixel((x,y),tuple(bytes.fromhex(PALETTES['black'][2]))+(255,))
    for x in range(3,13):
        for y in range(3,13):
            if x in (3,12) or y in (3,12):
                atlas.putpixel((x,16+y),tuple(bytes.fromhex(PALETTES['end'][0]))+(255,))
    # The seal is only 4.4 texels wide: compose its facets at that actual footprint.
    for y,row in enumerate([[3,3,2,1,0],[3,2,2,1,0],[2,2,1,1,0],[2,1,1,0,0],[1,1,0,0,0]]):
        for x,tone in enumerate(row):
            atlas.putpixel((16+x,16+y),tuple(bytes.fromhex(PALETTES['gem'][tone]))+(255,))
    # A four-texel circumference face still needs a clearly readable diagonal wrap.
    for y in range(1,39):
        for x in range(4):
            phase=(y-x)%5
            tone=0 if phase==4 else 2 if phase==0 else 1
            atlas.putpixel((48+x,y),tuple(bytes.fromhex(PALETTES['grip'][tone]))+(255,))


    els=[]
    def cube(name,a,b,mat,rotation=None,overrides=None):
        dims=np.subtract(b,a)
        e={'name':name,'from':a,'to':b,'faces':{}}
        for face,axes in {'north':(0,1),'south':(0,1),'east':(2,1),'west':(2,1),'up':(0,2),'down':(0,2)}.items():
            m=(overrides or {}).get(face,mat)
            ox,oy,w,h=REGIONS[m]
            uw,vh=dims[list(axes)]*2
            assert uw<=w and vh<=h,(name,face,uw,vh,m)
            # Exact square texel density, including fractional bevel dimensions.
            e['faces'][face]={'uv':[ox/4,oy/4,(ox+uw)/4,(oy+vh)/4],'texture':'#0'}
        if rotation:e['rotation']=rotation
        els.append(e)

    def octagonal(name,x0,x1,cy,cz,r,mat,end=False):
        """Seven overlapping boxes form an exact chamfered cross-section, no outer coplanar faces."""
        c=1.0
        overrides={'east':'end','west':'end'} if end else None
        cube(name+' core',[x0,cy-r+c,cz-r],[x1,cy+r-c,cz+r],mat,overrides=overrides)
        for s in [-1,1]:
            ya,yb=sorted([cy+s*(r-c),cy+s*r])
            cube(name+(' lower' if s<0 else ' upper'),[x0,ya,cz-r+c],[x1,yb,cz+r-c],mat)
        for sy in [-1,1]:
            for sz in [-1,1]:
                y,z=cy+sy*(r-c),cz+sz*(r-c)
                d=c/math.sqrt(2)
                cube(name+f' bevel {sy} {sz}',[x0,y-d,z-d],[x1,y+d,z+d],mat,
                     {'origin':[8,y,z],'axis':'x','angle':45,'rescale':False})

    cube('Long dark haft',[7.25,0,7.25],[8.75,22,8.75],'shaft')
    cube('Wrapped lower grip',[7,1,7],[9,10,9],'grip')
    cube('Pommel gold collar',[6.75,0,6.75],[9.25,1,9.25],'gold')
    cube('Pommel obsidian heel',[7,-1,7],[9,0,9],'black')
    cube('Grip upper ferrule',[6.75,10,6.75],[9.25,10.75,9.25],'gold')
    cube('Haft gold pin',[7.75,11.5,6.99],[8.25,12,7.26],'gold')
    cube('Socket block',[6.5,18.5,6.5],[9.5,20.5,9.5],'black')
    cube('Socket gold collar',[6.25,19.5,6.25],[9.75,20.25,9.75],'gold')
    octagonal('Obsidian head',0.5,15.5,23,8,3,'black',end=True)
    for x0,x1 in [(2,3.25),(12.75,14)]:
        octagonal('Gold binding',x0,x1,23,8,3.25,'gold')
    # Slightly proud strike plates keep the head purposeful and heavy.
    for x0,x1 in [(0,0.5),(15.5,16)]:
        cube('Recessed striking plate',[x0,21,6],[x1,25,10],'end')
    # Front and rear diamond seals, nested with depth separation (no z-fighting).
    for side in [-1,1]:
        z=8+side*3.1
        cube('Gold diamond seal',[6.5,21.5,z-.25],[9.5,24.5,z+.25],'gold',
             {'origin':[8,23,z],'axis':'z','angle':45})
        z=8+side*3.45
        cube('Teal cut stone',[6.9,21.9,z-.16],[9.1,24.1,z+.16],'gem',
             {'origin':[8,23,z],'axis':'z','angle':45})
        z=8+side*3.03
        for x in [4.5,11.5]:
            cube('Gold stud',[x-.25,22.75,z-.1],[x+.25,23.25,z+.1],'gold')

    model={'credit':'Astera Verdict Hammer / authored with build_hammer.py','parent':'minecraft:item/handheld',
           'textures':{'0':'astera:items/verdict-hammer','particle':'astera:items/verdict-hammer'},
           'gui_light':'front','ambientocclusion':False,'elements':els,
           'display':{
               'thirdperson_righthand':{'rotation':[0,90,-10],'translation':[0,1.5,-2],'scale':[.8,.8,.8]},
               'thirdperson_lefthand':{'rotation':[0,-90,10],'translation':[0,1.5,-2],'scale':[.8,.8,.8]},
               'firstperson_righthand':{'rotation':[0,-80,15],'translation':[1.5,-1,-3],'scale':[.65,.65,.65]},
               'firstperson_lefthand':{'rotation':[0,80,-15],'translation':[1.5,-1,-3],'scale':[.65,.65,.65]},
               'gui':{'rotation':[16,-30,-35],'translation':[-2.55,-2.9,0],'scale':[.53,.53,.53]},
               'ground':{'rotation':[0,0,-90],'translation':[0,3,0],'scale':[.4,.4,.4]},
               'fixed':{'rotation':[0,180,-35],'translation':[1,-2,-1],'scale':[.55,.55,.55]}}}
    return model, atlas
