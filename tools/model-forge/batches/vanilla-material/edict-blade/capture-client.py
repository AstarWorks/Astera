#!/usr/bin/env python3
"""Run only under flock /tmp/claude-1000/astera-mc-client.lock; isolated capture world required."""
import os, subprocess, time, signal
from pathlib import Path
wd=Path.cwd()/"build/edict-blade-client"
wd.mkdir(parents=True, exist_ok=True)
root=Path.cwd()
(wd/'options.txt').write_text('version:5023\nfov:0.0\nbobView:false\nrenderDistance:6\nsimulationDistance:5\nguiScale:2\npauseOnLostFocus:false\nmaxFps:30\nenableVsync:false\nsoundCategory_master:0.0\ntutorialStep:none\nonboardAccessibility:false\nresourcePacks:["vanilla","file/vanilla-material"]\n')
x=subprocess.Popen(['Xvfb','-displayfd','1','-screen','0','1280x720x24','-nolisten','tcp'],stdout=open(wd/'display','w'),stderr=open(wd/'xvfb.log','w'))
c=None
try:
 while not (wd/'display').read_text().strip():time.sleep(.1)
 env={**os.environ,'DISPLAY':':'+(wd/'display').read_text().strip(),'LIBGL_ALWAYS_SOFTWARE':'1','LP_NUM_THREADS':'4','XDG_RUNTIME_DIR':str(wd)}
 c=subprocess.Popen(['/home/node/.cache/astera-vfx-shot/venv/bin/portablemc','--main-dir','/home/node/.cache/astera-vfx-shot/mcdir','start','26.3','--mc-dir',str(wd),'--join-world','Edict Blade Materials','-u','EdictBladeReview','--resolution','1280x720','--jvm-arg=-Xmx1G'],env=env,stdout=open(wd/'client.log','w'),stderr=subprocess.STDOUT,start_new_session=True)
 # This local world is an isolated copy of the pilot-style creative superflat.
 deadline=time.monotonic()+180
 while time.monotonic()<deadline:
  if c.poll() is not None: raise RuntimeError('Client exited; see client.log')
  log=(wd/'logs/latest.log')
  if log.exists() and 'logged in with entity id' in log.read_text(): break
  time.sleep(1)
 else: raise TimeoutError('World did not load; see client.log')
 time.sleep(12)
 def key(*keys): subprocess.run(['xdotool','key',*keys],env=env,check=True)
 def command(value):
  key('t'); time.sleep(.4)
  subprocess.run(['xdotool','type','--clearmodifiers','--delay','1',value],env=env,check=True)
  key('Return');time.sleep(1)
 command('/kill @e[type=minecraft:item_display]')
 command('/clear @s')
 command('/give @s minecraft:iron_sword[minecraft:item_model="astera:edict-blade"]')
 key('1')
 command('/time set noon')
 command('/weather clear')
 command('/tp @s 0 -60 3 180 0')
 time.sleep(12)
 subprocess.run(['import','-window','root',str(root/'tools/model-forge/batches/vanilla-material/edict-blade/client.png')],env=env,check=True)

 command('/summon minecraft:item_display ~ ~1 ~-3 {item:{id:"minecraft:iron_sword",count:1,components:{"minecraft:item_model":"astera:edict-blade"}},item_display:"gui",billboard:"center"}')
 command('/data merge entity @e[type=minecraft:item_display,limit=1,sort=nearest] {transformation:{translation:[0f,0.4f,0f],left_rotation:[0f,0f,0f,1f],scale:[3f,3f,3f],right_rotation:[0f,0f,0f,1f]}}')
 key('2');time.sleep(8)
 subprocess.run(['import','-window','root',str(root/'tools/model-forge/batches/vanilla-material/edict-blade/client-detail.png')],env=env,check=True)
finally:
 if c and c.poll() is None:os.killpg(c.pid,signal.SIGTERM)
 x.terminate()
