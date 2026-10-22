#!/usr/bin/env python3
"""Local model agent CLI, REST and MCP adapters. All operations use Project.call."""
import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from core import Project, READS, MUTATIONS


def serve(path, port):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            try:
                # No browser origins: this is a local agent API, not a website backend.
                if self.headers.get('Origin'): raise ValueError('Browser origins are not accepted')
                if self.path!='/call': self.send_error(404); return
                length=int(self.headers.get('Content-Length','0'))
                if not 0<length<=8*1024*1024: raise ValueError('Request size must be 1..8 MiB')
                request=json.loads(self.rfile.read(length)); result=Project(path).call(request['op'],request.get('args',{})); status=200
            except (ValueError,KeyError,TypeError,OSError,IndexError) as e: result={'error':str(e)}; status=400
            data=json.dumps(result,allow_nan=False).encode(); self.send_response(status); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
        def do_GET(self):
            if self.path!='/health': self.send_error(404); return
            data=b'{"status":"ok"}'; self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    # ponytail: sequential HTTP requests; project file lock also serializes CLI/MCP writers.
    server=HTTPServer(('127.0.0.1',port),Handler)
    print(json.dumps({'listening':f'http://127.0.0.1:{server.server_port}','project':str(path)}),file=sys.stderr,flush=True)
    try: server.serve_forever()
    finally: server.server_close()


def mcp(path):
    for line in sys.stdin:
        id=None
        try:
            req=json.loads(line); id=req.get('id'); method=req['method']
            if method.startswith('notifications/'): continue
            if method=='initialize': result={'protocolVersion':'2024-11-05','capabilities':{'tools':{}},'serverInfo':{'name':'model-forge','version':'1.0.0'}}
            elif method=='ping': result={}
            elif method=='tools/list':
                result={'tools':[{'name':'forge','description':'Inspect/edit a persistent Minecraft model. Read AGENT.md and README.md for operation arguments. Query scene.get before edits; use board to inspect results.',
                    'inputSchema':{'type':'object','properties':{'op':{'type':'string','enum':sorted(READS|MUTATIONS)},'args':{'type':'object'}},'required':['op']}}]}
            elif method=='tools/call':
                if req['params']['name']!='forge': raise ValueError('Unknown tool')
                a=req['params']['arguments']
                try: value=Project(path).call(a['op'],a.get('args',{})); result={'content':[{'type':'text','text':json.dumps(value)}]}
                except (ValueError,KeyError,TypeError,OSError,IndexError) as e: result={'isError':True,'content':[{'type':'text','text':str(e)}]}
            else:
                print(json.dumps({'jsonrpc':'2.0','id':id,'error':{'code':-32601,'message':'Unknown method'}}),flush=True); continue
            response={'jsonrpc':'2.0','id':id,'result':result}
        except (ValueError,KeyError,TypeError) as e: response={'jsonrpc':'2.0','id':id,'error':{'code':-32602,'message':str(e)}}
        print(json.dumps(response),flush=True)


def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('--project',default='forge-project')
    sub=p.add_subparsers(dest='command',required=True)
    call=sub.add_parser('call'); call.add_argument('op',choices=sorted(READS|MUTATIONS)); call.add_argument('args',nargs='?',default='{}',help='JSON object, @file, or - for stdin')
    s=sub.add_parser('serve'); s.add_argument('--port',type=int,default=8765)
    sub.add_parser('mcp')
    args=p.parse_args()
    if args.command=='serve': serve(args.project,args.port)
    elif args.command=='mcp': mcp(args.project)
    else:
        raw=sys.stdin.read() if args.args=='-' else open(args.args[1:]).read() if args.args.startswith('@') else args.args
        result=Project(args.project).call(args.op,json.loads(raw)); print(json.dumps(result,indent=2,allow_nan=False))
        if args.op=='validate' and not result['valid']: return 1
    return 0

if __name__=='__main__':
    try: sys.exit(main())
    except (ValueError,KeyError,TypeError,OSError,IndexError) as e:
        print(json.dumps({'error':str(e)}),file=sys.stderr); sys.exit(1)
