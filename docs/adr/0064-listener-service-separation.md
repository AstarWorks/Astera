---
status: Accepted
date: 2026-05-19
accepted_date: 2026-05-19
deciders: orchestrator (Wave 5e)
supersedes: null
superseded_by: null
---

# ADR-0064: Listener / Service Separation (per-module event-handler ↔ business-logic split)

## Status

Accepted

## Context

RTM-PARITY-TRACKER issue #22 calls for a documented convention on how
each module routes domain events from event handlers (listeners) into
business logic (use cases / services). ADR-0001 (Hexagonal layering) is
a **global** invariant — it forbids `*.domain.*` from importing the
Bukkit family and forbids `*.application.*` from importing raw JVM
throwables. It does **not** prescribe where the listener / use case
boundary falls *inside* `application/`.

Without a written rule, three patterns have crept into Astera by Wave 5a:

1. **Thin listener → fat use case** (the majority — e.g.
   `RewardOnKillListener` calls `RecordKillUseCase` + `GrantXpUseCase`,
   each owning one transactional concern). Easy to test.
2. **Fat listener → no use case** (e.g. early `MissionProgressOnKillListener`
   inlined the norma-increment loop with the event emit). Hard to unit-test
   without spinning up an event bus.
3. **Use case owning subscription** (avoided so far, but tempting when
   a use case wants "react to event X" semantics without an intermediate
   listener class — would couple business logic to the bus topology).

The Wave 5a-fix2 deep audit (ADR-0029) added five new listeners across
stats / combat / economy; without a convention in writing, each future
worker / wave will re-invent which pattern to follow, and reviewers have
no objective standard to point at.

The 2026-05-19 wave roster (worker ι's bridge: norma → level shortcut)
crystallized the question: should `AdvanceLevelOnNormaCompletedListener`
own the per-player accumulator + threshold check + level advance, or
should it delegate every step to a dedicated use case? The answer
informs Wave 6's retrofit scope (~3 fat listeners) and Wave 5f's new
marketplace listeners.

A Spring-style auto-discovery option (`ApplicationListener<T>` +
component scan) was considered but rejected at Phase 1 scale — Astera
has ~25 listeners total, all wired by hand in `platform/Astera*Plugin.kt`.
Reflection-based discovery would force DI before Koin lands
(ADR-0004 + ADR-0022 § "Phase 1 manual constructor injection") and
would obscure the registration order that the in-process event bus
documents in code.

## Decision

Adopt **listener-as-thin-adapter, use-case-owns-business-logic** as the
default for every feature plugin:

1. **Listeners** live in
   `plugin/<plugin>/.../application/eventbus/listeners/<EventName>Listener.kt`.
   They:
   - Take use cases (and any pure config / thresholds) as constructor
     parameters.
   - Expose a single `handle(event: T)` method (one event type per
     listener class). Multiple subscriptions on the same event are
     separate listener classes (e.g. `RewardOnKillListener` +
     `MissionProgressOnKillListener` both subscribe `PlayerKilled`).
   - **MAY** hold short-lived in-memory state when the state is
     listener-local idempotency tracking, per-event-batch accumulators,
     or any caching of subscription-derived data (e.g. the
     `AdvanceLevelOnNormaCompletedListener` per-player counter +
     `handled` set). This state never becomes the system-of-record —
     SSOT for persistent state lives in repositories behind use cases.
   - **MUST NOT** own business invariants that other code paths depend
     on. If a listener computes `playerStats.kills + 1`, that
     computation belongs in `RecordKillUseCase`, not in the listener
     body.

2. **Use cases** live in
   `plugin/<plugin>/.../application/usecase/<feature>/<Verb>UseCase.kt`.
   They:
   - Own the business logic and outbound port calls (repository, event
     publish, broadcaster).
   - Return `Result<T, E>` per ADR-0001 / Konsist rule
     "application layers must not import raw JVM throwable types".
   - Are callable without an event bus — they take their inputs as
     ordinary parameters. This is what enables direct unit testing.

3. **Wiring** happens in
   `plugin/<plugin>/.../platform/Astera<Plugin>Plugin.kt`:
   ```kotlin
   val listener = RewardOnKillListener(recordKill, grantXp)
   eventBus.subscribe(PlayerKilled::class) { listener.handle(it) }
   ```
   Phase 1 manual; Koin annotations later (ADR-0004). Subscription
   handles are stored on the plugin for `onDisable` cancellation.

4. **Retrofit of existing fat listeners** is **deferred to Wave 6**.
   No retrofit blocks Wave 5e Phase C close. Wave 6's tracker row
   will name the offenders: any `application/eventbus/listeners/*.kt`
   whose `handle()` body references no use case is a candidate.

## Consequences

### Positive

- **Test surface narrows**. Use cases are exercised directly with fake
  repositories (no `FakeDomainEventBus` setup needed); listeners are
  exercised with the fake bus to cover the routing path. Each test
  file has a single reason to change.
- **New "react to event X" wiring is one file**: write a listener,
  add a `subscribe` call. No use case duplication.
- **Listener-local state has a documented home**. The Wave 5e
  `AdvanceLevelOnNormaCompletedListener` accumulator pattern is
  sanctioned rather than ambiguous.
- **Reviewer rubric**. `astera-reviewer` (Phase C subagent) has a single
  question for every new `*.kt` under `application/eventbus/listeners/`:
  "does `handle()` body call at least one use case?"
- **Aligns with ADR-0025** (event-wiring split): broadcaster vs event
  bus is the *transport* split; this ADR is the *handler* split.

### Negative / Trade-offs

- **More files per feature** — a single "norma completed → advance
  level" flow is now a use case + a listener + (often) a config
  class. The ceremony is the cost of testability.
- **No CI enforcement**. Konsist's package-purity rules do not
  express "listener body must call a use case" — that would require
  AST inspection beyond Konsist's current import-direction focus.
  Enforcement is human (PR review + `astera-reviewer`).
- **Retrofit cost**. Three to five existing listeners need to be
  decomposed in Wave 6. The handoff doc for Wave 6 must include
  this row.

### Mitigations

- Document the convention here so future workers default to the
  correct pattern even without explicit guidance in the handoff doc.
- The `astera-reviewer` subagent (`.claude/agents/astera-reviewer`)
  adds a Phase C checklist item: "any new `*Listener.kt` under
  `application/eventbus/listeners/` must take use cases as ctor
  params".
- Wave 6's RTM-PARITY-TRACKER row pre-allocates effort for the
  retrofit instead of leaving it as silent debt.

## Alternatives Considered

- **Listener-owns-business-logic** (current state in ~3 stats
  listeners). Rejected: fails to isolate the unit-of-test surface —
  every listener test re-implements the use-case logic in assertions.
  Also blurs the "what changes when" boundary for ADR-0030's damage
  pipeline refactors (Wave 6 will not want to touch a listener body
  when reworking the damage path).
- **Spring-style `ApplicationListener<T>` + component scan**. Rejected
  for Phase 1: forces DI framework adoption before
  ADR-0004/ADR-0022's "Phase 1 manual" decision rolls over; obscures
  registration order; introduces reflection-based discovery that
  Konsist cannot inspect; overkill for ~25 listeners.
- **Listener = use case (single class merge)**. Rejected: couples
  business logic to the bus topology. The same use case is sometimes
  invoked manually from a command (`/astera-stats complete` →
  `CompleteMissionUseCase`); merging would mean every command path
  also goes through a synthetic event publish.
- **Per-event-bus listener registry inside Core**. Considered as a
  future refinement (Wave 7+) — would allow a `@HandlesEvent`
  annotation processor to wire subscriptions at startup. Deferred:
  no value at current listener count, and the explicit wiring in
  `platform/Astera*Plugin.kt` doubles as documentation of which
  plugins react to which events.

## References

- [[adr/0001-adopt-hexagonal-architecture]] — global layering invariant
- [[adr/0004-defer-dependency-injection-framework]] — Phase 1 manual DI
- [[adr/0022-multi-plugin-portable-architecture]] — `application/` layout
- [[adr/0025-event-wiring-broadcaster-eventbus-split]] — transport split (companion)
- [[adr/0029-rtm-empirical-catalog]] §"Deep audit (2026-05-19)"
- [[../RTM-PARITY-TRACKER]] issue #22
- Konsist `tools/architecture-test/.../ArchitectureTest.kt` —
  "application layers must not import raw JVM throwable types"
