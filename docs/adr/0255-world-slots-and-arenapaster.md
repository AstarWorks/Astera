---
status: Accepted
date: 2026-10-26
deciders: ryuzu
supersedes: 0180, 0245, 0246
superseded_by: null
---

# ADR-0255: World slots on a 2048 grid, ArenaPaster, warm pools, per-player time/weather

## Status

Accepted. Supersedes [[adr/0180-procedural-sky-island-lobby]] (Astera-created lobby world),
[[adr/0246-server-built-sky-island-arenas]] (Astera-created `arenas` world) and
[[adr/0245-server-owned-world-baseline-no-natural-mobs-fixed-daytime]] (per-world baseline). Builds on
[[adr/0252-folia-runtime-and-executionscope-threading-model]].

## Context

Folia throws on runtime `createWorld` / `unloadWorld`, so "one world per match" is impossible. Regions are 16x16-chunk
sections (`grid-exponent` 4) with merge radius 1, so two builds are independent (not merged into one region thread) only if
roughly >= 3 sections (48 chunks, 768 blocks) apart. Worlds also carried the baseline (time, weather, mobs) of ADR-0245.

## Decision

1. **No runtime world creation.** `world` is a fixed **void world** declared at startup. Instances occupy **slots** on a
   grid with a **2048-block pitch** (128 chunks = 8 sections; comfortably above the >= 3-section independence bound, leaving
   room for arena + margin + siege overflow).
2. **SlotAllocator** runs on the global strand ([[adr/0253-gamescope-gameinstance-strand-and-handoff]]); it hands out slot
   `(sx, sz)` -> origin `(sx*2048, y, sz*2048)` and frees them on reset.
3. **ArenaPaster** pastes an `ArenaBuildPlan` chunk-wise: each chunk is written by `RegionScheduler` on its owning region,
   under a per-tick budget (blocks/ms per region, configurable) so a paste never starves a running match. It records a
   **block journal** of every changed block.
4. **Warm pools per stage**: N pre-pasted slots per stage stay `Ready` so match start is instant. Pool size is per-stage
   config.
5. **Reset = roll back the block journal** (restore the pre-paste/pre-match state chunk-wise), not repaste and not unload.
   Slots return `Free` then to the pool.
6. **Lobby is sharded**: several lobby slots, players balanced across them; no single shared lobby world.
7. **Per-player time/weather** (`setPlayerTime`, `setPlayerWeather` / packets) replaces the per-world baseline of
   ADR-0245. Natural-mob suppression and fixed daytime are applied per slot region and per player view.
8. Siege gets a per-player view/simulation distance cap ([[adr/0261-siege-budget-and-fronts-layout]]); open item: verify
   whether datapack dimensions load on Folia (a possible future alternative to slots).

## Consequences

### Positive
- Works on Folia and Paper identically; no world lifecycle API needed.
- Instant starts (warm pool) and cheap resets (journal rollback).
- Region independence by geometry gives parallelism for free.

### Negative / Trade-offs
- All instances share one world's chunk/level data and border; a 2048 pitch caps grid size by the world border (30M blocks: ample).
- Journal memory grows with edits per match.
- Void-world slots cannot use vanilla world-gen per instance.

### Mitigations
- Journal is chunk-compact and bounded by arena size; large edits (siege) have a cap ([[adr/0261-siege-budget-and-fronts-layout]]).
- Slot pitch is a single constant; can be raised if a stage outgrows it.

## Alternatives Considered

- **Multiverse-style runtime worlds**: throws on Folia.
- **One world per backend process**: wasteful, defeats multi-match-per-server.
- **Repaste on reset**: slower than journal rollback and spikes paste budget.
- **Tighter pitch (e.g. 1024)**: risks region merge between neighbours under combat spread and projectile range.

## References

- [[adr/0252-folia-runtime-and-executionscope-threading-model]], [[adr/0253-gamescope-gameinstance-strand-and-handoff]],
  [[adr/0261-siege-budget-and-fronts-layout]], [[adr/0263-homogeneous-backends-and-optional-proxy-scale-out]]
