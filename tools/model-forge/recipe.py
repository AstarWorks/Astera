#!/usr/bin/env python3
"""Designer-side model-forge. Never imported or invoked by Gradle."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
from raster import VIEWS, CENTER, SPAN, render
from validate import validate
from materials import vanilla_path

ROOT=Path(__file__).resolve().parents[2]
WORK=Path(__file__).resolve().parent/'work'


def write_json(path,data): path.write_text(json.dumps(data,indent=2)+'\n')


def texture_map(model,override=None):
    cache={}
    def resolve(ref,seen=()):
        if ref in seen: raise ValueError('Cyclic texture alias: '+ref)
        if ref.startswith('#'): return resolve(model['textures'][ref[1:]],seen+(ref,))
        if ref not in cache:
            if ref.startswith('minecraft:'): path=vanilla_path(ref)
            elif override: path=override
            else:
                namespace,rel=ref.split(':',1)
                if namespace!='astera' or '..' in Path(rel).parts: raise ValueError('Only local astera textures are supported: '+ref)
                path=ROOT/'content/textures'/(rel+'.png')
            cache[ref]=np.array(Image.open(path).convert('RGBA'))
        return cache[ref]
    return {'#'+key:resolve('#'+key) for key in model['textures']}


def load_model(path,seen=()):
    path=Path(path).resolve()
    if path in seen: raise ValueError('Cyclic model parent')
    m=json.loads(path.read_text())
    if 'parent' in m:
        namespace,rel=m.pop('parent').split(':',1)
        if namespace!='astera' or '..' in Path(rel).parts:
            raise ValueError('Resolve vanilla/builtin parents to explicit cuboids first; no guessed inheritance')
        parent=load_model(ROOT/'content/models'/(rel+'.model.json'),seen+(path,))
        for key in ('textures','display'): m[key]={**parent.get(key,{}),**m.get(key,{})}
        m={**parent,**m}
    return m


def tile(im):
    bg=Image.new('RGB',im.size,'#202b39')
    if im.mode=='RGBA': bg.paste(im,(0,0),im)
    else: bg.paste(im)
    return bg


def render_sheet(item,model,textures,target,out,clay=False):
    import concept
    out.mkdir(parents=True,exist_ok=True)
    spec=json.loads(target.read_text())
    if spec['item']!=item: raise ValueError('Concept belongs to a different item')
    if spec['camera']!={'center':CENTER.tolist(),'span':SPAN,'views':VIEWS} or spec['key']!=list(concept.KEY):
        raise ValueError('Concept camera/key differs from the fixed comparison contract')
    sheet=Image.new('RGB',(1280,1150),'#131b26');d=ImageDraw.Draw(sheet)
    d.text((16,12),f'ASTERA / {item} / construction target -> current model -> silhouette overlay',fill='white')
    metrics={}
    for i,view in enumerate(VIEWS):
        ref=concept.draw_view(spec,view);im,mask,diag=render(model,textures,view,clay=clay)
        refmask=(np.array(ref)!=concept.KEY).any(-1)
        union=(refmask|mask).sum();iou=float((refmask&mask).sum()/union) if union else 1.0
        overlay=np.full((256,256,3),[24,32,43],np.uint8)
        overlay[refmask&mask]=[180,201,209];overlay[refmask&~mask]=[234,85,114];overlay[mask&~refmask]=[67,194,233]
        y=54+i*274
        sheet.paste(ref,(24,y));sheet.paste(tile(im),(304,y));sheet.paste(Image.fromarray(overlay),(584,y))
        d.text((24,y-16),view+' / target',fill='white');d.text((304,y-16),'current render',fill='white')
        d.text((584,y-16),f'IoU {iou:.3f} | pink missing / cyan excess',fill='white')
        im.save(out/f'{view}.png');metrics[view]={'iou':round(iou,5),**diag}
    for i,view in enumerate(('gui','firstperson_righthand','thirdperson_righthand','ground','fixed')):
        im,mask,diag=render(model,textures,view,size=240,clay=clay)
        sheet.paste(tile(im).resize((200,200),Image.Resampling.NEAREST),(1000,54+i*220));d.text((1000,38+i*220),view,fill='white')
        im.save(out/f'{view}.png');metrics[view]=diag
    sheet.save(out/'comparison.png')
    # Native inventory sizes, displayed enlarged with nearest filtering.
    small=Image.new('RGB',(384,160),'#131b26');sd=ImageDraw.Draw(small)
    for i,size in enumerate((16,32,64)):
        im,_,_=render(model,textures,'gui',size=size)
        small.paste(tile(im).resize((128,128),Image.Resampling.NEAREST),(i*128,24));sd.text((i*128+5,5),f'{size}px inventory',fill='white')
    small.save(out/'inventory.png')
    report={'item':item,'concept_sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'views':metrics,
            'validation':validate(model,textures),'note':'IoU compares independent polygon construction targets, not the source icon. Hand views are isolated orthographic transform previews.'}
    write_json(out/'review.json',report)
    print(json.dumps({'item':item,'iou':{v:x['iou'] for v,x in metrics.items() if 'iou' in x},'valid':report['validation']['valid'],'clipped':[v for v,x in metrics.items() if x['clipped']]}))
    return report


def main():
    import author
    import concept
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='command',required=True)
    for name in ('concept','build','render','validate','finalize'):
        p=sub.add_parser(name);p.add_argument('item');p.add_argument('--out',type=Path)
        if name=='concept':
            p.add_argument('--brief',required=True,help='Art direction stored with the locked drawing');p.add_argument('--design',type=Path,help='Independent polygon construction JSON for a new item')
        if name=='build': p.add_argument('--stage',type=int,choices=range(1,6),required=True)
        if name in ('render','validate','finalize'):
            p.add_argument('--model',type=Path);p.add_argument('--texture',type=Path)
        if name=='render': p.add_argument('--clay',action='store_true');p.add_argument('--concept',type=Path)
    args=parser.parse_args()
    if not re.fullmatch('[a-z0-9-]+',args.item): parser.error('Invalid item id')
    work=WORK/args.item
    if args.command=='concept': concept.generate(args.item,args.brief,args.out or work,args.design);return
    if args.command=='build':
        author.build(args.item,args.stage,args.out or work/f'round-{args.stage}');return
    model=load_model(args.model or ROOT/'content/models/items'/f'{args.item}.model.json')
    textures=texture_map(model,args.texture)
    if args.command=='validate':
        report=validate(model,textures);print(json.dumps(report,indent=2))
        if args.out: args.out.mkdir(parents=True,exist_ok=True);write_json(args.out/'validation.json',report)
        raise SystemExit(0 if report['valid'] else 1)
    # Validate before rasterization, so malformed inputs fail with useful diagnostics.
    checked=validate(model,textures)
    if not checked['valid']: raise ValueError(json.dumps(checked))
    if args.command=='render':
        render_sheet(args.item,model,textures,args.concept or work/'concept.json',args.out or work/'preview',args.clay)
    elif args.command=='finalize':
        report=render_sheet(args.item,model,textures,work/'concept.json',work/'final')
        if any(v['clipped'] for v in report['views'].values()): raise ValueError('Clipped display: adjust transforms before finalizing')
        if any(report['views'][v]['iou']<.8 for v in VIEWS): raise ValueError('Silhouette IoU below 0.80: refine before finalizing')
        modelpath=ROOT/'content/models/items'/f'{args.item}.model.json';modelpath.parent.mkdir(parents=True,exist_ok=True)
        texpath=ROOT/'content/textures/items'/f'{args.item}.png';texpath.parent.mkdir(parents=True,exist_ok=True)
        expected=f'astera:items/{args.item}'
        if set(model['textures'].values())!={expected}: raise ValueError('Final output requires one item-local atlas')
        write_json(modelpath,model);Image.fromarray(textures['#0']).save(texpath)
        # Final sheet includes concept source context and native-size readability panel.
        im=Image.open(work/'final/comparison.png');cs=Image.open(work/'concept-sheet.png');inv=Image.open(work/'final/inventory.png')
        final=Image.new('RGB',(1280,im.height+cs.height+170),'#131b26');final.paste(cs,(0,0));final.paste(im,(0,cs.height));final.paste(inv,(24,cs.height+im.height))
        final.save(work/'final-sheet.png')
        report['sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (modelpath,texpath)}
        report['rounds']=[]
        for round_dir in sorted(work.glob('round-*')):
            if (round_dir/'review.json').exists():
                prior=json.loads((round_dir/'review.json').read_text())
                report['rounds'].append({'round':round_dir.name,'views':prior['views'],
                    'concept_sha256':prior['concept_sha256'],
                    'sha256':{name:hashlib.sha256((round_dir/name).read_bytes()).hexdigest()
                              for name in ('model.json','texture.png','comparison.png')}})
        write_json(work/'final-review.json',report)

if __name__=='__main__':
    try: main()
    except (ValueError,KeyError,OSError) as e: raise SystemExit(str(e))
