---
status: Superseded
date: 2026-10-23
deciders: ryuzu
supersedes: null
superseded_by: 0255
---

# ADR-0246: Server-built sky-island arenas in a void `arenas` world

## Status

**Superseded by [[adr/0255-world-slots-and-arenapaster]]** (2026-10-26, Folia migration). Body below preserved per ADR convention; do not act on the design described here.

Originally: Accepted

## Context

No arena existed. Stages (`content/stages/*.yaml`) were "hand-built" invisible AABBs plus spawn points, and matches
teleported players onto an empty superflat grass plane (game backend `level-type=flat`, surface y=-61):

- `CreateMatchWorldUseCase` looked for a per-match `.schem` through `InMemorySchematicLoader` and soft-failed every
  time; `InMemoryStructurePaste` was a stub nothing consumed.
- The star generators (`arena-*.yaml`) said `match_stage_id: example-arena` at y=64 while the stage stood at y=-60, so
  they hung in the air. `siege-arena`, `forest-arena` and `boss-arena` had y>=64 coordinates and no lane used them.
- The lobby already proved the alternative: a procedural sky island built in code with a version marker (ADR-0180).

The founder wants real arenas: floating sky islands like the lobby, built by the server itself.

## Decision

### 1. A void arena world, built on every backend that hosts matches

`PaperArenaWorld` (Core, `adapter.paper.arena`) creates the void `arenas` world (empty-chunk generator, plains biome
provider) and builds every stage that has a `layout`. It runs on `serverRole.hostsMatches` (game and all, never on a
pure lobby backend), from `ServerLoadEvent` / the next tick with a few retries, because Paper forbids `createWorld` at
STARTUP (same constraint and pattern as the lobby). ADR-0245's world baseline applies automatically: it hooks every
`WorldLoadEvent`, so `arenas` gets no mob spawning and fixed daytime.

### 2. Data-driven layout on the stage

`StageYamlConfig` gets an optional `layout:` (domain `ArenaLayout` on `Stage`):

```yaml
world: arenas
layout:
  marker_version: 1
  seed: 20261023
  surface_y: 64            # top layer of every island; players stand at 65
  kill_y: 30               # void-fall kill plane
  islands:   [{ id, kind: base|center|outpost, team?, center: {x,z}, radius }]
  bridges:   [{ from, to, width }]
  landmarks: [{ kind: generator, x, z }]
```

`ArenaIslandGenerator` (pure, `core.domain.stage`) turns a `Stage` into an `ArenaBuildPlan` (vendor-neutral
block-data strings): the lobby's island look (grass over dirt over tapering stone/andesite/tuff with stalactites,
rim wobble from a seed), stone-brick plaza for the centre, 3-wide stone/andesite bridges with lantern posts,
team-coloured rim trim, spawn pad and two banner posts per base island, flowers away from everything a player must
see. The stage's own data is decorated, so the anchors sit exactly at the built positions:

| Stage data | Built |
|---|---|
| team `spawn_points` | coloured terracotta/concrete pad, checked on an island at `surface_y + 1` |
| `core-<team>` (y = surface+1) | blackstone pedestal, team-coloured corners; the mode puts the emerald core on top |
| `jewelry-*`, `wool-*`, `tnt-spawn` (y = surface) | 3x3 pedestal; centre block is the mode's marker cell (volatile) |
| `pillars` (Annihilation tiers, y = surface+1) | 3x3 footing with a team-coloured centre |
| `layout.landmarks` generator | blackstone-brick pedestal with an iron block |

The plan collects `problems` (spawn off an island or at the wrong height, anchor region at the wrong y, anchor off the
islands, overlapping islands). They are logged on build, and `ShippedArenaStagesBuildTest` (real `content/`) fails the
build on any, plus: every shipped stage has a layout, the stages do not overlap in the shared world, the kill plane is
below the build, every shipped generator stands on a landmark pedestal at `surface_y + 1`.

### 3. Versioned, idempotent build

A marker file (`arena-worlds.marker`, plugin data folder) stores per stage `layout.marker_version`, a digest of the
generated plan (`blocks.hashCode()`), and the bounds. If the marker matches and a sample of eight plan blocks is
really in the world, nothing is placed; otherwise the old and new bounds are cleared and the plan is pasted. A changed
layout, spawn, anchor or landmark therefore rebuilds that stage on the next boot or match start; bumping
`marker_version` forces it when only the builder code changed.

### 4. Matches use an arena that exists

`CreateMatchWorldUseCase` now takes the stage registry and the new `IArenaBuilder` port: on `MatchStarted` it calls
`ensureBuilt(stage)` (idempotent, main thread) and records `MatchWorld(matchId, stage.world, stageId)`, i.e. the stage's
real world instead of the symbolic `match-<id>` with a schematic path. Players are teleported after `MatchStarted`
(entry runs after the match is created), so the island exists when they land. `DestroyMatchWorldUseCase` asks
`IArenaBuilder.repairLater(stage)`: three seconds after the match, island blocks players destroyed are re-placed
(objective marker cells excluded, so a repair never fights a mode's marker). The in-memory default
`NoopArenaBuilder` (priority 0) keeps the simulator working; the Paper builder registers at priority 100.

Removed as dead: `IStructurePaste`, `InMemoryStructurePaste`, `FakeStructurePaste` (nothing consumed them). **Kept:**
`ISchematicLoader` / `InMemorySchematicLoader`, which survival island persistence (`InMemoryIslandPersistence`,
ADR-0107) still uses; `CreateMatchWorldUseCase` no longer touches it.

### 5. Void kill plane

`PaperArenaVoidListener` kills a survival/adventure player who moves below the world's `kill_y` through
`IPlayerHealthGateway.damage` (the same Astera death path as every other death, source `vanilla-void`, the label the kill resolver counts as a damage death), at most once per
second, skipping spectators, creative players and players already at 0 HP. Vanilla's void damage at the world floor
(y=-128) would take seconds longer.

### 6. Content

`example-arena`, `annihilation-arena` and the renamed `siege-isles` (was `siege-flat`) are rewritten onto `arenas`.
Stages share the world, each in its own area: `example-arena` around z=0, `siege-isles` around z=300,
`annihilation-arena` around z=-300 (so the e2e `x < 0` = red tests keep working on the first two). Team islands sit
~30-33 blocks from the centre island (radius 12-14, centre 9-11), outposts 26 blocks out:

- Norma (2 teams): red/blue islands, centre island with two generators, north/south outposts with one generator each.
- Annihilation (3 teams): three base islands on a radius-33 triangle, bridges to a centre island; per base 4 spawns
  behind 3 pillars (tier 1 outer .. tier 3 Nexus, `hp: 800` kept verbatim for `docker/e2e-tune-content.sh`).
- Siege (break-core, carry-tnt, capture-wool, steal-jewelry, battle-loyal): red/blue base islands (core at x=+-41, jewelry
  pedestal inside the base), centre island with the TNT spawn and wool-b, north/south outposts with wool-a / wool-c.

The four generators moved onto islands (y=65, landmarks). Deleted: `siege-arena`, `forest-arena`, `boss-arena` (no lane
used them; pre-release posture: delete, do not keep).

### 7. E2E proves it

`PlaythroughBase` gains `inArena` / `tpArena` (a console `tp x y z` runs in the overworld, so arena coordinates go
through `execute in minecraft:arenas`), `arenaBlockIs`, `arenaSolid` and `islandStanceProblem` (dimension is
`minecraft:arenas`, a non-air block under the feet). The Norma, Siege and Annihilation playthroughs assert that every
bot spawns standing on an island block in the arena world, that the objective anchors exist as blocks (generator
pedestals and spawn pads; core emeralds, jewelry/TNT/wool pedestals; all nine pillar blocks on coloured footings), that
there is void between islands, and (siege) that dropping into the void kills and the victim respawns on an island. The
Reconnect and Hud suites follow the world change.

## Consequences

### Positive
- Matches are played on real, recognisable arenas; the lobby's builder approach is reused, no schematic/FAWE dependency.
- Anchors cannot drift from the geometry: the stage's own data drives the pedestals, and a shipped-content test checks it.
- Editing a layout is a YAML change; the next boot rebuilds only what changed.

### Negative / Trade-offs
- All stages share one world, so stage areas must stay disjoint (a test enforces it) and a reload that changes a stage's
  area leaves the old build to be cleared by its marker bounds.
- The builder places ~12-18k blocks per stage on the main thread at first build (lobby-scale, a few hundred ms).
- Island destruction during a match persists until the post-match repair; there is no mid-match regeneration.

## Alternatives Considered

- **`.schem` + FAWE**: external tool, binary assets, no diff-able review, cannot derive anchors from the stage.
- **One void world per stage**: more worlds to load/save and baseline; no benefit over disjoint areas.
- **Hand-placed layout without decorating spawns/anchors**: the original failure mode (data and blocks drifting apart).
- **Rebuild the arena at every match start**: unnecessary churn; the marker + the cheap post-match repair do the job.

## References

- ADR-0180 (procedural lobby), ADR-0245 (world baseline), ADR-0107 (survival islands keep `ISchematicLoader`),
  `content/stages/README.md`, `docs/contributing/playtest.md`
