# MAP-01 / MAP-06 / MAP-09 implementation report

2026-10-26, branch `wp/map-siege-scale`. ADR: [0264](adr/0264-authored-siege-arenas.md) (Proposed).

Two siege castles now stand 320 blocks apart, each with four 25-block towers, walls, an open gate, objective keep
and accessible upper positions. Roads lead from forward spawns through the contested ruins or north/south flanks.
Other production stages have distinct desert, frost and volcanic palettes; Norma bases are 280 blocks apart,
Annihilation bases about 286–287, and PvE expands more conservatively to preserve wave travel times.

## Files and behavior

- `content/structures/`: six original full-cube YAML parts plus [format documentation](../content/structures/README.md).
- Core `ArenaStructure*`, `ArenaLayout`, `ArenaIslandGenerator`: strict part loading, palette substitution,
  quarter-turn placement, reserved building footprints and sprint roads.
- Core `PaperArenaWorld`, `IArenaBuilder`, `CorePaperBindings`: serial off-thread planning/digests;
  asynchronous chunk acquisition and bounded placement/clearing/repair/ticket release. Shared 4 ms tick budget,
  maximum 1024 operations per job slice; log actual elapsed time, ticks, operations and maximum slice duration.
- `content/stages/*.yaml`, `content/generators/arena-*.yaml`: expanded production geometry and route-based stars;
  four independent compact `debug-*` stages in `debug-arenas`.
- `docker/e2e-tune-content.sh`, `docker/Dockerfile.e2e-runner`: E2E-only compact fixture substitution under existing production IDs/world,
  preserving queue and protocol-test coordinates. The runner applies the same overlay so bot objectives and
  navigation agree with backend geometry. Production queue definitions and game-mode content are unchanged.
- Match `StageYamlConfig`, `StartMatchUseCase`, `MatchWiring` and English/Japanese message keys:
  stage schema and match admission gated on completed construction.
- `tools/arena-preview`, `tools/lobby-gen/LobbyRenderer`, `settings.gradle.kts`, `Makefile`: same-generator PNGs
  and real-content/routing validation. Run `make arena-preview`.
- Bot `ArenaRouteGraph`, `StageObjectives`, `Navigator`, `PathFinder`, `BotBrain` and module dependency:
  real stage loader, coarse island/bridge routes and local A* capped at 5000 nodes; vertical heuristic for climbs.
- Existing `ShippedArenaStagesBuildTest`: load authored parts and compare overlap only within the same world.
  Compiled, not executed.

## Measurements

Single-pass wall times on this host, excluding file loading/rendering; the first stage includes JVM warm-up.
These measure pure plan generation and digesting, not Paper block placement.

| Stage | Theme | Blocks | Generation ms | Digest ms | Bounds |
|---|---|---:|---:|---:|---|
| annihilation-arena | FROST | 323696 | 174.7 | 24.2 | x=-199..203 z=-949..-524 y=36..89 |
| debug-annihilation-arena | TEMPERATE | 81240 | 28.6 | 1.5 | x=-78..79 z=-383..-212 y=40..68 |
| debug-example-arena | TEMPERATE | 53080 | 16.3 | 0.9 | x=-83..86 z=-72..73 y=41..68 |
| debug-pve-proving-grounds | TEMPERATE | 58507 | 10.5 | 0.5 | x=231..371 z=-71..71 y=39..68 |
| debug-siege-isles | TEMPERATE | 62992 | 11.2 | 0.5 | x=-89..88 z=231..369 y=41..68 |
| example-arena | DESERT | 238358 | 44.9 | 1.8 | x=-170..175 z=-156..158 y=34..89 |
| pve-proving-grounds | VOLCANIC | 189772 | 24.4 | 1.2 | x=685..919 z=-119..118 y=34..72 |
| siege-isles | TEMPERATE | 519972 | 114.0 | 7.2 | x=-214..212 z=575..826 y=29..89 |

## Routing

213 real-content route checks passed: base pairs, both bridge directions, forward spawns to enemy bases,
all siege objective approaches, 13 tower climbs and two keep galleries. All routed local searches are below 5000.
Direct Annihilation base searches still cap at 5000; the three base pairs complete through the coarse graph in
341/363/380 total local nodes (175–214 worst segment). Vertical A* accounts for climb/drop costs.

| Stage | Routes | Direct capped | Routed complete | Worst local nodes |
|---|---:|---:|---:|---:|
| annihilation-arena | 30 | 4 | 30 | 1090 |
| debug-annihilation-arena | 27 | 0 | 27 | 276 |
| debug-example-arena | 19 | 0 | 19 | 101 |
| debug-pve-proving-grounds | 24 | 0 | 24 | 57 |
| debug-siege-isles | 27 | 0 | 27 | 92 |
| example-arena | 25 | 0 | 25 | 1161 |
| pve-proving-grounds | 24 | 0 | 24 | 126 |
| siege-isles | 37 | 0 | 37 | 1240 |

The offline measurement treats every non-air voxel as solid, matching packet Terrain conservatively, and assumes
all chunks are known. Live Navigator retains waypoints while local A* makes partial progress through observed chunks.

## Artifacts

All are in this worktree under `build/arena-previews/` (generated, not committed):

- `siege-isles-top.png`, `siege-isles-iso.png`
- `example-arena-top.png`, `example-arena-iso.png`
- `annihilation-arena-top.png`, `annihilation-arena-iso.png`
- `pve-proving-grounds-top.png`, `pve-proving-grounds-iso.png`
- Corresponding `debug-*-top.png` and `debug-*-iso.png` for all four compact stages.
- `report.md`: full bounds, generation times and every direct/hierarchical route result.
- `e2e-fixtures/`: PNGs and report from the actual E2E preparation output; 97 routes pass on four compact stages.

## Validation and remaining risks

- Compile-only Gradle: `:tools:arena-preview:compileKotlin`, `:tools:e2e:compileTestKotlin`,
  `:plugin:match:compileTestKotlin`, `:plugin:core:compileTestKotlin`.
- Ran the compiled preview entry point directly with Java on shipped content and on copied, E2E-patched content.
  Zero content/routing problems. E2E overlay shell syntax and English/Japanese key parity pass.
- No unit tests, Gradle test/check tasks, E2E run, live containers, push, model edits or private edits.
- Actual Paper placement duration, chunk scheduling and worst tick time remain for the manager's batched E2E/server
  review. Runtime logs expose these; a budget cannot preempt an individual Bukkit call or a JVM pause. World creation
  remains a required main-thread Paper operation. Early assignments are rejected safely until the stage is ready;
  players must queue again. This also applies while a post-match repair is pending.
- Offline routing is complete with known chunks. Live progressive chunk discovery, combat behavior, spawn travel feel,
  client FPS and match balance need player-visible verification. Large production maps retain more blocks/chunks.
- The initial part format deliberately supports full cubes only. Directional states, entities and finer authoring
  shapes require a later format decision. Part edits take effect on restart (or a marker/version change plus reload).
