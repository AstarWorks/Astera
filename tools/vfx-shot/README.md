# vfx-shot

Film one skill cast with a **real Minecraft client** (headless: Xvfb + llvmpipe software GL) against an
Astera server, so VFX can be judged as a player sees them. Scripts only; compile-only Gradle tasks run on the host.

```
make vfx-shot SKILL=couture-rapier-s2                     # all three views, 60 ticks (640x360)
make vfx-shot SKILL=bastion-greatsword-s3 VIEW=caster TICKS=100
SKILL=bastion-greatsword-s1 ITEM=bastion-greatsword VIEW=compare CASE=bastion-greatsword-s1 make vfx-shot   # 1280x720, case camera
make vfx-shot SKILL=... BEAT_TICKS=case                   # only the PNGs of the case's beat ticks
make vfx-shot-up [VIEW=compare CASE=<case>]               # create/refresh the warm stack and park the clients, no capture
make vfx-shot-status [CPU=1]                              # what the stack serves vs the working tree, clients, locks
make vfx-shot-down                                        # drop the stack (server, pack host, volumes, parked clients)
```

## Warm stack (default)

Each checkout (worktree) owns **one warm stack**: compose project `astera-shot-<sha1(checkout path)[:8]>` = Paper server +
resource-pack host (stock nginx) + redis, and the parked clients (`Watcher`, `Caster`, `Target` as the views need them). It is
created by the first capture (or `make vfx-shot-up`) and then reused by every skill / case / view filmed from that checkout;
it survives between runs until `make vfx-shot-down`. There is **no image build**: the server is the stock
`itzg/minecraft-server` image, and plugin jars, `content/` and the pack zip are `docker cp`ed into named volumes
(`/plugins`, `/content`) and the nginx html dir. `SHOT_EPHEMERAL=1` restores the old one-shot behaviour (stack and clients are
removed when the run ends).

Every capture first **syncs the stack with the working tree** (each step is a no-op when nothing changed; phase timings are
logged as `[+Ns]`):

| what changed | action | cost |
|---|---|---|
| `content/` (any file; fingerprint = `contentSourceHash`) | stage the working tree (`git ls-files -c -o --exclude-standard`) + the e2e timer tuning of `docker/e2e-tune-content.sh`, `docker cp` into `/content`, `/astera admin reload all`, `/astera items refresh` (same as `scripts/dev-deploy.sh content`) | ~2 s |
| pack inputs (`content/{textures,models,sounds,hud,fonts,pack-assets,shader-patches,particle-skins,items,sprites}`, `tools/resource-pack`) | host `./gradlew :tools:resource-pack:buildResourcePack`, `docker cp` zip + sha1 into the pack host, `/astera admin pack reload force`; parked clients are waited for until they have reloaded the pack | ~10-20 s |
| plugin code (`plugin/`, `buildSrc/`, `gradle/`, root build files; fingerprint = `codeHash`) | host `./gradlew :plugin:*:shadowJar` (seven jars), copy the changed jars, **restart the server container** (~40 s); the clients drop and are rejoined by the capture | ~1-2 min |
| `SHOT_NO_FX` | recreate the server container (it is an env var) | ~40 s |

**No stale content is ever served**: after every push the hash of `/content` inside the container is computed with the same
`find | sha256sum` pipeline as the staged tree and must match (`stack.deployedContentHash`, `contentVerifiedInContainer` in
`meta.json`), the pack sha1 served by the pack host must equal the zip just built, and `capture_meta.py` fingerprints the working tree
again after the capture: if content, pack inputs or plugin code changed on disk while the capture ran, `meta.json` gets
`captureQuality.invalid: true` (recapture). `make vfx-shot-status` prints deployed vs working-tree hashes per layer (`fresh` / `STALE`).

**Parked clients are frozen between captures** (`parker.sh`): a joined software-GL client renders at ~2 cores even when nobody
looks, so a detached loop `SIGSTOP`s every parked JVM and thaws it for 2 s out of every 10 s (the server's keepalive timeout is 30 s).
Measured: ~470 % of a core for three idle clients, ~90 % frozen-cycled. A capture thaws them first (`capture.active` marker).
A client is reused when it is alive, joined, and was started with the same window size, FOV option, render distance and server
address; otherwise it is restarted (join ~50 s). Switching views therefore restarts a client only when its size changes
(`Caster` is 640x360 in the `caster` view and 480x270 as the dummy caster of the other views; `VIEW=compare` keeps `Watcher` at 1280x720).

### Locks and concurrency (not the e2e flock)

Captures **never take `/tmp/claude-1000/astera-e2e.lock`** (that lock protects the host from e2e's memory, not from capture
stacks; do not wrap `make vfx-shot` in it). Three layers instead:

1. **per-stack lock** `build/vfx-shot/.state/<id>/stack.lock`: captures from the same checkout queue behind each other (they share
   the arena and the parked clients). Different skills / views of one checkout are fine, just serial.
2. **global semaphore** `/tmp/claude-1000/vfx-shot-slots/slot-{1..SHOT_SLOTS}.lock` (default 2): at most `SHOT_SLOTS` captures
   render at once, host-wide, across worktrees. Held for the capture only, not for the life of the warm stack. The old one-shot flow
   (another agent still running a pre-warm `vfx-shot.sh`) uses the same slots, so both flows queue behind each other correctly.
3. **memory guard**: starting a server or a client waits while `MemAvailable` < `SHOT_MIN_FREE_GB` (default 8; `SHOT_MEM_WAIT`
   seconds, default 900, then it fails). A warm stack is ~6-7 GB resident (server 2 GB heap + ~2.5 GB per client).

**Different worktrees never corrupt each other**: the choice is *one stack per checkout* (own compose project, subnet, volumes,
client directories, deployed-state record), so a content edit in worktree A never reaches worktree B's server. The price is memory
(one stack each, which is what the guard budgets) instead of a queue on a shared server.

Output: `build/vfx-shot/<skill>/<view>/`

| file | what |
|---|---|
| `frame_NNN.png` | NNN = ticks after the cast (20 fps, so 1 frame = 1 tick); 640x360 for caster/opponent/side, 1280x720 for compare (`RES=` overrides). With `BEAT_TICKS` only the beat frames exist (frame_NNN keeps its number) |
| `frame_NNN.webp` | 転送用の軽量プレビュー。原寸PNGは比較・計測用に保持する |
| `sheet.png` | contact sheet, 6 columns, every `SHEET_STEP`-th tick labelled `t+N` |
| `<skill>-<view>.gif` / `.mp4` | the same frames as a loop (gif 640 px wide); not written with `BEAT_TICKS` |
| `clapper.json` | tick clapperboard measurement (below), also copied into `meta.json` `clapper` |
| `recording.mkv`, `recording.showinfo.log` | only with `SHOT_KEEP_REC=1` (debugging the clapperboard) |

`BEAT_TICKS=case` keeps the PNGs of the `beats[].tick` of `tools/skill-compare/data/<CASE>/case.json`; `BEAT_TICKS=0,8,20` an explicit list
(ticks after the cast). A beat-only capture has `meta.json` `beatFramesOnly: true` and is **not publishable** (`publish-capture.mjs` wants a
contiguous PNG sequence); it is for fast pose / VFX iteration.

## Tick clapperboard (frame <-> server tick, measured)

A capture used to assume `frame 0 = RCON stamp + CAST_LAG_MS (60 ms)`. Now (`CLAPPER=1`, default) the stack carries a tiny datapack
(`tools/vfx-shot/clapper-pack`, installed by the sync step, hash-checked) whose per-tick function writes `game time mod 40` into the fill
of a red boss bar. The recording starts and ends with the HUD visible (`CLAP_HEAD` / `CLAP_TAIL`, 1.2 s each; F1 hides it for the cast
and the motion, so **the saved frames never contain the bar**). `clapper.py` reads the bar in those head/tail windows, unwraps it
against the game-time marks that `director.py` reads on the same RCON connection right before and after the cast (`MARK_BEFORE` /
`MARK_AFTER`: equal = the cast tick is exact, one tick apart = +-0.5 tick), fits `game time(t)` over both windows with outlier trimming
(frames the bursty software client drew late), and extracts frame 0 at the video time at which the cast tick was on screen.
`meta.json` / `clapper.json` `clapper`: `castTick`, `castTickSpan`, `castTickExact`, `measuredCastLagMs` (cast stamp -> frame showing the cast tick),
`tickRateHz` (must be 20 +-1 or the fit is rejected), `maxResidualTicks`, `droppedSamples`, `barSamples`, `fitHolds`, and for compare
`cutCheck`.

The vanilla boss bar eases toward each new value over 100 ms, so the bar reads 100 ms later than the world. Camera hard cuts are real
world changes with a known server tick (`CUT_MARK` = game time right before each `tp`); the median of (frame in which the cut appears) -
(bar prediction for its tick) is the correction (measured -100 ms, `barLerpCorrectionMs`); captures without cuts apply -100 ms.
Measured on this host for bastion-greatsword-s1: lag 53-130 ms (not a constant 60 ms), with +-1-2 ticks of frame-to-frame jitter that no
amount of calibration removes (the client draws in bursts). If the bar cannot be read or the fit does not hold, the capture falls back to
`CAST_LAG_MS` and `meta.json` says `clapper.measured: false`. `CLAPPER=0` skips the bookends (about 2.5 s faster).

比較撮影の`meta.json`は参照・contentの版、撮影日時、実装コミット、カメラ内容のハッシュを保持する。
`captureQuality.maxRepeatedFrames`が15以上なら停止映像の疑いを目視確認する。静止した場面もあるため、自動で不合格にはしない。
視聴側クライアントのログ(`viewer-client.log`)に`You were defeated`・ロビー復帰・ワールド変更があるか、視聴者が撮影前位置から40ブロック超動いた(`viewer-pos.txt`)場合は、`captureQuality.invalid: true`と`reason`が自動で付く。再撮影が必要。
opponent視点のTargetは最大HP200で全回復してから撮影する(多段ヒットでの死亡を防ぐ)。
実際に描画停止を確認した撮影には`captureQuality.invalid: true`を付けると比較画面から除外できる。
ソフトウェア描画は既定で`LP_NUM_THREADS=4`。共有環境では撮影と重いビルドを重ねない。

## Views

| view | client | scene |
|---|---|---|
| `caster` | the caster, first person (`Caster`) | husk (AI off) 4 blocks ahead |
| `opponent` | the target (`Target`) looking at the caster | caster is a second real client (small, 480x270) |
| `side` | `Watcher` in spectator, 6 blocks off the caster's left, looking at the midpoint | caster client + husk target |
| `right` | as `side`, from the caster's right | caster client + husk target |
| `front` | `Watcher` ahead of the caster, 2 blocks beside the target line (+1.7 up), looking back at the caster's chest | caster client + husk target |
| `back` | `Watcher` 5 blocks behind the caster (+2.0 up), looking at its chest | caster client + husk target |
| `high` | `Watcher` back-left, 5.8 up, looking ~45 deg down at the swing | caster client + husk target |
| `near` | `Watcher` at eye height ~2.4 blocks off the caster's left (a bystander) | caster client + husk target |

The orbit views (`side`, `right`, `front`, `back`, `high`, `near`) are placed by `orbit_cam` in `vfx-shot.sh`; use them to check
that an effect reads from every angle (founder 2026-10-26), not only from the compare camera. Publish each with
`publish-capture.mjs <dir> <root> <skill> <view>`; skill-compare lists them in the in-game source menu.
| `compare` | `Watcher` in spectator, camera from a skill-compare case | caster client (facing +x, nametag hidden) + husk (200 HP, speed 0 + follow range 0 instead of NoAI, which ignores knockback) `ENEMY_X` ahead, on a 61x61 light-grey concrete floor |

The cast is `/astera skill cast <skill> Caster --force` (existing operator command, ADR-0196: the regular
cast pipeline, no resource cost, cooldown cleared). The caster aims where its client looks, so the scene is
framed with `/tp x y z yaw pitch`. Arena: overworld flat world at (300.5, -60, 300.5), away from the lobby.

### `VIEW=compare CASE=<case-id>`

```
SKILL=bastion-greatsword-s3 VIEW=compare CASE=zwei-s3 bash tools/vfx-shot/vfx-shot.sh   # TICKS defaults to intent duration_ticks
```

`tools/skill-compare/data/<case>/intent.json` drives the scene: `actors.enemy` first key = enemy distance, `camera` keys
(x toward the enemy, y up, z toward the side camera, caster at the origin; world = arena centre + intent) = spectator
`/tp` hard cuts at `t` ticks after the cast (`director.py` sends the cast + the cuts over one RCON connection on a wall
clock). The client FOV option is the first key's fov; other keys' fov is emulated by dollying along the view ray
(`compare_plan.py`). `CAMERA=side` ignores the keys (6 blocks to the side, eye level). Output
`build/vfx-shot/<skill>/compare/` has `PRE_TICKS_CMP` (6) frames before the cast and `meta.json` with `cast_tick_ms`,
which skill-compare reads (source `vfx-compare`, the default) to align the frames with the case's skill start.

Every view writes `meta.json` (`view`, `skill`, `fps`, `frames`, `fov`, `fovMode` = `fixed` | `intent-first-key` | `override-dolly` | `override-nodolly`, `resolution`,
`capturedAt`, `branch`, `implementationCommit`, plus the revision fields below, the `stack` block and the `clapper` block); skill-compare shows it as the provenance badge and `make skill-brief` copies it into its manifest.

| revision field | meaning |
|---|---|
| `contentRevision` | fingerprint of what was filmed: `git rev-parse HEAD:content` (tree hash of `content/`), plus `+dirty-<12 hex>` (sha256 over every modified / deleted / untracked file under `content/`) when the tree is not clean |
| `contentDirty` | `content/` had uncommitted changes at capture time (the fingerprint then only reproduces from that working tree) |
| `codeRevision` | `git rev-parse HEAD` of the checkout that built the server (same value as `implementationCommit`) |
| `codeDirty` | uncommitted changes outside `content/` at capture time |
| `referenceRevision`, `caseContentRevision` | copied from the skill-compare `case.json` (compare view only); skill-compare matches captures to cases on these, never on `contentRevision` |

Equal `contentRevision` + `codeRevision` (neither dirty) means identical content and code were filmed.

Warm-run provenance, `meta.json` `stack` (read from the deployed-state record `build/vfx-shot/.state/<id>/deployed.json`, written by the sync steps):

| field | meaning |
|---|---|
| `mode` | `cold` when this run created the stack, `warm` when it reused it |
| `contentSourceHash`, `deployedContentHash`, `contentVerifiedInContainer` | fingerprint of the working-tree `content/` that was pushed, the hash of `/content` as the server holds it (staged tree incl. the e2e tuning), and whether the two matched after the push |
| `packHash`, `packSha1` | fingerprint of the pack inputs and the sha1 the pack host serves |
| `codeHash`, `deployedCodeRevision`, `deployedCodeDirty` | fingerprint of the plugin sources behind the running jars, and `git HEAD` / dirtiness at deploy time |
| `workingTreeInSync` | the working tree still matched the deployed state when the capture ended (false => `captureQuality.invalid`) |

`contentRevision` / `codeRevision` keep describing the working tree at capture time; because the sync runs first and `workingTreeInSync`
guards the end, they equal what was served.

## How it works

1. Boots (or reuses) compose project `astera-shot-<id>` from `docker/docker-compose.e2e.yml` + `compose.override.yml`
   (stock images, pins a free `10.N.77.0/24` network + container IPs; `astera-dev` / `astera-e2e-*` are never touched).
   The dev container is attached to that network (`docker network connect`) so clients reach the server.
2. `rcon.py` talks RCON straight to the server IP; readiness is polled (RCON / client log), never slept on.
3. Clients: `portablemc` (offline name) with `--join-server`. Options are written per client
   (render distance 6, FOV 90 (compare: the intent's), no view bob, particles all, GUI scale 2, sound off, no pause on focus loss).
   F1 hides the HUD (`HIDE_HUD=0` keeps it; note F1 also hides the first-person hand).
   **Caster view caveat:** with the default `HIDE_HUD=1` the caster's own held item is NEVER drawn in first person, before,
   during or after the skill (f0-2 are empty-handed too). Only world entities show, so a `hide_held_item` rig appears and
   then the hand looks empty to the last frame. That is the capture, not a missing restore. To judge the held-item
   hand-over in first person, run `HIDE_HUD=0 make vfx-shot ... VIEW=caster`. Bastion S1 at 5dbac89d with the HUD on: held sword f0-2,
   held sword + rig overlap f2-4, bare hand + rig f48-58, held sword back at f59 (vanilla re-equip raise from the bottom-right,
   settled by ~f72). `PaperEntityEffectsGateway.sendHeldItem` sends both hide and restore to every player in the
   world, the caster included.
4. `ffmpeg -f x11grab` records with wall-clock timestamps; `director.py` stamps the epoch time just before the cast and reads the
   server game time around it, and `clapper.py` turns that into the frame offset (see "Tick clapperboard"; `CAST_LAG_MS` is only the fallback).
5. Clients and the stack stay up after the run (parked); `make vfx-shot-down` removes them. `SHOT_EPHEMERAL=1` tears everything down on exit.

### Resource pack

The server pushes `astera.zip` from the compose `e2e-resource-pack` service (URL = its pinned IP).
`servers.dat` pre-accepts it (`acceptTextures`), so there is no prompt. **Astera item textures need an
extra atlas source**: the pack stores them in `textures/generated/items/` and `textures/items/`, which no
vanilla atlas covers, so every Astera item is magenta/black without help. `overlay-pack/` (zipped per run into the
client's `resourcepacks/`) adds `assets/minecraft/atlases/items.json`. Remove it once the pack builder ships
that atlas itself.

## Skin-rig animations (ADR-0250)

`content/animations/*.yaml` motions replace the caster's body with a Display-entity rig for their length. To film one
from the side on the caster's own skin, use the demo skill that plays it:

```
make vfx-shot SKILL=anim-demo-sword-swing VIEW=side TICKS=40        # 12 ticks of motion + 3 blend-out, 40 leaves a tail
```

`VIEW=side` puts a spectator `Watcher` 6 blocks away looking at the caster (a second real client), which is the
third-person footage needed to judge the rig. Check, frame by frame: the rig appears with no pop at `t+0` (rest pose
equals the vanilla body), the head / torso / both arms / both legs show the right parts of the skin (not the head
texture stretched over every bone), the held item sits in the right hand, and the real body is back after ~15 ticks.
To add another motion, copy `content/skills/anim-demo-sword-swing.yaml` and change `animation:`. The offline-mode test
clients have the default Steve / Alex skin of their UUID, not a custom skin: to film a real skin, join an online-mode
dev server with the pack and run `/astera emote sword-swing` (any id in `content/animations/`).

### Animation review capture (`anim-shot.sh`)

One command per motion, feeding the skill-compare `/anim` pages (founder review in the browser):

```
make anim-shot ANIM=wave                        # an animation id: played with `/astera emote wave`
make anim-shot ANIM=anim-demo-sword-swing       # a skill id whose play_animation runs the motion (id looked up in content/skills/)
```

Same machinery as `vfx-shot.sh` (it sources the file with `VFX_SHOT_SOURCED=1`): slot queue, throwaway server, `Watcher` spectator
at 1280x720 FOV 90 + a `Caster` client, wall-clock sync on the RCON stamp. Camera: 2.4 blocks from the caster, `front` (looking at
the caster's face), `side` (90 degrees), `top` (3 blocks above, looking down). Output `build/vfx-shot/anim/<animation-id>/`:

| file | what |
|---|---|
| `vanilla-{front,side,top}.png` | the real body at rest |
| `rig-{front,side,top}.png` | the rig at tick `REST_TICK` (default max(2, blend_in_ticks): the first tick the rig is surely on screen, since at t+0 the client has not drawn it yet; the real tick is in `meta.json` `restTick` and on the page) |
| `play/{front,side}/frame_NNN.{png,webp}` | every tick of the motion plus its blend-out (`length + blend_out + 4` ticks; a `loop: true` motion films `length` ticks); NNN = tick + 3, so `frame_000` = t-3 |
| `blend-in/{front,side}/`, `blend-out/{front,side}/` | slices of `play/` around the blends (a looping motion films its blend-out separately, relative to `emote stop`); first tick in `meta.json` |
| `meta.json` | provenance: `branch`, `implementationCommit`, `capturedAt`, `anim`, `trigger`, `lengthTicks`, `loop`, blend lengths, `playPreTicks`, `resolution`, `fov` |

Knobs: `ITEM` (held item, default `bastion-greatsword`), `D` (camera distance), `REST_TICK`, plus `RES`, `FOV`, `SETTLE`, `CAST_LAG_MS`, `SHOT_KEEP`. `anim-shot` is still a one-shot
flow (own throwaway stack, torn down at the end; it now also boots from the stock images instead of building one), not the warm stack. It uses the same slots; do not wrap it in the e2e lock. Frames are not "published" (no
`publish-capture.mjs` step: that tool wants a contiguous `frame_NNN` set per skill/view); pages cache-bust on the capture's mtime.
Read the result in the browser: `make skill-compare` -> `/anim` (see `tools/skill-compare/README.md`).

## Knobs (env)

`VIEW`, `TICKS`, `ITEM` (default: skill id minus `-sN`/`-def`), `TIME_OF_DAY` (6000), `DISTANCE` (4),
`RES` (default 1280x720 for compare, 640x360 for the other views), `HIDE_HUD`, `GUI_SCALE`, `BEAT_TICKS`, `CLAPPER` (1), `CLAP_HEAD` / `CLAP_TAIL` (1.2 s),
`SETTLE_WARM` (2 s, scene settle when both clients were already parked), `SHOT_EPHEMERAL`, `SHOT_MIN_FREE_GB` (8), `SHOT_MEM_WAIT` (900 s), `SHOT_SLOTS` (2),
`SHOT_KEEP_REC`, `PARK_RUN` / `PARK_STOP` (2 / 8 s parker cycle), `CPU=1 make vfx-shot-status` (samples the parked cost over 10 s), `FOV` (degrees, 30-110; default 90 for caster/opponent/side, compare: the intent's first camera key unless set), `FOV_DOLLY` (compare: 1 = keep the reference framing, 0 = keep camera positions and widen), `FOV_OPT` (raw normalised value, 0.0 = 70), `RENDER_DISTANCE`, `SETTLE` (6 s),
`PRE_ROLL`, `SHEET_STEP` (2), `CAST_LAG_MS` (fallback only), `SHOT_CACHE` (portablemc venv + game files,
default `~/.cache/astera-vfx-shot`; first run downloads the client ~ 1 GB).

## Troubleshooting

- **`Pool overlaps`** creating the network: the script probes for a free `10.N.77.0/24`; if all of N=211..250
  are taken, free one with `docker network prune`.
- **Client exits / never joins**: `build/vfx-shot/.clients/<name>/client.log`; GL problems -> make sure
  `LIBGL_ALWAYS_SOFTWARE=1` works (`glxinfo` under Xvfb). Needs ~2 GB RAM per client.
- **Magenta items**: `client.log` lists `Missing textures in model astera:...`; check the overlay pack got zipped.
- **Frames look early/late**: read `meta.json` `clapper` (measured lag, residual, `fitHolds`); software GL renders in bursts, so +-1-2 ticks of jitter is normal. `CAST_LAG_MS` only matters when `clapper.measured` is false.
- **`clapperboard could not be read`**: re-run with `SHOT_KEEP_REC=1` and look at `build/vfx-shot/<skill>/<view>/clapper.json` / `recording.mkv`; usual causes are a host so loaded that the client barely draws, or a missing datapack (`datapack list` over RCON must show `file/vfxclap`).
- **Stack looks wrong / half-created**: `make vfx-shot-down`, then run again. `make vfx-shot-status` shows what is stale.
- **Skill does nothing**: cast needs the skill registered (`astera skill list`) and the caster able to cast in
  the overworld; check `docker logs astera-shot-e2e-1`.
- **Players bounce back to the lobby**: the lobby plugin sends anybody below y=19 back (void rescue), also in the overworld; the arena is therefore a platform at y=100 (`FLOOR_BLOCK`).
- **Stale processes**: the trap kills clients by process group; leftovers are `portablemc` / `java` / `Xvfb :19x`
  under your user - kill by pid, never `pkill java`.
- **Old server in the way**: `make vfx-shot-down` (drops this checkout's warm stack; other worktrees' stacks and the `astera-dev-*` / `astera-e2e-*` containers are untouched).

## Parallel runs (several agents, one machine)

Every checkout is isolated by `SHOT_ID` = hash(worktree path): compose project `astera-shot-<id>` (`SHOT_PROJECT=` to override),
container, volumes, docker network and its own free `10.N.77.0/24` (picked + created under a global lock). No fixed host ports (the
server publishes ephemeral 127.0.0.1 ports only; clients join over the compose network IP), Xvfb picks its own display (`-displayfd`).
State (subnet, deployed-state record, client pids, locks) lives in `build/vfx-shot/.state/<id>/`, client directories in `build/vfx-shot/.clients/<id>/`.
`make vfx-shot-down` drops the stack of the current checkout. See "Locks and concurrency" above for the stack lock, the slots and the memory guard
(the slot semaphore is held per capture; the kernel frees it when the run exits, however it ends).

**Target dummy**: husk with movement_speed 0, follow_range 0, attack_damage 0, silent, fixed rotation facing the caster, HP 200, summoned
fresh per view (NOT `NoAI`: that would also swallow knockback/velocity). It can still turn its head toward the caster, never walk or attack.


When reusing one client for several comparison cases, pass its actual FOV in degrees as the optional last argument to `compare_plan.py` (for example `70`). This converts every intent camera key to the running client's FOV. Without this argument the emitted `CMP_FOV_OPT` must be applied when starting the client; changing a shell variable after startup does not change the rendered FOV. A camera plan with correct response timing but the wrong client FOV is not valid framing evidence.
