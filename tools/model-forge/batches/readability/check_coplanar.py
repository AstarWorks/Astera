"""Read-only geometry audit for the 2026-10-23 polish; run from repository root.
Only same-facing coplanar rectangles with positive-area overlap are counted.
Opposite-facing contact faces, near-coplanar faces, and visibility are not classified.
"""
import sys, json, itertools
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from raster import faces, element_points

def conflicts(m):
 # Exact coplanar rectangles, including rotated cuboids; edge-only contacts excluded by SAT.
 fs=[]
 for i,e in enumerate(m['elements']):
  for f,(pts,_) in faces(e).items():
   if f not in e['faces']:continue
   p=element_points(e,pts);u=p[1]-p[0];v=p[3]-p[0];n=np.cross(u,v);area=np.linalg.norm(n);n/=area
   fs.append((i,f,p,n,area,u/np.linalg.norm(u),v/np.linalg.norm(v)))
 out=[]
 for a,b in itertools.combinations(fs,2):
  if a[0]==b[0] or np.dot(a[3],b[3])<1-1e-8:continue
  if abs(np.dot(b[2][0]-a[2][0],a[3]))>1e-6:continue
  if all(min((a[2]@axis).max(),(b[2]@axis).max())-max((a[2]@axis).min(),(b[2]@axis).min())>1e-6 for axis in (a[5],a[6],b[5],b[6])):
   out.append((a,b))
 return out

if __name__ == '__main__':
    total = 0
    for path in sorted(Path('content/models/items').glob('*.model.json')):
        count = len(conflicts(json.loads(path.read_text())))
        total += count
        print(f'{path.stem}: {count}')
    print(f'Total same-facing coplanar overlaps: {total}')
    sys.exit(bool(total))
