"""Batch C authoring; reuses forge renderer/validator without changing shared code."""
import sys,json,math,hashlib,shutil
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
FORGE=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(FORGE))
from raster import render
from validate import validate
from forge import tile,texture_map
from author import displays
ROOT=FORGE.parents[1]
PALETTE=[['10141c','202833','343f4d','566574','91a0a9'],['633408','a65d0d','d59724','f5c64e','fff0a5'],['064356','08788b','0eacc0','41d9df','b2ffff'],['21171b','44291e','75472a','a56c36','d8a45b'],['282d38','465362','718599','a5bec9','e1f5f8'],['400e20','811631','be2448','ec4164','ff9caa'],['331051','6d248d','af35d3','e866ee','ffb8ff'],['153618','316b29','59b33c','94ed61','ddffb9']]
class Prop:
 def __init__(self,item,stage):self.item=item;self.stage=stage;self.elements=[]
 def box(self,name,a,b,mat=0,angle=0,axis='z'):
  dims=np.subtract(b,a);ox=(mat%4)*32;oy=(mat//4)*64
  fs={}
  for face,axes in {'north':(0,1),'south':(0,1),'east':(2,1),'west':(2,1),'up':(0,2),'down':(0,2)}.items():
   u,v=map(float,dims[list(axes)]*2)
   if u>32 or v>64:raise ValueError((name,u,v))
   fs[face]={'texture':'#0','uv':[ox/8,oy/8,(ox+u)/8,(oy+v)/8]}
  e={'name':name,'from':a,'to':b,'faces':fs}
  if angle:e['rotation']={'axis':axis,'angle':angle,'origin':((np.array(a)+b)/2).tolist()}
  self.elements.append(e)
 def bar(self,name,c,size,mat=0,angle=0,axis='z'):
  self.box(name,(np.array(c)-np.array(size)/2).tolist(),(np.array(c)+np.array(size)/2).tolist(),mat,angle,axis)
 def cyl(self,name,c,length,r,mat=0,axis='x'):
  # Five overlapping rectangular sections trace a stepped circular cross section.
  for i,(h,w) in enumerate([(1,0.44),(.88,.70),(.70,.88),(.44,1)]):
   dims=[length,2*r*h,2*r*w]
   if axis=='y':dims=[2*r*h,length,2*r*w]
   if axis=='z':dims=[2*r*h,2*r*w,length]
   self.bar(name+f' facet {i}',c,dims,mat)
 def atlas(self):
  im=Image.new('RGBA',(128,128))
  for m,pal in enumerate(PALETTE):
   ox=m%4*32;oy=m//4*64
   if self.item=='verdict-hammer' and m==4:pal=['1c232b','29333d','394653','596975','84949e']
   for y in range(64):
    for x in range(32):
     t=2
     if self.stage>=4:
      t=3 if x%16 in (0,1) or y%16==0 else 1 if x%16>11 else 2
      if m==0:t=2 if x%12==0 else 1 if (x+y)%19 else 0
      if m==3:t=1 if x%9 in (6,7) else 3 if (x*7+y)%31==0 else 2
      if m in (2,6,7):t=4 if x%8<2 else 3 if x%8<4 else 2
      if self.stage>=5 and (x*7+y*13)%113==0:t=4
     im.putpixel((ox+x,oy+y),tuple(bytes.fromhex(pal[t]))+(255,))
  return im
 def model(self):return {'credit':'Astera / Codex gpt-6-astra / image-generated target','textures':{'0':f'astera:items/{self.item}','particle':f'astera:items/{self.item}'},'gui_light':'front','ambientocclusion':False,'elements':self.elements,'display':displays(self.item)}

def review(p,display=None,finish=False):
 work=FORGE/'work'/p.item;out=work/f'redo-{p.stage}';out.mkdir(parents=True,exist_ok=True)
 m=p.model()
 if display:m['display']=display
 atlas=p.atlas();textures={'#0':np.array(atlas),'#particle':np.array(atlas)}
 check=validate(m,textures,budget=250,density=2)
 if not check['valid']:raise ValueError(check)
 (out/'model.json').write_text(json.dumps(m,indent=2)+'\n');atlas.save(out/'texture.png')
 concept=Image.open(work/'ai-concept.png').convert('RGB');concept.thumbnail((700,700))
 sheet=Image.new('RGB',(1600,1000),'#17202c');sheet.paste(concept,(0,50));d=ImageDraw.Draw(sheet)
 d.text((16,12),f'{p.item} | round {p.stage} | AI target left / forge renders right | {len(p.elements)} elements',fill='white')
 metrics={}
 views=['front','side','top','iso','gui','firstperson_righthand','thirdperson_righthand','firstperson_lefthand','thirdperson_lefthand','ground','fixed']
 for i,v in enumerate(views):
  im,_,diag=render(m,textures,v,size=256);im.save(out/f'{v}.png');metrics[v]=diag
  x=710+(i%4)*220;y=45+(i//4)*245
  sheet.paste(tile(im).resize((215,215)),(x,y));d.text((x,y-16),v,fill='white')
 for i,size in enumerate((16,32,64)):
  im,_,_=render(m,textures,'gui',size=size);sheet.paste(tile(im).resize((128,128),Image.Resampling.NEAREST),(720+i*150,810));d.text((720+i*150,790),str(size)+' px',fill='white')
 sheet.save(out/'comparison.png')
 report={'item':p.item,'round':p.stage,'validation':check,'views':metrics,'concept_sha256':hashlib.sha256((work/'ai-concept.png').read_bytes()).hexdigest(),'comparison':'Visual comparison against image-generated turnaround; no self-derived silhouette score. Hand views are isolated orthographic display transforms, not a client simulation.'}
 (out/'review.json').write_text(json.dumps(report,indent=2)+'\n')
 print(json.dumps({'elements':len(p.elements),'valid':check['valid'],'clipped':[v for v,r in metrics.items() if r['clipped']]}))
 if finish:
  if any(r['clipped'] for r in metrics.values()):raise ValueError('Clipped view')
  shutil.copyfile(out/'model.json',ROOT/f'content/models/items/{p.item}.model.json');shutil.copyfile(out/'texture.png',ROOT/f'content/textures/items/{p.item}.png')
  shutil.copyfile(out/'comparison.png',work/'final-sheet.png')
  report['rounds']=[json.loads(q.read_text()) for q in sorted(work.glob('redo-*/review.json'))]
  report['sha256']={f:hashlib.sha256((out/f).read_bytes()).hexdigest() for f in ('model.json','texture.png')}
  (work/'final-review.json').write_text(json.dumps(report,indent=2)+'\n')

def hammer(stage):
 p=Prop('verdict-hammer',stage)
 p.bar('dark long haft',[8,10,8],[1.5,19,1.5])
 if stage==1:p.bar('head block',[8,23,8],[14,7,7])
 else:
  p.cyl('obsidian barrel',[8,23,8],13,3.75)
  for x in (1.25,14.75):p.cyl('striking cap',[x,23,8],1.0,3.25)
  for x in (2.5,13.5):p.cyl('broad golden hoop',[x,23,8],1.25,4,1)
  for x in (.75,15.25):p.cyl('outer rim',[x,23,8],.35,3.35,1)
  p.cyl('socket collar',[8,18.8,8],.75,1.65,1,'y')
  p.cyl('socket neck',[8,17.8,8],1.5,1.0,1,'y')
  p.cyl('heel collar',[8,1.4,8],1.0,1.1,1,'y')
  p.cyl('pommel',[8,.1,8],1.5,1.5,1,'y')
 if stage>=3:
  for x in (.4,15.6):p.cyl('recessed black striking face',[x,23,8],.5,2.95,0)
  for z in (4.15,11.85):
   p.cyl('gold seal',[8,23,z],.5,1.9,1,'z')
   p.cyl('teal seal',[8,23,z+(-.35 if z<8 else .35)],.4,1.5,2,'z')
   p.bar('gem table',[8,23,z+(-.6 if z<8 else .6)],[1.6,2.1,.25],2)
  for z in (6.5,9.5):p.cyl('pommel inlay',[8,.1,z],.25,.85,2,'z')
  for y in np.arange(3,17,.85):
   for z in (7.18,8.82):p.bar('diagonal leather binding',[8,float(y),z],[1.65,.25,.2],4,-22.5)
 if stage>=4:
  for x in (3.35,12.65):
   for y,z in ((26,8),(20,8),(23,4.7),(23,11.3)):
    p.bar('gold hoop rivet',[x,y,z],[.4,.4,.4],1)
  for z in (4.1,11.9):
   for x,y in ((5,23),(11,23),(8,25.6),(8,20.4)):
    p.bar('seal pin',[x,y,z],[.4,.4,.35],1)
 if stage>=5:
  for x in (.15,15.85):
   p.cyl('striking medallion',[x,23,8],.25,1.55,1,'x')
   p.cyl('end teal core',[x+(-.2 if x<8 else .2),23,8],.2,1.0,2,'x')
  for x in (6.45,9.55):p.cyl('pommel side gem',[x,.1,8],.25,.8,2,'x')
  for y in (2.2,17.2):p.cyl('collar highlight',[8,y,8],.25,1.05,1,'y')
 return p
if __name__=='__main__':review(hammer(int(sys.argv[1])),finish='--finish' in sys.argv)
