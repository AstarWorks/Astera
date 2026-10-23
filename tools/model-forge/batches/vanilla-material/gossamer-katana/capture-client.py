"""Run under the shared client flock; create an isolated creative superflat via UI.
Game directory: build/gossamer-katana-client. Auto-capture after world login.
"""
import os, subprocess, time, signal
from pathlib import Path
wd=Path.cwd()/"build/gossamer-katana-client"
wd.mkdir(parents=True, exist_ok=True)
root=Path.cwd()
pack=wd/'resourcepacks/vanilla-material'
subprocess.run(['python3', str(Path(__file__).with_name('capture-pack.py')), '--out', str(pack)], check=True)
(wd/'options.txt').write_text('version:5023\nfov:0.0\nbobView:false\nrenderDistance:6\nsimulationDistance:5\nguiScale:2\npauseOnLostFocus:false\nmaxFps:30\nenableVsync:false\nsoundCategory_master:0.0\ntutorialStep:none\nonboardAccessibility:false\nresourcePacks:["vanilla","file/vanilla-material"]\n')
(wd/'display').write_text('')
x=subprocess.Popen(['Xvfb','-displayfd','1','-screen','0','1280x720x24','-nolisten','tcp'],stdout=open(wd/'display','w'),stderr=open(wd/'xvfb.log','w'))
c=None
try:
 while not (wd/'display').read_text().strip():time.sleep(.1)
 env={**os.environ,'DISPLAY':':'+(wd/'display').read_text().strip(),'LIBGL_ALWAYS_SOFTWARE':'1','LP_NUM_THREADS':'4','XDG_RUNTIME_DIR':str(wd)}
 while subprocess.run(['xdpyinfo'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:time.sleep(.1)
 c=subprocess.Popen(['/home/node/.cache/astera-vfx-shot/venv/bin/portablemc','--main-dir','/home/node/.cache/astera-vfx-shot/mcdir','start','26.3','--mc-dir',str(wd),'-u','KatanaReview','--resolution','1280x720','--jvm-arg=-Xmx1G'],env=env,stdout=open(wd/'client.log','w'),stderr=subprocess.STDOUT,start_new_session=True)
 deadline=time.monotonic()+900
 log=wd/'logs/latest.log'
 while not (log.exists() and 'logged in with entity id' in log.read_text()):
  if c.poll() is not None or time.monotonic()>deadline:raise RuntimeError('World did not load; see client.log')
  time.sleep(1)
 time.sleep(10)
 def key(*keys):subprocess.run(['xdotool','key',*keys],env=env,check=True)
 def command(value):
  key('t');time.sleep(.4)
  subprocess.run(['xdotool','type','--clearmodifiers','--delay','1',value],env=env,check=True)
  key('Return');time.sleep(1)
 for value in ('/clear @s','/gamemode creative','/give @s minecraft:iron_sword[minecraft:item_model="astera:gossamer-katana"]','/time set noon','/weather clear','/difficulty peaceful','/tp @s 0 -60 3 0 0'):
  command(value)
 key('1');time.sleep(12)
 if 'Unknown or incomplete command' in log.read_text():raise RuntimeError('Capture command failed')
 subprocess.run(['import','-window','root',str(Path(__file__).with_name('client.png'))],env=env,check=True)

finally:
 if c and c.poll() is None:
  os.killpg(c.pid,signal.SIGTERM)
  c.wait(timeout=30)
 x.terminate()
 x.wait(timeout=10)
