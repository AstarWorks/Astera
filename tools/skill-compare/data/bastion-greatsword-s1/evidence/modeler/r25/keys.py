import numpy as np, math, re, sys
def ax(a,deg):
    t=math.radians(deg); c,s=math.cos(t),math.sin(t)
    if a=='x': return np.array([[1,0,0],[0,c,-s],[0,s,c]])
    if a=='y': return np.array([[c,0,s],[0,1,0],[-s,0,c]])
    return np.array([[c,-s,0],[s,c,0],[0,0,1]])
def eul(y,p,r): return ax('y',-y)@ax('x',p)@ax('z',r)
L0=np.array([.2,1,-.7]);L0/=np.linalg.norm(L0);L1=np.array([-.2,1,.7]);L1/=np.linalg.norm(L1)
def van(n): return min(1,0.6*(max(0,n@L0)+max(0,n@L1))+0.4)
def lift(n): x=min(1,max(0,(-n[1]-0.2)/0.5)); return 0.62*x*x*(3-2*x)
txt=open(sys.argv[1]).read()
keys={int(m[0]):tuple(map(float,m[1:])) for m in re.findall(r"\{ t: (\d+), rotate: \[([-\d.]+), ([-\d.]+), ([-\d.]+)\] \}",txt)}
tilt=eul(0,0,-20); rest=eul(0,90,0)
# anchor (x left, y up, z fwd) -> world columns
A={'compare':np.column_stack([[0,0,-1],[0,1,0],[1,0,0]]),'side':np.column_stack([[1,0,0],[0,1,0],[0,0,1]])}
# camera relative to the pivot (~1.3 above feet)
cam={'compare':np.array([2.2,3.6-1.3,8.0]),'side':np.array([6,2.62-1.3,2])}
for view in ['compare','side']:
  print('==',view)
  for k in sorted(keys):
    if not (k<=8 or k>=44): continue
    R=A[view]@tilt@eul(*keys[k])@rest
    blade=R@np.array([0,1,0.])
    v=cam[view]/np.linalg.norm(cam[view])
    out=[]
    for nm,loc in [('+Z',[0,0,1]),('-Z',[0,0,-1]),('+X',[1,0,0]),('-X',[-1,0,0])]:
      n=R@np.array(loc,float); d=n@v
      if d>0: out.append(f"{nm} vis{d:.2f} n=({n[0]:+.2f},{n[1]:+.2f},{n[2]:+.2f}) van{van(n):.2f} adr{max(van(n),lift(n)):.2f}")
    print(f"k{k:2d} blade=({blade[0]:+.2f},{blade[1]:+.2f},{blade[2]:+.2f}) "+' | '.join(out))
