"""Item-local recipe and batch preview helpers; shared forge modules remain untouched."""
import sys,json,math,hashlib,shutil
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[4]
sys.path.insert(0,str(ROOT/'tools/model-forge'))
from author import displays
from forge import texture_map,tile
from raster import render,VIEWS
from validate import validate
PALETTE={
 'steel':['354759','758b9e','b4c9d7','d9e7ed','f5ffff'],
 'cyan':['004e79','008fba','00c8ee','65edff','d3ffff'],
 'dark':['091222','142542','25436b','3a6191','6788ad'],
 'gold':['6b300d','ad6018','e9a52d','ffce58','fff0a2'],
 'red':['350d17','6d1525','ac2133','e84245','ff9383'],
 'leather':['211919','453026','72503a','a17a51','ceb080'],
 'edge':['354554','617583','a0b4bc','d0e1e7','ffffff'],
 'black':['10151a','222c34','3c4953','61727c','99aab2']}
REGIONS={n:(i%4*32,i//4*64) for i,n in enumerate(PALETTE)}
class Prop:
 def __init__(self,item,stage):self.item=item;self.stage=stage;self.elements=[]
 def box(self,n,x,y,z,w,h,d,mat,angle=0,axis='z'):
  a=[x-w/2,y-h/2,z-d/2];b=[x+w/2,y+h/2,z+d/2];ox,oy=REGIONS[mat];f={}
  for face,sz in {'north':(w,h),'south':(w,h),'east':(d,h),'west':(d,h),'up':(w,d),'down':(w,d)}.items():
   u,v=[q*3 for q in sz]
   if u>32 or v>64:raise ValueError((n,sz))
   f[face]={'texture':'#0','uv':[ox/8,oy/8,(ox+u)/8,(oy+v)/8]}
  e={'name':n,'from':a,'to':b,'faces':f}
  if angle:e['rotation']={'origin':[x,y,z],'axis':axis,'angle':angle}
  self.elements.append(e)
 def line(self,n,a,b,z,t,d,mat):
  x,y=(a[0]+b[0])/2,(a[1]+b[1])/2;dx,dy=b[0]-a[0],b[1]-a[1]
  angle=math.degrees(math.atan2(dy,dx));length=math.hypot(dx,dy)+t*.35
  while angle>90:angle-=180
  while angle<-90:angle+=180
  if abs(angle)>45:self.box(n,x,y,z,t,length,d,mat,round((angle-90 if angle>0 else angle+90)/22.5)*22.5)
  else:self.box(n,x,y,z,length,t,d,mat,round(angle/22.5)*22.5)
 def loop(self,n,cx,cy,rx,ry,z,mat,t=.5,count=16):
  points=[(cx+rx*math.cos(i*math.tau/count),cy+ry*math.sin(i*math.tau/count)) for i in range(count)]
  for i in range(count):self.line(n+str(i),points[i],points[(i+1)%count],z,t,.55,mat)
 def gem(self,n,x,y,z,size,mat):
  self.box(n+' seat',x,y,z,size*1.18,size*1.18,.5,'steel',45)
  self.box(n+' crown',x,y,z+.32,size,size,.45,mat,45)
  for side in (-1,1):self.box(n+' facet',x+side*size*.34,y,z+.57,size*.28,size*.65,.12,mat,45)
  self.box(n+' glint',x-size*.12,y+size*.23,z+.65,size*.2,size*.2,.08,'edge',45)
 def atlas(self):
  im=Image.new('RGBA',(128,128))
  for mat,(ox,oy) in REGIONS.items():
   ramp=[tuple(bytes.fromhex(v))+(255,) for v in PALETTE[mat]]
   for y in range(64):
    for x in range(32):
     t=2
     if self.stage>=4:
      t=4 if x%12==0 else 3 if x%12<3 else 2 if x%12<7 else 1
      if mat in ('dark','leather','black'):t=1 if (y+x//2)%7<5 else 2
      if mat in ('steel','cyan','edge'):
       t=2 if x<3 else 3 if x<7 else 1 if x<11 else 2
      if mat in ('leather','black') and (x*7+y*13)%97==0:t=0
      if self.stage>=5 and x>6 and (x+3*y)%59==0:t=4
     im.putpixel((ox+x,oy+y),ramp[t])
  return im
 def save(self):
  work=ROOT/'tools/model-forge/work'/self.item;out=work/f'redo-{self.stage}';out.mkdir(parents=True,exist_ok=True)
  m={'credit':'Astera / Codex (gpt-6-astra)','textures':{'0':f'astera:items/{self.item}','particle':f'astera:items/{self.item}'},'elements':self.elements,'display':displays(self.item),'gui_light':'front','ambientocclusion':False}
  for v in ('thirdperson_righthand','thirdperson_lefthand'):m['display'][v]['scale']=[.59]*3
  if self.item=='dagger':
   m['display']['gui']['translation']=[-1,-1,0];m['display']['gui']['scale']=[.73]*3
  (out/'model.json').write_text(json.dumps(m,indent=2)+'\n');self.atlas().save(out/'texture.png')
  textures=texture_map(m,out/'texture.png');check=validate(m,textures,budget=250,density=3)
  if not check['valid']:raise ValueError(check)
  # AI reference alongside direct model-forge raster views. No code-drawn target or self-IoU.
  sheet=Image.new('RGB',(1536,1050),'#16202c');draw=ImageDraw.Draw(sheet)
  target=Image.open(work/'ai-target.png').convert('RGB');target.thumbnail((640,940));sheet.paste(target,(0,40))
  draw.text((12,12),f'{self.item} | AI TARGET   /   ROUND {self.stage} | {len(self.elements)} elements',fill='white')
  metrics={}
  views=list(VIEWS)+['gui','firstperson_righthand','thirdperson_righthand','firstperson_lefthand','thirdperson_lefthand','ground','fixed']
  for i,v in enumerate(views):
   im,mask,diag=render(m,textures,v,size=224);im.save(out/f'{v}.png');metrics[v]=diag
   x=650+(i%4)*220;y=40+(i//4)*250;sheet.paste(tile(im).resize((214,214)),(x,y));draw.text((x,y-16),v,fill='white')
  for j,size in enumerate((16,32,64)):
   im,_,_=render(m,textures,'gui',size=size);sheet.paste(tile(im).resize((128,128),Image.Resampling.NEAREST),(660+j*150,825));draw.text((660+j*150,805),f'{size}px GUI',fill='white')
  sheet.save(out/'comparison.png')
  report={'item':self.item,'round':self.stage,'validation':check,'views':metrics,'target_sha256':hashlib.sha256((work/'ai-target.png').read_bytes()).hexdigest(),'comparison':'Visual comparison to image-generated target; no synthetic target IoU','limitations':'Orthographic transform previews, not in-client hand/FOV validation.'}
  (out/'review.json').write_text(json.dumps(report,indent=2)+'\n')
  print(self.item,self.stage,len(self.elements),'clipped',[v for v,d in metrics.items() if d['clipped']])
  if '--publish' in sys.argv:
   assert self.stage>=5 and not any(d['clipped'] for d in metrics.values())
   shutil.copy(out/'model.json',ROOT/f'content/models/items/{self.item}.model.json');shutil.copy(out/'texture.png',ROOT/f'content/textures/items/{self.item}.png');shutil.copy(out/'comparison.png',work/'final-sheet.png');shutil.copy(out/'review.json',work/'final-review.json')
  return out

def build(stage):
 p=Prop('couture-rapier',stage);b=p.box
 b('navy grip',8,2.5,8,1.35,5,1.5,'dark')
 b('needle',8,20,8,.85,18,.65,'cyan')
 b('ricasso',8,10.25,8,1.5,1.5,1,'steel')
 b('collar',8,10,8,2,.5,1.7,'steel')
 b('guard anchor',8,7.6,8,3,1,1.8,'steel')
 if stage>=2:
  b('continuous tang',8,7.5,8,1.1,5,1.2,'steel')
  # Four stepped bevels make the cyan needle narrow progressively.
  p.elements=[e for e in p.elements if e['name']!='needle']
  for i,(y,h,w) in enumerate([(13,5,1),(18,5,.8),(23,5,.6),(27,3,.35)]):
   b('blade taper '+str(i),8,y,8,w,h,.65,'cyan')
   b('blade edge '+str(i),8-w*.35,y,8.35,.15,h,.12,'edge')
  p.loop('upper sweep',8,7.3,3.5,2.5,7.5,'steel',.55)
  p.loop('lower knuckle scroll',9.1,4.25,1.7,2.4,8.25,'steel',.55)
  p.line('quillon left',(3.5,5.5),(7.8,7),9,.65,.8,'steel')
  p.line('quillon right',(8,7),(12.5,5.5),9,.65,.8,'steel')
  p.gem('center jewel',8,7.5,9,1.9,'cyan')
  p.gem('pommel',8,-.75,8.2,.95,'cyan')
 if stage>=3:
  p.loop('inner filigree',7.3,6.7,2.05,1.7,8.6,'steel',.3)
  p.loop('finger ring',6.75,9,1,1.25,8,'steel',.3)
  for x in (3.5,12.5):p.gem('quillon stone',x,5.5,9,.55,'cyan')
  for y in (.1,4.8):b('grip ferrule',8,y,8,1.7,.4,1.8,'steel')
  for y in np.arange(.5,4.6,.55):b('wire binding',8,float(y),8,1.45,.13,1.6,'dark',-22.5)
 if stage>=4:
  for x,y in [(6.1,7.5),(9.9,7.5),(8,9.5),(8,5.4)]:p.gem('scroll rivet',x,y,9.2,.3,'cyan')
 if stage>=5:
  for x in (7.45,8.55):b('blade socket petal',x,10.7,8,.3,1,.8,'steel',22.5 if x<8 else -22.5)
  for x in (7.65,8.35):b('pommel finial',x,-1.5,8,.5,.35,1,'steel')
 return p.save()
if __name__=='__main__':build(int(sys.argv[1]))
