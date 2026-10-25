---
status: Accepted
date: 2026-10-26
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0254: Event bus v2 - routed events, declared delivery, local and global buses

## Status

Accepted. Evolves the `IDomainEventBus` / sealed `DomainEvent` model of the module layout
([[adr/0022-multi-plugin-portable-architecture]], superseded by [[adr/0258-independent-jar-graph-and-skills-bundle]]) for
the threading model of [[adr/0252-folia-runtime-and-executionscope-threading-model]].

## Context

`IDomainEventBus` assumes one thread: publish runs subscribers synchronously on the caller. With region threads and
per-instance strands ([[adr/0253-gamescope-gameinstance-strand-and-handoff]]) a subscriber must run on the thread that owns
the state it mutates, and a single sealed `DomainEvent` in Core forces every jar to edit Core to add an event, which
conflicts with independent jars.

## Decision

1. **Every event carries a route**:

   ```kotlin
   sealed interface EventRoute { data class Instance(val id: InstanceId); data class Player(val id: PlayerId); data object Global }
   interface IDomainEvent { val route: EventRoute }
   ```
2. **Subscriptions declare a delivery**: `SameStrand` (run on the publisher's strand/thread, synchronously, in
   subscription order), `Global` (hop to the global strand), or `Io` (hop to the IO pool). A subscriber that does not
   declare one does not compile.
3. **Two buses**:
   - a **per-instance local bus** with synchronous, ordered semantics inside the instance strand (the behaviour match code
     relies on today);
   - a **global bus** for cross-instance and player-routed events. Ordering is **FIFO per (source, target)** pair; no
     global total order is promised.
4. Events published from a region thread to an instance are enqueued to the strand (never executed inline on the region).
5. **Unsealing (T1b)**: `DomainEvent` is no longer one sealed hierarchy in Core. Each jar exposes its events in a `-api`
   package (`items.api.event`, `combat.api.event`, ...). Core keeps only the interface, route and bus. Exhaustive `when`
   over all events is dropped; handlers subscribe by type.

## Consequences

### Positive
- Subscribers run on the owning thread by construction; no hidden cross-thread mutation.
- Jars add events without touching Core, enabling independent update ([[adr/0258-independent-jar-graph-and-skills-bundle]]).

### Negative / Trade-offs
- Cross-instance event latency is at least one hop; handlers must tolerate stale reads.
- Loss of exhaustive `when` over events.

### Mitigations
- Per-(source,target) FIFO preserves causal order where it matters (kill then score).
- Konsist ([[adr/0262-folia-safety-konsist-rules]]) checks `-api` packages hold no implementation imports.

## Alternatives Considered

- **Keep one synchronous bus and lock inside subscribers**: contention and deadlocks across regions.
- **Total global order via a single queue**: becomes the global tick again.
- **Keep sealed `DomainEvent` in Core**: blocks independent jars.

## References

- [[adr/0252-folia-runtime-and-executionscope-threading-model]], [[adr/0253-gamescope-gameinstance-strand-and-handoff]],
  [[adr/0174-host-neutral-wiring-and-headless-game-simulator]]
