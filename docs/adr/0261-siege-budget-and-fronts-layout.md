---
status: Accepted
date: 2026-10-26
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0261: Siege budget (strategy A) and the "fronts" layout (B) as a future game-design option

## Status

Accepted. Strategy A is adopted now; layout B is recorded as a *future option*, not committed. Depends on
[[adr/0255-world-slots-and-arenapaster]] and [[adr/0256-same-tick-region-thread-combat]].

## Context

A siege game (large destructible map, many players, many projectiles) is the worst case for a regionised server: one big
contiguous area is one or a few merged region threads, so parallelism collapses and a single thread carries the whole match.

## Decision

### Strategy A (adopted): bound the cost

- **Per-player view/simulation distance cap** for siege instances (via per-player view distance API / packets), so entities
  and chunks far from a player are not simulated or sent for them.
- Instance slot sized to the siege map within the 2048 pitch; hard caps on concurrent projectiles and destructible-block
  edits per tick (edits recorded in the block journal, journal size capped).
- The overrun policy of [[adr/0256-same-tick-region-thread-combat]] applies (VFX LOD, defer HUD, never defer gameplay).
- Metrics (T8a) per siege region drive tuning; T13 implements the budget.
- Open check: whether datapack dimensions load on Folia (would allow isolation beyond slots).

### Layout B (future game-design option): "fronts"

Split the siege map into several spatially separated *fronts* (>= 3 region sections apart, separate regions) that play
concurrently and are linked by game rules (objectives, reinforcements) rather than by physical adjacency, so each front runs
on its own region thread. This is a game-design change, not an infrastructure one, and is **not** implemented by this ADR.

## Consequences

### Positive
- Siege remains playable on region-threaded Folia within known limits; nothing blocks other instances.
- B keeps a path to real parallelism if A's measurements fall short.

### Negative / Trade-offs
- Distance caps change sight/engagement ranges and may be visible to players.
- A does not parallelise a single siege; one region may still saturate.

### Mitigations
- Caps are per-stage config; B is available if metrics show saturation.

## Alternatives Considered

- **Make B the default now**: constrains game design before any measurement.
- **Ignore the problem**: a siege could starve its own region and drag shared worker pools.
- **Dedicated backend per siege**: possible with [[adr/0263-homogeneous-backends-and-optional-proxy-scale-out]]; kept as an operational fallback.

## References

- [[adr/0255-world-slots-and-arenapaster]], [[adr/0256-same-tick-region-thread-combat]], [[adr/0263-homogeneous-backends-and-optional-proxy-scale-out]]
