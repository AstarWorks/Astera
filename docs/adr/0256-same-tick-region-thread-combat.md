---
status: Accepted
date: 2026-10-26
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0256: Same-tick combat on the caster's region thread, baked skills, spatial grid, fork/join phase, overrun policy

## Status

Accepted. Applies [[adr/0252-folia-runtime-and-executionscope-threading-model]] and
[[adr/0253-gamescope-gameinstance-strand-and-handoff]] (combat state is entity-owned) to the skill hot path. Determinism
rules are in [[adr/0257-determinism-seeds-and-ordering]].

## Context

Founder requirement: **no 1-2 tick skill delay and no tick stretching.** A skill input must resolve in the same tick it
arrives. The obvious Folia-friendly designs (hop to the instance strand, or run a 1-tick pipeline: input tick N, resolve
N+1) add latency; stretching the tick under load is forbidden. So the work per cast must be cheap enough to run inline, and
the heavy parts must be bounded or parallelised **within the tick**.

## Decision

### Hot path (steps run in the input event, on the caster's owning region thread)

`validate -> targets -> damage -> apply -> VFX start`

- All steps run in the same tick on the thread that already owns the caster.
- **Assert target ownership** (`isOwnedByCurrentRegion`). Targets in another region (cross-region long-range) hop to the
  target's entity scheduler: the only 1-tick case, rare and accepted.
- Match consequences (kill, score) are published to the instance strand asynchronously
  ([[adr/0254-event-bus-v2-routing]]); they never block the cast.

### Reduce compute first: BakedSkill (at content load)

- Hit-shape tables precomputed.
- VFX keyframe and particle tables stored **caster-relative**, rotated at cast time by one quaternion.
- Constant-folded formulas; CEL expressions compiled once; matrices flattened.
- **content-lint** enforces a worst-case op budget per skill; over budget fails content load.

### Spatial hash grid

Per scope (OpenWorld world or Instance), keyed by chunk, updated on entity move. Voxel view for line-of-sight comes from
the `ArenaBuildPlan` plus the block journal ([[adr/0255-world-slots-and-arenapaster]]). Target queries are O(nearby), not O(all).

### Budget

A typical cast costs **< 20 us** on the region thread. The measured heavy items are: VFX packet fan-out (encode once, write
the same buffer to all viewers from the pool), many projectiles, big AoE, pathfinding. Metrics (T8a) measure before any
parallelism is added.

### Phase task and fork/join

A per-region Astera phase task batches projectiles and damage-over-time. Above a configurable threshold it does
**fixed-chunk fork/join** on `IComputePool.forkJoin` with **in-order merge**, still **within the same tick** (the region
thread joins before returning). Parallelism is only added where measured.

### Overrun fallback (strict order)

1. VFX LOD by viewer distance (fewer/farther particles).
2. Defer HUD updates ([[adr/0260-packet-only-hud-and-packetevents-hard-dependency]]).
3. **Never defer gameplay**; raise an alert/metric instead.

### Purity

Compute code (`combat-compute`, `domain.compute`) is pure: no Bukkit, no clock, no global state, no I/O. Konsist enforces it
([[adr/0262-folia-safety-konsist-rules]]).

## Consequences

### Positive
- Zero added input latency; behaves like single-threaded Paper to the player.
- Cost is front-loaded to content load and measurable per skill.
- Heavy fan-out is parallel without breaking tick boundaries.

### Negative / Trade-offs
- Skill authors are bound by an op budget; very large AoEs need LOD or shape tricks.
- Cross-region long-range targets cost one extra tick.
- Content load is slower (baking).

### Mitigations
- content-lint reports the budget per skill with line pointers.
- Overrun policy keeps gameplay correct under load.

## Alternatives Considered

- **1-tick pipeline (input N, resolve N+1)**: rejected. It adds a guaranteed tick of latency to every skill to win
  scheduling simplicity; it contradicts the founder requirement and makes cooldown/hit timing visibly laggy.
- **Hop to the instance strand to resolve**: same added latency plus cross-thread reads of live entity state.
- **Stretch the tick when over budget**: forbidden; slows the whole region.
- **Async everything on a worker pool**: nondeterministic ordering and ownership violations.

## References

- [[adr/0252-folia-runtime-and-executionscope-threading-model]], [[adr/0253-gamescope-gameinstance-strand-and-handoff]],
  [[adr/0257-determinism-seeds-and-ordering]], [[adr/0258-independent-jar-graph-and-skills-bundle]]
