# content/stages/

Stage (map) definitions. Schema: `plugin/match/.../application/config/StageYamlConfig.kt`
(strict decode: unknown keys fail the file).

```yaml
id: example-arena
display_name_key: astera.stage.example-arena.name
world: world                 # optional; Bukkit world the stage is played in (default: main world "world")
regions:                     # named AABBs; other YAMLs refer to areas by `name`
  - name: full-arena
    min: { x: -50.0, y: -60.0, z: -50.0 }
    max: { x:  50.0, y: -30.0, z:  50.0 }
spawn_points:
  - team: red                # per-team spawn; any number of teams (team ids = the match's teams)
    position: { x: -45.0, y: -60.0, z: 0.0 }
    yaw: 270.0               # optional
  - role: lobby              # team-less: lobby | spectator
    position: { x: 0.0, y: -60.0, z: 0.0 }
```

- Put several spawns per team: entry and respawn hand out the least-used one, so teammates spread.
- 2-team lanes use team ids `red` / `blue`; the `annihilation` mode uses `red` / `blue` / `yellow`.
- Stages are hand-built in their world (no schematic / core fields; add them together with a consumer).
- The docker playtest world is superflat: stand at y=-60. `example-arena`,
  `annihilation-arena` and `siege-flat` target it; the other stages keep their original (y>=64) coordinates.

### Region names are the contract with the game modes

Region `name`s are how a mode finds its objective zones (zones are plain AABBs;
make them span y -61..-55 on the flat world so a standing or jumping player is inside).
`siege-flat` shows all of them:

| Region name | Used by | Meaning |
|---|---|---|
| `core-<team>` | `break-core` | exactly one block: the team's core (marker placed at match start) |
| `base-<team>` | `carry-tnt`, `steal-jewelry` | team base: TNT delivery target / jewel deposit |
| `jewelry-<team>` | `steal-jewelry` | pedestal zone; its centre floor block is the diamond marker |
| `tnt-spawn` | `carry-tnt` | where the TNT waits |
| `wool-*` | `capture-wool` | any number of control points; the centre floor block is the wool marker |

`<team>` is the team id (`red` / `blue`). A stage that lacks the regions a mode
needs lets the match run without an objective (warning in the log).
`battle-loyal` needs no regions.
