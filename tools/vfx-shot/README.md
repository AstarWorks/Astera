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
| `sheet.png` | contact sheet, 6 columns, every `SHEET_STEP`-th tick labelled `t+N` |
| `<skill>-<view>.gif` / `.mp4` | the same frames as a loop (gif 640 px wide) |

## Views

| view | client | scene |
|---|---|---|
| `caster` | the caster, first person (`Caster`) | husk (AI off) 4 blocks ahead |
| `opponent` | the target (`Target`) looking at the caster | caster is a second real client (small, 480x270) |
| `side` | `Watcher` in spectator, 6 blocks to the side, looking at the midpoint | caster client + husk target |

The cast is `/astera skill cast <skill> Caster --force` (existing operator command, ADR-0196: the regular
cast pipeline, no resource cost, cooldown cleared). The caster aims where its client looks, so the scene is
framed with `/tp x y z yaw pitch`. Arena: overworld flat world at (300.5, -60, 300.5), away from the lobby.

## How it works

1. Boots compose project `astera-shot` from `docker/docker-compose.e2e.yml` + `compose.override.yml`
   (pins a free `10.N.77.0/24` network + container IPs; `astera-dev` / `astera-e2e-*` are never touched).
   The dev container is attached to that network (`docker network connect`) so clients reach the server.
2. `rcon.py` talks RCON straight to the server IP; readiness is polled (RCON / client log), never slept on.
3. Clients: `portablemc` (offline name) with `--join-server`. Options are written per client
   (render distance 6, FOV 70, no view bob, particles all, GUI scale 2, sound off, no pause on focus loss).
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

## Knobs (env)

`VIEW`, `TICKS`, `ITEM` (default: skill id minus `-sN`/`-def`), `TIME_OF_DAY` (6000), `DISTANCE` (4),
`RES` (1280x720), `HIDE_HUD`, `GUI_SCALE`, `FOV_OPT` (0.0 = 70), `RENDER_DISTANCE`, `SETTLE` (6 s),
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
