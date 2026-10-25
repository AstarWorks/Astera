---
status: Proposed
date: 2026-10-26
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0252: Authored structure parts on siege-scale arenas

## Status

Proposed

## Context

MAP-01/MAP-06/MAP-09, builds-maps: even the enlarged ADR-0246 islands feel too small for siege combat. The founder requests castles, contested settlements, flanks and elevation, with 250–400 blocks between bases. Pure random cover cannot express buildings. Minecraft must remain an edge; no paid authoring service or new runtime dependency is needed.

## Decision

Keep procedural floating foundations and add `content/structures/*.yaml`: an id and ordered, inclusive cuboids (`min/max: [x,y,z]`, `block: minecraft:<full-cube-id>` or `@palette-slot`). Air carves openings. Only full cubes are supported, avoiding ambiguous rotated block states and collision approximations. Stage `layout.structures` names each part, world x/z, surface-relative y, clockwise rotation (0/90/180/270) and optional palette overrides. `layout.theme` supplies ground, soil, rock, wall, deck, log, leaves and light materials. Parts are loaded through the existing strict content loader, passed into the pure generator, and included in the final plan digest. Unknown parts/slots fail visibly. Authored parts precede random features; their footprints are reserved.

Siege bases are 320 blocks apart with front spawns, accessible battlements, open gates and keeps around objectives. Other production stages grow within practical build budgets. Preserve the previous layouts as separate debug stages; only the E2E image substitutes them into the production stage IDs. Production queue configuration continues to reference large maps. The offline arena preview invokes the real loaders and generator, renders top-down and isometric PNGs, and measures generation and bot A* routes.

Generate and fingerprint plans off the server thread. Clear and paste work shares a time and operation budget across stages; even urgent match starts cannot drain a large job synchronously. Match admission requires a completed arena. Log maximum slice time and operation counts to make the budget observable. Chunk acquisition is asynchronous before block placement; the per-operation clock cannot preempt a Bukkit call or JVM pause.

## Consequences

### Positive

- Human-authored reusable architecture, rotation and stage palettes without schematic libraries or AI services.
- Preview and runtime consume identical geometry; reports include block counts and path-search node counts.
- Debug geometry keeps automated gameplay fixtures and coordinates small and deterministic.

### Negative / Trade-offs

- Cuboids are verbose for detailed sculptures; no stairs, doors, entities or block entities in this first format.
- Larger stages take more boot time and memory. Early match admission can fail while an arena is building.
- Offline timings measure pure planning and synthetic budget overhead, not real Paper block/chunk costs.

### Mitigations

- Bound local coordinates, cuboid volumes and box count. Keep islands thin and maximum radius bounded.
- Gate match admission on readiness, prebuild stages at boot, log actual Paper slice times for manager E2E review.
- Keep E2E fixtures separate from production layout editing; validate both with the preview command.

## Alternatives Considered

- **Vanilla structure NBT:** capable, but requires binary tooling and block-state rotation/collision support. The requested reusable full-cube architecture fits the existing neutral build-plan representation.
- **More procedural castle code:** fewer content lines initially, but keeps the original authoring limitation and makes visual iteration require Kotlin edits.
- **Paid map services / downloaded schematics:** unnecessary cost and licensing uncertainty.

## References

- [ADR-0246](0246-server-built-sky-island-arenas.md)
- [Structure authoring](../../content/structures/README.md)
- [Stage authoring](../../content/stages/README.md)
