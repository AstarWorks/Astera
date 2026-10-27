#!/usr/bin/env python3
"""Reviewed join restructuring and fully covered opaque face removal via Forge.
No partially covered face is removed. Axis-aligned opaque cuboids only.
"""
import argparse,json,sys,shutil
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from core import Project,ROOT
from surfaces import surface_pairs
from raster import faces


def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',required=True,type=Path);ap.add_argument('--finish',action='store_true');a=ap.parse_args()
 materials=json.loads((ROOT/'tools/model-forge/materials.json').read_text())
 for folder in sorted(a.out.iterdir()):
  if not (folder/'concept.json').exists():continue
  p=Project(folder/'project');item=folder.name;log=[]
  stage='finish' if a.finish else 'cleanup'
  if a.finish and item not in {'chain-rod','frag-grenade','glacier-wand','sanctuary-staff'}:continue
  def call(op,args=None):
   r=p.call(op,args);log.append({'op':op,'args':args,'result':r});return r
  call('scene.get',{'fields':['id','name','faces']});call('measure');call('validate')
  call('history.snapshot',{'name':stage+'-before'})
  ops=[];changed=[]
  def update(id,**changes):
   ops.append({'op':'element.update','args':{'id':id,'changes':changes}});changed.append(id)
  def clone(id,new_id,lo,hi):
   e=json.loads(json.dumps(p.element(id)));e.pop('id',None);e['from']=lo;e['to']=hi
   ops.append({'op':'element.add','args':{'id':new_id,'element':e}});changed.append(new_id)
  if item=='scythe':
   update('part006',**{'from':[6.7,24,6.7],'to':[9.3,26,9.3]})
   update('part005',**{'from':[-6,16,7.4],'to':[-4.5,22,8.6]})
   update('part002',**{'to':[9.2,29,9]})
  if item=='chain-rod':
   for i,e in enumerate(p.model['elements']):
    if e['name'].startswith('chain link'):
     # Link ends are classified with their group's start, not the upper bar's y.
     number=int(e['id'][4:]);level=(number-4)//4
     z=7.25 if level%2==0 else 7.75
     lo=e['from'].copy();hi=e['to'].copy();lo[2]=z;hi[2]=z+1
     update(e['id'],**{'from':lo,'to':hi})
  if item=='gale-fan':
   for e in p.model['elements']:
    if e['name']=='fan rib':
     lo=e['from'].copy();lo[1]=12.5;update(e['id'],**{'from':lo})
  if item=='frag-grenade':update('part007',**{'to':[11.7,14.5,8.6]})
  if item=='grappling-hook':update('part006',**{'to':[25,13.5,8.7]})
  if item=='rapier':update('part006',**{'from':[11.5,5.2,7.3],'to':[12.4,10,8.7]})
  if item=='sanctuary-staff':update('part003',**{'from':[7,25,7.2],'to':[9,32,8.8]})
  if item=='war-axe':update('part003',**{'from':[9.2,20,6.8]})
  if item=='cleaver':update('part001',**{'to':[11.8,22,8.9]})
  if item=='mace':
   update('part003',**{'to':[7,22,9]})
   clone('part003','flange-right',[9,17,7],[12.2,22,9])
  if item=='glacier-wand':
   update('part005',**{'from':[4,26,7]});update('part006',**{'from':[10.5,26,7]})
  if item=='revolver':
   update('part004',**{'to':[13,10,8.5]})
   update('part005',**{'to':[12,15,10.6]})
   clone('part005','cylinder-right',[13,10,5.4],[15,15,10.6])
  if a.finish:
   ops=[];changed=[]
   if item=='frag-grenade':
    update('part006',**{'from':[4.8,11,5.3],'to':[11.2,11.45,10.7]})
    update('part007',**{'to':[11.7,14.5,8.6]})
   if item=='glacier-wand':
    update('part007',**{'from':[4,25,6.5],'to':[12,26,9.5]})
    update('part003',**{'from':[6,26,6.5]})
   if item=='sanctuary-staff':update('part005',**{'from':[6.6,27.2,6.5],'to':[9.4,28.8,9.5]})
   if item=='chain-rod':
    update('part003',**{'from':[9.5,18.1,7],'to':[13,18.9,9]})
    for id,lo,hi in [
     ('part008',[13,15.5,6.5],[13.6,18.3,7.1]),
     ('part009',[13,15.5,8.9],[13.6,18.3,9.5]),
     ('part010',[13,15.5,7.1],[13.6,16.1,8.9]),
     ('part011',[13,17.7,7.1],[13.6,18.3,8.9])]:
     update(id,**{'from':lo,'to':hi})
   # Reconstruct all faces before full-containment pruning after the join edits.
   for id in changed:
    ref=next(iter(p.element(id)['faces'].values()))['texture']
    ops.append({'op':'element.update','args':{'id':id,'changes':{'faces':{f:{'texture':ref,'uv':[0,0,1,1]} for f in ['north','south','east','west','up','down']}}}})
  if ops:
   call('batch',{'ops':ops})
   crops=[]
   for id in changed:
    e=p.element(id);f=next(iter(e['faces'].values()));ref=p.model['textures'][f['texture'][1:]]
    mat=next(k for k,v in materials.items() if v['texture']==ref)
    idx=f.get('tintindex',-1);tint=('#%06x'%p.model['astera_tints'][idx]) if idx>=0 else None
    if a.finish:
     tint='#619841' if item=='sanctuary-staff' else '#a2ecff' if item=='glacier-wand' else '#46535e' if item=='frag-grenade' and id=='part006' else '#bccbd0'
    crops.append({'op':'material.apply','args':{'ids':[id],'material':mat,'tint':tint,'seed':20261028}})
   call('batch',{'ops':crops})
  # All faces are opaque vanilla block materials. Only full containment qualifies.
  # For coincident exterior rectangles, keep the first equal face, or the larger face.
  ops=[];removed=[]
  es=p.model['elements']
  for i,e in enumerate(es):
   if e.get('rotation',{}).get('angle',0):continue
   fs=dict(e['faces'])
   for face,(points,normal) in faces(e).items():
    if face not in fs:continue
    pts=np.array(points);n=np.array(normal);axis=int(np.argmax(abs(n)));value=pts[0,axis]
    for j,b in enumerate(es):
     if i==j or b.get('rotation',{}).get('angle',0):continue
     low=np.array(b['from']);high=np.array(b['to'])
     if not (np.all(pts>=low-1e-7) and np.all(pts<=high+1e-7)):continue
     exterior=abs(value-(high[axis] if n[axis]>0 else low[axis]))<1e-7
     if exterior:
      bpts=np.array(faces(b)[face][0]);same=np.allclose(pts.min(0),bpts.min(0)) and np.allclose(pts.max(0),bpts.max(0))
      if face not in b['faces'] or (same and i<j):continue
     del fs[face];removed.append({'id':e['id'],'face':face,'covered_by':b['id']});break
   if fs!=e['faces']:ops.append({'op':'element.update','args':{'id':e['id'],'changes':{'faces':fs}}})
  if ops:call('batch',{'ops':ops})
  call('history.snapshot',{'name':stage+'-after'})
  call('history.diff',{'a':stage+'-before','b':stage+'-after'})
  board=call('board',{'name':stage,'resolution':128});shutil.copyfile(board['path'],folder/(stage+'.png'))
  call('board',{'name':stage+'-contexts','resolution':128,'views':['gui','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand','ground','fixed']})
  call('measure');validation=call('validate')
  export=call('export',{'path':str(folder/'export')});shutil.copyfile(export['path'],ROOT/f'content/models/items/{item}.model.json')
  (folder/(stage+'-operations.json')).write_text(json.dumps(log,indent=2)+'\n')
  (folder/'surface-review.json').write_text(json.dumps({'removed_fully_covered_faces':removed,'remaining_pairs':surface_pairs(p.model),'validation':validation},indent=2)+'\n')
  print(item,'faces removed',len(removed),'pairs remaining',len(surface_pairs(p.model)),flush=True)

if __name__=='__main__':main()
