import sys
from forge_local import Prop,PALETTE
s=int(sys.argv[1]);p=Prop('fire-staff',s,PALETTE);p.shaft()
if s==1:
 p.box('flame block',[8,25,8],[4,8,3],'energy')
 p.box('cradle block',[8,20.5,8],[5,2,4],'gold')
else:
 # Broad teardrop becomes an asymmetric curling flame.
 for j,(x,y,w,d) in enumerate([(8,21.5,2,2),(8,22.25,3,2.75),(8,23,4,3.25),(7.75,23.75,4.5,3.5),(7.5,24.5,4.25,3.25),(7.75,25.25,3.5,3),(8.25,26,2.75,2.5),(8.5,26.75,2,2),(8.75,27.5,1.5,1.5),(8.5,28.25,1,1),(8.1,28.9,.5,.6)]):
  p.box('flame contour',[x,y,8],[w,.8,d],'energy')
 for sign in [-1,1]:
  p.box('fork root',[8+sign*1.5,20.75,8],[2.7,.8,1.2],'gold',sign*22.5)
  p.box('fork prong',[8+sign*2.8,22.5,8],[.7,3.1,1],'gold',-sign*22.5)
 p.box('forward cradle prong',[8,22,9.75],[.7,3.5,.7],'gold',-22.5,'x')
 p.band(20,3,1)
if s>=3:
 for z in [6.25,9.75]:
  p.gem([7.8,24.1,z],2.5,4,.35,'light')
  p.gem([7.8,24,z+(.2 if z>8 else -.2)],1.1,2.5,.2,'light')
 p.box('socket bezel',[8,19.75,9.65],[1.7,1.7,.4],'gold',45)
 p.box('socket ruby',[8,19.75,9.9],[1,1,.3],'accent',45)
 for z in [6.6,9.4]:p.box('pommel ruby',[8,.25,z],[.9,.9,.3],'accent',45)
if s>=4:
 for sign in [-1,1]:
  p.box('prong engraved ridge',[8+sign*2.8,22.5,8.56],[.25,2.5,.18],'light',-sign*22.5)
 for y in [18.5,19,20.75]:p.box('socket incised lip',[8,y,9.25],[2,.18,.25],'gold')
if s>=5:
 for x,y,z in [(6.5,24,9.25),(9,25,9.25),(8.5,27,8.9),(6.75,23,9.3),(8.75,22.75,9.25),(8,25.5,9.4)]:
  p.box('flame facet glint',[x,y,z],[.4,.8,.2],'light',22.5)
 p.box('curl point',[7.8,29.25,8],[.4,.9,.5],'energy',45)
p.finish()
