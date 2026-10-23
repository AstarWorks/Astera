#!/usr/bin/env python3
"""Run under flock /tmp/claude-1000/astera-mc-client.lock from the repo root.

Creates builder-equivalent constant tint item definitions and requires an isolated
creative singleplayer world: --world-source <directory>. No external server.
"""
import argparse
import gzip
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time
import json

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--world-source', required=True, type=Path)
a = parser.parse_args()
root = Path.cwd()
wd = root/'build/shotgun-client'
wd.mkdir(parents=True, exist_ok=True)
world = wd/'saves/Shotgun Superflat Review'
if not world.exists(): shutil.copytree(a.world_source, world, ignore=shutil.ignore_patterns('session.lock'))
# Enable commands only in this local copy; the source world is read-only.
level = world/'level.dat'
nbt = gzip.decompress(level.read_bytes())
flag = b'\x01\x00\x0dallowCommands'
if nbt.count(flag) != 1: raise ValueError('Expected one 26.3 allowCommands byte tag')
pos = nbt.index(flag)+len(flag)
nbt = nbt[:pos]+b'\x01'+nbt[pos+1:]
nbt = nbt.replace(b'\x01\x00\x08hardcore\x01', b'\x01\x00\x08hardcore\x00')
level.write_bytes(gzip.compress(nbt))
pack = wd/'resourcepacks/vanilla-material'
# Match ResourcePackBuilder.addModels/modelDefinition: move authoring RGB table
# to native constant tint sources; ship no authoring metadata or texture pixels.
model = json.loads((root/'content/models/items/shotgun.model.json').read_text())
palette = model.pop('astera_tints')
definition = {'type':'minecraft:model','model':'astera:items/shotgun',
              'tints':[{'type':'minecraft:constant','value':v} for v in palette]}
files = {
    'pack.mcmeta':{'pack':{'description':'Shotgun tint capture','min_format':[97,0],'max_format':[97,1]}},
    'assets/astera/items/shotgun.json':{'model':definition},
    'assets/astera/models/items/shotgun.json':model,
}
for name, data in files.items():
    path = pack/name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data,indent=2)+'\n')
assert all(0 <= f.get('tintindex',-1) < len(palette)
           for e in model['elements'] for f in e['faces'].values())
(wd/'options.txt').write_text('version:5023\nfov:0.0\nbobView:false\nrenderDistance:6\nsimulationDistance:5\nguiScale:2\npauseOnLostFocus:false\nmaxFps:30\nenableVsync:false\nsoundCategory_master:0.0\ntutorialStep:none\nonboardAccessibility:false\nresourcePacks:["vanilla","file/vanilla-material"]\n')
(wd/'display').write_text('')
x = subprocess.Popen(['Xvfb','-displayfd','1','-screen','0','1280x720x24','-nolisten','tcp'], stdout=open(wd/'display','w'), stderr=open(wd/'xvfb.log','w'))
c = None
try:
    while not (wd/'display').read_text().strip(): time.sleep(.1)
    env = {**os.environ,'DISPLAY':':'+(wd/'display').read_text().strip(),'LIBGL_ALWAYS_SOFTWARE':'1','LP_NUM_THREADS':'4','XDG_RUNTIME_DIR':str(wd)}
    (wd/'logs/latest.log').unlink(missing_ok=True)
    c = subprocess.Popen(['/home/node/.cache/astera-vfx-shot/venv/bin/portablemc','--main-dir','/home/node/.cache/astera-vfx-shot/mcdir','start','26.3','--mc-dir',str(wd),'--join-world',world.name,'-u','ShotgunReview','--resolution','1280x720','--jvm-arg=-Xmx1G'], env=env, stdout=open(wd/'client.log','w'), stderr=subprocess.STDOUT, start_new_session=True)
    deadline = time.monotonic()+180
    while time.monotonic() < deadline:
        if c.poll() is not None: raise RuntimeError('Client exited; see client.log')
        log = wd/'logs/latest.log'
        if log.exists() and 'logged in with entity id' in log.read_text(): break
        time.sleep(1)
    else: raise TimeoutError('World did not load; see client.log')
    time.sleep(12)
    def key(*keys): subprocess.run(['xdotool','key',*keys], env=env, check=True)
    def command(value):
        key('t'); time.sleep(.4)
        subprocess.run(['xdotool','type','--clearmodifiers','--delay','1',value], env=env, check=True)
        key('Return'); time.sleep(1)
    command('/kill @e[type=minecraft:item_display]')
    command('/clear @s')
    command('/gamemode creative')
    command('/give @s minecraft:iron_axe[minecraft:item_model="astera:shotgun"]')
    key('1')
    command('/time set noon')
    command('/weather clear')
    command('/difficulty peaceful')
    command('/tp @s 0 -60 3 0 0')
    time.sleep(12)
    if 'Unknown or incomplete command' in log.read_text(): raise RuntimeError('Capture commands failed; see latest.log')
    subprocess.run(['import','-window','root',str(Path(__file__).with_name('client.png'))], env=env, check=True)
    key('f'); time.sleep(3)
    subprocess.run(['import','-window','root',str(Path(__file__).with_name('client-offhand.png'))], env=env, check=True)
finally:
    if c and c.poll() is None:
        os.killpg(c.pid, signal.SIGTERM)
        c.wait(timeout=30)
    x.terminate()
    x.wait(timeout=10)
