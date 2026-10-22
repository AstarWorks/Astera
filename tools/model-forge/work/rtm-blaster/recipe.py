import sys,runpy
from pathlib import Path
h=runpy.run_path(str(Path(__file__).resolve().parents[1]/'rifle/recipe.py'))
def build(stage):
 p=h['longgun']('rtm-blaster',stage)
 p.elements=[e for e in p.elements if not any(k in e['name'] for k in ('magazine','front sight'))]
 p.bar('energy housing',[18.5,14.5,8],[8,2.5,2.8])
 if stage>=2:
  for y in (13,16):p.bar('housing longitudinal rail',[18.5,y,8],[8,.5,3.3],4)
  p.cyl('heavy front collar',[22.5,14.5,8],1.0,1.9,0)
  p.bar('underbarrel cell',[18,12.8,8],[4,1.4,3])
 if stage>=3:
  for z in (6.5,9.5):
   p.bar('long pink core',[18.5,14.5,z],[7,.8,.25],6)
   p.bar('lower battery window',[18,12.7,z],[3,.6,.25],6)
   for x in (3,4,5):p.bar('charge indicator',[x,14,z+(-.3 if z<8 else .3)],[.5,.5,.25],6)
 if stage>=4:
  for z in (6.05,9.95):
   for x in (9,13.7,22.5):p.bar('housing screw',[x,15,z],[.35,.35,.25],4)
 if stage>=5:
  for z in (6.35,9.65):p.bar('violet channel surround',[18.5,14.5,z],[7.3,1.25,.2],6)
  for x in (10,11.5,13):
   p.bar('coil top highlight',[x,15.9,8],[.25,.25,1.3],6)
 return p
if __name__=='__main__':h['review'](build(int(sys.argv[1])),h['gun_display']('rtm-blaster'),finish='--finish' in sys.argv)
