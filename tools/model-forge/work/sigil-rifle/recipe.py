import sys,runpy,math
from pathlib import Path
h=runpy.run_path(str(Path(__file__).resolve().parents[1]/'rifle/recipe.py'))
def ring(p,r,x=24):
 t=r*.4142;pts=[(-r,-t),(-r,t),(-t,r),(t,r),(r,t),(r,-t),(t,-r),(-t,-r)]
 for (a,b),(c,d) in zip(pts,pts[1:]+pts[:1]):
  dy,dz=c-a,d-b;length=math.hypot(dy,dz)+.15
  angle=0 if abs(dy)<.01 or abs(dz)<.01 else 45 if dy*dz>0 else -45
  dims=[.35,.35,length] if abs(dy)<.01 else [.35,length,.35]
  p.bar('open sigil ring',[x,14.5+(a+c)/2,8+(b+d)/2],dims,1,angle,'x')
def build(stage):
 p=h['longgun']('sigil-rifle',stage)
 p.elements=[e for e in p.elements if not any(k in e['name'] for k in ('magazine','gold sigil arc','front sight'))]
 p.bar('short red chamber',[6,12.2,8],[1.7,1.4,2.8],5)
 if stage>=2:
  ring(p,4.3)
  for x in (7.5,16):p.cyl('red binding',[x,14.5,8],.7,1.65,5)
  for y in (11.2,17.8):p.bar('sigil bracket',[21.7,y,8],[4.5,.4,.5],1,22.5 if y>14 else -22.5)
  for z in (6.65,9.35):
   p.bar('stock gold comb',[-4,14.8,z],[6.5,.35,.25],1)
   p.bar('stock gold heel',[-4,10.9,z],[6.5,.35,.25],1,22.5)
   p.bar('gold butt binding',[-7.5,12,z],[.4,5.3,.25],1)
 if stage>=3:
  ring(p,3.1,24.2)
  for y,z,sy,sz in ((14.5,8,8.4,.2),(14.5,8,.2,8.4)):
   p.bar('sigil cross',[24.3,y,z],[.25,sy,sz],1)
  p.bar('sigil star diamond',[24.5,14.5,8],[.4,1.4,1.4],1,45,'x')
  for z in (6.65,9.35):
   for x,y,a in ((-5.5,13,45),(-4.8,13.5,0),(-4,13.3,-45),(-4.2,12.6,45),(-5,12.2,0),(-5.5,12.5,-45)):
    p.bar('gold stock scroll',[x,y,z],[1,.25,.25],1,a)
   p.bar('red grip jewel',[1,10,z+(.4 if z<8 else -.4)],[.7,1,.25],5,45)
 if stage>=4:
  for e in p.elements:
   if e['name'] in ('receiver upper bevel','receiver lower bevel','grip ribs','receiver side plate','barrel'):
    gold=e['name'] not in ('receiver side plate','barrel')
    for f in e['faces'].values():
     u,v,U,V=f['uv'];f['uv']=[u+(4 if gold else 0),v-8,U+(4 if gold else 0),V-8]
  for y,z in ((18.8,8),(10.2,8),(14.5,3.7),(14.5,12.3)):
   p.bar('sigil compass diamond',[24,y,z],[.5,.6,.6],1,45,'x')
  for y,z in ((17.5,10.5),(17.5,5.5),(11.5,10.5),(11.5,5.5)):
   p.bar('raised rune stroke',[24.25,y,z],[.25,.65,.2],1)
   p.bar('raised rune cross',[24.25,y+.2,z],[.25,.2,.65],1)
 if stage>=5:
  original=p.atlas
  def atlas():
   im=original()
   ramp=[(19,23,29,255),(43,49,58,255),(64,73,84,255),(91,103,115,255),(151,164,174,255)]
   for y in range(64):
    for x in range(32):im.putpixel((x,y),ramp[3 if y==0 else 2 if x%12<2 else 0 if (x+y)%29==0 else 1])
   return im
  p.atlas=atlas
  for z in (6.3,9.7):
   for x in (2,3,4):p.bar('red receiver slit',[x,14,z],[.3,1.1,.25],5)
   p.bar('gold receiver rail',[10.5,15.5,z],[5,.4,.3],1)
  for x in (14,19):p.cyl('gold barrel band',[x,14.5,8],.4,1.1,1)
 return p
if __name__=='__main__':h['review'](build(int(sys.argv[1])),h['gun_display']('sigil-rifle'),finish='--finish' in sys.argv)
