"""Batch-local authoring utilities; shared model-forge modules remain unmodified."""
import sys,json,math,hashlib,shutil
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[4]
sys.path.insert(0,str(ROOT/'tools/model-forge'))
from raster import render
from validate import validate
from author import displays
from forge import tile
class Prop:
 def __init__(self,item,stage,palettes):
  self.item=item;self.stage=stage;self.els=[];self.palettes=palettes
  self.atlas=Image.new('RGBA',(128,128))
  for i,(name,ramp) in enumerate(palettes.items()):
   ox=i%4*32;oy=i//4*64
   for y in range(64):
    for x in range(32):
     t=2
     if stage>=4:
      t=3 if x%12<2 else 2 if x%12<7 else 1
      if x%12==11:t=0
      if (x*7+y*3)%47==0:t=4
      if name in ('wood','leather'):t=1 if x%5<3 else 2;t=0 if (y+x*3)%19==0 else t
     self.atlas.putpixel((ox+x,oy+y),tuple(bytes.fromhex(ramp[t]))+(255,))
 def box(self,name,c,d,mat,angle=0,axis='z'):
  c=np.array(c,float);d=np.array(d,float);a=c-d/2;b=c+d/2
  i=list(self.palettes).index(mat);ox=i%4*32;oy=i//4*64
  if self.stage>=5 and mat=='energy':ox+=4
  if self.stage>=5 and 'spiral wood' in name:ox+=3
  fs={}
  for f,axes in {'north':(0,1),'south':(0,1),'east':(2,1),'west':(2,1),'up':(0,2),'down':(0,2)}.items():
   u,v=d[list(axes)]*2
   if u>32 or v>64:raise ValueError((name,u,v))
   fs[f]={'texture':'#0','uv':[ox/8,oy/8,float((ox+u)/8),float((oy+v)/8)]}
  e={'name':name,'from':a.tolist(),'to':b.tolist(),'faces':fs}
  if angle:e['rotation']={'axis':axis,'angle':angle,'origin':c.tolist()}
  self.els.append(e)
 def band(self,y,w=2.5,h=.5,mat='gold'):
  self.box('collar', [8,y,8],[w,h,w],mat)
  if self.stage>=3:
   for x,z in [(8-w/2,8),(8+w/2,8),(8,8-w/2),(8,8+w/2)]:self.box('collar bevel',[x,y,z],[.25,h*.65,.25],mat,45,'y')
 def gem(self,c,w,h,d,mat):
  for j in range(5):
   t=(j+.5)/5;f=1-abs(2*t-1)*.7
   self.box('stepped crystal facet',[c[0],c[1]+(t-.5)*h,c[2]],[w*f,h/5+.015,d*f],mat)
 def shaft(self):
  self.box('wooden shaft',[8,10,8],[1.6 if self.stage>=4 else 1.4,20,1.6 if self.stage>=4 else 1.4],'wood')
  if self.stage>=2:
   for y in (2.5,6.5,17.5,20): self.band(y)
   self.box('leather grip',[8,4.5,8],[1.65,4,1.65],'leather')
   self.gem([8,.25,8],2.5,2.5,2.5,'gold')
  if self.stage>=3:
   for y in np.arange(7.5,17,.9):
    self.box('spiral wood ridge',[8,y,8.78],[1.55,.35,.25],'wood',-22.5)
    self.box('spiral wood return',[8,y+.45,7.22],[1.55,.35,.25],'wood',22.5)
   for y in np.arange(3,6.5,.55):self.box('raised leather wrap',[8,y,8],[1.8,.2,1.8],'leather')
 def finish(self,display=None):
  out=ROOT/f'tools/model-forge/work/{self.item}/r{self.stage}';out.mkdir(parents=True,exist_ok=True)
  m={'credit':'Astera / Codex (gpt-6-astra)','gui_light':'front','ambientocclusion':False,'textures':{'0':f'astera:items/{self.item}','particle':f'astera:items/{self.item}'},'elements':self.els,'display':displays(self.item)}
  m['display']['gui'].update(rotation=[12,-25,-35],translation=[-1.8,-2.6,0],scale=[.46]*3)
  for hand in ('righthand','lefthand'):m['display']['thirdperson_'+hand]['scale']=[.58]*3
  if display:m['display']=display
  tex={'#0':np.array(self.atlas)};v=validate(m,tex,budget=250)
  if not v['valid']:raise ValueError(v)
  (out/'model.json').write_text(json.dumps(m,indent=2)+'\n');self.atlas.save(out/'texture.png')
  concept=Image.open(out.parent/'ai-target/concept.png').convert('RGB');concept.thumbnail((1024,683))
  sheet=Image.new('RGB',(1280,1120),'#151e2a');sheet.paste(concept,(0,22));dr=ImageDraw.Draw(sheet);dr.text((8,5),f'{self.item} ROUND {self.stage}: image-generated target / model-forge renders',fill='white')
  views=['front','side','top','iso','gui','firstperson_righthand','thirdperson_righthand','firstperson_lefthand','thirdperson_lefthand','ground','fixed']
  diagnostics={}
  for i,view in enumerate(views):
   im,mask,di=render(m,tex,view,size=256,clay=self.stage<3);im.save(out/f'{view}.png');diagnostics[view]=di
   if i<4:x=i*256;y=720
   else:x=1024+(i-4)%1*128;y=22+(i-4)*150
   if i<4:sheet.paste(tile(im),(x,y));dr.text((x+5,y-15),view,fill='white')
   else:
    sheet.paste(tile(im).resize((128,128)),(x,y));dr.text((x+130,y+5),view.replace('person',''),fill='white')
  for i,size in enumerate([16,32,64]):
   im,_,_=render(m,tex,'gui',size=size);sheet.paste(tile(im).resize((96,96),Image.Resampling.NEAREST),(i*100,1000));dr.text((i*100,1098),f'{size}px',fill='white')
  sheet.save(out/'comparison.png')
  result={'item':self.item,'round':self.stage,'validation':v,'views':diagnostics,'concept_sha256':hashlib.sha256((out.parent/'ai-target/concept.png').read_bytes()).hexdigest(),'comparison':'Visual comparison to image-generated target; no code-drawn polygon IoU used.'}
  (out/'review.json').write_text(json.dumps(result,indent=2)+'\n')
  print(json.dumps({'item':self.item,'stage':self.stage,'elements':len(self.els),'valid':v['valid'],'clipped':[k for k,v in diagnostics.items() if v['clipped']]}))
  return m
PALETTE={'wood':['281a13','50321d','79502b','a77942','d0a36a'],'leather':['281612','4c261a','763d25','a85e32','ce9353'],'gold':['6b3e13','a4691c','d99c30','f4cb59','fff1a1'],'dark':['151c24','293440','465767','718493','acc3ce'],'energy':['8f180e','c8310d','f2670d','ffaf25','fff08b'],'light':['9c4010','d67a16','ffbc33','ffe16a','fff6bd'],'silver':['314450','607985','96acb4','cbdde0','f2ffff'],'accent':['481317','791e14','bb3d14','ed6b20','ffa745']}
