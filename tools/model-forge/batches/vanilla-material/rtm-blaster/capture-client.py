"""Run under the shared client flock with an isolated creative superflat save. No server."""
import os, subprocess, time, signal
from pathlib import Path
wd=Path.cwd()/"build/rtm-blaster-client"
wd.mkdir(parents=True, exist_ok=True)
root=Path.cwd()
pack=wd/'resourcepacks/vanilla-material'
subprocess.run(['python3','tools/model-forge/batches/vanilla-material/rtm-blaster/capture-pack.py','--out',str(pack)],check=True)
(wd/'options.txt').write_text('version:5023\nfov:0.0\nbobView:false\nrenderDistance:6\nsimulationDistance:5\nguiScale:2\npauseOnLostFocus:false\nmaxFps:30\nenableVsync:false\nsoundCategory_master:0.0\ntutorialStep:none\nonboardAccessibility:false\nresourcePacks:["vanilla","file/vanilla-material"]\n')
x=subprocess.Popen(['Xvfb','-displayfd','1','-screen','0','1280x720x24','-nolisten','tcp'],stdout=open(wd/'display','w'),stderr=open(wd/'xvfb.log','w'))
c=None
try:
 while not (wd/'display').read_text().strip():time.sleep(.1)
 env={**os.environ,'DISPLAY':':'+(wd/'display').read_text().strip(),'LIBGL_ALWAYS_SOFTWARE':'1','LP_NUM_THREADS':'4','XDG_RUNTIME_DIR':str(wd)}
 c=subprocess.Popen(['/home/node/.cache/astera-vfx-shot/venv/bin/portablemc','--main-dir','/home/node/.cache/astera-vfx-shot/mcdir','start','26.3','--mc-dir',str(wd),'-u','BlasterReview','--resolution','1280x720','--jvm-arg=-Xmx1G','--join-world','Blaster Material Review'],env=env,stdout=open(wd/'client.log','w'),stderr=subprocess.STDOUT,start_new_session=True)
 # Wait for the copied isolated creative-superflat save to finish joining.
 deadline=time.monotonic()+180
 while time.monotonic()<deadline:
  log=(wd/'logs/latest.log')
  if log.exists() and 'BlasterReview joined the game' in log.read_text(errors='replace'):break
  if c.poll() is not None:raise RuntimeError('Client exited before world join')
  time.sleep(1)
 else:raise RuntimeError('World join timed out; inspect client.log')
 time.sleep(8)
 def key(*args):subprocess.run(['xdotool',*args],env=env,check=True)
 for command in ('/clear @s','/give @s minecraft:iron_axe[minecraft:item_model="astera:rtm-blaster"]','/tp @s 0 -60 0 -90 0','/time set noon','/weather clear'):
  key('key','t');time.sleep(.3)
  key('type','--clearmodifiers','--delay','1',command);key('key','Return');time.sleep(.8)
 key('key','1')
 time.sleep(12)
 subprocess.run(['import','-window','root',str(root/'tools/model-forge/batches/vanilla-material/rtm-blaster/client.png')],env=env,check=True)
 print('Captured native rtm-blaster/client.png',flush=True)
finally:
 if c and c.poll() is None:os.killpg(c.pid,signal.SIGTERM)
 x.terminate()
