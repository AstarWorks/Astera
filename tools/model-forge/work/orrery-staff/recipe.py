import sys,math
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'fire-staff'))
from forge_local import Prop,PALETTE
s=int(sys.argv[1]);pal=dict(PALETTE);pal['energy']=['20093d','46106f','782bc0','b770ef','edd3ff'];pal['accent']=['07405e','086c96','23a7cf','7de4f2','e1ffff'];pal['light']=['5b2090','944bcd','c486f4','ecc7ff','ffffff']
p=Prop('orrery-staff',s,pal);p.shaft()
def ring(plane):
 for j in range(16):
  t=j*22.5;r=math.radians(t);a=t
  while a>45:a-=90
  tangent=[.65 if s>=5 else .5,1.8,.65 if s>=5 else .5]
  if round((t-a)/90)%2:tangent[0],tangent[1]=tangent[1],tangent[0]
  if plane=='xy':p.box('meridian gold segment',[8+4.25*math.cos(r),24.5+4.25*math.sin(r),8],tangent,'gold',a)
  elif plane=='yz':p.box('side meridian segment',[8,24.5+4.25*math.cos(r),8+4.25*math.sin(r)],[.5,tangent[0],tangent[1]],'gold',a,'x')
  else:
   p.box('equator gold segment',[8+4.25*math.cos(r),24.5,8+4.25*math.sin(r)],[tangent[0],.5,tangent[1]],'gold',-a,'y')
if s==1:p.box('orrery envelope',[8,24.5,8],[8.5,8.5,8.5],'gold')
else:
 ring('xy');ring('xz')
 p.gem([8,24.5,8],4.25 if s>=5 else 3.75,5,4.25 if s>=5 else 3.75,'energy')
 p.box('axis pin',[8,24.5,8],[.45,9.5,.45],'gold')
 p.band(19.75,2.7,.65);p.band(29,1.5,.5)
if s>=3:
 # Diagonal orbit uses connected stepped beads to respect single-axis Java rotations.
 for j in range(40):
  r=2*math.pi*j/40;p.box('inclined orbit bevel link',[8+3.8*math.cos(r),24.5+2.687*math.sin(r),8+2.687*math.sin(r)],[.72,.72,.72],'gold')
 for c in [[3.75,24.5,8],[12.25,24.5,8],[8,27.2,10.7]]:
  p.gem(c,1.35,1.6,1.35,'accent')
 p.gem([8,24.5,10],1.25,2.75,.25,'light')
 for y in [2.5,6.5,17.5,19.75]:
  p.box('collar amethyst',[8,y,9.45],[.6,.6,.25],'energy')
if s>=4:
 ring('yz')
 p.els=[e for e in p.els if 'spiral wood' not in e['name']]
 for x,y in [(3.75,24.5),(12.25,24.5),(8,28.75),(8,20.25)]:
  p.box('ring boss',[x,y,8.5],[.9,.9,.25],'gold')
  p.box('boss amethyst',[x,y,8.7],[.4,.4,.2],'energy')
 for y in [8.5,11.5,14.5]:
  for z in [7.1,8.9]:p.box('fine gold wire',[8,y,z],[1.8,.2,.2],'gold',-22.5)
if s>=5:
 for j in range(8):
  t=math.radians(j*45);p.box('equator etched tick',[8+4.25*math.cos(t),24.8,8+4.25*math.sin(t)],[.25,.1,.25],'light')
 p.gem([8,29.5,8],.8,1.1,.8,'gold')
p.finish()
