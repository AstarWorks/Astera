---
status: Accepted
date: 2026-10-26
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0253: GameScope, GameInstance, per-instance strand, directory and handoff

## Status

Accepted. Builds on [[adr/0252-folia-runtime-and-executionscope-threading-model]]. Re-bases the "match" concept of
[[adr/0022-multi-plugin-portable-architecture]] (superseded by [[adr/0258-independent-jar-graph-and-skills-bundle]]).

## Context

Astera must run many concurrent matches and independent game kinds on one server, and must also let the "skills only"
bundle (core + items + combat, [[adr/0258-independent-jar-graph-and-skills-bundle]]) run on a plain survival server with
no match, lobby or Postgres. Combat therefore cannot depend on a match object, yet a match needs somewhere to keep
score, phase, timers, norma, stars and roster.

## Decision

### GameScope

```kotlin
sealed interface GameScope {
    data class OpenWorld(val world: WorldId) : GameScope     // vanilla survival: no instance
    data class Instance(val id: InstanceId) : GameScope      // a slot-backed match/lobby/siege/PvE run
}
fun interface IGameScopeResolver { fun resolve(entity: EntityRef): GameScope }
```

Core's default resolver returns `OpenWorld`. The instances jar registers a higher-priority resolver through
`ServiceRegistry` (priority rule from the module layout). No Core code names `GameInstance`.

### Ownership split

- **Combat state is entity-owned in both scopes**: cooldowns, statuses, attributes, combo live with the entity and run on
  its region thread ([[adr/0256-same-tick-region-thread-combat]]). It never waits for an instance.
- **An instance owns only slow match state**: score, phase, timers, norma, stars, roster. That state is mutated only on
  the instance's **serial strand** (a per-instance mailbox executed on the worker pool, `ExecutionScope.Instance`). The
  region threads publish match consequences (kill, objective hit) to the strand asynchronously.

### GameInstance lifecycle

`Allocating -> Building -> Ready -> Running -> Draining -> Resetting -> Free` (Free re-enters Allocating or the warm pool,
see [[adr/0255-world-slots-and-arenapaster]]). Transitions run on the strand; slot allocation and paste are requested
from the global strand.

### InstanceDirectory

`player -> instance` map, **written only on the global strand** (single writer), read lock-free from any thread through an
immutable snapshot. Region threads resolve scope from the snapshot.

### Handoff (moving a player between instances or to the lobby)

`InTransit -> PlayerCarry -> teleportAsync -> admit`. `PlayerCarry` is the player's snapshot (inventory, effects, mode)
held by the instance system while the teleport is in flight. On teleport failure the player is **re-admitted** to the
source; a **retired sweep** on the global strand reclaims carries whose player disconnected. Directory entry flips only
after the destination admits.

## Consequences

### Positive
- Skills work with no match; matches work without touching combat hot paths.
- Match logic is single-writer per instance, so no locking inside a match.
- A crash/stall in one instance cannot block another (separate strands).

### Negative / Trade-offs
- Match state is eventually consistent with region-thread events (a kill is observed on the strand after the tick).
- Global strand is a single writer; allocation bursts serialise there.

### Mitigations
- The global strand does only O(1) directory and slot bookkeeping; paste work is budgeted on regions ([[adr/0255-world-slots-and-arenapaster]]).
- Event routing and ordering guarantees: [[adr/0254-event-bus-v2-routing]].

## Alternatives Considered

- **Instance owns combat state too**: forces every cast to hop to the strand; adds latency and breaks the skills-only
  bundle. Rejected.
- **Match state under locks on region threads**: contention across regions and deadlock risk with merge/split. Rejected.
- **One strand per server**: re-creates a global tick. Rejected.

## References

- [[adr/0252-folia-runtime-and-executionscope-threading-model]], [[adr/0254-event-bus-v2-routing]],
  [[adr/0255-world-slots-and-arenapaster]], [[adr/0259-async-persistence-and-write-behind]]
