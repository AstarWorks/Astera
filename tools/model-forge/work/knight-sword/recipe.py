import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'fire-staff'))
from forge_local import Prop,PALETTE
s=int(sys.argv[1]);p=Prop('knight-sword',s,PALETTE)
p.box('leather grip',[8,4.5,8],[1.6,5.5,1.6],'leather')
if s==1:
 p.box('blade block',[8,19,8],[3,20,1],'silver');p.box('guard block',[8,8,8],[9,1.2,2],'gold');p.box('pommel block',[8,0.8,8],[2.5,2.5,2],'gold')
else:
 p.box('ricasso',[8,8.75,8],[2.5,1.5,.8],'silver')
 p.box('blade web',[8,17.75,8],[2.5,17.5,.8],'silver')
 for side in [-1,1]:
  for step in range(4):p.box('long edge bevel',[8+side*(1.25+step*.18),17.75,8],[.22,17.5,.8-step*.16],'silver')
 for j in range(6):p.box('point taper',[8,26.5+j*.5,8],[3.5-j*.55,.52,.75-j*.1],'silver')
 for side in [-1,1]:
  p.box('guard arm',[8+side*2.5,8,8],[4.5,1,2],'gold',side*0)
  for j in range(3):p.box('quillon curl',[8+side*(4+j*.3),8.3+j*.4,8],[.6,.8,1.75-j*.3],'gold',-side*22.5)
 p.band(1.8,2,.4);p.band(7.25,2,.4)
 p.gem([8,.25,8],3,2.75,1.7,'gold')
 p.box('guard center',[8,8,8],[2.25,1.65,2.5],'gold')
if s>=3:
 for z in [7.5,8.5]:
  p.box('dark inset fuller',[8,17.5,z],[.6,16,.12],'dark')
  for side in [-1,1]:p.box('fuller silver lip',[8+side*.45,17.5,z],[.17,16,.2],'silver')
  for x in [4,5.5,8,10.5,12]:
   p.box('guard rivet bezel',[x,8,z+(.6 if z>8 else -.6)],[.55,.55,.2],'accent')
   p.box('guard rivet',[x,8,z+(.75 if z>8 else -.75)],[.3,.3,.2],'gold')
  p.box('wheel boss',[8,.25,z+(.5 if z>8 else -.5)],[1.4,1.4,.35],'gold',45)
 for j in range(10):
  for z in [7.13,8.87]:p.box('leather diagonal binding',[8,2.3+j*.48,z],[1.6,.18,.25],'wood',22.5)
if s>=4:
 for z in [6.92,9.08]:
  for y in [7.65,8.35]:p.box('incised gold border',[8,y,z],[7.8,.15,.12],'gold')
 for z in [6.7,9.3]:p.box('guard boss',[8,8,z],[.7,.7,.2],'gold')
if s>=5:
 p.box('wheel middle',[8,.25,8],[2.8,1.25,1.7],'gold')
 for y in [-.65,1.15]:p.box('wheel shoulder',[8,y,8],[1.85,.5,1.7],'gold')
 for side in [-1,1]:
  for y in [11,15,19,23]:p.box('edge nick highlight',[8+side*1.6,y,8.25],[.2,.5,.1],'silver')
 for z in [6.7,9.3]:p.box('pommel boss pin',[8,.25,z],[.4,.4,.25],'gold')
p.finish()
