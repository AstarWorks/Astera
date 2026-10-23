import argparse, gzip, os, shutil, subprocess, time, signal
from pathlib import Path
parser=argparse.ArgumentParser()
parser.add_argument('--world-source', type=Path, required=True)
a=parser.parse_args()
wd=Path.cwd()/"build/vendetta-rifle-client"
wd.mkdir(parents=True, exist_ok=True)
root=Path.cwd()
world=wd/'saves/Vendetta material review'
if not world.exists(): shutil.copytree(a.world_source, world, ignore=shutil.ignore_patterns('session.lock'))
level=world/'level.dat'
nbt=gzip.decompress(level.read_bytes())
flag=b'\x01\x00\x0dallowCommands'
assert nbt.count(flag)==1
pos=nbt.index(flag)+len(flag)
nbt=nbt[:pos]+b'\x01'+nbt[pos+1:]
# The source may be a survival/hardcore review world; make this copy creative.
nbt=nbt.replace(b'\x03\x00\x08GameType'+bytes(4), b'\x03\x00\x08GameType'+bytes([0,0,0,1]))
nbt=nbt.replace(b'\x01\x00\x08hardcore\x01', b'\x01\x00\x08hardcore\x00')
# Do not carry another review account's saved singleplayer identity.
owner=b'\x0b\x00\x11singleplayer_uuid'+bytes([0,0,0,4])
if owner in nbt:
 pos=nbt.index(owner)
 nbt=nbt[:pos]+nbt[pos+len(owner)+16:]
level.write_bytes(gzip.compress(nbt))
(wd/'display').write_text('')
(wd/'logs/latest.log').unlink(missing_ok=True)
(wd/'options.txt').write_text('version:5023\nfov:0.0\nbobView:false\nrenderDistance:6\nsimulationDistance:5\nguiScale:2\npauseOnLostFocus:false\nmaxFps:30\nenableVsync:false\nsoundCategory_master:0.0\ntutorialStep:none\nonboardAccessibility:false\nresourcePacks:["vanilla","file/vanilla-material"]\n')
x=subprocess.Popen(['Xvfb','-displayfd','1','-screen','0','1280x720x24','-nolisten','tcp'],stdout=open(wd/'display','w'),stderr=open(wd/'xvfb.log','w'))
c=None
try:
 while not (wd/'display').read_text().strip():time.sleep(.1)
 env={**os.environ,'DISPLAY':':'+(wd/'display').read_text().strip(),'LIBGL_ALWAYS_SOFTWARE':'1','LP_NUM_THREADS':'4','XDG_RUNTIME_DIR':str(wd)}
 c=subprocess.Popen(['/home/node/.cache/astera-vfx-shot/venv/bin/portablemc','--main-dir','/home/node/.cache/astera-vfx-shot/mcdir','start','26.3','--mc-dir',str(wd),'--join-world','Vendetta material review','-u','VendettaReview','--resolution','1280x720','--jvm-arg=-Xmx1G'],env=env,stdout=open(wd/'client.log','w'),stderr=subprocess.STDOUT,start_new_session=True)
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
 command('/gamemode creative')
 command('/kill @e[type=minecraft:item_display]')
 command('/difficulty peaceful')
 command('/clear @s')
 command('/give @s minecraft:iron_axe[minecraft:item_model="astera:vendetta-rifle"]')
 key('1')
 command('/time set noon')
 command('/weather clear')
 command('/tp @s 0 -60 0 -25 0')
 time.sleep(12)
 if 'Unknown or incomplete command' in log.read_text(): raise RuntimeError('Capture commands failed; see latest.log')
 subprocess.run(['import','-window','root',str(root/'tools/model-forge/batches/vanilla-material/vendetta-rifle/client.png')],env=env,check=True)

finally:
 if c and c.poll() is None:
  os.killpg(c.pid,signal.SIGTERM)
  c.wait(timeout=30)
 x.terminate()
 x.wait(timeout=10)
