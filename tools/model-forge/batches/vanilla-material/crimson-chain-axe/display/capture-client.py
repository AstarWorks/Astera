#!/usr/bin/env python3
"""Capture all three display fixes. Run under flock /tmp/claude-1000/astera-mc-client.lock.

Prepare a commands-enabled creative superflat save named Display Creative Review in
--game-dir/saves first (peaceful, structures off, seed 20261024). No server or build.
"""
import argparse
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[6]
ITEMS = [('crimson-chain-axe', 'iron_axe'), ('edict-blade', 'iron_sword'), ('orrery-staff', 'iron_axe')]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--game-dir', type=Path, default=ROOT/'build/display/client')
parser.add_argument('--world', default='Display Creative Review')
parser.add_argument('--items', nargs='+', choices=[name for name, _ in ITEMS], default=[name for name, _ in ITEMS])
a = parser.parse_args()
wd = a.game_dir.resolve()
if not (wd/'saves'/a.world/'level.dat').exists():
    parser.error('Prepare the isolated superflat save described above first.')
pack = wd/'resourcepacks/vanilla-material'
files = {
    'pack.mcmeta': {'pack': {'description': 'Display transform review', 'min_format': [97, 0], 'max_format': [97, 1]}},
    'assets/minecraft/atlases/blocks.json': {'sources': [{'type': 'directory', 'source': 'blocks', 'prefix': 'blocks/'}]},
}
for name, _ in ITEMS:
    files[f'assets/astera/items/{name}.json'] = {'model': {'type': 'minecraft:model', 'model': f'astera:items/{name}'}}
    files[f'assets/astera/models/items/{name}.json'] = json.loads((ROOT/f'content/models/items/{name}.model.json').read_text())
for name, _ in ITEMS:
    model = files[f'assets/astera/models/items/{name}.json']
    for ref in set(model['textures'].values()):
        if ref.startswith('astera:'):
            relative = ref.split(':', 1)[1]+'.png'
            path = pack/'assets/astera/textures'/relative
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT/'content/textures'/relative, path)
for name, data in files.items():
    path = pack/name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2)+'\n')
(wd/'options.txt').write_text('version:5023\nfov:0.0\nbobView:false\nrenderDistance:6\nsimulationDistance:5\nguiScale:2\npauseOnLostFocus:false\nmaxFps:30\nenableVsync:false\nsoundCategory_master:0.0\ntutorialStep:none\nonboardAccessibility:false\nresourcePacks:["vanilla","file/vanilla-material"]\n')
(wd/'display').write_text('')
x = subprocess.Popen(['Xvfb', '-displayfd', '1', '-screen', '0', '1280x720x24', '-nolisten', 'tcp'], stdout=open(wd/'display', 'w'), stderr=open(wd/'xvfb.log', 'w'))
c = None
try:
    deadline = time.monotonic()+20
    while not (wd/'display').read_text().strip():
        if x.poll() is not None or time.monotonic() > deadline:
            raise RuntimeError('Xvfb failed; see xvfb.log')
        time.sleep(.1)
    env = {**os.environ, 'DISPLAY': ':'+(wd/'display').read_text().strip(), 'LIBGL_ALWAYS_SOFTWARE': '1', 'LP_NUM_THREADS': '4', 'XDG_RUNTIME_DIR': str(wd)}
    log = wd/'logs/latest.log'
    if log.exists():
        log.unlink()
    c = subprocess.Popen(['/home/node/.cache/astera-vfx-shot/venv/bin/portablemc', '--main-dir', '/home/node/.cache/astera-vfx-shot/mcdir', 'start', '26.3', '--mc-dir', str(wd), '--join-world', a.world, '-u', 'DisplayReview', '--resolution', '1280x720', '--jvm-arg=-Xmx1G'], env=env, stdout=open(wd/'client.log', 'w'), stderr=subprocess.STDOUT, start_new_session=True)
    deadline = time.monotonic()+180
    while not (log.exists() and 'logged in with entity id' in log.read_text()):
        if c.poll() is not None or time.monotonic() > deadline:
            raise RuntimeError('World failed to load; see client.log')
        time.sleep(1)
    time.sleep(12)

    def key(*keys):
        subprocess.run(['xdotool', 'key', *keys], env=env, check=True)

    def command(value):
        key('t')
        time.sleep(.3)
        subprocess.run(['xdotool', 'type', '--clearmodifiers', '--delay', '1', value], env=env, check=True)
        key('Return')
        time.sleep(.6)

    for value in ['/gamerule minecraft:send_command_feedback false', '/gamemode creative', '/difficulty peaceful', '/time set noon', '/weather clear', '/gamerule minecraft:advance_time false', '/gamerule minecraft:advance_weather false', '/kill @e[type=minecraft:item_display]', '/tp @s 0 -60 3 180 0']:
        command(value)
    for name, base in ITEMS:
        if name not in a.items:
            continue
        out = ROOT/'tools/model-forge/batches/vanilla-material'/name/'display'
        out.mkdir(exist_ok=True)

        def shot(filename):
            subprocess.run(['import', '-window', 'root', str(out/filename)], env=env, check=True)

        command('/clear @s')
        command(f'/give @s minecraft:{base}[minecraft:item_model="astera:{name}"]')
        key('1')
        time.sleep(12)
        shot('client-firstperson.png')
        command('/gamemode survival')
        time.sleep(1)
        key('e')
        time.sleep(2)
        shot('client-inventory.png')
        key('e')
        command('/gamemode creative')
        key('f')
        time.sleep(3)
        shot('client-firstperson-lefthand.png')
        key('f')
        key('F5')
        time.sleep(.5)
        key('F5')
        time.sleep(3)
        shot('client-thirdperson-righthand.png')
        key('f')
        time.sleep(3)
        shot('client-thirdperson-lefthand.png')
        key('f')
        key('F5')
        print(name, 'captured', flush=True)
finally:
    if c and c.poll() is None:
        os.killpg(c.pid, signal.SIGTERM)
        c.wait(timeout=30)
    x.terminate()
    x.wait(timeout=10)
