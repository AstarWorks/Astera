---
status: Accepted
date: 2026-10-27
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0281: Pre-built CC BY-SA worlds as stages

## Status

Accepted.

## Context

Every shipped arena is generated from YAML numbers by `ArenaIslandGenerator` (ADR-0246, ADR-0273). The founder wants a route for real, hand-built maps. The PGM community publishes maps under CC BY-SA 4.0 (OvercastCommunity/CommunityMaps accepts only that licence; PublicMaps ships a per-map `LICENSE.txt`). Earlier policy ("third-party maps are not used", licence unclear) does not apply to maps whose licence explicitly allows copying and adapting.

The existing slot flow must keep working: `ArenaPaster` pastes an `ArenaBuildPlan` chunk-wise with a rollback journal inside a 2048-block slot (ADR-0255), every block state is checked against the registry-backed guard, and the marker/rebuild logic compares a plan digest.

## Decision

A stage layout may carry `import: {map: <id>, x: 0, z: 0}` instead of `islands`. The map id names a file in `content/arenas/<id>.yaml`:

- `credits` (title, authors, source URL, licence, modification note), `origin`, `size`, a block-state `palette` and one run-length encoded string per y layer (`token:count`, token 0 = air). No kotlinx.serialization in the domain: the DTO is `ArenaImportYamlConfig` in `application/config`, converted to the domain `ArenaImport`.
- `ArenaIslandGenerator.generate(stage, parts, imports)` returns the imported blocks shifted by the placement as an ordinary `ArenaBuildPlan`. Star generator landmarks get an `iron_block` at `surface_y`; non-spectator spawns must stand at `surface_y + 1`. Nothing else is generated, so paste, rollback, slot shift (`PaperArenaWorld.placed`) and digest-based rebuild are unchanged.
- Objectives, spawns, regions and generators stay on the stage YAML, written by hand (PGM `map.xml` is a reference, not an input).
- `tools/map-import/` holds the offline converter (legacy Anvil to modern block states, shifted so the spawn floor is `surface_y`, stray fragments dropped, block states validated against `tools/lobby-gen` `block-registry.txt`). The downloaded world is parsed as data only. Hand-added bridges and plinths are recorded as `fill` boxes in the per-map config and listed in `credits.modified`.
- `:tools:arena-preview` loads `content/arenas/` and routes bots from a team spawn to every objective with plain A* when the stage has no island graph.

Licence handling (the repository is public):

1. Derived world data (`content/arenas/*.yaml` and the pasted blocks) stays **CC BY-SA 4.0**. The rest of the repository keeps its own licence. ShareAlike applies to the adapted map, not to code that loads it.
2. Attribution is in the file header and `credits` block of each arena file and in `content/stages/NOTICE.md` (authors, source URL at a pinned commit, licence, "modified").
3. Only maps whose upstream licence was checked (licence file plus `map.xml` authors) are imported. Maps without a clear licence are never imported.

## Consequences

### Positive
- Real hand-built terrain and structures without a procedural generator change; same slot, guard and rebuild behaviour.
- Credits live next to the data and cannot drift from it.

### Negative / Trade-offs
- Imported maps are large YAML (about 90-180 KB each) and fixed in shape; relief, theme and `marker_version` knobs do not apply.
- Maps are authored for a different game: objectives and bridges are adapted by hand and need a founder look in game.
- Bots use plain A* (5000-node cap) because there is no island graph; large maps may need waypoints later.

### Mitigations
- `ShippedArenaStagesBuildTest` builds every shipped stage including imports; arena-preview reports spawn, generator and route problems.

## Alternatives Considered

- **Schematic loader (`.schem`)**: needs a new format reader at runtime and ships binary data; a text palette plus layers format reviews and diffs better.
- **Bundle the original world folders**: untrusted legacy format, no registry validation, no digest.
- **Rebuild each map from scratch as structure parts**: loses the authors' work and takes far longer.

## References

- ADR-0246, ADR-0255, ADR-0273
- `content/stages/NOTICE.md`
- https://creativecommons.org/licenses/by-sa/4.0/
