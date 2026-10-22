import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'couture-rapier'))
from recipe import Prop,PALETTE
PALETTE['steel']=['16212b','2d3b47','495c6a','748995','b1c3ce']
PALETTE['red']=['360c14','741420','b62230','ce323c','e45f60']
p=Prop('greatsword',int(sys.argv[1]));s=p.stage;b=p.box
b('black grip',8,3,8,1.8,7,2,'black');b('red guard',8,7.1,8,10,1.4,2.6,'red');b('dark steel blade',8,16.5,8,4.8,17.5,1.6,'steel')
if s>=2:
 for side in (-1,1):
  for j in range(3):b('steel bevel step',8+side*(2.3+j*.25),16.5,8,.4,17.5,1.4-j*.45,'edge')
  for j in range(3):b('crimson terminal',8+side*4.9,7.3,8,1.3-j*.2,2.3-j*.2,2.6+j*.15,'red')
 for j in range(6):
  b('stepped spear point',8,25.4+j*.5,8,4.8-j*.8,.6,max(.2,1.6-j*.25),'steel')
  if j<5:
   for side in (-1,1):b('point bevel',8+side*(2.4-j*.4),25.4+j*.5,8,.4,.6,.35,'edge')
 for z in (7.14,8.86):b('deep central channel',8,17,z,.8,16.5,.16,'black')
 for j in range(3):b('pommel bevel',8,-1,8,2.6-j*.3,1.4+j*.25,2.6-j*.3,'red')
if s>=3:
 for z in (6.6,9.4):
  b('guard central frame',8,7.1,z,2.6,2.6,.4,'red',45)
  b('guard raised boss',8,7.1,z+(.3 if z>8 else -.3),1.4,1.4,.3,'red')
  for side in (-1,1):
   b('blade shoulder collar',8+side*1.8,8.4,z+(.5 if z<8 else -.5),1.2,1.4,.5,'black')
   for j in range(3):b('guard narrow ridge',8+side*(1.8+j*.9),7.2,z,.75,.3,.2,'red')
 for j in range(10):b('black grip wrap',8,.2+j*.6,8,1.95,.25,2.15,'black',-22.5)
 for y in (-.15,6.25):b('grip collar',8,y,8,2.1,.35,2.3,'steel')
if s>=4:
 for z in (6.65,9.35):
  for x,y in [(6.2,8.7),(9.8,8.7),(5.9,7.1),(10.1,7.1),(3.2,7.1),(12.8,7.1)]:b('iron rivet',x,y,z+(.2 if z<8 else -.2) if y>8 else z,.35,.35,.25,'edge',45)
 for z in (7.08,8.92):
  for side in (-1,1):b('fuller border',8+side*.55,17,z,.15,16.5,.15,'steel')
if s>=5:
 for z in (6.65,9.35):
  b('pommel inset',8,-1,z,1.4,.9,.2,'red')
  for x in (7.2,8.8):b('pommel stud',x,-1,z,.2,.2,.2,'steel',45)
 for z in (7.15,8.85):
  for y in (12,19,23):b('forged wear cut',9,y,z,.15,.55,.08,'steel',45)
p.save()
