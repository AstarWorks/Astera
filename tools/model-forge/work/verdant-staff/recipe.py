import sys,math
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'fire-staff'))
from forge_local import Prop,PALETTE
s=int(sys.argv[1]);pal=dict(PALETTE);pal['energy']=['10431c','1f7220','4bb227','97d939','e4ff8a'];pal['light']=['639d23','9ccf35','d5ed61','f3ffac','ffffdf'];pal['accent']=['122e17','234c1d','3e7826','78ad39','b6d65c'];pal['gold']=['4b3217','70512b','a27d40','c8a05a','e5c886']
if s>=5:pal['accent']=['122e17','234c1d','3e7826','568f30','83b647']
p=Prop('verdant-staff',s,pal)
def leaf(x,y,z,w,h,a):
 start=len(p.els)
 for j in range(5):
  t=(j+.5)/5;f=1-abs(2*t-1)*.9
  p.box('leaf stepped blade',[x,y+(t-.5)*h,z],[w*f,h/5+.02,.35],'energy')
 if s>=3:p.box('leaf vein',[x,y,z+.23],[.18,h*.85,.15],'light')
 for e in p.els[start:]:e['rotation']={'axis':'z','angle':a,'origin':[x,y,z]}
if s==1:
 p.box('wood shaft',[8,10,8],[1.6,20,1.6],'wood');p.box('loop block',[8,24.5,8],[6,9,2.5],'wood')
else:
 for j in range(10):p.box('crooked bark shaft',[8+(.25 if j%3==0 else 0),j*2+.5,8],[1.6,2.1,1.6],'wood')
 for sign in [-1,1]:
  for x,y,h,a in [(1.2,20.5,2.8,-45),(2.5,22.5,2.8,-22.5),(2.8,24.8,2.6,0),(2.1,27,2.8,45),(1,28.2,2.4,45)]:p.box('curled wood loop',[8+sign*x,y,8],[.85,h,1.1],'wood',sign*a)
 p.gem([8,24.5,8],3,5,2.5,'energy')
 for v in [(4.7,24.5,8,1.8,3,45),(11.5,23,8,1.8,3,-45),(5.8,27.5,8,1.5,2.7,-22.5),(10.8,27.8,8,1.8,2.7,-45),(8.7,29,8,1,2.5,-22.5)]:leaf(*v)
 p.band(5,2,.55);p.band(17.5,2,.55)
if s>=3:
 p.box('seed stem',[8,21.5,8],[.6,2.2,.7],'wood')
 p.gem([8,24.4,9.35],1.5,2.5,.3,'light')
 p.gem([8,24.4,6.65],1.5,2.5,.3,'light')
 for j in range(12):
  p.box('winding vine',[8,6+j*.9,8.85],[1.7,.35,.25],'accent',-22.5)
  p.box('rear winding vine',[8,6.45+j*.9,7.15],[1.7,.35,.25],'accent',22.5)
 for sign in [-1,1]:
  p.box('root pommel branch',[8+sign*.65,2.1,8],[.75,3,1],'wood',sign*22.5)
  p.box('root pommel hook',[8+sign*.9,.5,8],[.6,1.2,.8],'wood',-sign*22.5)
 leaf(8,3,9,1.6,3,0)
if s>=4:
 for x,y,a in [(6.5,25.5,45),(9.5,26.5,-45)]:
  leaf(x,y,6.5,1.2,2.2,a)
  p.box('rear leaf twig',[x,y-.6,7.25],[.4,.6,1.8],'wood')
 for x,y,a in [(7,21.5,-45),(8.8,22.8,-45),(10.5,24,-22.5)]:p.box('forward winding root',[x,y,8.7],[.6,2.8,.65],'wood',a)
 for v in [(6.5,18.8,8.5,1.1,2,45),(9.2,13.5,8.4,.8,1.5,-45),(6.9,9.5,8.5,.8,1.5,45)]:leaf(*v)
 for sign in [-1,1]:p.box('loop bark raised vein',[8+sign*2.8,24.8,8.6],[.25,2.4,.2],'gold')
if s>=5:
 for x,y in [(7.5,24.7),(8.4,24.3)]:p.box('seed bright heart',[x,y,9.6],[.4,.65,.2],'light',45)
 for y in [5,17.5]:
  for x in [7.4,8.6]:p.box('bronze pin',[x,y,9.1],[.25,.25,.15],'gold')
p.finish()
