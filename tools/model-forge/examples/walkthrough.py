#!/usr/bin/env python3
"""Reproducible real CLI -> REST authoring session, not a test suite.
Run from repository root. Requires a fresh --out directory.
"""
import argparse
import hashlib
import json
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

TOOL=Path(__file__).resolve().parents[1]/'forge.py'
ROOT=TOOL.parents[2]


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--out',default=str(Path(__file__).parent/'session'))
    out=Path(parser.parse_args().out).resolve()
    if out.exists(): raise SystemExit('Choose a fresh --out directory to preserve earlier evidence')
    out.mkdir(parents=True); project=out/'project'; log=[]
    def record(transport,op,args,value):
        log.append({'transport':transport,'op':op,'args':args,'response':value})
        (out/'transcript.jsonl').write_text(''.join(json.dumps(row,separators=(',',':'))+'\n' for row in log))
        return value
    def cli(op,args=None):
        args=args or {}; proc=subprocess.run([sys.executable,str(TOOL),'--project',str(project),'call',op,json.dumps(args)],capture_output=True,text=True,check=True,cwd=ROOT)
        return record('CLI',op,args,json.loads(proc.stdout))
    cli('import',{'path':str(ROOT/'content/models/items/verdict-hammer.model.json')})
    cli('scene.get',{'fields':['id','name','from','to']}); cli('validate'); cli('history.snapshot',{'name':'baseline'})
    views=['front','back','left','right','top','bottom','iso','iso_back']
    baseline=cli('board',{'views':views,'name':'baseline'})
    cli('group.create',{'id':'ornaments','name':'Agent-added ornaments'})
    detail={'name':'Teal socket crest','from':[7.25,17.5,8.7],'to':[8.75,19.25,9.15],'group':'ornaments',
            'faces':{f:{'texture':'#detail','uv':[0,0,4,4]} for f in ('north','south','east','west','up','down')}}
    cli('batch',{'ops':[{'op':'texture.create','args':{'key':'detail','size':[64,64],'color':'#d6ad56'}},
                        {'op':'element.add','args':{'id':'crest','element':detail}}]})
    cli('uv.auto',{'key':'detail','ids':['crest'],'density':2})
    cli('texture.paint',{'key':'detail','ops':[{'type':'gradient-ramp','box':[0,0,63,63],'colors':['#155b60','#8cf3d9'],'steps':5},
                                                           {'type':'line','points':[[0,0],[63,0]],'color':'#ffe9a1'},
                                                           {'type':'fill','point':[1,63],'color':'#22666a'},
                                                           {'type':'pixels','pixels':[[1,1,'#ffffff']]} ]})
    cli('board',{'views':views,'name':'cli-detail','highlight':['crest']})
    cli('history.undo')
    cli('board',{'views':views,'name':'cli-undo'})
    cli('history.snapshot',{'name':'cli-detail'})
    # OS-selected available port; server reports readiness through health, no arbitrary sleep gate.
    with socket.socket() as sock: sock.bind(('127.0.0.1',0)); port=sock.getsockname()[1]
    with (out/'server.log').open('w') as stderr:
        server=subprocess.Popen([sys.executable,str(TOOL),'--project',str(project),'serve','--port',str(port)],stderr=stderr,stdout=stderr)
        try:
            for _ in range(100):
                try:
                    urllib.request.urlopen(f'http://127.0.0.1:{port}/health',timeout=.5).close(); break
                except OSError:
                    if server.poll() is not None: raise RuntimeError('Server exited')
                    time.sleep(.05)
            else: raise RuntimeError('Server did not become ready')
            def rest(op,args=None):
                args=args or {}; body=json.dumps({'op':op,'args':args}).encode()
                req=urllib.request.Request(f'http://127.0.0.1:{port}/call',data=body,headers={'Content-Type':'application/json'})
                with urllib.request.urlopen(req,timeout=30) as response: value=json.load(response)
                return record('REST',op,args,value)
            rest('scene.element',{'id':'crest'})
            rest('element.duplicate',{'id':'crest','new_id':'crest_back'})
            rest('element.mirror',{'id':'crest_back','axis':'z'})
            rest('element.update',{'id':'crest_back','changes':{'name':'Rear socket crest'}})
            rest('texture.paint',{'key':'detail','ops':[{'type':'rect','box':[0,0,63,63],'color':'#ff00ff'}]})
            rest('board',{'views':['front','back','iso','iso_back'],'name':'rest-paint'})
            rest('history.undo')
            rest('history.snapshot',{'name':'rest-ready'})
            rest('group.move',{'ids':['crest_back'],'parent':None})
            rest('element.delete',{'id':'crest_back'})
            rest('history.restore',{'name':'rest-ready'})
            restored=rest('history.diff',{'a':'rest-ready','b':'current'})
            if restored['diff']: raise RuntimeError('Snapshot restore differs')
            # A rejected transaction must leave neither a partial edit nor an undo entry.
            bad={'op':'batch','args':{'ops':[{'op':'element.delete','args':{'id':'crest'}},
                                            {'op':'element.update','args':{'id':'missing','changes':{'name':'bad'}}}]}}
            request=urllib.request.Request(f'http://127.0.0.1:{port}/call',data=json.dumps(bad).encode(),headers={'Content-Type':'application/json'})
            try: urllib.request.urlopen(request,timeout=30); raise RuntimeError('Invalid batch unexpectedly succeeded')
            except urllib.error.HTTPError as error:
                if error.code!=400: raise
                record('REST',bad['op'],bad['args'],json.load(error))
            if rest('history.diff',{'a':'rest-ready','b':'current'})['diff']: raise RuntimeError('Failed batch changed state')
            comparison=rest('compare',{'views':views,'references':{r['view']:r['path'] for r in baseline['images']},'name':'baseline-diff'})
            final=rest('board',{'views':views+['gui','firstperson_righthand','thirdperson_righthand','ground','fixed'],'name':'final'})
            rest('board',{'views':['front','iso'],'mode':'wireframe','name':'wireframe','overlay':['axes','bbox']})
            rest('board',{'views':['front','iso'],'mode':'xray','name':'xray'})
            rest('board',{'mode':'uv','key':'detail','name':'detail-atlas','overlay':['ids']})
            rest('measure'); rest('texture.regions'); rest('texture.palette',{'key':'detail'})
            rest('history.snapshot',{'name':'final'})
            rest('history.diff',{'a':'baseline','b':'final'})
            rest('export',{'path':str(out/'export')})
            validation=rest('validate')
            # Observable verifier: no magenta remains after undo; both crests persist.
            palette=rest('texture.palette',{'key':'detail'})['colors']
            if any(c['rgba'][:3]==[255,0,255] for c in palette): raise RuntimeError('Paint undo did not land')
            count=rest('measure')['element_count']
            if count!=42 or not validation['valid']: raise RuntimeError('Final model is not valid with two added elements')
            (out/'evidence.json').write_text(json.dumps({'final_board':final['path'],'comparison':comparison['path'],
                'element_count':count,'valid':validation['valid'],'paint_undo_verified':True,
                'metrics':comparison['metrics'],'model_sha256':hashlib.sha256((out/'export/model.json').read_bytes()).hexdigest()},indent=2)+'\n')
        finally: server.terminate(); server.wait(timeout=5)
    print(out)

if __name__=='__main__': main()
