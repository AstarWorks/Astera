"""Long-gun batch authoring, distinct silhouette recipes from generated targets."""
import sys,runpy
from pathlib import Path
WORK=Path(__file__).resolve().parents[1]
helpers=runpy.run_path(str(WORK/'pistol/recipe.py'))
Prop,review,gun_display=[helpers[k] for k in ('Prop','review','gun_display')]

def longgun(item,stage):
 p=Prop(item,stage)
 original_atlas=p.atlas
 def painted_atlas():
  im=original_atlas()
  if stage>=4:
   for y in range(64):
    for x in range(32):
     color=(151,94,45,255) if y%13==0 else (79,47,29,255) if (y+x//9)%9==0 else (117,71,42,255)
     im.putpixel((96+x,y),color)
  return im
 p.atlas=painted_atlas
 shot=item=='shotgun';magic=item=='sigil-rifle';energy=item=='rtm-blaster';sniper=item=='vendetta-rifle'
 accent=6 if energy else 5 if magic or sniper or shot else 7
 stockmat=0 if magic else 3
 p.bar('receiver',[4,14,8],[9,3,3])
 p.bar('stock core',[-4,12.7,8],[7,4,2.5],stockmat)
 p.bar('grip',[1,10,8],[2,5,2.2],0,-22.5)
 p.bar('forearm',[11,13.7,8],[5,2.5,3],0 if energy or magic or sniper else 3)
 p.bar('barrel',[18.5,14.5,8],[10,1,1],4)
 if not shot:p.bar('magazine',[6.5,10.5,8],[2.5,4,2.25],0,22.5 if not sniper else 0)
 if stage>=2:
  # Stock has a sloped underside and stepped cheek comb, not a rectangular plank.
  p.bar('stock sloping heel',[-4.3,11.3,8],[6.4,2,2.6],stockmat,22.5)
  p.bar('stock neck',[-.7,13.7,8],[2.5,1.8,2],stockmat)
  p.bar('stock buttpad',[-7.6,12,8],[.6,5.2,2.9],0)
  p.bar('cheek comb',[-4,15,8],[4,.6,2.3],stockmat)
  for z in (6.45,9.55):
   p.bar('receiver upper bevel',[4,15.3,z],[8.5,.45,.4],4)
   p.bar('receiver lower bevel',[4,12.7,z],[8.5,.35,.35],4)
  p.bar('guard heel',[3.2,10.8,8],[3,.5,.8])
  p.bar('guard front',[4.5,11.6,8],[.5,1.8,.8])
  p.bar('trigger',[3,11.8,8],[.35,1.3,.5],4,22.5)
  for x in (14.5,18.5):p.cyl('barrel collar',[x,14.5,8],.7,.85,0)
  p.cyl('muzzle shroud',[23.4,14.5,8],1.5,.95,4)
  p.cyl('dark muzzle face',[24.2,14.5,8],.2,.65,0)
  if shot:
   p.cyl('lower tube',[17,13.1,8],8,.55,0)
   p.bar('front bead',[23,15.6,8],[.4,.4,.4],1)
  else:
   p.bar('front sight post',[19,15.5,8],[.4,1.5,.6])
   if not magic:
    p.bar('scope mounts',[5,16.5,8],[5,1,1.4])
    p.cyl('scope main',[5,18,8],7,1,0)
    p.cyl('scope ocular',[1.3,18,8],1.2,.85,4)
    p.cyl('scope objective',[8.6,18,8],1.2,1.3,0)
    p.cyl('lens',[9.25,18,8],.2,1.0,1 if sniper else accent)
 if stage>=3:
  for z in (6.4,9.6):
   p.bar('receiver side plate',[3.8,14,z],[5.8,1.5,.25],4)
   p.bar('ejection recess',[5.2,14.3,z+(-.15 if z<8 else .15)],[2.8,.6,.25],0)
   p.bar('selector',[1.8,13.7,z+(-.2 if z<8 else .2)],[1,.3,.3],accent,-22.5)
   for x in (1.2,7.7):
    for y in (13,15):p.bar('receiver fastener',[x,y,z],[.3,.3,.3],4)
   for y in (8.3,9.1,9.9,10.7,11.5):p.bar('grip ribs',[1+(y-10)*.414,y,6.8 if z<8 else 9.2],[1.5,.2,.2],4,-22.5)
   for x in (9.2,10.2,11.2,12.2):p.bar('forearm fluting',[x,13.7,z],[.3,2,.25],accent if energy or magic or sniper else 3)
  for x in (2.6,7.3):
   if not shot and not magic:p.cyl('scope clamp',[x,18,8],.45,1.1,4)
  if not shot and not magic:p.cyl('scope turret',[5,19.2,8],.6,.7,4,'y')
  if not shot:
   p.bar('magazine heel',[7,8.4,8],[3,.5,2.5],4,22.5)
   for z in (6.75,9.25):
    for x in (5.6,6.3,7):p.bar('magazine pressed rib',[x,10.4,z],[.2,3.4,.2],4,22.5)
 if stage>=4:
  for z in (6.15,9.85):
   for x in (2,3,4,5,6,7):p.bar('top rail tooth',[x,15.7,z],[.35,.3,.2],0)
   for x in (-6,-3):p.bar('stock screw',[x,13,z+(.5 if z<8 else -.5)],[.3,.3,.3],4)
  for z in (7,9):
   for x in (23,23.6):p.bar('muzzle port',[x,14.5,z],[.3,.5,.15],0)
  if shot:
   for x in (3.5,4.5):
    p.cyl('red shell',[x,12.9,10],2,.4,5,'y');p.cyl('shell brass head',[x,14,10],.3,.45,1,'y')
  if energy:
   for x in (10,11.5,13):
    p.cyl('energy coil',[x,14,8],.45,1.85,6)
   p.cyl('energy nozzle',[22.8,14.5,8],2.3,1.4,0)
   p.cyl('luminous bore',[24.1,14.5,8],.25,1.0,6)
  if magic:
   # Eight-sided open magic sigil; separate bars preserve negative space.
   for y,z,sy,sz,angle in [(18,8,.4,3,0),(11,8,.4,3,0),(14.5,4.5,3,.4,0),(14.5,11.5,3,.4,0),(17,5.5,.4,3,45),(17,10.5,.4,3,-45),(12,5.5,.4,3,-45),(12,10.5,.4,3,45)]:
    p.bar('gold sigil arc',[23.8,y,z],[.35,sy,sz],1,angle,'x')
   for x in (-6,-3,1,8,13):p.bar('gold heraldic trim',[x,14.5,9.7],[.6,2,.3],1,22.5)
  if sniper:
   p.bar('bayonet lug',[20,13.7,8],[2,.7,1.2],5)
   for x,w in ((21,1.5),(23,1),(24.5,.5)):
    p.bar('bayonet tapered blade',[x,12.9,8],[2,w,.45],4)
 if stage>=5:
  for z in (6.1,9.9):
   p.bar('receiver identity stripe',[5.8,15,z],[4,.35,.2],accent)
   p.bar('forearm edge',[11,14.9,z],[4,.25,.2],accent if energy or magic or sniper else 3)
  for x in (-7,-6.5,-6):p.bar('buttplate ribs',[x,12,9.5],[.2,3.5,.2],0)
  if not shot and not magic:
   p.cyl('lens retaining lip',[9.4,18,8],.2,1.05,4)
   p.cyl('lens exposed center',[9.55,18,8],.2,.8,1 if sniper else accent)
 return p
if __name__=='__main__':
 stage=int(sys.argv[1]);review(longgun('rifle',stage),gun_display('rifle'),finish='--finish' in sys.argv)
