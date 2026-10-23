#!/usr/bin/env python3
"""Run under the shared client flock; capture/stop marker files control review."""
import os, subprocess, time, signal, json
from pathlib import Path
root=Path(__file__).resolve().parents[5]
wd=root/"build/shotgun-client"
wd.mkdir(parents=True, exist_ok=True)
pack=wd/'resourcepacks/vanilla-material'
files={'pack.mcmeta':{'pack':{'description':'Shotgun material capture','min_format':[97,0],'max_format':[97,1]}},'assets/astera/items/shotgun.json':{'model':{'type':'minecraft:model','model':'astera:items/shotgun'}},'assets/astera/models/items/shotgun.json':json.loads((root/'content/models/items/shotgun.model.json').read_text())}
for name,data in files.items():
 p=pack/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(data,indent=2)+'\n')
(wd/'options.txt').write_text('version:5023\nfov:0.0\nbobView:false\nrenderDistance:6\nsimulationDistance:5\nguiScale:2\npauseOnLostFocus:false\nmaxFps:30\nenableVsync:false\nsoundCategory_master:0.0\ntutorialStep:none\nonboardAccessibility:false\nresourcePacks:["vanilla","file/vanilla-material"]\n')
x=subprocess.Popen(['Xvfb','-displayfd','1','-screen','0','1280x720x24','-nolisten','tcp'],stdout=open(wd/'display','w'),stderr=open(wd/'xvfb.log','w'))
c=None
try:
 while not (wd/'display').read_text().strip():time.sleep(.1)
 env={**os.environ,'DISPLAY':':'+(wd/'display').read_text().strip(),'LIBGL_ALWAYS_SOFTWARE':'1','LP_NUM_THREADS':'4','XDG_RUNTIME_DIR':str(wd)}
 c=subprocess.Popen(['/home/node/.cache/astera-vfx-shot/venv/bin/portablemc','--main-dir','/home/node/.cache/astera-vfx-shot/mcdir','start','26.3','--mc-dir',str(wd),'-u','ShotgunReview','--resolution','1280x720','--jvm-arg=-Xmx1G'],env=env,stdout=open(wd/'client.log','w'),stderr=subprocess.STDOUT,start_new_session=True)
 deadline=time.monotonic()+900
 while not (wd/'stop').exists() and c.poll() is None and time.monotonic()<deadline:time.sleep(1)
 if (wd/'capture').exists():
  subprocess.run(['import','-window','root',str(root/'tools/model-forge/batches/vanilla-material/shotgun/client.png')],env=env,check=True)
finally:
 if c and c.poll() is None:os.killpg(c.pid,signal.SIGTERM)
 x.terminate()
 x.wait(timeout=10)
