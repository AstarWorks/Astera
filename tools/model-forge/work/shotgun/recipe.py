import sys,runpy
from pathlib import Path
h=runpy.run_path(str(Path(__file__).resolve().parents[1]/'rifle/recipe.py'))
def build(stage):
 p=h['longgun']('shotgun',stage)
 p.elements=[e for e in p.elements if not any(k in e['name'] for k in ('grip','forearm','barrel collar','muzzle','front bead')) and e['name']!='barrel']
 p.bar('wooden pump',[11.5,12.5,8],[6,2.5,3],3)
 if stage==1:p.bar('long grey barrel',[16,15,8],[16,1.75,1.75],4)
 if stage>=2:
  p.cyl('long stepped barrel',[16,15,8],16,.9,4)
  p.cyl('muzzle rim',[24,15,8],.4,1,4)
  p.cyl('dark open bore',[24.25,15,8],.15,.7,0)
  p.cyl('lower tube cap',[21,13.1,8],.5,.7,4)
  p.bar('small front bead',[23.5,16,8],[.5,.5,.5],4)
  p.bar('wood wrist',[-.8,12.2,8],[2.2,2,2.3],3,22.5)
 if stage>=3:
  for x in (9,10,11,12,13,14):
   p.cyl('raised wooden pump rib',[x,12.5,8],.4,1.65,3)
  p.bar('shell carrier',[4,13.1,9.55],[2.3,2.5,.4],0)
 if stage>=4:
  for x in (8.3,14.7):p.cyl('pump ferrule',[x,12.5,8],.3,1.45,0)
 if stage>=5:
  original=p.atlas
  def atlas():
   im=original()
   ramp=[(176,195,205,255),(125,146,161,255),(89,109,126,255),(61,76,91,255),(39,49,62,255)]
   for y in range(64):
    for x in range(32):im.putpixel((x,64+y),ramp[min(y%16//3,4)])
   return im
  p.atlas=atlas
  for z in (7.2,8.8):p.bar('barrel highlight rail',[16,15.4,z],[15.5,.2,.2],4)
  for x in (3.5,4.5):p.bar('shell bright seam',[x,13,10.4],[.2,1.4,.2],5)
 return p
if __name__=='__main__':h['review'](build(int(sys.argv[1])),h['gun_display']('shotgun'),finish='--finish' in sys.argv)
