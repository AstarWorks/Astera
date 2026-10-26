# content/stages/

Stage (map) definitions. Schema: `plugin/match/.../application/config/StageYamlConfig.kt`
(strict decode: unknown keys fail the file). Design: ADR-0246 and ADR-0273.

Production stages are **floating siege-scale arenas** built by the server into world slots of the one void `world` (ADR-0255); stage YAML coordinates are stage-local.
Procedural foundations carry [authored YAML structure parts](../structures/README.md). Debug stages are absent from real queues, and retain the previous small layouts.

**Imported worlds (ADR-0281).** `layout.import: {map: <id>, x: 0, z: 0}` (instead of `islands`) pastes the pre-built
world in `content/arenas/<id>.yaml`; objectives, spawns, regions and generator landmarks stay on the stage and use the
map's own x/z with `surface_y` as the spawn floor. Attribution and licence (CC BY-SA 4.0) are in [NOTICE.md](NOTICE.md).

```yaml
id: example-arena
display_name_key: astera.stage.example-arena.name
layout:                      # sky-island block layout; see below
  marker_version: 1
  theme: desert             # temperate | frost | desert | volcanic
  seed: 20261023
  surface_y: 64              # top layer of every island; players stand at surface_y + 1
  kill_y: 30                 # void-fall kill plane (default surface_y - 34)
  relief: 1.0                # terrain relief strength 0..1.5 (hills, channels, cover); 0 = flat (debug stages)
  islands:
    - { id: red, kind: base, team: red, center: { x: -66, z: 0 }, radius: 20 }
    - { id: center, kind: center, center: { x: 0, z: 0 }, radius: 22 }
    - { id: north, kind: outpost, center: { x: 0, z: -62 }, radius: 12 }
  bridges:
    - { from: red, to: center, width: 3 }
  landmarks:
    - { kind: generator, x: 0, z: -62 }
regions:                     # named AABBs; other YAMLs refer to areas by `name`
  - name: full-arena
    min: { x: -100.0, y: 20.0, z: -90.0 }
    max: { x: 100.0, y: 140.0, z: 90.0 }
spawn_points:
  - team: red                # per-team spawn; any number of teams (team ids = the match's teams)
    position: { x: -69.5, y: 65.0, z: 0.5 }
    yaw: 270.0               # optional
  - role: lobby              # team-less: lobby | spectator
    position: { x: 0.5, y: 65.0, z: 0.5 }
```

## Layout

- `kind: base` (team island: grass, team-coloured rim, spawn pad and two banner posts), `center` (stone-brick
  plaza) or `outpost` (small grass satellite). A base island's `team` picks its colour (`accent` overrides it).
- `radius` is nominal (3..64); the rim wobbles by up to ~19 percent. Keep anchors within about 0.78 × radius.
  Production bases are 280 blocks apart (Norma), 320 (Siege), and about 287 (three-team Annihilation).
  Siege castles have four 25-block towers, walls, an open gate, keep, and climbable galleries. Front spawns sit
  about 120–124 blocks from the contested centre (roughly 22 seconds at sprint speed). Wide roads and seven-block
  bridges keep movement straightforward. PvE expands more conservatively to keep wave travel reasonable.
- `theme` supplies a material palette for terrain, trees, roads, bridges, structure walls and lights. Optional
  `structures` placements select a part, x/z, surface-relative y, rotation and palette overrides; see the part README.
- Terrain the builder adds by itself, deterministic in `seed`: stepped hills, ruined walls (centre) / boulders and trees
  (other islands) as cover, a wall arc behind `core-*` only in stages without authored structures. It stays clear of spawn pads, anchors, pillar footings
  and the straight lane from each island centre to its bridges.
- Bridges are drawn centre to centre; an island block always wins over a bridge block.
- Every stage is authored around its own origin (0,0); the server pastes a copy into a free world slot and shifts spawns, regions, pillars, layout and generators by the slot origin at claim time.
- Per-lane siege-isles variants (MODE-06, ADR-0273) copy `siege-isles` with their own lane objective and generator ids: `siege-isles` (break-core), `siege-isles-tnt`, `-wool`, `-jewelry`, `-loyal`, `-mini-tnt`. They carry no offsets (slots separate concurrent matches); keep each variant in sync with `siege-isles.yaml`.
- `arena-worlds.marker` stores each stage's marker version, plan digest and previous bounds. Changed content rebuilds
  on restart; bump `marker_version` when editing a cached stage. The old bounds are cleared before placement.
  Planning/digesting runs off thread. Chunk loading is asynchronous; clearing, placing and releasing chunk tickets
  run in slices sharing a 4 ms tick budget, at most 1024 operations per job slice. Logs report actual maximum slice
  duration, elapsed time and ticks. No match-start path drains a build synchronously: admission returns
  `arena-not-ready` while a stage is pending, so players can queue again once construction completes.
- After each match the builder re-places destroyed island blocks (objective marker cells excluded).
- Players who fall below `kill_y` die.

The builder decorates what the stage already says, so the anchors sit exactly at the built positions:

| Stage data | What gets built |
|---|---|
| `spawn_points` (team) | coloured spawn pad under the team's spawns (checked: on an island, at `surface_y + 1`) |
| `core-<team>` region (y = `surface_y + 1`) | 3x3 blackstone pedestal with team-coloured corners |
| `jewelry-<team>`, `wool-*`, `tnt-spawn` (y = `surface_y`) | 3x3 pedestal; the centre block is replaced by the mode's marker |
| `pillars` (y = `surface_y + 1`) | 3x3 footing with a team-coloured centre |
| `layout.landmarks` `generator` | 3x3 pedestal with an iron block; keep it in step with `content/generators/*.yaml` |

`ShippedArenaStagesBuildTest` builds every shipped stage and fails on any inconsistency (a spawn off an island, an
anchor at the wrong height, overlapping islands, a generator without a landmark).

- Put several spawns per team: entry and respawn hand out the least-used one, so teammates spread.
- 2-team lanes use team ids `red` / `blue`; the `annihilation` mode uses `red` / `blue` / `yellow`.
- Spawn and objective Y values are `surface_y + 1` (standing on the top layer) unless the table says otherwise.

### Region names are the contract with the game modes

Region `name`s are how a mode finds its objective zones (zones are plain AABBs; make them span
`surface_y .. surface_y + 6` so a standing or jumping player is inside). `siege-isles` shows all of them:

| Region name | Used by | Meaning |
|---|---|---|
| `core-<team>` | `break-core` | exactly one block: the team's core (marker placed at match start, one above the floor) |
| `base-<team>` | `carry-tnt`, `steal-jewelry` | team base: TNT delivery target / jewel deposit |
| `jewelry-<team>` | `steal-jewelry` | pedestal zone; its centre floor block is the diamond marker |
| `tnt-spawn` | `carry-tnt` | where the TNT waits |
| `wool-*` | `capture-wool` | any number of control points; the centre floor block is the wool marker |

`<team>` is the team id (`red` / `blue`). A stage that lacks the regions a mode
needs lets the match run without an objective (warning in the log).
`battle-loyal` needs no regions.

## Preview and E2E fixtures

`make arena-preview` renders top-down and isometric PNGs for every stage under `build/arena-previews/`, with
`report.md` recording generation times, counts, content validation, and actual bot A* node counts. The coarse
island/bridge graph chooses long-distance corridors; local A* remains capped at 5000 nodes and uses observed chunks.

`debug-{example-arena,siege-isles,annihilation-arena,pve-proving-grounds}.yaml` are independent small stages. The
`E2E_FAST=1` backend and E2E runner image preparation script substitutes these under the production IDs and restores
compact generator coordinates. This preserves protocol suites' queue assignments, objective coordinates and RCON
assertions. Normal images and real queues use the expanded maps. Do not move the fixtures when adjusting production
maps. The manager runs E2E; this track runs compile-only validation and the offline preview.

## Look and mood (MAP-04 / MAP-05 / MAP-08)

- Outlines are noise-driven: lobes, one or two bays (never towards a bridge) and a peninsula; spawns, anchors and
  authored parts are covered by a guaranteed disc so nothing moves. Flanks are terraced cliffs with overhangs, and
  lower shelves hang around the shore.
- Large islands (radius >= 30) get a themed spire (stone tower / ice spire / stepped obelisk / horned basalt) ending in a
  beacon whose glass colours the beam in the team colour (white on the centre); large outposts get a lit cairn; bases
  get eight team banner standards; objective pedestals get four glowing pylons. Small (debug) islands get none.
- `layout.atmosphere: { time_of_day: 0..23999, weather: clear|rain }` sets the sky. Slots share one world, so it is applied per
  player (fixed player time and weather while inside a slot of that stage, reset on leaving). Omitted = world baseline (noon).
