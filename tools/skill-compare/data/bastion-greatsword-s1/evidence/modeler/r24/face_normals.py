import numpy as np, math, sys
def ax(a,deg):
    t=math.radians(deg); c,s=math.cos(t),math.sin(t)
    if a=='x': return np.array([[1,0,0],[0,c,-s],[0,s,c]])
    if a=='y': return np.array([[c,0,s],[0,1,0],[-s,0,c]])
    return np.array([[c,-s,0],[s,c,0],[0,0,1]])
def eul(y,p,r): return ax('y',-y)@ax('x',p)@ax('z',r)
L0=np.array([.2,1,-.7]);L0/=np.linalg.norm(L0);L1=np.array([-.2,1,.7]);L1/=np.linalg.norm(L1)
def van(n): return min(1,0.6*(max(0,n@L0)+max(0,n@L1))+0.4)
def lift(n,f=0.62,a=0.2,b=0.7):  # ADR-0253 final rule
    x=min(1,max(0,(-n[1]-a)/(b-a))); return f*x*x*(3-2*x)
tilt=eul(0,0,-20); rest=eul(0,90,0)
keys={0:(-336.4,-47.8,-59.4),5:(-228.7,15.6,87.6),6:(-345.0,-27.1,51.9),7:(-401.4,-38.5,28.9),8:(-461.2,-27.2,-15.9)}
for k,(y,p,r) in keys.items():
    R=tilt@eul(y,p,r)@rest
    out=[]
    for name,v in [('+Z face',[0,0,1]),('-Z face',[0,0,-1]),('+X edge',[1,0,0]),('-X edge',[-1,0,0]),('-Y end',[0,-1,0])]:
        n=R@np.array(v,float); out.append(f"{name} n=({n[0]:+.2f},{n[1]:+.2f},{n[2]:+.2f}) van {van(n):.2f} lift {lift(n):.2f}")
    print('key',k); print('\n'.join('   '+o for o in out))
