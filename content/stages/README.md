# content/stages/

Stage (map) definitions. Schema: `plugin/match/.../application/config/StageYamlConfig.kt`
(strict decode: unknown keys fail the file). Design: ADR-0246.

Every playable stage is a **floating sky-island arena the server builds itself** in the void `arenas` world
(created by Astera on game-role backends). Nothing is hand-built and no schematic is needed.

```yaml
id: example-arena
display_name_key: astera.stage.example-arena.name
world: arenas                # the void arena world (default: the main world "world")
layout:                      # sky-island block layout; see below
  marker_version: 1
  seed: 20261023
  surface_y: 64              # top layer of every island; players stand at surface_y + 1
  kill_y: 30                 # void-fall kill plane (default surface_y - 34)
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
- `radius` is nominal; the rim wobbles by up to ~19 percent. Keep every spawn / anchor within about 0.78 x radius of
  its island centre so it is always on the island. Rough guide (founder playtest, ADR-0246 follow-up): team islands
  55-75 blocks from the centre, radius 20-22; centre island radius 22-30; flank / outpost islands radius 8-14,
  with a second route between the teams (bridges base - flank - base). Keep the spawn-to-centre walk under ~20 s.
- Terrain the builder adds by itself, deterministic in `seed`: stepped hills, ruined walls (centre) / boulders and trees
  (other islands) as cover, a wall arc behind every `core-*`. It stays clear of spawn pads, anchors, pillar footings
  and the straight lane from each island centre to its bridges.
- Bridges are drawn centre to centre; an island block always wins over a bridge block.
- Stages share the `arenas` world, so each lives in its own area (`example-arena` at the origin, `siege-isles` at
  z=300, `annihilation-arena` at z=-300, `pve-proving-grounds` at x=300); each spans under ~200 blocks, so the areas
  stay disjoint (`ShippedArenaStagesBuildTest`).
- The build is versioned: a marker per stage (in Astera-Core's data folder, `arena-worlds.marker`) stores
  `marker_version` + a digest of the generated blocks. A changed layout, spawn, anchor or landmark rebuilds that
  stage on the next boot (or the next match start); an unchanged one places nothing. Bump `marker_version` to force it.
  A rebuild first clears the previous build's recorded bounds, so smaller old islands never stay floating. A build of
  more than 50k blocks is placed in per-tick slices (about 20 ms per tick) and logs its duration; a match that starts
  meanwhile finishes it on the spot.
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
