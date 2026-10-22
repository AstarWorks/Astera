import sys,math
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'fire-staff'))
from forge_local import Prop,PALETTE,displays
s=int(sys.argv[1]);pal=dict(PALETTE);pal['dark']=['080b10','141b24','28323e','47515d','75808b'];pal['gold']=['4a2c17','784922','a46c34','cf9854','efd095'];pal['silver']=['1e2834','36414f','536272','82919e','bccbd0']
p=Prop('ember-gauntlet',s,pal)
def cuff(y,h,r,mat):
 for j in range(8):
  a=j*45;t=math.radians(a);x=8+r*math.cos(t);z=8+r*math.sin(t)
  if j%2==0:d=[.65,h,2.75] if j%4==0 else [2.75,h,.65];ang=0
  else:d=[.65,h,2.75];ang=-45 if j%4==1 else 45
  p.box('octagonal cuff plate',[x,y,z],d,mat,ang,'y')
if s==1:
 p.box('bracer block',[8,4.5,8],[7,8,7],'dark');p.box('fist block',[8,13,8],[9,9,6],'dark');p.box('thumb block',[13,12,8],[3,5,3],'dark')
else:
 cuff(4,7,3.5,'dark')
 for y in [1,4,7.5]:cuff(y,.45,3.65,'gold' if y!=4 else 'silver')
 p.box('palm chassis',[8,11,8],[8,6,4.75],'dark')
 p.box('backhand raised plate',[8,11.5,10.6],[7.5,4.5,.65],'dark')
 for i,x in enumerate([4.7,6.9,9.1,11.3]):
  y=15.5+(.5 if i in [1,2] else 0)
  p.box('finger outer plate',[x,y,9],[1.95,4,3.2],'dark')
  p.box('curled finger tip',[x,y-1.1,6.6],[1.85,2.1,1.8],'dark')
  p.box('knuckle cap',[x,y+1.8,8.8],[2,1,3.2],'dark')
 p.box('thumb base',[12.5,11.5,8],[2.5,3.8,3],'dark',-22.5)
 p.box('folded thumb',[13.3,13.4,8.5],[2.25,3,2.5],'dark',-22.5)
if s>=3:
 p.box('wrist connector',[8,8.1,8],[6,1.5,5],'dark')
 for i,x in enumerate([4.7,6.9,9.1,11.3]):
  y=15.5+(.5 if i in [1,2] else 0)
  p.box('hot knuckle',[x,y+2.35,8.8],[1.3,.2,2.2],'energy')
  p.box('knuckle furnace face',[x,y+1.5,10.65],[1.3,1,.18],'energy')
  p.box('finger heat slit',[x,y-.4,10.65],[.45,2.1,.18],'energy')
  for side in [-1,1]:p.box('finger edge bevel',[x+side*.9,y,10.3],[.2,3.8,.55],'silver')
  for yy in [y-1.75,y+.9]:p.box('finger bronze lip',[x,yy,10.7],[1.85,.22,.2],'gold')
 for x in [5.6,6.8,8,9.2,10.4]:
  p.box('backhand furnace vent',[x,11.2,11],[.45,2.2,.2],'energy')
  p.box('vent grille',[x+.35,11.2,11.15],[.2,2.75,.25],'dark')
 for y in [2.5,5.5]:
  for x in [6.5,9.5]:p.box('bracer furnace window',[x,y,11.55],[.7,1.2,.2],'energy')
 p.box('thumb hot strip',[13.4,13.4,9.85],[.65,2.3,.2],'energy',-22.5)
 for y in [9.25,13.65]:p.box('backhand border',[8,y,11.1],[7.5,.35,.25],'gold')
if s>=4:
 for j in range(4):p.box('stepped shield rim',[8,9.25-j*.45,11.1],[7.5-j*1.75,.35,.3],'silver')
 for y in [2.4,5.4]:
  p.box('overlap bracer lame',[8,y,11.6],[5.4,.5,.35],'dark')
  p.box('lame edge highlight',[8,y+.3,11.65],[5.3,.15,.2],'silver')
 p.box('thumb side furnace',[14.45,13.4,8.5],[.2,1.5,1],'energy')
 p.box('thumb bronze top',[13.7,14.8,8.5],[2,.3,2.5],'gold',-22.5)
 for x in [4.8,11.2]:
  for y in [9.5,13.5]:
   p.box('rivet socket',[x,y,11.3],[.7,.7,.2],'gold')
   p.box('rivet pin',[x,y,11.5],[.3,.3,.25],'gold')
 p.box('furnace crest bezel',[8,13,11.25],[1.25,1.25,.3],'gold',45)
 p.box('furnace crest core',[8,13,11.5],[.65,.65,.25],'energy',45)
 for y in [1,7.5]:
  for x in [5.75,8,10.25]:p.box('cuff rivet',[x,y,11.85],[.4,.4,.2],'gold')
if s>=5:
 cuff(.65,.4,3.85,'gold')
 for x in [4.7,6.9,9.1,11.3]:p.box('rear finger seam',[x,14,5.65],[1.5,.25,.15],'silver')
 for i,x in enumerate([4.7,6.9,9.1,11.3]):
  y=15.5+(.5 if i in [1,2] else 0)
  p.box('knuckle yellow core',[x,y+2.48,8.8],[.5,.12,1.2],'light')
  p.box('knuckle front core',[x,y+1.5,10.8],[.5,.5,.12],'light')
 for x in [6.8,8,9.2]:p.box('vent hot core',[x,11.1,11.15],[.15,1.5,.1],'light')
 for y in [2.5,5.5]:p.box('rear bracer seam',[8,y,4.4],[4.5,.3,.2],'silver')
d=displays('ember-gauntlet');d['gui']={'rotation':[20,-30,-25],'translation':[0,-1,0],'scale':[.65]*3}
for hand,sign in [('righthand',1),('lefthand',-1)]:
 d['firstperson_'+hand]={'rotation':[75,-15*sign,-8*sign],'translation':[0,0,-2],'scale':[.68]*3}
 d['thirdperson_'+hand]={'rotation':[90,0,0],'translation':[0,1,0],'scale':[.65]*3}
d['fixed']={'rotation':[0,180,0],'translation':[0,-1,0],'scale':[.7]*3};d['ground']={'rotation':[90,0,0],'translation':[0,2,0],'scale':[.4]*3}
p.finish(d)
