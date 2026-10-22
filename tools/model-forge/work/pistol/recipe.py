"""Pistol and sibling batch gun recipes; import forge-C local authoring helpers."""
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'verdict-hammer'))
from recipe import Prop,review

def gun_display(item):
 small=item=='pistol'
 def d(r,t,s):return {'rotation':r,'translation':t,'scale':[s]*3}
 return {'gui':d([12,-25,12],[0,-2.3,0],.67 if small else .43),
 'firstperson_righthand':d([0,65,0],[1,-2,-3],.75),
 'firstperson_lefthand':d([0,65,0],[-1,-2,-3],.75),
 'thirdperson_righthand':d([0,90,0],[0,-1,-2],.7),
 'thirdperson_lefthand':d([0,90,0],[0,-1,-2],.7),
 'ground':d([0,0,0],[0,1,0],.35),'fixed':d([0,180,0],[0,-2,0],.55 if small else .43)}

def pistol(stage):
 p=Prop('pistol',stage)
 p.bar('slide',[8,15,8],[16,3.5,3.5])
 p.bar('raked grip frame',[2.5,9.5,8],[3,7,3],0,-22.5)
 p.bar('lower frame',[9,12.6,8],[12,1.5,3])
 if stage>=2:
  for z in (6.25,9.75):
   p.bar('slide bevel',[8,16.6,z],[15,.5,.5],4)
   p.bar('slide lower bevel',[8,13.3,z],[15,.4,.4],4)
   p.bar('walnut grip inset',[2.5,9.5,z],[2.25,5.5,.25],3,-22.5)
  p.bar('magazine heel',[1.25,6.2,8],[3.8,.7,3.5],4,-22.5)
  p.bar('guard bottom',[6,10,8],[4,.5,1])
  p.bar('guard front',[8,11,8],[.5,2.2,1],0,-22.5)
  p.bar('trigger',[5.6,11.5,8],[.4,1.8,.5],4,22.5)
  p.bar('emitter socket',[16,15,8],[.5,2.8,2.8],4)
  p.bar('emitter recess',[16.3,15,8],[.25,2.1,2.1])
  p.bar('cyan emitter',[16.5,15,8],[.25,1.4,1.4],2)
  for x in (1,14):p.bar('sight',[x,17.1,8],[1.5,.8,1],4)
 if stage>=3:
  for z in (6.05,9.95):
   p.bar('light channel shadow',[10.5,15,z],[8,1.5,.25])
   p.bar('cyan side strip',[10.5,15,z+(-.15 if z<8 else .15)],[7.5,.75,.25],2)
   for x in (1.5,2.5,3.5,4.5):
    p.bar('slide serration',[x,15,z],[.35,1.6,.3],4,-22.5)
   for x in (12.5,13.5):p.bar('lower diode',[x,12.5,z],[.45,.8,.25],2)
   for y in (7.5,8.5,9.5,10.5,11.5):p.bar('grip scored edge',[2.5+(y-9.5)*.414,y,z],[2,.15,.15],0,-22.5)
  for x in (0,15.5):
   for y in (13.8,16.1):
    for z in (6.5,9.5):p.bar('corner step',[x,y,z],[.5,.5,.5],4)
 if stage>=4:
  for z in (5.85,10.15):
   for x,y in ((.8,14),(5,16),(14.5,14),(2,8),(3,11)):
    p.bar('frame pin',[x,y,z],[.3,.3,.2],4)
   for x in (7,8,9,10):p.bar('underside cooling fin',[x,12,z],[.3,.45,.25],0)
  p.bar('top channel',[9,16.9,8],[7,.25,1],4)
  p.bar('rear cap accent',[0,15,8],[.25,2,2],2)
 if stage>=5:
  for z in (6.0,10.0):
   p.bar('light polished edge',[10.5,15.4,z],[7.5,.2,.15],2)
   for x in (6.4,14.6):p.bar('light channel end cap',[x,15,z],[.3,1.25,.3],4)
  for y,z in ((14,7),(14,9),(16,7),(16,9)):p.bar('emitter bevel corner',[16.4,y,z],[.3,.5,.5],4,45,'x')
 return p
if __name__=='__main__':
 stage=int(sys.argv[1]);review(pistol(stage),gun_display('pistol'),finish='--finish' in sys.argv)
