import os, subprocess, time, signal, json
from pathlib import Path
wd=Path.cwd()/"build/greatsword-client"
wd.mkdir(parents=True, exist_ok=True)
root=Path.cwd()
pack=wd/'resourcepacks/vanilla-material'
files={'pack.mcmeta':{'pack':{'description':'Greatsword material capture','min_format':[97,0],'max_format':[97,1]}},'assets/astera/items/greatsword.json':{'model':{'type':'minecraft:model','model':'astera:items/greatsword'}},'assets/astera/models/items/greatsword.json':json.loads((root/'content/models/items/greatsword.model.json').read_text())}
for name,data in files.items():
 p=pack/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(data,indent=2)+'\n')
(wd/'options.txt').write_text('version:5023\nfov:0.0\nbobView:false\nrenderDistance:6\nsimulationDistance:5\nguiScale:2\npauseOnLostFocus:false\nmaxFps:30\nenableVsync:false\nsoundCategory_master:0.0\ntutorialStep:none\nonboardAccessibility:false\nresourcePacks:["vanilla","file/vanilla-material"]\n')
x=subprocess.Popen(['Xvfb','-displayfd','1','-screen','0','1280x720x24','-nolisten','tcp'],stdout=open(wd/'display','w'),stderr=open(wd/'xvfb.log','w'))
c=None
try:
 while not (wd/'display').read_text().strip():time.sleep(.1)
 env={**os.environ,'DISPLAY':':'+(wd/'display').read_text().strip(),'LIBGL_ALWAYS_SOFTWARE':'1','LP_NUM_THREADS':'4','XDG_RUNTIME_DIR':str(wd)}
 c=subprocess.Popen(['/home/node/.cache/astera-vfx-shot/venv/bin/portablemc','--main-dir','/home/node/.cache/astera-vfx-shot/mcdir','start','26.3','--mc-dir',str(wd),'-u','GreatswordReview','--resolution','1280x720','--jvm-arg=-Xmx1G'],env=env,stdout=open(wd/'client.log','w'),stderr=subprocess.STDOUT,start_new_session=True)
 while not (wd/'stop').exists() and c.poll() is None:time.sleep(1)
 if (wd/'capture').exists():
  subprocess.run(['import','-window','root',str(root/'tools/model-forge/batches/vanilla-material/greatsword/client.png')],env=env,check=True)
finally:
 if c and c.poll() is None:os.killpg(c.pid,signal.SIGTERM)
 x.terminate()
