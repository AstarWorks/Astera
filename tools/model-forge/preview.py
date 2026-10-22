"""Multi-view inspection and image comparison on the shared software rasterizer."""
import math
import numpy as np
from PIL import Image, ImageDraw
from raster import render, display_matrix
from core import bbox

VIEWS={'front':(0,0),'back':(180,0),'left':(90,0),'right':(-90,0),
       'top':(0,90),'bottom':(0,-90),'iso':(-30,20),'iso_back':(150,20)}
DISPLAYS=('gui','firstperson_righthand','thirdperson_righthand','ground','fixed')


def tile(im):
    bg=Image.new('RGBA',im.size,'#202b39'); bg.alpha_composite(im.convert('RGBA')); return bg.convert('RGB')


def preview(project, op, a):
    size=a.get('resolution',256)
    if type(size)!=int or not 32<=size<=1024: raise ValueError('Resolution must be integer 32..1024')
    if set(a.get('overlay',[]))-{'ids','grid','axes','bbox'}: raise ValueError('Unknown overlay')
    mode=a.get('mode','textured')
    if mode not in ('textured','solid','wireframe','xray','uv'): raise ValueError('Unknown render mode')
    if mode=='uv':
        key=a.get('key',next(iter(project.state['images']),'0')); im=project.image(key).resize((size,size),Image.Resampling.NEAREST)
        d=ImageDraw.Draw(im)
        for r in project.regions():
            if r['texture']!='#'+key: continue
            u,v,U,V=np.array(r['uv'])*size/16
            d.rectangle((min(u,U),min(v,V),max(u,U),max(v,V)),outline='#ff36ad')
            if 'ids' in a.get('overlay',[]): d.text((min(u,U),min(v,V)),r['id']+'/'+r['face'],fill='white')
        return project.artifact(im,a.get('name','atlas'),a)
    views=a.get('views',list(VIEWS))
    if not isinstance(views,list) or not 1<=len(views)<=16: raise ValueError('Supply 1..16 views')
    if any(v not in VIEWS and v not in DISPLAYS for v in views): raise ValueError('Unknown view')
    boxes=np.array([bbox(e) for e in project.model['elements']])
    center=(boxes[:,0].min(0)+boxes[:,1].max(0))/2 if len(boxes) else np.array([8,8,8])
    # One shared framing across canonical views preserves proportions between tiles.
    span=max(1,float(np.linalg.norm(boxes[:,1].max(0)-boxes[:,0].min(0)))*1.15) if len(boxes) else 32
    textures={'#'+k:np.array(project.image(k)) for k in project.state['images']}
    images=[]; artifacts=[]; metrics={}
    for view in views:
        camera=None
        if view in VIEWS:
            yaw,pitch=VIEWS[view]; camera={'yaw':yaw,'pitch':pitch,'center':center.tolist(),'ortho':span}
        if a.get('camera'):
            camera={**(camera or {}),**a['camera']}
            if 'distance' in camera: camera['ortho']=float(camera['distance'])*2*math.tan(math.radians(35))
        if camera and (not math.isfinite(float(camera.get('ortho',span))) or float(camera.get('ortho',span))<=0): raise ValueError('Camera span must be positive and finite')
        im,mask,diag=render(project.model,textures,view,size=size,camera=camera,mode=mode,highlight=a.get('highlight',[]),overlay=a.get('overlay',[]))
        if view in ('firstperson_righthand','thirdperson_righthand'):
            # Approximate screen-space hand guide, not geometry or part of the silhouette score.
            display=project.model.get('display',{}).get(view,{})
            grip=(np.array([8,5,8])-8)*np.array(display.get('scale',[1,1,1]))
            grip=grip@display_matrix(display.get('rotation',[0,0,0])).T+np.array(display.get('translation',[0,0,0]))
            x,y=grip[:2]*[size/diag['span'],-size/diag['span']]+size/2
            hand=Image.new('RGBA',im.size); d=ImageDraw.Draw(hand)
            d.rounded_rectangle((x-size*.035,y-size*.035,x+size*.11,y+size*.12),radius=size*.025,fill='#ac805c',outline='#e2b58e')
            hand.alpha_composite(im); im=hand
            ImageDraw.Draw(im).text((5,size-15),'approx. hand',fill='white')
        artifacts.append({'view':view,**project.artifact(im,a.get('name','render')+'-'+view,a),'diagnostics':diag})
        if op=='compare':
            refpath=a.get('references',{}).get(view,a.get('reference_image'))
            if not refpath: raise ValueError('Missing reference for '+view)
            ref=Image.open(refpath).convert('RGBA').resize((size,size),Image.Resampling.NEAREST)
            ar=np.array(ref); bg=np.array(a.get('background',[255,0,255]))
            refmask=(ar[:,:,3]>0)&np.any(ar[:,:,:3]!=bg,axis=2)
            union=(refmask|mask).sum(); iou=float((refmask&mask).sum()/union) if union else 1.
            def histogram(rgb,m):
                h=np.histogramdd(rgb[m,:3],bins=(8,8,8),range=((0,256),)*3)[0]; return h/max(1,h.sum())
            distance=float(np.abs(histogram(ar,refmask)-histogram(np.array(im),mask)).sum()/2)
            diff=np.full((size,size,3),[24,32,43],dtype=np.uint8); diff[refmask&mask]=[180,201,209]; diff[refmask&~mask]=[234,85,114]; diff[mask&~refmask]=[67,194,233]
            row=Image.new('RGB',(size*3,size)); row.paste(tile(ref),(0,0)); row.paste(tile(im),(size,0)); row.paste(Image.fromarray(diff),(size*2,0))
            images.append((view+f' IoU={iou:.3f} color={distance:.3f}',row)); metrics[view]={'iou':iou,'colour_histogram_distance':distance}
        else: images.append((view,tile(im)))
    if op=='render': return {'images':artifacts}
    cols=1 if op=='compare' else min(4,len(images)); width=images[0][1].width; height=size+24
    board=Image.new('RGB',(cols*width,math.ceil(len(images)/cols)*height),'#131b26'); d=ImageDraw.Draw(board)
    for i,(label,im) in enumerate(images):
        x=i%cols*width; y=i//cols*height; board.paste(im,(x,y+24)); d.text((x+6,y+6),label,fill='white')
    return {**project.artifact(board,a.get('name',op),a),'images':artifacts,'metrics':metrics}
