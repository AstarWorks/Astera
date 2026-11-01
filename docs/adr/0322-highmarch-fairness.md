---
status: Accepted
date: 2026-11-01
deciders: Founder, anni-maturity manager
supersedes: null
superseded_by: null
---

# ADR-0322: Highmarch fairness amendment

## Status

Accepted

## Context

ADR-0321 established Highmarch's continuous continent while preserving the earlier spawn, pillar and ore anchors. astera-41 diagnosed unequal access to Mid resources: iron was roughly 77 blocks from yellow versus 150+ from blue; clay and gold favoured blue, timber and silt favoured red. Each spawn was approximately 25 blocks from all three of its own pillars, allowing attackers to camp the spawn and threaten every tier from the same small area. These diagnostic estimates motivated a reproducible route comparison; they are not the PathFinder cell counts below.

This amendment replaces ADR-0321's fixed-spawn, fixed-T1 and fixed-Mid-ore decisions and its 74-node count. Its continuous terrain, gorge bridges, distinct town themes, deterministic authoring and ADR-0281 import format remain in force. ADR-0322 was reserved by orchestrator astera-5c on 2026-11-01; implementation evidence is commit `24845747f19ad759261cf280227c7ecb23a660e1`.

## Decision

Author one Mid ore sector and copy it at 120° and 240° about the origin, with equal counts of each ore type per sector and rounding to voxel centres (at most 0.71 block). Require each team's reference spawn to the nearest reachable Mid node of each ore type to stay within ±10% across teams, enforced as max/min <= 1.10; the measured maximum deviation is 6.8% (clay, max/min 1.068). Use the same spawn/T1/T2/T3 path layout for each team: reference-spawn paths are 20/45/32 cells, with attacker-entry-to-T1 paths of 38–49 cells. Move T1 13 blocks inward, relocate all four spawns to the market plaza, and preserve contestable ground with shoulder cover between the attacker entries and T1 in the former gate court. Give every ore-quarter gate the same service opening. `HighmarchScenario` measures and asserts the fairness table and defender access with production content loaders, `ArenaIslandGenerator` and bot `PathFinder`. Red's industrial forge, blue's light-stone harbour and yellow's timber farmland retain their distinct themes.

The compact comparison uses **R/B/Y = red/blue/yellow** and the first shipped spawn per team. Distances count path cells including the start and replans; a diagonal counts as one cell. For each ore type, the minimum is searched across all Mid nodes and all standable cardinal mining stances, with runtime ores and pillars placed. Pillar endpoints use reachable chest-height stances. These are route measurements, not straight-line blocks or stopwatch timings.

| Route (cells) | Before R/B/Y | After R/B/Y | Max/min before → after |
|---|---:|---:|---:|
| spawn → Mid emerald | 106/122/128 | 107/105/105 | 1.208 → 1.019 |
| spawn → Mid diamond | 140/114/116 | 109/105/109 | 1.228 → 1.038 |
| spawn → Mid gold | 144/91/145 | 99/99/105 | 1.593 → 1.061 |
| spawn → Mid heartwood | 141/130/101 | 98/94/93 | 1.396 → 1.054 |
| spawn → Mid silt | 97/150/142 | 95/91/94 | 1.546 → 1.044 |
| spawn → Mid iron | 133/141/89 | 87/87/88 | 1.584 → 1.011 |
| spawn → Mid timber | 86/140/163 | 90/92/88 | 1.895 → 1.045 |
| spawn → Mid clay | 148/85/131 | 90/94/88 | 1.741 → 1.068 |
| spawn → own T1 | 24/24/24 | 20/20/20 | 1.000 → 1.000 |
| spawn → own T2 | 21/21/21 | 45/45/45 | 1.000 → 1.000 |
| spawn → own T3 | 25/25/25 | 32/32/32 | 1.000 → 1.000 |
| spawn → contest | 18/18/18 | 20/20/20 | 1.000 → 1.000 |
| Mid entry → T1 | 31/31/31 | 42/42/42 | 1.000 → 1.000 |
| Mid entry → contest | 37/37/37 | 37/37/37 | 1.000 → 1.000 |
| left entry → T1 | 132/48/126 | 49/49/49 | 2.750 → 1.000 |
| left entry → contest | 138/54/132 | 54/54/54 | 2.556 → 1.000 |
| right entry → T1 | 41/41/41 | 38/38/38 | 1.000 → 1.000 |
| right entry → contest | 38/38/38 | 38/38/38 | 1.000 → 1.000 |

Entries are the first interior cells at the Mid/left/right gate throats: local `(0,38)`, `(-38,0)`, `(38,0)`, all at y65. The contest point is local `(5,15)`, y65. Every defender spawn reaches the contest point before an attacker from any entry; the reference defender also reaches T1 before each entry attacker. `HighmarchScenario` enforces max/min <= 1.10 for every comparison row, checks T2/T3 paths exceed 30 cells and verifies the rounded rotational orbits and decoded ore/handoff parity. Exact table values record this revision; the assertions enforce these bounds and priorities rather than freezing every cell count.

## Consequences

### Positive

- Mid resource access no longer depends on a team's privileged ore sector; ore row max/min falls from 1.208–1.895 to 1.011–1.068.
- Identical defensive route layouts separate T2/T3 from the immediate spawn area and give defenders an approach they can contest before attackers reach T1.
- The implementation report records 670 routes with 0 failures, 75 exposed mining pockets and arena-preview with 0 problems. It preserves the radius-17 wave disk and existing pacing bounds. These are offline implementation results, not new verification performed for this documentation change.

### Negative / Trade-offs

- Nonzero exact 120° orbits cannot be represented on an integer square lattice; voxel rounding remains necessary.
- Original per-type Mid counts were not multiples of three. Minimal rounding changes Mid 53→54 and total nodes **74→75**: emerald 8→9, diamond 10→9, heartwood 2→3, iron 11→12 and timber 4→3; gold, silt and clay stay at 6 each. The original 21 forge nodes remain fixed. Economic fields are unchanged, but available resource geometry changes, particularly heartwood supply.
- `content/stages/annihilation-arena.yaml` changes spawn coordinates, T1 anchors and their regions; `content/ore-nodes/annihilation-arena.yaml` changes Mid positions. The generated `content/arenas/annihilation-highmarch.yaml` changes with the geometry. Coordinate owners are notified through the manager's fairness handoff (modes-combat for stage placement, builds-maps for terrain, norma/tool-skills for resource effects). These anchors supersede the earlier fixed-coordinate guarantee.
- Cell-count fairness does not establish live sightlines, spawn-camping resistance or combat feel. Batched live e2e and in-game review remain pending.

### Mitigations

- Keep coordinate consumers tied to shipped YAML. T1 stage-local `(x,y,z)` anchors change red `(0,65,-86)` → `(0,65,-99)`, blue `(72,65,54)` → `(85,65,54)`, yellow `(-72,65,54)` → `(-85,65,54)`. First spawn positions change red `(-3.5,65,-109.5)` → `(20.5,65,-106.5)`, blue `(96.5,65,50.5)` → `(93.5,65,74.5)`, yellow `(-95.5,65,58.5)` → `(-92.5,65,34.5)`; the full diff records all twelve spawns and region bounds.
- `AnnihilationPlaythroughE2eTest` loads pillar anchors from shipped stage YAML and therefore follows the new coordinates without a fixed-coordinate edit. Its terrain/gorge probes remain unchanged. The manager runs this coverage in the batched integration window; this ADR does not claim a live pass.
- Preserve `HighmarchScenario` fairness, mining-stance, traversal and defender-priority checks when regenerating the map. T2/T3 anchors, forge ores, shops/NPCs, star/cache coordinates and economic parameters remain fixed in this revision; game-mode and generator coordinate files require no updates.

## Alternatives Considered

- **Keep ADR-0321's anchors and adjust only terrain**: leaves unequal Mid access and the compact spawn-to-all-pillars arrangement that caused the diagnosis.
- **Copy entire towns, including their visual themes**: unnecessary; shared route geometry and rotated ore sectors establish measurable parity while distinct town identities aid navigation.
- **Keep every original per-type count**: incompatible with equal three-sector counts. Minimal rounding avoids introducing team-specific leftover veins.

## References

- Amends [[adr/0321]]; related [[adr/0281]], [[adr/0317]], [[adr/0318]].
- Implementation: `24845747f19ad759261cf280227c7ecb23a660e1` (Highmarch resource sectors and defender approaches).
- `tools/map-gen/HighmarchScenario.kt`, `tools/map-gen/highmarch.py`, `tools/map-gen/run-scenario.sh`, `tools/map-gen/README.md`.
- `content/stages/annihilation-arena.yaml`, `content/ore-nodes/annihilation-arena.yaml`, `content/arenas/annihilation-highmarch.yaml`.
- `tools/e2e/src/test/kotlin/com/astarworks/astera/e2e/AnnihilationPlaythroughE2eTest.kt`, `docs/vision/areas/builds-maps.md`.
- External review evidence: `/tmp/claude-1000/anni-map/fairness/implementation-report.md`, `before.md`, `after.md`, `coordinate-changes.diff` (all in the same fairness directory).
