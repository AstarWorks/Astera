#!/usr/bin/env python3
"""Real 26.3 client capture of the held couture-rapier model. Run under
`flock /tmp/claude-1000/astera-mc-client.lock` from the repo root.

Starts a throw-away offline Paper server (flat world) from --server-dir (a Paper dir that has
already generated a world once; nothing is written outside the working copy) and a headless
client (Xvfb + llvmpipe, 1280x720, FOV 70, bob off, GUI scale 2) that joins it with a minimal
resource pack built by capture-pack.py (vanilla texture references + constant tints only; no
Mojang PNGs). Captures: first person main hand / off hand, third person back/front for both,
fixed item display and a dropped item in the first shot.
"""
import argparse, hashlib, os, shutil, signal, subprocess, time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--server-dir', required=True, type=Path)
a = parser.parse_args()
here = Path(__file__).resolve().parent
root = Path.cwd()
wd = root/'build/couture-shears-v2-client'
shutil.rmtree(wd, ignore_errors=True)
wd.mkdir(parents=True)
name = 'CoutureReview'
uuid = hashlib.md5(('OfflinePlayer:'+name).encode()).digest()
b = bytearray(uuid); b[6] = (b[6] & 0x0f) | 0x30; b[8] = (b[8] & 0x3f) | 0x80
h = b.hex(); uid = f'{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}'
srv = wd/'server'
shutil.copytree(a.server_dir, srv)
(srv/'ops.json').write_text('[{"uuid":"%s","name":"%s","level":4,"bypassesPlayerLimit":true}]' % (uid, name))
pack = wd/'resourcepacks/vanilla-material'
subprocess.run(['python3', str(here/'capture-pack.py'), '--out', str(pack)], check=True)
(wd/'options.txt').write_text('version:5023\nfov:0.0\nbobView:false\nrenderDistance:6\nsimulationDistance:5\nguiScale:2\npauseOnLostFocus:false\nmaxFps:30\nenableVsync:false\nsoundCategory_master:0.0\ntutorialStep:none\nonboardAccessibility:false\nresourcePacks:["vanilla","file/vanilla-material"]\n')
(wd/'display').write_text('')
s = subprocess.Popen(['java', '-Xmx1g', '-jar', 'paper.jar', '--nogui'], cwd=srv, stdin=subprocess.PIPE, stdout=open(wd/'server.log', 'w'), stderr=subprocess.STDOUT, text=True, start_new_session=True)
x = subprocess.Popen(['Xvfb', '-displayfd', '1', '-screen', '0', '1280x720x24', '-nolisten', 'tcp'], stdout=open(wd/'display', 'w'), stderr=open(wd/'xvfb.log', 'w'))
c = None
try:
    t0 = time.monotonic()
    while 'Done (' not in (wd/'server.log').read_text():
        if time.monotonic()-t0 > 240 or s.poll() is not None: raise RuntimeError('server did not start')
        time.sleep(1)
    while not (wd/'display').read_text().strip(): time.sleep(.1)
    env = {**os.environ, 'DISPLAY': ':'+(wd/'display').read_text().strip(), 'LIBGL_ALWAYS_SOFTWARE': '1', 'LP_NUM_THREADS': '4', 'XDG_RUNTIME_DIR': str(wd)}
    c = subprocess.Popen(['/home/node/.cache/astera-vfx-shot/venv/bin/portablemc', '--main-dir', '/home/node/.cache/astera-vfx-shot/mcdir', 'start', '26.3', '--mc-dir', str(wd), '--join-server', '127.0.0.1', '--join-server-port', '25999', '-u', name, '--resolution', '1280x720', '--jvm-arg=-Xmx1G'], env=env, stdout=open(wd/'client.log', 'w'), stderr=subprocess.STDOUT, start_new_session=True)
    t0 = time.monotonic()
    while 'joined the game' not in (wd/'server.log').read_text():
        if time.monotonic()-t0 > 240 or c.poll() is not None: raise RuntimeError('client did not join; see client.log')
        time.sleep(1)
    time.sleep(10)
    def key(*keys): subprocess.run(['xdotool', 'key', *keys], env=env, check=True)
    def command(value):
        key('t'); time.sleep(.5)
        subprocess.run(['xdotool', 'type', '--clearmodifiers', '--delay', '1', value], env=env, check=True)
        key('Return'); time.sleep(1.2)
    def shot(fname):
        time.sleep(2.5)
        subprocess.run(['import', '-window', 'root', str(here/fname)], env=env, check=True)
    item = 'minecraft:iron_sword[minecraft:item_model="astera:couture-rapier"]'
    s.stdin.write(f'op {name}\n'); s.stdin.flush(); time.sleep(2)
    command('/gamemode creative')
    command('/time set noon'); command('/weather clear'); command('/difficulty peaceful')
    command('/tp @s 0 -60 3 0 0')
    command('/clear @s')
    command(f'/give @s {item}')
    key('1')
    command('/summon minecraft:item_display 0 -58.5 6 {item:{id:"minecraft:iron_sword",count:1,components:{"minecraft:item_model":"astera:couture-rapier"}},item_display:"fixed"}')
    command('/summon minecraft:item 2 -59 5 {PickupDelay:32767,Item:{id:"minecraft:iron_sword",count:1,components:{"minecraft:item_model":"astera:couture-rapier"}}}')
    shot('client-firstperson-right.png')
    key('F5'); shot('client-thirdperson-right-back.png')
    key('F5'); shot('client-thirdperson-right-front.png')
    key('F5')
    command('/clear @s')
    command(f'/item replace entity @s weapon.offhand with {item}')
    shot('client-firstperson-left.png')
    key('F5'); shot('client-thirdperson-left-back.png')
    key('F5'); shot('client-thirdperson-left-front.png')
    log = (wd/'logs/latest.log')
    print('log problems:', [l[:200] for l in log.read_text().splitlines() if 'ERROR' in l and ('model' in l.lower() or 'texture' in l.lower() or 'astera' in l.lower())][:8])
finally:
    for p in (c, s):
        if p and p.poll() is None:
            if p is s:
                try: s.stdin.write('stop\n'); s.stdin.flush(); s.wait(timeout=60)
                except Exception: os.killpg(s.pid, signal.SIGKILL)
            else:
                os.killpg(p.pid, signal.SIGTERM)
                try: p.wait(timeout=30)
                except Exception: os.killpg(p.pid, signal.SIGKILL)
    x.terminate(); x.wait(timeout=10)
