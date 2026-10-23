#!/usr/bin/env python3
"""Run under flock /tmp/claude-1000/astera-mc-client.lock; uses the pilot runtime.

Provide an isolated creative superflat save at build/rifle-client/saves/rifle-review.
The game directory, options, pack and X display are private to this capture.
"""
import json
import os
from pathlib import Path
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parents[5]
wd = ROOT/'build/rifle-client'
wd.mkdir(parents=True, exist_ok=True)
pack = wd/'resourcepacks/vanilla-material'
files = {
    'pack.mcmeta': {'pack': {'description': 'Rifle material capture', 'min_format': [97,0], 'max_format': [97,1]}},
    'assets/astera/items/rifle.json': {'model': {'type': 'minecraft:model', 'model': 'astera:items/rifle'}},
    'assets/astera/models/items/rifle.json': json.loads((ROOT/'content/models/items/rifle.model.json').read_text()),
}
for name, data in files.items():
    path = pack/name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2)+'\n')
(wd/'options.txt').write_text('version:5023\nfov:0.0\nbobView:false\nrenderDistance:6\nsimulationDistance:5\nguiScale:2\npauseOnLostFocus:false\nmaxFps:30\nenableVsync:false\nsoundCategory_master:0.0\ntutorialStep:none\nonboardAccessibility:false\nresourcePacks:["vanilla","file/vanilla-material"]\n')
x = subprocess.Popen(['Xvfb','-displayfd','1','-screen','0','1280x720x24','-nolisten','tcp'], stdout=open(wd/'display','w'), stderr=open(wd/'xvfb.log','w'))
client = None
try:
    while not (wd/'display').read_text().strip():
        time.sleep(.1)
    env = {**os.environ, 'DISPLAY': ':'+(wd/'display').read_text().strip(), 'LIBGL_ALWAYS_SOFTWARE':'1', 'LP_NUM_THREADS':'4', 'XDG_RUNTIME_DIR':str(wd)}
    client = subprocess.Popen(['/home/node/.cache/astera-vfx-shot/venv/bin/portablemc','--main-dir','/home/node/.cache/astera-vfx-shot/mcdir','start','26.3','--mc-dir',str(wd),'-u','RifleReview','--join-world','rifle-review','--resolution','1280x720','--jvm-arg=-Xmx1G'], env=env, stdout=open(wd/'client.log','w'), stderr=subprocess.STDOUT, start_new_session=True)
    deadline = time.monotonic()+180
    while 'RifleReview joined the game' not in (wd/'client.log').read_text(errors='replace'):
        if client.poll() is not None or time.monotonic()>deadline:
            raise RuntimeError('Client did not enter isolated world; inspect build/rifle-client/client.log')
        time.sleep(1)
    time.sleep(8)
    def key(*args):
        subprocess.run(['xdotool', *args], env=env, check=True, timeout=20)
    subprocess.run(['import','-window','root',str(wd/'pre-input.png')], env=env, check=True)
    subprocess.run(['xwininfo','-root','-tree'], env=env, stdout=open(wd/'windows.txt','w'), check=True)
    key('mousemove','640','360','click','1')
    for command in ('/clear @s', '/gamemode creative', '/time set noon', '/weather clear', '/tp @s 0 -60 0 -35 0', '/give @s minecraft:iron_hoe[minecraft:item_model="astera:rifle"]'):
        key('key','t'); time.sleep(.3)
        key('type','--clearmodifiers','--delay','3',command)
        key('key','Return'); time.sleep(1)
    key('key','1')
    time.sleep(12)
    subprocess.run(['import','-window','root',str(Path(__file__).with_name('client.png'))], env=env, check=True)
finally:
    if client and client.poll() is None:
        os.killpg(client.pid, signal.SIGTERM)
    x.terminate()
