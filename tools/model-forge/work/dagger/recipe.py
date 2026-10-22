import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'couture-rapier'))
from recipe import Prop,PALETTE
PALETTE['steel']=['202b33','384852','60727c','91a5ac','d2dfe4']
p=Prop('dagger',int(sys.argv[1]));s=p.stage;b=p.box
b('leather grip',8,5,8,1.8,5,1.9,'leather');b('guard core',8,8,8,5.5,.65,2,'steel')
if s==1:b('blade mass',8,13.5,8,3.5,10,1.1,'steel')
if s>=2:
 for j in range(12):
  y=9+j*.85;w=3.6-j*.28
  b('tapered blade section',8,y,8,max(.15,w-.4),.9,max(.25,1.1-j*.065),'steel')
  for side in (-1,1):b('ground edge bevel',8+side*(w/2-.12),y,8,min(.4,w*.6),.9,.25,'edge')
  if s>=3:
   for z in (8-max(.125,.55-j*.0325)-.04,8+max(.125,.55-j*.0325)+.04):b('central ridge',8,y,z,.25,.9,.16,'steel')
 for side in (-1,1):
  for j in range(3):b('guard bevel',8+side*(1.5+j*.5),8+j*.1,8,.7,.5,1.8-j*.3,'steel')
 for y in (2.4,7.5):b('grip collar',8,y,8,2.1,.35,2.1,'steel')
 for j in range(3):b('pommel facet',8,1.6,8,2.2-j*.35,1.2+j*.35,2.2-j*.35,'steel')
if s>=3:
 for j in range(8):b('leather lap',8,2.9+j*.55,8,1.95,.24,2.05,'leather',-22.5)
 for z in (6.95,9.05):
  for y in (3,7):b('grip pin',8,y,z,.25,.25,.15,'steel')
if s>=4:
 for j in range(6):b('leather stitch',7.5,3.3+j*.55,9.08,.12,.2,.08,'leather',22.5)
if s>=5:
 b('pommel diagonal facet',8,1.6,8,1.65,1.45,1.65,'steel',45,'y')
 for side in (-1,1):
  for j in range(4):
   y=10+j*1.4;z=8+side*(.55-(y-9)/.85*.0325+.02)
   b('subtle grinding mark',8.35,y,z,.1,.35,.06,'steel',22.5)
p.save()
