import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'fire-staff'))
from forge_local import Prop,PALETTE
s=int(sys.argv[1]);pal=dict(PALETTE);pal['energy']=['06315f','075b9c','178dcc','69d5ed','e6ffff'];pal['light']=['226c9e','51aaca','9ce6f3','d6fbff','ffffff'];pal['accent']=['173c55','285e83','398fc1','74daf1','dbffff']
p=Prop('frost-staff',s,pal);p.shaft()
def shard(x,y,z,w,h,d,angle=0,axis='z'):
 start=len(p.els)
 for j in range(8):
  t=(j+.5)/8;f=min(1,t*5,(1-t)*4)
  p.box('ice shard stepped facet',[x,y+t*h,z],[w*f,h/8+.02,d*f],'energy')
  if s>=3 and 1<j<7:p.box('ice pale facet',[x-w*f*.24,y+t*h,z+d*f/2+.04],[w*f*.3,h/8+.01,.15],'light')
 if angle:
  for e in p.els[start:]:e['rotation']={'axis':axis,'angle':angle,'origin':[x,y,z]}
if s==1:p.box('ice cluster block',[8,25,8],[5,8,3],'energy')
else:
 shard(8,20,8,2.75,9.5,2.6)
 shard(7.25,20.5,8,1.7,6,1.8,22.5);shard(8.75,20.5,8,1.8,6.5,1.8,-22.5)
 if s>=3:shard(8,20,9.5,1.4,3.5,1.4,22.5 if s>=4 else 0,'x')
 if s>=4:shard(8,21,7.5,1.25,3.5,1.4,-22.5,'x')
 for sign in [-1,1]:
  p.box('bronze root',[8+sign*1.5,20.75,8],[.7,3.5,1],'gold',-sign*45)
  p.box('root tip',[8+sign*2.75,22.2,8],[.5,1.5,.75],'gold')
 p.band(19.25,2.75,.9,'silver')
if s>=3:
 for y in [10,14]:
  for z in [7,9]:p.box('silver spiral inlay',[8,y,z],[1.9,.45,.25],'silver',-22.5)
  p.box('inlay gem',[8,y,9.2],[.6,.6,.2],'energy',45)
 p.box('socket ice bezel',[8,19.25,9.5],[1.3,1.3,.2],'silver',45)
 p.box('socket ice gem',[8,19.25,9.7],[.8,.8,.2],'energy',45)
 p.gem([8,-.5,8],1.4,2,1.4,'energy')
if s>=4:
 for y in [18.75,19.75]:p.band(y,2.9,.2,'silver')
 for z in [6.8,9.2]:p.box('pommel ice inlay',[8,.25,z],[.75,.75,.2],'energy',45)
if s>=5:
 p.gem([8,-1.2,8],1,1.5,1,'energy')
 for x,y,z in [(7.5,27.5,8.7),(8.5,25.5,9.3),(6.5,24.5,8.7),(9.75,24.5,8.8),(8,22,10.2)]:p.box('frost fracture',[x,y,z],[.2,.8,.15],'light',22.5)
p.finish()
