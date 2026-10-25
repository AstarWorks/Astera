---
status: Accepted
date: 2026-10-26
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0257: Determinism - scope seeds, stable ordering, fixed chunks, inline sim

## Status

Accepted. Constrains [[adr/0256-same-tick-region-thread-combat]] (fork/join) so results do not depend on thread timing.
Keeps the headless simulator idea of [[adr/0174-host-neutral-wiring-and-headless-game-simulator]] (rebased onto `SimRuntime`).

## Context

Parallel fork/join and region threads make evaluation order a scheduling accident. game-sim scenarios, bot determinism
(bots use deterministic A*) and bug reproduction require that the same inputs give the same outputs regardless of pool size.

## Decision

1. **Seed per cast**: `seed = hash(scopeSeed, casterUuid, castCounter)`. `scopeSeed` is the instance seed (or world seed for
   OpenWorld); `castCounter` is a per-caster monotonically increasing counter stored with the entity-owned combat state.
   All randomness in a cast (spread, crit, VFX jitter) derives from this seed; no `Random()` / `Math.random()` in compute.
2. **Stable-id sort**: any iteration over a set of entities/projectiles is sorted by a stable id (UUID, then spawn
   counter) before use.
3. **Fixed chunks for fork/join**: work is split into chunks by index ranges that depend only on the input size and a fixed
   chunk size, never on the number of worker threads. Results are merged **in chunk order**.
4. **game-sim runs the pool inline on the same code path**: `IComputePool` in the sim executes chunks sequentially in
   order; the production pool must give the identical result.
5. Compute code is pure (no clock, no thread-locals); enforced by Konsist ([[adr/0262-folia-safety-konsist-rules]]).

## Consequences

### Positive
- Scenario tests are reproducible and can assert exact damage numbers.
- Fork/join can change worker count without changing outcomes.

### Negative / Trade-offs
- Chunk size is a tuning constant that cannot adapt to thread count.
- Sorting adds small overhead per cast.

### Mitigations
- Sorting uses presorted spatial-grid buckets ([[adr/0256-same-tick-region-thread-combat]]).

## Alternatives Considered

- **Per-thread RNG**: results vary with scheduling.
- **Adaptive chunking by thread count**: different merge order across machines.
- **Accept nondeterminism, test statistically**: weakens game-sim; rejected.

## References

- [[adr/0256-same-tick-region-thread-combat]], [[adr/0174-host-neutral-wiring-and-headless-game-simulator]],
  [[adr/0262-folia-safety-konsist-rules]]
