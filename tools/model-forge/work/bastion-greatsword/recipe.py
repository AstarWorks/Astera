import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'couture-rapier'))
from recipe import Prop,PALETTE
PALETTE['edge']=['98702c','cba344','ffe49a','fff2b9','fffde6']
p=Prop('bastion-greatsword',int(sys.argv[1]));s=p.stage;b=p.box
b('black grip',8,3,8,1.8,7,2,'black')
b('crossguard',8,7.3,8,10,1.4,2.6,'gold')
b('blade',8,17,8,4.8,18,1.8,'gold')
if s>=2:
 for j in range(5):
  y=25.5+j*.65;w=4.8-j*.85
  b('taper '+str(j),8,y,8,w,.7,1.8-j*.28,'gold')
 for side in (-1,1):
  for step in range(3):
   b('edge bevel',8+side*(2.4+step*.25),17,8,.3,17.5,1.6-step*.45,'edge')
  b('shoulder flare',8+side*2.6,9,8,.9,2.5,1.9,'edge',side*22.5)
  for z in (6.98,9.02):
   b('fuller rail',8+side*.75,17,z,.35,16, .3,'edge')
   p.line('crown bevel',(8+side*2.8,25.5),(8,29),8,.5,1.3,'edge')
  b('guard terminal',8+side*5,7.7,8,1.8,2.5,2.8,'gold')
 p.gem('pommel',8,-1,8,1.7,'gold')
if s>=3:
 for z in (6.85,9.15):
  b('shield plate',8,7.3,z,2.7,2, .5,'gold')
  b('shield foot',8,6.4,z,1.6,1.6,.5,'gold',45)
  b('shield inset',8,7.2,z+(.3 if z>8 else -.3),1.7,1.5,.2,'edge')
  for side in (-1,1):
   for j in range(3):b('guard terrace',8+side*(2+j*.8),7.5,z,.8,.8-j*.15,.3,'edge')
   p.line('shoulder chevron',(8+side*2.3,9.4),(8,11),z,.35,.25,'edge')
 for y in (.1,3,6.1):b('grip gold band',8,y,8,2,.3,2.2,'gold')
 for j in range(9):b('leather wrap',8,.6+j*.58,8,1.9,.2,2.1,'black',-22.5)
 for x in (3,13):
  for y in (6.7,8.5):b('guard cap',x,y,8,1.5,.4,2.9,'edge')
if s>=4:
 for z in (6.9,9.1):
  for j in range(12):b('fuller etched dash',8,11+j*1.1,z,.25,.4,.15,'gold')
  for x in (6.2,9.8):b('guard rivet',x,7.4,z,.4,.4,.4,'edge',45)
if s>=5:
 for z in (6.7,9.3):
  for side in (-1,1):
   p.line('crest engraving',(8+side*.5,7.1),(8,6.5),z,.15,.1,'gold')
   b('blade base stud',8+side*2,10,z,.3,.3,.15,'gold',45)
p.save()
