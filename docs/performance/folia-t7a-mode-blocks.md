# T7a: mode world access uses its region/entity owner

Implements the world-side ownership part of ADR-0252/0253/0254. Mode/match state
remains on Global; no Instance migration is included.

## Changes

- `PaperWorldGateway` retains the base's Region dispatch for all block placement/removal
  (pillars, wool/TNT/objective markers, delivery/regrow, cores and pedestals). Each
  actual write now asserts ownership. Zero-delay calls already on the owner run inline.
  Synchronous `blockAt` explicitly requires ownership and floors negative coordinates.
- `PaperBlockStateGateway` dispatches custom block placement to Region while retaining
  caller-owned ledger/event updates. Synchronous damage/material reads assert ownership.
- `PaperStarDropWorld` reserves a logical PDC handle before the region spawn. Pending
  drops retain their location instead of being respawned by polling. Relabel/removal
  use captured entity references and Entity scopes; despawn before/during spawning
  removes the visual without leaking it. Pickup events use the logical PDC handle.
- `PaperBarrierGateway` retains Global desired state, reconciles spawns on the correct
  world's anchor region (previously it used the first loaded world), and updates/removes
  through Entity. Delayed spawns observe the latest desired state/status.
- Particle viewers are enumerated as identities from `server.onlinePlayers`; location,
  world and LOD reads plus delivery run on each viewer's Entity. Packet fanout takes
  explicit recipients, with no `world.players` fallback. Sounds use the source Region.
- Norma fireworks use the player owner. Damage indicators capture the target on Entity,
  spawn on the jittered location's Region (including cross-chunk offsets), and show
  themselves on the viewer owner.
- `PaperMatchPlayerPositions` supplies immutable, per-tick Entity-captured snapshots to
  Global star logic. Star polling returns coordinates/retirement to Global before
  changing drop records. Pickup validation and crediting also run on Global.
  Capture-zone occupancy returns to Global before computing/persisting zone progress.
- `kernel:paper`'s `assertWorldOwnership` logs a JUL **SEVERE** with caller stack and
  rejects wrong-owner access. It is enabled in all builds, including debug/E2E.

The direct-access sweep covered `plugin/match` and `core/feature-gameplay` for
`getBlockAt`, block setters, spawn/drop calls, nearby queries and world player lists.
Sign validation already dispatches to Region. Hologram world access already has an
ownership guard; its wider NPC/display lifecycle belongs to T12. No direct
`getNearbyEntities` or `spawnEntity` call remains in these two source trees.

## Compile verification

```sh
./gradlew compileKotlin compileTestKotlin :plugin:core:shadowJar :plugin:match:shadowJar
```

**BUILD SUCCESSFUL** (97 actionable tasks; final compile 12 s). This compiles the
integration/game-sim/E2E/Konsist sources and builds both touched plugin bundles.
`git diff --check` passes. No tests, E2E, check tasks or live containers were run.
Local compile output: `build-t7a-compile.log`.

## Manager verification

- Game-sim `AnnihilationPillarScenarioTest`: `annihilation-pillar-siege` (placement,
  skill destruction, end cleanup).
- Game-sim `SiegeModesScenarioTest`: `siege-break-core`, `siege-carry-tnt`,
  `siege-capture-wool`, `siege-steal-jewelry`, `siege-battle-loyal`, `siege-lane-*`.
  Extended assertions cover both wool chunks at start/end and the TNT pickup marker
  remaining available after delivery.
- Game-sim `CarryMiniTntScenarioTest`: `siege-carry-mini-tnt` (delivery/regrow).
- Game-sim `StarsNormaScenarioTest`: `stars-and-norma` (generator pops, burst,
  pickup, Norma and match cleanup).
- Paper and Folia E2E: `AnnihilationPlaythroughE2eTest`,
  `CaptureWoolPlaythroughE2eTest`, `CarryTntPlaythroughE2eTest`,
  `SiegePlaythroughE2eTest`, `StarsE2eTest`, plus the lane's SEVERE/ownership log gate.
  All three previously failing modes must reach ACTIVE and complete their objective.
  Manager owns batched execution and `build/e2e-report.md`.

## Limits and integration notes

- The requested `docs/contributing/folia-e2e-lane.md` is absent from this base.
  Read the supplied W2 common notes, ADR-0253/0254, T4b report and existing boot-spike
  guidance; the manager must integrate the lane's current guide/log gate.
- Region side effects are eventual when called from Global. Snapshot positions can
  lag by a sampling tick plus scheduler handoff; match state is still single-writer.
  Game-sim verifies mode semantics; real region ownership needs the Folia lane.
- Particle packet encoding now occurs per viewer owner; batching/performance should
  be measured with the other Folia performance work.
- Cross-region capture-zone occupancy providers must supply snapshots/partitioned
  counts; owning the zone minimum does not permit arbitrary remote player reads.
- No content, language/model files, private files, harness files or ADRs changed.

Board 更新案: T7a → implementation complete / manager verification pending;
evidence: this report and the implementation commit. No Board writes performed.
