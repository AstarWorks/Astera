# vfx-shot

Film one skill cast with a **real Minecraft client** (headless: Xvfb + llvmpipe software GL) against a
throwaway Astera server, so VFX can be judged as a player sees them. Scripts only, no Gradle.

```
make vfx-shot SKILL=couture-rapier-s2                     # all three views, 60 ticks
make vfx-shot SKILL=bastion-greatsword-s3 VIEW=caster TICKS=100
SHOT_KEEP=1 make vfx-shot SKILL=...                       # keep the server up for fast re-runs
make vfx-shot-down                                        # drop it afterwards
```

Output: `build/vfx-shot/<skill>/<view>/`

| file | what |
|---|---|
| `frame_NNN.png` | NNN = ticks after the cast (20 fps, so 1 frame = 1 tick), 1280x720 |
| `frame_NNN.webp` | 転送用の軽量プレビュー。原寸PNGは比較・計測用に保持する |
| `sheet.png` | contact sheet, 6 columns, every `SHEET_STEP`-th tick labelled `t+N` |
| `<skill>-<view>.gif` / `.mp4` | the same frames as a loop (gif 640 px wide) |

比較撮影の`meta.json`は参照・contentの版、撮影日時、実装コミット、カメラ内容のハッシュを保持する。
`captureQuality.maxRepeatedFrames`が15以上なら停止映像の疑いを目視確認する。静止した場面もあるため、自動で不合格にはしない。
実際に描画停止を確認した撮影には`captureQuality.invalid: true`を付けると比較画面から除外できる。
ソフトウェア描画は既定で`LP_NUM_THREADS=4`。共有環境では撮影と重いビルドを重ねない。

## Views

| view | client | scene |
|---|---|---|
| `caster` | the caster, first person (`Caster`) | husk (AI off) 4 blocks ahead |
| `opponent` | the target (`Target`) looking at the caster | caster is a second real client (small, 480x270) |
| `side` | `Watcher` in spectator, 6 blocks to the side, looking at the midpoint | caster client + husk target |
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

Every view writes `meta.json` (`view`, `skill`, `frames`, `fov`, `fovMode` = `fixed` | `intent-first-key` | `override-dolly` | `override-nodolly`, `resolution`,
`capturedAt`, `branch`, `implementationCommit`); skill-compare shows it as the provenance badge and `make skill-brief` copies it into its manifest.

## How it works

1. Boots compose project `astera-shot` from `docker/docker-compose.e2e.yml` + `compose.override.yml`
   (pins a free `10.N.77.0/24` network + container IPs; `astera-dev` / `astera-e2e-*` are never touched).
   The dev container is attached to that network (`docker network connect`) so clients reach the server.
2. `rcon.py` talks RCON straight to the server IP; readiness is polled (RCON / client log), never slept on.
3. Clients: `portablemc` (offline name) with `--join-server`. Options are written per client
   (render distance 6, FOV 90 (compare: the intent's), no view bob, particles all, GUI scale 2, sound off, no pause on focus loss).
   F1 hides the HUD (`HIDE_HUD=0` keeps it; note F1 also hides the first-person hand).
4. `ffmpeg -f x11grab` records with wall-clock timestamps; `rcon.py --stamp` prints the epoch time just before
   the cast, so frame 0 is the cast tick (plus `CAST_LAG_MS`, default 60, for render/network lag).
5. Everything (clients, Xvfb, server) is torn down on exit (trap), unless `SHOT_KEEP=1`.

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

Knobs: `ITEM` (held item, default `bastion-greatsword`), `D` (camera distance), `REST_TICK`, plus `RES`, `FOV`, `SETTLE`, `CAST_LAG_MS`, `SHOT_KEEP`. Run it under
the global lock like every capture (`flock /tmp/claude-1000/astera-e2e.lock make anim-shot ANIM=...`). Frames are not "published" (no
`publish-capture.mjs` step: that tool wants a contiguous `frame_NNN` set per skill/view); pages cache-bust on the capture's mtime.
Read the result in the browser: `make skill-compare` -> `/anim` (see `tools/skill-compare/README.md`).

## Knobs (env)

`VIEW`, `TICKS`, `ITEM` (default: skill id minus `-sN`/`-def`), `TIME_OF_DAY` (6000), `DISTANCE` (4),
`RES` (1280x720), `HIDE_HUD`, `GUI_SCALE`, `FOV` (degrees, 30-110; default 90 for caster/opponent/side, compare: the intent's first camera key unless set), `FOV_DOLLY` (compare: 1 = keep the reference framing, 0 = keep camera positions and widen), `FOV_OPT` (raw normalised value, 0.0 = 70), `RENDER_DISTANCE`, `SETTLE` (6 s),
`PRE_ROLL`, `SHEET_STEP` (2), `CAST_LAG_MS`, `SHOT_KEEP`, `SHOT_CACHE` (portablemc venv + game files,
default `~/.cache/astera-vfx-shot`; first run downloads the client ~ 1 GB).

## Troubleshooting

- **`Pool overlaps`** creating the network: the script probes for a free `10.N.77.0/24`; if all of N=211..250
  are taken, free one with `docker network prune`.
- **Client exits / never joins**: `build/vfx-shot/.clients/<name>/client.log`; GL problems -> make sure
  `LIBGL_ALWAYS_SOFTWARE=1` works (`glxinfo` under Xvfb). Needs ~2 GB RAM per client.
- **Magenta items**: `client.log` lists `Missing textures in model astera:...`; check the overlay pack got zipped.
- **Frames look early/late**: tune `CAST_LAG_MS`; software GL at 1280x720 renders ~35 fps, so +-1 tick is normal.
- **Skill does nothing**: cast needs the skill registered (`astera skill list`) and the caster able to cast in
  the overworld; check `docker logs astera-shot-e2e-1`.
- **Players bounce back to the lobby**: the lobby plugin sends anybody below y=19 back (void rescue), also in the overworld; the arena is therefore a platform at y=100 (`FLOOR_BLOCK`).
- **Stale processes**: the trap kills clients by process group; leftovers are `portablemc` / `java` / `Xvfb :19x`
  under your user - kill by pid, never `pkill java`.
- **Old server in the way**: `make vfx-shot-down`.

## Parallel runs (several agents, one machine)

Every run is isolated by `SHOT_ID` = hash(worktree path + CASE-or-SKILL): compose project `astera-shot-<id>` (`SHOT_PROJECT=` to override),
image tag, container, docker network and its own free `10.N.77.0/24` (picked + created under a global lock). No fixed host ports (the
server publishes ephemeral 127.0.0.1 ports only; clients join over the compose network IP), Xvfb picks its own display (`-displayfd`),
the trap disconnects this container from the network and removes the project. `make vfx-shot-down` (same SKILL/CASE) drops a `SHOT_KEEP=1` leftover.

**Slots**: a global semaphore of `flock`ed files `/tmp/claude-1000/vfx-shot-slots/slot-{1..N}.lock` (`SHOT_SLOTS`, default 2; `SHOT_SLOT_DIR`).
A run takes a slot BEFORE the image build (gradle inside docker) and keeps it through server + clients + capture; when all are busy it
prints `queued: ...` and polls every 3 s. The kernel frees the slot when the run exits, however it ends.

**Target dummy**: husk with movement_speed 0, follow_range 0, attack_damage 0, silent, fixed rotation facing the caster, HP 200, summoned
fresh per view (NOT `NoAI`: that would also swallow knockback/velocity). It can still turn its head toward the caster, never walk or attack.


When reusing one client for several comparison cases, pass its actual FOV in degrees as the optional last argument to `compare_plan.py` (for example `70`). This converts every intent camera key to the running client's FOV. Without this argument the emitted `CMP_FOV_OPT` must be applied when starting the client; changing a shell variable after startup does not change the rendered FOV. A camera plan with correct response timing but the wrong client FOV is not valid framing evidence.
