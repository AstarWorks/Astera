import sys,math
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'fire-staff'))
from forge_local import Prop,PALETTE
s=int(sys.argv[1]);pal=dict(PALETTE);pal['energy']=['1c093b','35116b','6625ae','a55ce5','e6b4ff'];pal['light']=['b06c06','e7a719','ffe134','fff278','ffffc9'];pal['accent']=pal['energy'];pal['leather']=['1b1220','352036','533150','785373','aa839e']
p=Prop('thunder-staff',s,pal);p.shaft()
if s==1:p.box('orb block',[8,25,8],[6,6,5],'energy')
else:
 for j in range(7):
  yy=(j-3)*.8;r=math.sqrt(9-yy*yy)
  if s<3:p.box('orb latitude',[8,25+yy,8],[2*r,.82,2*r*.85],'energy')
  else:
   for k in range(-2,3):
    zz=k*r*.36;w=2*math.sqrt(max(.1,r*r-zz*zz))
    p.box('orb faceted latitude',[8,25+yy,8+zz],[w,.82,r*.38],'energy')
 for sign in [-1,1]:
  for x,y,w,h,a in [(1.6,21.75,.7,3,-45),(3.3,24,.8,3,0),(2.8,26.5,.8,2.75,45),(1.9,28,.7,1.2,0)]:
   p.box('swept gold prong',[8+sign*x,y,8],[w,h,1.1],'gold',sign*a)
 p.band(20.5,2.75,.75)
if s>=3:
 for z in [5,11]:
  p.box('lightning upper stroke',[8,26,z],[.7,3,.3],'light',-22.5)
  p.box('lightning cross stroke',[8,24.75,z],[1.9,.65,.3],'light')
  p.box('lightning lower stroke',[8.1,23.45,z],[.7,3,.3],'light',-22.5)
 p.box('socket bezel',[8,19.75,9.5],[1.4,1.6,.3],'gold',45)
 p.box('socket amethyst',[8,19.75,9.75],[.9,1.1,.3],'energy',45)
 for z in [6.7,9.3]:p.box('pommel amethyst',[8,.25,z],[1,1,.3],'energy',45)
if s>=4:
 for y in [23.45,24.75,26]:
  for z in [5.9,10.1]:p.box('bolt mounting post',[8,y,z],[.3,.3,1.8],'gold')
 for sign in [-1,1]:
  for y in [23.3,24.5,25.25]:
   p.box('prong inset',[8+sign*3.3,y,8.65],[.4,.55,.18],'accent')
   p.box('prong gold stud',[8+sign*3.3,y,8.8],[.25,.25,.2],'gold')
 for y in [2.5,6.5,17.5]:
  for x in [7.2,8,8.8]:p.box('collar stud',[x,y,9.3],[.3,.3,.2],'gold')
if s>=5:
 for x,y in [(7.5,26.5),(7,25.75),(8,27.25)]:p.box('orb glint',[x,y,10.3],[.4,.4,.2],'accent')
 p.box('bolt upper tip',[8.65,27.45,11],[.35,.8,.3],'light',-22.5)
 p.box('bolt lower tip',[7.45,22,11],[.3,.9,.3],'light',-22.5)
p.finish()
