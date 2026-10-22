import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'couture-rapier'))
from recipe import Prop,PALETTE
PALETTE['cyan']=['07265d','0756a5','159be1','65d9ff','d4f6ff']
p=Prop('edict-blade',int(sys.argv[1]));s=p.stage;b=p.box
b('navy grip',8,2.5,8,1.5,5,1.6,'dark');b('gold guard',8,6.3,8,8, .8,1.9,'gold');b('blade core',8,16,8,2.5,19,1.1,'steel')
if s>=2:
 b('continuous tang',8,5.6,8,1.3,1.6,1.3,'gold')
 for side in (-1,1):
  for j in range(3):b('blade stepped bevel',8+side*(1.2+j*.16),16,8,.25,19,.95-j*.3,'edge')
  for j in range(3):b('guard facet',8+side*(2+j*.7),6.4,8,.9,.7+j*.25,2-j*.2,'gold',side*22.5)
 for j in range(5):b('blade point',8,25.7+j*.55,8,2.5-j*.48,.6,max(.2,.9-j*.16),'edge')
 for z in (7.42,8.58):b('dark narrow fuller',8,16.5,z,.4,17,.12,'black')
 for y in (-.1,5.3):b('gold collar',8,y,8,1.9,.4,2,'gold')
 p.gem('guard sapphire',8,6.3,9,1.5,'cyan');p.gem('pommel sapphire',8,-1.4,8.4,.8,'cyan')
 # Gold bezels maintain the icon's material identity.
 for e in p.elements:
  if 'seat' in e['name']:
   for f in e['faces'].values():f['uv']=[u+12 if i%2==0 else u for i,u in enumerate(f['uv'])]
if s>=3:
 b('pommel neck',8,-.6,8,1.2,1.3,1.3,'gold')
 for j in range(9):
  y=.4+j*.5
  b('diagonal wrap',8,y,8,1.6,.2,1.7,'dark',-22.5)
  b('wrap seam',8.45,y,8.88,.15,.3,.1,'black',-22.5)
 for z in (6.9,9.1):
  for x in (4.1,11.9):
   for j in range(3):b('terminal bevel',x,6.4,z, .8-j*.15,1.1-j*.2,.15,'gold',45)
  for x in (6.2,9.8):b('guard rivet',x,6.3,z,.35,.35,.2,'gold',45)
if s>=4:
 for z in (7.35,8.65):
  for x,y in [(7.3,8),(8.7,8),(8,9.3)]:b('ricasso gold inlay',x,y,z,.25,.45,.1,'gold',45)
  for side in (-1,1):p.line('ricasso crest',(8+side*.6,8.8),(8,9.6),z,.13,.1,'gold')
if s>=5:
 for z in (7.35,8.65):
  for side in (-1,1):b('fuller lip',8+side*.3,16.5,z,.12,16.8,.12,'edge')
 for x in (7.2,8.8):b('gem claw',x,6.3,9.7,.25,.5,.25,'gold')
p.save()
