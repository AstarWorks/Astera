---
status: Accepted
date: 2026-11-01
deciders: Founder, anni-maturity manager
supersedes: null
superseded_by: null
---

# ADR-0317: Original authored objective worlds — Highmarch

## Status

Superseded by ADR-0321

## Context

The founder rejected the Annihilation arena's anonymous cuboid castle parts and crowded base objectives / 74-node Mid grid (2026-11-01). Buildings must have recognisable purposes, separate districts and usable access. The mode remains three teams of four, red north, blue east, yellow west. Ore values, star supply and phase rules belong to other tracks.

The supplied research did not establish a freely licensed Annihilation world. Shotbow's gameplay principles inform the brief, but no Shotbow block data, layout or dimensions are used. ADR-0281 already imports palette/RLE worlds, so a new world format or runtime architecture generator is unnecessary.

## Decision

Author original objective maps with deterministic Python standard-library generator scripts emitting ADR-0281 import worlds, instead of noise islands and anonymous cuboid parts. Highmarch (`tools/map-gen/highmarch.py`) builds one floating highland region: three fortified hill towns surrounding a terraced ruined quarry, linked by stone bridges across void gaps and three toll outposts. Composable builders construct walls, gatehouses, accessible towers/keeps, gabled houses, market stalls, forges, pillar monuments, bridges, terraces, ramps, a ruined chapel and working-height cranes. The generator owns the stage anchors and ore positions as well as voxels, validates against the current runtime block registry, and emits the star-economy handoff separately. It never changes ore values or generator supply rules.

Highmarch is original work: **Copyright (c) 2026 AstarWorks, MIT; see repository LICENSE**. This applies to its generator, stage and arena data; it does not change the CC BY-SA attribution of Temple Trouble. Credits record a repository-local source rather than implying an external imported map. Manager review of the licence line accompanies merge.

Each pillar remains one exposed block with its existing material / affinity: tier 1/2 at y=65 and tier 3 at y=69 on the keep’s raised y=68 hill plinth. A 9×9 paving and roughly 10-block-high open monument provides silhouette, lighting and team colour; it does not enlarge the damage hitbox. All four cardinal approaches within five blocks remain usable, including the keep sanctum. Multi-block pillar hitboxes are a follow-up owned by **modes-combat**.

## Consequences

### Positive

- Named wards have 20+ blocks between their centres, streets and clear purposes: spawn plaza, market, forge, gate court, inner ward, keep and house.
- Mid has a y=64 rim, y=60/55 terraces and y=50 floor, three walkable ramps, veins against quarry faces, two crane scaffolds and an accessible chapel outcrop.
- Seed and authored coordinates are fixed; regeneration and `--check` expose output drift. No runtime dependency on Python or new Python package.
- RLE world and stage YAML use the existing production decoder. The same bot PathFinder verifies spawn pacing, all pillar sides, ore stances, rooftops and wall walks in `HighmarchScenario`.

### Negative / Trade-offs

- A roughly 277×280-block world costs more placement operations than the old arena. Offline generation/digest numbers do not measure live chunk placement; the manager must inspect server slice timings.
- Import layout lists must stay empty. Generic island graph routing and centre labels cannot infer imported topology. The integration scenario therefore supplies the actual quarry-rim / climb goals from generator metadata, without adding fake islands.
- Preview previously ignored terrain below surface_y−8 and demanded a surface pedestal even for sunken waves. Its height scan now reaches the world bottom, its section cuts through the sanctum, and its wave check uses the wave's own y; normal generators still require iron landmarks. The runtime import loader is unchanged.
- Star-economy must apply the handoff coordinates before integration. Until then, the repository preview correctly rejects old generator positions. The review preview uses an explicitly labelled `/tmp` copy with only generator coordinates patched.
- `ShippedArenaStagesBuildTest` currently demands y=65 / surface landmarks for waves too. Its owner must update that assumption for the y=51 quarry-floor wave.

### Mitigations

- Keep four visual review rounds and production PathFinder traversal reports outside git, and record timings / paths in the track report.
- A manager-run integration window checks existing shop/ore/pillar/star scenarios and real placement. No unit tests, e2e runs, live containers or pushes are performed by this worker.
- Four-team support is a follow-up: design a fourth town with comparable travel times, then change team rosters/queues/colour assumptions in the owning tracks. The current three-town plan is not claimed to be a four-team layout.

## Alternatives Considered

- **More procedural castle parts**: does not resolve anonymous architecture or provide authored interior routes and separated wards.
- **An external Annihilation map**: no verified free licence in the supplied research; original geometry avoids copying.
- **A second import format or runtime generator**: ADR-0281 already handles this world; extra infrastructure would duplicate it.
- **Enlarge the pillar damage target here**: requires combat/runtime changes outside map ownership; retain one-block semantics and separate visual monument.

## References

- Related ADRs: [[adr/0281]], [[adr/0307]], ADR-0318 star-economy handoff.
- `tools/map-gen/README.md`, `content/stages/NOTICE.md`, `docs/vision/areas/builds-maps.md`.
- Manager brief: Understanding — annihilation-arena → original map Highmarch (2026-11-01).
