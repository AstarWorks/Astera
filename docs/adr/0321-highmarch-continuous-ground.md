---
status: Accepted
date: 2026-11-01
deciders: Founder, anni-maturity manager
supersedes: ADR-0317
superseded_by: null
---

# ADR-0321: Highmarch continuous ground

## Status

Accepted; amended by ADR-0322

## Context

Highmarch's original void-separated towns and quarry forced players through single-file lanes without ground for flanking. Those fights played like Skywars rather than siege combat. The founder requested a continuous landscape, distinct town identities and a quarry whose geology and ore veins read clearly. This supersedes ADR-0317's topology while retaining its deterministic original-world authoring decision, fixed objective/economy anchors and ADR-0281 import format.

## Decision

Highmarch is **one continuous continent**, with void only at the outer rim. Wooded valleys carry each town-to-Mid lane across a river gorge at mid-lane. Each lane has one seven-block-wide stone bridge as its hard chokepoint; the surrounding land permits approaches and flanking. Covered ridge crossings run between the towns through the three outposts, including independent stone arches across tributaries. Both gorge banks have climb-out ladders with a maximum design interval of 24.34 blocks (under 26 allowing grid rounding; tributaries 24), satisfying the 24–30-block recovery spacing budget. Ladders run from y57 through y64, with clear exits above them. Water is at y56 over a solid bed, far above kill_y16.

Bridge centres, measured by projecting along each town-to-Mid lane from the origin, are approximately **61–65 blocks** away:

| Lane | Centre x,z | Lane distance | Radial distance | Dry land from r42 rim to gorge |
|---|---|---:|---:|---:|
| Red | 0.00,-65.00 | 65.00 | 65.00 | 18.01 |
| Blue | 48.02,42.13 | 60.66 | 63.88 | 18.58 |
| Yellow | -49.24,44.21 | 63.07 | 66.17 | 20.71 |

Red is an industrial forge town (brick, deepslate, smelters), blue a light-stone harbour town with a lighthouse, and yellow a timber farmland town with a windmill. The quarry has irregular coarse-dirt, gravel, tuff, andesite and cobbled-deepslate patches across shelves and exposed faces. Ore veins have readable colour cues: iron white, gold yellow, diamond cyan, emerald green and lapis blue. Three cranes, haul rails, cart tubs, a scaffold and a ruined chapel provide landmarks and cover. The rim is y64, terraces y60/55 and floor y50; the centre star wave is at (0,51,0), radius17.

Original maps continue to be authored as deterministic Python standard-library generator scripts emitting ADR-0281 palette/RLE import worlds. `tools/map-gen/highmarch.py` emits `content/arenas/annihilation-highmarch.yaml` and the existing stage/ore coordinate outputs without changing ore values or star supply rules. Highmarch remains original AstarWorks work under the repository MIT licence; third-party map attribution is unaffected. Star-economy coordinates are integrated. Preview the **real shipped content** with `./gradlew -q :tools:arena-preview:run`; no patched temporary content tree is needed.

## Consequences

### Positive

- Towns, quarry and outposts form a traversable landscape, with river bridges retaining deliberate local chokepoints and ridge routes offering alternatives.
- The r4b production-decoder → ArenaIslandGenerator → PathFinder traversal proof reports **294 routes, 0 failures**, including six ridge segments, all 74 ore stances and the wave disk.
- Twelve spawn-to-Mid-rim routes measure **81–88 cells / 14.46–15.71 s**; all 24 enemy-tier-1 routes measure **172–217 cells / 30.71–38.75 s**. Times are comparative estimates at cells/5.6, not measured player runs.
- The current world has **1,974,928 blocks**, **509,217 bytes**, dimensions **293×73×298 (x,y,z)**: x=-146..146, y=27..99, z=-158..139. It contains **three cranes**, replacing ADR-0317's older size and two-crane description.
- Final real-content preview reports **0 problems**, plan **546.0 ms**, digest **40.9 ms**. Generation took **11.91 s** and reproducibility validation **10.69 s**. These are single r4b offline measurements.

### Negative / Trade-offs

- Nearly two million blocks still require live placement measurement. Offline plan/digest timings exclude Paper chunk placement, water behaviour and combat feel.
- PathFinder treats water and ladders as non-air solids. Route success alone does not establish swimming or ladder recovery; the protocol-bot gorge e2e must be run by the manager in the integration window.
- Imported topology is authored, not inferred from the generic island graph. Traversal metadata and e2e must track the shipped world.

### Mitigations

- Preserve deterministic regeneration checks and production-content previews, with top/isometric/height/section visual review. Record measurements without presenting them as live performance guarantees.
- Add real-server coverage for dropping into gorge water, surviving above kill_y and recovering via the nearest ladder onto the bank within a bounded time.
- **Four teams** remain follow-up work: author an equally paced fourth town and update roster/queue/colour assumptions in the owner tracks. The current map supports three teams of four.
- **Multi-block pillar hitboxes** remain a **modes-combat** follow-up. The nine objectives remain single blocks inside large monuments; visual bulk does not enlarge the damage target.

## Alternatives Considered

- **Void-only lanes**: rejected because they impose single-file chokepoints without flanking and play like Skywars.
- **Remove every chokepoint**: loses deliberate bridge fights; retain one stone crossing per town-to-Mid lane and independent ridge crossings.
- **A new runtime generator or import format**: unnecessary; deterministic authored scripts and ADR-0281 already represent the continent.
- **Move objectives or rebalance resources during the terrain pass**: unnecessary scope expansion; fixed spawns, pillars, NPCs, ores, star/cache anchors and wave disk are preserved.

## References

- Supersedes [[adr/0317]]; related [[adr/0281]], [[adr/0307]], [[adr/0318]].
- `tools/map-gen/highmarch.py`, `tools/map-gen/README.md`, `content/arenas/annihilation-highmarch.yaml`, `content/stages/annihilation-arena.yaml`.
- `tools/e2e/src/test/kotlin/com/astarworks/astera/e2e/AnnihilationPlaythroughE2eTest.kt`.
- `docs/vision/areas/builds-maps.md`, `content/stages/NOTICE.md`.
- Measurement evidence: r4b report (commit c39bed1b3b0f4499f2e859a6e374b0d97a1a462f), `/tmp/claude-1000/anni-map/r4b-report.md`, `r4b-merged/{coordinates.json,traversal.md}` and `r4b-final/` preview/timing artifacts (external review evidence).
