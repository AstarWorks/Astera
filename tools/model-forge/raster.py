"""Deterministic, orthographic Java cuboid preview; adapted from the forge seed."""
import math
import numpy as np
from PIL import Image, ImageDraw

VIEWS = {'front': [0,0,0], 'side': [0,-90,0], 'top': [90,0,0], 'iso': [20,-30,0]}
CENTER = np.array([8,13,8])
SPAN = 36

def rotation(axis,angle):
    a=math.radians(angle);c,s=math.cos(a),math.sin(a)
    if axis=='x':return np.array([[1,0,0],[0,c,-s],[0,s,c]])
    if axis=='y':return np.array([[c,0,s],[0,1,0],[-s,0,c]])
    return np.array([[c,-s,0],[s,c,0],[0,0,1]])

def display_matrix(angles):
    return rotation('x',angles[0])@rotation('y',angles[1])@rotation('z',angles[2])

def faces(e):
    x0,y0,z0=e['from'];x1,y1,z1=e['to']
    # Java face UV corner order: u0/v0, u1/v0, u1/v1, u0/v1.
    return {
       'south':([[x0,y1,z1],[x1,y1,z1],[x1,y0,z1],[x0,y0,z1]],[0,0,1]),
       'north':([[x1,y1,z0],[x0,y1,z0],[x0,y0,z0],[x1,y0,z0]],[0,0,-1]),
       'east':([[x1,y1,z1],[x1,y1,z0],[x1,y0,z0],[x1,y0,z1]],[1,0,0]),
       'west':([[x0,y1,z0],[x0,y1,z1],[x0,y0,z1],[x0,y0,z0]],[-1,0,0]),
       'up':([[x0,y1,z0],[x1,y1,z0],[x1,y1,z1],[x0,y1,z1]],[0,1,0]),
       'down':([[x0,y0,z1],[x1,y0,z1],[x1,y0,z0],[x0,y0,z0]],[0,-1,0])}

def element_points(e, points):
    er=e.get('rotation',{}); origin=np.array(er.get('origin',[8,8,8]))
    p=(np.array(points)-origin)@rotation(er.get('axis','x'),er.get('angle',0)).T
    if er.get('rescale',False):
        factors=np.full(3,1/math.cos(math.radians(er.get('angle',0))))
        factors['xyz'.index(er['axis'])]=1
        p*=factors
    return p+origin


def default_uv(e, face):
    x,y,z=e['from']; X,Y,Z=e['to']
    return {'down':[x,16-Z,X,16-z], 'up':[x,z,X,Z],
            'north':[16-X,16-Y,16-x,16-y], 'south':[x,16-Y,X,16-y],
            'west':[z,16-Y,Z,16-y], 'east':[16-Z,16-Y,16-z,16-y]}[face]


def render(model, textures, view, size=256, clay=False, camera=None, mode='textured', highlight=(), overlay=()):
    """Return RGBA, silhouette and diagnostics. Fixed cameras, never auto-fit."""
    d=model.get('display',{}).get(view,{})
    angles=list(VIEWS.get(view,d.get('rotation',[0,0,0])))
    if view.endswith('lefthand'):
        angles[1]*=-1; angles[2]*=-1
    R=display_matrix(angles)
    scale=np.array(d.get('scale',[1,1,1])) if view not in VIEWS else np.ones(3)
    T=np.array(d.get('translation',[0,0,0])) if view not in VIEWS else np.zeros(3)
    if view.endswith('lefthand'): T[0]*=-1
    center=CENTER if view in VIEWS else np.full(3,8)
    span=SPAN if view in VIEWS else 16 if view=='gui' else 32
    if camera:
        R=display_matrix([camera.get('pitch',0),camera.get('yaw',0),0])
        center=np.array(camera.get('center',center)); span=float(camera.get('ortho',span))
    out=np.zeros((size,size,4),dtype=np.uint8)
    depth=np.full((size,size),-np.inf)
    projected=[]; lines=[]; labels=[]
    for e in model['elements']:
        for face,(points,_) in faces(e).items():
            if face not in e['faces']: continue
            p=((element_points(e,points)-center)*scale)@R.T+T
            normal=np.cross(p[1]-p[0],p[2]-p[0])
            # Vertex winding is clockwise as seen from outside.
            normal=-normal
            length=np.linalg.norm(normal)
            if length<1e-10: continue
            visible=normal[2]>1e-10
            if not visible and mode not in ('wireframe','xray'): continue
            normal/=length
            f=e['faces'][face]
            u,v,U,V=f.get('uv',default_uv(e,face))
            uv=np.roll(np.array([[u,v],[U,v],[U,V],[u,V]])/16,
                       f.get('rotation',0)//90,axis=0)
            atlas=textures[f['texture']]
            shade=1 if not e.get('shade',True) else .72+.28*max(0,float(normal@np.array([-.3,.5,.8124])))
            p=p.copy();p[:,:2]=p[:,:2]*[size/span,-size/span]+size/2
            projected.extend(p[:,:2].tolist())
            lines.append((p[:,:2].tolist(), e.get('id') in highlight))
            if 'ids' in overlay: labels.append((p[:,:2].mean(0).tolist(),e.get('id','?')))
            if mode=='wireframe' or not visible: continue
            for ids in [(0,1,2),(0,2,3)]:
                q=p[list(ids)];tex=uv[list(ids)]
                xmin,ymin=np.maximum(0,np.floor(q[:,:2].min(0)).astype(int))
                xmax,ymax=np.minimum(size-1,np.ceil(q[:,:2].max(0)).astype(int))
                if xmin>xmax or ymin>ymax: continue
                Y,X=np.mgrid[ymin:ymax+1,xmin:xmax+1];X=X+.5;Y=Y+.5
                a,b,c=q;den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
                if abs(den)<1e-9: continue
                w0=((b[1]-c[1])*(X-c[0])+(c[0]-b[0])*(Y-c[1]))/den
                w1=((c[1]-a[1])*(X-c[0])+(a[0]-c[0])*(Y-c[1]))/den
                weights=np.stack([w0,w1,1-w0-w1],-1);z=weights@q[:,2]
                target=depth[ymin:ymax+1,xmin:xmax+1]
                hit=(weights.min(-1)>=-1e-8)&(z>target)
                coords=weights@tex
                tx=np.clip(np.floor(coords[:,:,0]*atlas.shape[1]).astype(int),0,atlas.shape[1]-1)
                ty=np.clip(np.floor(coords[:,:,1]*atlas.shape[0]).astype(int),0,atlas.shape[0]-1)
                color=atlas[ty,tx].copy();hit&=color[:,:,3]>=128
                if clay or mode=='solid': color[:,:,:3]=[178,190,204]
                if e.get('id') in highlight: color[:,:,:3]=[255,186,58]
                color[:,:,:3]=np.rint(color[:,:,:3]*shade).clip(0,255).astype(np.uint8)
                color[:,:,3]=255
                out[ymin:ymax+1,xmin:xmax+1][hit]=color[hit];target[hit]=z[hit]
    mask=out[:,:,3]>0
    bounds=None
    if mask.any():
        y,x=np.where(mask);bounds=[int(x.min()),int(y.min()),int(x.max()),int(y.max())]
    pts=np.array(projected)
    clipped=bool(len(pts) and ((pts<0).any() or (pts>size).any()))
    im=Image.fromarray(out)
    if mode in ('wireframe','xray') or overlay:
        if mode=='xray':
            out[:,:,3]=np.where(mask,90,0); im=Image.fromarray(out)
        draw=ImageDraw.Draw(im)
        if 'grid' in overlay:
            for n in range(0,size,max(1,int(size/span))):
                draw.line([(n,0),(n,size)],fill=(65,80,95,110)); draw.line([(0,n),(size,n)],fill=(65,80,95,110))
        if mode in ('wireframe','xray'):
            for points,selected in lines: draw.line([tuple(p) for p in points+[points[0]]],fill='#ffba3a' if selected else '#a5cde6',width=1)
        if 'ids' in overlay:
            seen=set()
            for p,id in labels:
                if id not in seen: draw.text(tuple(p),id,fill='white'); seen.add(id)
        if 'bbox' in overlay and bounds: draw.rectangle(bounds,outline='#ffba3a')
        if 'axes' in overlay:
            origin=np.array([size*.12,size*.88])
            for i,color in enumerate(('red','lime','cyan')):
                end=origin+R[:2,i]*[size*.10,-size*.10]
                draw.line([tuple(origin),tuple(end)],fill=color,width=2); draw.text(tuple(end),'XYZ'[i],fill=color)
    return im, mask, {'bounds':bounds,'clipped':clipped,'span':span}
