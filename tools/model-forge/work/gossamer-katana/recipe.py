import sys,math
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'couture-rapier'))
from recipe import Prop,PALETTE
PALETTE['dark']=['061c38','09366a','135eaa','408fd5','83c9f2']
PALETTE['red']=['370c17','701222','a92031','cb3742','e65c60']
p=Prop('gossamer-katana',int(sys.argv[1]));s=p.stage;b=p.box
b('blue underwrap',8,4,8,1.4,8,1.5,'dark');b('tsuba core',8,8.5,8,3.4,.45,2.6,'gold')
if s==1:b('blade block',8,18.5,8,1.5,19.5,.8,'steel')
if s>=2:
 for j in range(16):
  t=j/15;x=8+2.7*t*t;y=9.35+j*1.2;w=1.4 if j<13 else 1.4-(j-12)*.3
  b('curved silver section',x,y,8,w,1.25,.7,'steel',-22.5 if j>11 else 0)
  b('bright cutting edge',x+w/2,y,8,.22,1.25,.25,'edge',-22.5 if j>11 else 0)
  b('dark spine',x-w/2,y,8,.18,1.25,.72,'black',-22.5 if j>11 else 0)
 for j in range(16):
  a=j*math.tau/16;b('oval tsuba rim',8+1.65*math.cos(a),8.55,8+1.25*math.sin(a),.55,.45,.55,'gold')
 for y in (.1,8):b('gold ferrule',8,y,8,1.6,.45,1.7,'gold')
 for j in range(3):b('pommel terrace',8,-.4-j*.15,8,1.6-j*.25,.3,1.7-j*.25,'gold')
if s>=3:
 for z in (7.18,8.82):
  for j in range(8):
   for side in (-1,1):b('crossed crimson silk',8,.6+j*.9,z,.25,1.35,.14,'red',side*45)
 for z in (7.1,8.9):
  for y in (1.3,6.6):b('brass mekugi pin',8,y,z,.25,.25,.15,'gold',45)
if s>=4:
 for j in range(8):
  for side in (-1,1):b('silk side return',8+side*.76,.6+j*.9,8,.15,.2,1.65,'red')
 for side in (-1,1):
  for z in (7.35,8.65):
   for angle in (-45,0,45):b('web spoke',8+side*1,8.82,z,.9,.08,.08,'edge',angle,'y')
if s>=5:
 for z in (7.6,8.4):
  for j in range(6):b('habaki engraving',7.55+j*.18,9,z,.08,.6,.07,'gold')
 b('pommel pin',8,-.5,8.85,.35,.35,.12,'gold',45)
p.save()
