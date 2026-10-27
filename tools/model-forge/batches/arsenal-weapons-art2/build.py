#!/usr/bin/env python3
"""Author the simple arsenal via the shared Forge operation/history/material pipeline.
Run blockout, inspect PNGs, then run shape; models are never used as concept targets.
"""
import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path
import numpy as np
import yaml
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from core import Project, ROOT
from raster import display_matrix

BATCH = Path(__file__).parent
SPEC = ROOT/'tools/item-art/sheets/arsenal-weapons-art2.yaml'
VANILLA = {'longbow','shortbow','hand-crossbow','repeater-crossbow','shield-bulwark'}
GUNS = {'revolver','smg','sniper','harpoon','grappling-hook'}
SMALL = {'throwing-knives','sticky-bomb','venom-flask','smoke-bomb','delay-mine','frag-grenade','flash-bang','fireball-charge','gale-fan'}
# Semantic vanilla patterns; colours are constant face tints, never derivative PNGs.
MATERIALS = {
    'steel': ('metal.iron_block','#bccbd0'), 'edge': ('metal.iron_block','#edf5f5'),
    'dark': ('metal.iron_block','#46535e'), 'gold': ('metal.gold_block',None),
    'wood': ('wood.stripped_oak_log',None), 'wrap': ('cloth.white_wool','#62422f'),
    'red': ('cloth.white_wool','#b93431'), 'cyan': ('metal.iron_block','#55d6df'),
    'blue': ('cloth.white_wool','#315e9c'), 'ice': ('metal.iron_block','#a2ecff'),
    'green': ('metal.iron_block','#619841'), 'orange': ('metal.iron_block','#ef922e'),
    'white': ('metal.iron_block','#e6e8db'), 'yellow': ('metal.iron_block','#f6d45b'),
}

def design(item):
    parts=[]
    def box(name,lo,hi,mat='steel',angle=0,origin=None):
        e={'name':name,'from':lo,'to':hi,'faces':{f:{'texture':'#clay','uv':[0,0,1,1]} for f in ['north','south','east','west','up','down']}}
        if angle: e['rotation']={'axis':'z','angle':angle,'origin':origin or [8,8,8]}
        parts.append((e,mat))
    def shaft(top=25,mat='wood'):
        box('haft',[7.2,4,7.2],[8.8,top,8.8],mat)
        box('grip',[7,5.5,7],[9,10,9],'wrap')
    def sword(length=26,width=3,mat='steel',guard='gold'):
        box('grip',[7,4,7],[9,10,9],'wrap')
        box('pommel',[6.8,3,6.8],[9.2,4.5,9.2],guard)
        box('guard',[3.5,10,6.8],[12.5,11.2,9.2],guard)
        box('blade',[8-width/2,11.2,7.35],[8+width/2,length-2,8.65],mat)
        box('point',[7.5,length-2,7.45],[8.5,length,8.55],'edge')
        box('ridge',[7.65,11.2,7.15],[8.35,length-3,8.85],'dark')
    if item=='iron-blade': sword()
    elif item=='war-axe':
        shaft(24)
        box('red binding',[6.8,20,6.8],[9.2,22,9.2],'red')
        box('axe cheek',[8.8,20,6.8],[13.5,27,9.2])
        box('broad cutting edge',[13.5,18.5,7.1],[15,28,8.9],'edge')
        box('poll',[5.5,22,6.8],[7.2,25,9.2],'dark')
    elif item=='spear':
        shaft(24)
        box('socket',[6.8,22.5,6.8],[9.2,25,9.2])
        box('leaf base',[6,25,7.3],[10,28,8.7])
        box('leaf shoulder',[6.7,28,7.4],[9.3,30,8.6],'edge')
        box('needle',[7.55,30,7.5],[8.45,32,8.5],'edge')
    elif item=='mace':
        shaft(17)
        box('iron core',[6.3,16,6.3],[9.7,23,9.7],'dark')
        box('x flanges',[3.8,17,7],[12.2,22,9])
        box('z flanges',[7,17,3.8],[9,22,12.2])
        box('crown',[6.5,23,6.5],[9.5,24.5,9.5],'edge')
    elif item=='twin-daggers':
        for x,z in [(5.7,7),(10.3,9)]:
            box('dagger grip',[x-.8,4,z-.8],[x+.8,10,z+.8],'wrap')
            box('dagger guard',[x-2,10,z-1],[x+2,11,z+1],'dark')
            box('dagger blade',[x-1.1,11,z-.6],[x+1.1,20,z+.6])
            box('dagger tip',[x-.45,20,z-.5],[x+.45,22,z+.5],'edge')
        box('paired grip bridge',[5.7,7.5,7.1],[10.3,8.5,8.9],'dark')
    elif item=='katana':
        box('red hilt',[7,3,7],[9,10,9],'red')
        for y in [4,6,8]: box('wrap knot',[6.85,y,6.85],[9.15,y+.5,9.15],'dark')
        box('tsuba',[5.7,10,5.8],[10.3,10.8,10.2],'dark')
        box('blade root',[7,10.8,7.5],[9,21,8.5])
        box('curved blade',[7.5,21,7.5],[9.5,27,8.5])
        box('curved tip',[8.2,27,7.5],[9.5,30,8.5],'edge')
    elif item=='halberd':
        shaft(28)
        box('axe socket',[6.5,23,6.5],[9.5,27,9.5],'dark')
        box('axe wing',[9.5,23,7],[14,27,9])
        box('axe edge',[14,21,7.2],[15.2,28,8.8],'edge')
        box('rear hook',[3.5,25,7],[6.5,26.5,9])
        box('hook tooth',[3.5,23,7.2],[4.7,25,8.8])
        box('pike',[7.4,28,7.4],[8.6,32,8.6],'edge')
    elif item=='rapier':
        sword(30,1.2,guard='gold')
        # Open rectangular bow keeps finger clearance visible.
        box('knuckle bow',[11.5,4.8,7.3],[12.4,10.2,8.7],'gold')
        box('bow heel',[8.5,4.3,7.3],[12.4,5.2,8.7],'gold')
    elif item=='cleaver':
        box('handle',[7,3.5,7],[9,11,9],'wrap')
        box('heel',[5,11,7.1],[12.5,22,8.9])
        box('clipped nose',[5,22,7.1],[10.5,24,8.9])
        box('cutting edge',[4.3,11,7.25],[5,23,8.75],'edge')
        box('spine',[11.8,11,6.9],[12.8,22,9.1],'dark')
    elif item=='scythe':
        shaft(28,'dark')
        box('blade root',[3,26.5,7],[8.8,29,9])
        box('crescent shoulder',[-2,25,7.1],[3,27.5,8.9])
        box('crescent sweep',[-5,21,7.2],[-1,25.5,8.8])
        box('hook tip',[-6,17,7.4],[-4.5,22,8.6],'edge')
        box('collar',[6.7,24,6.7],[9.3,26,9.3],'gold')
    elif item=='club':
        shaft(15)
        box('club body',[5.8,13,5.8],[10.2,23,10.2],'wood')
        box('club crown',[5.2,21,5.2],[10.8,25,10.8],'wood')
        for y in [14,19,23]: box('iron band',[5,y,5],[11,y+1,11],'dark')
    elif item in GUNS:
        end={'revolver':22,'smg':24,'sniper':31,'harpoon':28,'grappling-hook':22}[item]
        box('grip',[7,4.5,6.6],[9.2,11,9.4],'wrap')
        box('receiver',[5,10.5,6.2],[16,14.5,9.8],'dark')
        box('barrel',[16,11.5,7.1],[end,13.5,8.9])
        box('trigger guard lower',[9,8.7,7.5],[13,9.4,8.5],'steel')
        box('trigger guard front',[12.3,9.4,7.5],[13,10.5,8.5],'steel')
        if item=='revolver':
            box('cylinder',[11,10,5.4],[15,15,10.6])
            box('cylinder band',[12,10,5.2],[13,15,10.8],'dark')
            box('front sight',[20,13.5,7.6],[21,14.4,8.4],'dark')
        elif item in {'smg','sniper'}:
            box('stock rail',[1,11,7.1],[5,13.2,8.9],'dark')
            box('stock heel',[0,8,6.5],[2,14,9.5],'wrap')
            box('magazine',[12,5,6.8],[15,10.5,9.2],'dark')
            if item=='smg':
                box('cyan band',[16,11.8,6.8],[17,13.2,9.2],'cyan')
                box('folding sight',[8,14.5,7.4],[10,16,8.6],'dark')
            else:
                box('scope mount',[10,14.5,7.3],[17,15.5,8.7],'steel')
                box('scope',[9,15.5,6.8],[20,17.5,9.2],'dark')
                box('scope lens',[20,15.7,7],[20.5,17.3,9],'cyan')
        elif item=='harpoon':
            box('spearhead',[25,11,6.7],[30,14,9.3],'cyan')
            box('upper barb',[25,14,7.3],[27,16,8.7],'cyan')
            box('lower barb',[25,9,7.3],[27,11,8.7],'cyan')
            box('pressure tank',[6,13,5.8],[15,15.5,10.2],'blue')
        else:
            box('rope drum',[11,9.5,5],[16,15.5,11],'wrap')
            box('hook stem',[22,11.5,7.3],[27,13.5,8.7])
            box('hook crossbar',[25,9,7.3],[26.5,16,8.7])
            box('upper hook',[22,15,7.3],[25,16,8.7])
            box('lower hook',[22,9,7.3],[25,10,8.7])
    elif item=='throwing-knives':
        for x,ang in [(5.5,22.5),(8,0),(10.5,-22.5)]:
            box('knife grip',[x-.65,5,7.3],[x+.65,10,8.7],'dark',ang,[8,8,8])
            box('knife blade',[x-.85,10,7.5],[x+.85,17,8.5],'steel',ang,[8,8,8])
            box('knife point',[x-.3,17,7.6],[x+.3,19,8.4],'edge',ang,[8,8,8])
    elif item in {'sticky-bomb','smoke-bomb','frag-grenade','flash-bang','venom-flask','delay-mine'}:
        mat={'sticky-bomb':'red','smoke-bomb':'dark','frag-grenade':'green','flash-bang':'white','venom-flask':'green','delay-mine':'dark'}[item]
        if item=='delay-mine':
            box('flat mine plate',[3,6.5,3],[13,9,13],'dark')
            box('pressure pad',[5,9,5],[11,10,11],'orange')
            for x in [3.5,11.5]: box('bolt',[x,9,7.5],[x+1,9.5,8.5])
        else:
            box('body',[5,5,5.5],[11,12,10.5],mat)
            box('shoulder',[5.7,12,6.2],[10.3,13.5,9.8],mat)
            box('base',[5.7,4,6.2],[10.3,5,9.8],'dark')
            box('cap',[6.5,13.5,6.5],[9.5,15,9.5],'wrap' if item=='venom-flask' else 'steel')
            if item=='sticky-bomb':
                box('adhesive plate',[4.5,6.5,5],[11.5,8,11])
                box('fuse',[7.5,15,7.5],[8.5,18,8.5],'yellow')
            elif item in {'flash-bang','smoke-bomb'}:
                box('identification band',[4.8,8,5.3],[11.2,10,10.7],'gold' if item=='flash-bang' else 'cyan')
                box('pin ring crossbar',[6,15,7.5],[10,16,8.5])
            elif item=='frag-grenade':
                for y in [6,8.5,11]: box('segmentation band',[4.8,y,5.3],[11.2,y+.45,10.7],'dark')
                box('safety spoon',[10.8,9,7.4],[11.7,15,8.6])
                box('lever top',[8,14.5,7.4],[11.7,15.3,8.6])
            else:
                box('poison label',[6.5,7,5.2],[9.5,10,5.6],'yellow')
    elif item in {'shock-wand','ember-rod','frost-rod','glacier-wand','sanctuary-staff','chain-rod'}:
        top=25 if item in {'glacier-wand','sanctuary-staff'} else 19
        shaft(top,'white' if item=='sanctuary-staff' else 'wood')
        mat={'shock-wand':'yellow','ember-rod':'orange','frost-rod':'ice','glacier-wand':'ice','sanctuary-staff':'gold','chain-rod':'dark'}[item]
        box('focus collar',[6.5,top-2,6.5],[9.5,top,9.5],mat)
        if item in {'ember-rod','frost-rod','glacier-wand'}:
            box('crystal core',[6,top,6.5],[10,top+4,9.5],mat)
            box('crystal tip',[7,top+4,7],[9,top+6,9],mat)
            if item=='glacier-wand':
                box('left ice prong',[4,top,7],[5.5,top+4,9],mat)
                box('right ice prong',[10.5,top,7],[12,top+4,9],mat)
                box('prong bridge',[4,top,7],[12,top+1,9],mat)
        elif item=='shock-wand':
            box('fork base',[4.5,top,7],[11.5,top+1,9],mat)
            box('fork left',[4.5,top+1,7],[6,top+6,9],mat)
            box('fork right',[10,top+1,7],[11.5,top+6,9],mat)
        elif item=='sanctuary-staff':
            box('cross stem',[7,top,7],[9,32,9],'gold')
            box('cross arms',[3.5,27,7],[12.5,29,9],'gold')
            box('emerald focus',[6.6,27,6.5],[9.4,29,9.5],'green')
        else:
            box('chain mount',[8.5,top-1,7],[13,top,9],'steel')
            for y in [13,15.5,18]:
                for x in [11.8,14.2]: box('chain link side',[x,y,7.5],[x+.6,y+2.8,8.5])
                for yy in [y,y+2.2]: box('chain link end',[12.4,yy,7.5],[14.2,yy+.6,8.5])
    elif item=='fireball-charge':
        box('red crystal shell',[5,5,5],[11,12,11],'red')
        box('orange heart',[4.5,7,6],[11.5,10.5,10],'orange')
        box('flame tip',[6,12,6.5],[9.5,16,9.5],'orange')
        box('flame needle',[7,16,7],[8.5,18,8.5],'yellow')
        box('side flicker',[10,10,7],[12.5,14,9],'orange')
    elif item=='gale-fan':
        box('handle',[7.2,3,7.2],[8.8,10,8.8],'blue')
        for angle in [-45,-22.5,0,22.5,45]:
            box('fan leaf',[6.7,10,7.7],[9.3,20,8.3],'ice',angle,[8,10,8])
            box('fan rib',[7.7,9,7.3],[8.3,20.5,8.7],'blue',angle,[8,10,8])
        box('fan hinge',[6.8,8.8,6.8],[9.2,10.5,9.2],'gold')
    else: raise ValueError(item)
    return parts


def displays(item,parts):
    gun=item in GUNS
    base=json.loads((ROOT/'content/models/items'/('pistol.model.json' if gun else 'knight-sword.model.json')).read_text())['display']
    # Our named grip is centered at (8,8,8), exactly the item-layer origin.
    for view in ['firstperson_righthand','firstperson_lefthand']:
        base[view]['translation']=[0,0,-3.2]
        base[view]['scale']=[.62 if item in SMALL else .5]*3
    for view in ['thirdperson_righthand','thirdperson_lefthand']:
        base[view]['translation']=[0,0,0]
        base[view]['scale']=[.75]*3
    lo=np.min([p[0]['from'] for p in parts],axis=0)
    hi=np.max([p[0]['to'] for p in parts],axis=0)
    center=(lo+hi)/2-8
    span=max(hi-lo)
    for view,limit in [('gui',12),('ground',8),('fixed',15)]:
        scale=min(1.1,limit/span)
        base[view]['scale']=[scale]*3
        base[view]['translation']=(-display_matrix(base[view]['rotation'])@(center*scale)).round(4).tolist()
        if view=='ground': base[view]['translation'][1]+=2.5
    return base


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('stage',choices=['blockout','shape','polish'])
    ap.add_argument('--out',required=True,type=Path)
    ap.add_argument('items',nargs='*')
    args=ap.parse_args()
    spec=yaml.safe_load(SPEC.read_text())
    source=ROOT/'content/textures/sources/sheets/arsenal-weapons-art2.png'
    if not source.exists(): raise ValueError('Generate and inspect the locked concept sheet first')
    target_hash=hashlib.sha256(source.read_bytes()).hexdigest()
    for item,brief in spec['cells'].items():
        if item in VANILLA or (args.items and item not in args.items): continue
        parts=design(item)
        out=args.out/item
        out.mkdir(parents=True,exist_ok=True)
        p=Project(out/'project')
        operations=[]
        def call(op,a=None):
            result=p.call(op,a)
            operations.append({'op':op,'args':a,'result':result})
            return result
        if args.stage=='blockout':
            if p.file.exists(): raise ValueError('Use a fresh project path')
            (out/'concept.json').write_text(json.dumps({'sheet':str(source.relative_to(ROOT)),'sha256':target_hash,'cell':list(spec['cells']).index(item),'brief':brief,'grip':[8,8,8],'review':'Single icon defines silhouette/palette; missing orthographic concept views remain unknown.'},indent=2)+'\n')
            masses=parts[:3]
            model={'credit':'Astera / Codex (gpt-6.1-sol)','textures':{'clay':'minecraft:block/white_concrete'},'elements':[e for e,m in masses],'display':displays(item,parts)}
            scratch=out/'blockout.model.json'
            scratch.write_text(json.dumps(model))
            call('import',{'path':str(scratch)})
        else:
            assert json.loads((out/'concept.json').read_text())['sha256']==target_hash
        call('scene.get',{'fields':['id','name','from','to','faces']})
        call('scene.element',{'id':p.model['elements'][0]['id']})
        call('measure');call('validate')
        call('history.snapshot',{'name':args.stage+'-before'})
        if args.stage=='shape':
            ops=[{'op':'element.delete','args':{'id':e['id']}} for e in p.model['elements']]
            for i,(e,mat) in enumerate(parts): ops.append({'op':'element.add','args':{'id':f'part{i:03d}','element':e}})
            call('batch',{'ops':ops})
            ops=[]
            for mat in MATERIALS:
                ids=[f'part{i:03d}' for i,(e,m) in enumerate(parts) if m==mat]
                if ids:
                    material,tint=MATERIALS[mat]
                    ops.append({'op':'material.apply','args':{'ids':ids,'material':material,'tint':tint,'seed':20261028}})
            call('batch',{'ops':ops})
        if args.stage=='polish':
            # Fixes chosen after opening round-2 boards; keep edits in Forge history.
            fixes=json.loads((BATCH/'fixes.json').read_text()).get(item,[])
            if fixes: call('batch',{'ops':fixes})
        call('history.snapshot',{'name':args.stage+'-after'})
        call('history.diff',{'a':args.stage+'-before','b':args.stage+'-after'})
        result=call('board',{'name':args.stage,'resolution':128})
        shutil.copyfile(result['path'],out/(args.stage+'.png'))
        call('board',{'name':args.stage+'-contexts','views':['gui','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand','ground','fixed'],'resolution':128})
        call('measure');call('validate')
        if args.stage!='blockout':
            export=call('export',{'path':str(out/'export')})
            target=ROOT/f'content/models/items/{item}.model.json'
            # Existing authored models are protected; only models owned by this batch may be updated.
            if target.exists() and args.stage=='shape': raise ValueError('Refuse to overwrite existing model '+str(target))
            shutil.copyfile(export['path'],target)
        (out/(args.stage+'-operations.json')).write_text(json.dumps(operations,indent=2)+'\n')
        print(item,args.stage,flush=True)

if __name__=='__main__':main()
