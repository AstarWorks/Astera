import sys,runpy
from pathlib import Path
h=runpy.run_path(str(Path(__file__).resolve().parents[1]/'rifle/recipe.py'))
def build(stage):
 p=h['longgun']('vendetta-rifle',stage)
 p.elements=[e for e in p.elements if not any(k in e['name'] for k in ('magazine','bayonet'))]
 p.bar('short magazine',[5,11.8,8],[3,1.2,2.4])
 p.bar('dark wood forearm',[11,13.5,8],[6,1.6,3.1],3)
 if stage==1:p.bar('blade block',[21,12.2,8],[8,1.5,.6],4)
 if stage>=2:
  p.bar('blade root',[19,12.2,8],[5,1.5,.65],4)
  p.bar('blade taper',[22.25,12.4,8],[2,1.1,.55],4)
  p.bar('blade tip',[23.8,12.6,8],[1.4,.65,.45],4)
  p.bar('blade point',[24.6,12.75,8],[.6,.35,.3],4)
  p.bar('bayonet red guard',[16.5,12.5,8],[.65,3,1.4],5,-22.5)
  p.bar('bayonet mount',[16,13.4,8],[2,1.3,1.5])
  for x in (2.6,7.3):p.cyl('crimson optic ring',[x,18,8],.3,1.15,5)
 if stage>=3:
  for z in (6.15,9.85):
   p.bar('crimson upper receiver',[4.5,15,z],[6,.65,.25],5)
   p.bar('magenta receiver inset',[4.5,14.2,z],[4.5,.3,.3],6)
   p.bar('magenta forearm inset',[11,14.5,z],[3,.3,.25],6)
  for x,y,z in ((3,13.5,9.9),(3,13.5,6.1),(16.3,12.5,8.8),(16.3,12.5,7.2)):
   p.bar('jewel crimson bezel',[x,y,z],[1.1,1.1,.3],5,45)
   p.bar('jewel magenta facet',[x,y,z+(.2 if z>8 else -.2)],[.65,.65,.25],6,45)
  for z in (7.6,8.4):p.bar('blade bevel edge',[20,11.6,z],[6,.2,.15],4)
 if stage>=4:
  for z in (6.4,9.6):
   for x in (0,8,14):p.bar('crimson receiver collar',[x,14,z],[.45,2.6,.35],5)
  for z in (6.7,9.3):p.bar('butt red binding',[-7.2,12,z],[.35,5,.25],5)
 if stage>=5:
  for e in p.elements:
   if e['name']=='barrel':
    for f in e['faces'].values():
     u,v,U,V=f['uv'];f['uv']=[u,v-8,U,V-8]
  for z in (7.55,8.45):
   p.bar('blade fuller',[19.8,12.5,z],[5,.25,.15],0)
   p.bar('blade tip bevel',[23,12.6,z],[2.5,.25,.15],4,22.5)
  p.bar('bolt lever',[1.5,13.5,10],[.5,1.5,.5],4,-22.5)
  p.cyl('bolt knob',[1.2,12.8,10.5],.5,.45,4,'z')
  original=p.atlas
  def atlas():
   im=original();orange=[(100,28,8,255),(174,55,10,255),(238,108,19,255),(255,173,48,255),(255,236,137,255)]
   wood=[(36,23,21,255),(66,39,27,255),(103,62,36,255),(145,91,48,255),(191,134,75,255)]
   for y in range(64):
    for x in range(32):
     im.putpixel((32+x,y),orange[4 if y%16==0 else 3 if y%16<3 else 2 if x%12<6 else 1])
     im.putpixel((96+x,y),wood[4 if y%19==0 else 3 if y%11==0 else 0 if (y+x//8)%17==0 else 1 if (y+x//8)%7==0 else 2])
   return im
  p.atlas=atlas
 return p
if __name__=='__main__':h['review'](build(int(sys.argv[1])),h['gun_display']('vendetta-rifle'),finish='--finish' in sys.argv)
