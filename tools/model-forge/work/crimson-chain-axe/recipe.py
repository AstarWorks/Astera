import sys,math
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'couture-rapier'))
from recipe import Prop,PALETTE
PALETTE['steel']=['28313b','47525f','82929e','b3c2c9','edf6f8']
p=Prop('crimson-chain-axe',int(sys.argv[1]));s=p.stage;b=p.box
b('crimson haft',8,10,8,1.3,20,1.5,'red');b('iron socket',8,22,8,2.5,4,2.5,'black')
if s==1:
 b('left head mass',4.5,25,8,5,5,1.2,'red');b('right head mass',11.5,20,8,5,7,1.2,'red')
if s>=2:
 # Authored crescent profiles: inner concave edge and outer convex cutting edge.
 for side,rows in [(-1,[(20.5,5,5.4),(21.25,4.7,5.3),(22,4,5),(22.75,2.9,5),(23.5,1.4,5),(24.25,1.1,4.9),(25,1.5,4.6),(25.75,2,4.25),(26.5,2.6,3.85),(27.25,1.5,3),(28,1,2),(28.75,.3,.9)]),(1,[(15.5,3.3,3.8),(16.25,3.1,4.3),(17,2.8,4.8),(17.75,2.2,5.1),(18.5,1.4,5.2),(19.25,1.2,5.3),(20,1.1,5.3),(20.75,1.2,5.1),(21.5,1.5,4.8),(22.25,2.1,4.4),(23,2.7,3.9),(23.75,3.2,3.6)])]:
  for j,(y,inner,outer) in enumerate(rows):
   b('crimson crescent',8+side*(inner+outer)/2,y,8,outer-inner,.8,1.3,'red')
   b('cutting bevel',8+side*(outer-.12),y,8,.28,.8,.55,'edge')
   if s>=3:b('raised blade rib',8+side*(inner+.22),y,8.75,.3,.8,.25,'red')
 b('socket crown spike',8,25,8,.8,2,1.3,'steel',-22.5)
 for y in (0,18,20,24):b('iron ferrule',8,y,8,2,.65,2.1,'black')
 p.gem('pommel ruby',8,-1,8,.9,'red')
if s>=3:
 b('left socket bridge',6.3,23.5,8,1.5,1.2,1.5,'black')
 for j in range(6):
  y=2+j*2.5
  start=len(p.elements)
  p.loop('chain link',8+(.25 if j%2 else -.25),y,1,1.35,8.9,'steel',.45,8)
  if s>=4 and j%2:
   for e in p.elements[start:]:
    for key in ('from','to'):e[key][0],e[key][2]=e[key][2],e[key][0]
    dims=[e['to'][k]-e['from'][k] for k in range(3)]
    for f,axes in {'north':(0,1),'south':(0,1),'east':(2,1),'west':(2,1),'up':(0,2),'down':(0,2)}.items():
     uv=e['faces'][f]['uv'];uv[2]=uv[0]+dims[axes[0]]*3/8;uv[3]=uv[1]+dims[axes[1]]*3/8
    if 'rotation' in e:
     r=e['rotation'];r['origin'][0],r['origin'][2]=r['origin'][2],r['origin'][0];r['axis']='x';r['angle']=-r['angle']
  b('chain rear return',8,y,7.1,1.8,.35,.35,'steel',45 if j%2 else -45)
 for z in (6.65,9.35):
  b('white star vertical',8,22,z,.25,1.6,.12,'edge')
  b('white star horizontal',8,22,z,1,.25,.12,'edge')
if s>=4:
 for z in (6.6,9.4):
  for x in (7,9):
   for y in (20.5,23.5):b('socket rivet',x,y,z,.35,.35,.25,'steel',45)
 for y in (0,18):
  for x in (7.25,8.75):b('band rivet',x,y,9.1,.25,.25,.15,'edge')
if s>=5:
 for z in (7.15,8.85):
  for x,y in [(4.5,25),(11.7,18),(11.4,22)]:b('blade pin',x,y,z,.32,.32,.25,'steel',45)
 for y in (20,24):b('socket rim',8,y,8,2.7,.25,2.7,'steel')
p.save()
