---
status: Accepted
date: 2026-05-17
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0025: Event Wiring & Broadcaster / EventBus Split

## Status

Accepted

## Context

ADR-0022 (Multi-plugin Portable Architecture) introduced two distinct event
abstractions on the Core registry:

- `IDomainEventBus.publish(event)` + `subscribe(type, handler)` — for in-process
  same-JVM notification. Cross-plugin listeners (Stats subscribing to Combat's
  `PlayerKilled`, Economy subscribing to Stats's `XpGranted`, etc.) flow through
  this bus.
- `IBroadcaster.publish(event)` — for external fan-out (Redis pub/sub, Discord
  webhook, persistence audit log). Phase 1 logs only; Wave 5 will register the
  real adapters.

ADR-0022 named both ports but did not formalise *which one each use case should
call*. As Wave 2 (R1) added five new cross-plugin listeners (MatchEnded → Stats
record/result, MatchEnded → Economy reward payout, MissionCompleted → Economy
deposit, LeveledUp → external broadcast), the divergence risk became real:
two reasonable engineers could disagree on whether a particular event belongs
on the in-process bus or the external broadcaster, and silent inconsistency
would corrupt future Phase 4 fan-out (Discord notifications duplicated, or
missed entirely).

Wave 2 R1.d audited every call site in the existing codebase (19 publishes + 4
subscribes; `docs/internal/broadcaster-eventbus-audit.md`) and found zero
violations of the silly-knuth §5 rule. ADR-0025 is the permanent record of
that rule plus the twin-stage pattern that enables both in-process consumers
and external fan-out without duplicating event sources.

## Decision

### 1. `IDomainEventBus` is the single source of truth

All `DomainEvent` instances originate from `eventBus.publish(...)` on the
Core-shared `IDomainEventBus`. A use case **must not** publish a Domain Event
directly to the broadcaster; if external fan-out is needed, a separate
listener subscribes to the in-process bus and re-publishes to the broadcaster
(see §3 below).

Use cases (`Application/UseCase/...`) publish in-process; they never touch
`IBroadcaster`. The audit confirmed this is already universal: every existing
`eventBus.publish(...)` call in `combat / stats / match / economy / weapons`
follows this rule.

### 2. `IBroadcaster` is the seam to external observers

`IBroadcaster.publish(event)` is reserved for crossing the JVM/server
boundary: Redis pub/sub for a future second instance, Discord webhook for
player notifications, persistence audit log for compliance, log shippers, etc.
Phase 1 ships a no-op / logging implementation. Wave 5 will register Redis +
Discord adapters via the priority-stacked `ServiceRegistry`, so no listener
needs to change when the production fan-out goes live.

`IBroadcaster` is **not** a substitute for the in-process bus. A subscriber
inside the same JVM that listens only to the broadcaster would miss any event
the broadcaster's adapter does not actually transport (the no-op default).

### 3. Twin-stage pattern for events that need both

When an event has both in-process consumers *and* external observers — e.g.
`LeveledUp` is consumed by chat-title updaters in-process and announced on
Discord externally — the canonical pattern is:

1. The producing use case (`GrantXpUseCase`) calls `eventBus.publish(LeveledUp)`
   once. Every in-process subscriber sees it.
2. A dedicated bridge listener (`BroadcastOnLeveledUpListener`) is registered
   as one more in-process subscriber. Its sole job is to call
   `broadcaster.publish(event)`.

This keeps the event's *origin* singular (the use case still publishes once,
to one port) while explicitly making fan-out a configurable, observable
listener. Adding or removing external observers is a registration change, not
a use case rewrite. The contract is symmetric: any event can opt into external
fan-out by registering a bridge listener; the producer never needs to know.

### 4. Where listeners live

| Layer | Allowed actions on events |
|---|---|
| `application/usecase/` | `eventBus.publish(...)` only |
| `application/eventbus/listeners/` | `subscribe` to events, `eventBus.publish` for cascades, `broadcaster.publish` only if this listener is the bridge |
| `application/service/` | neither (services are queried, not event-driven) |
| `domain/` | neither (pure rules) |

Bridge listeners (`Broadcast*Listener`) are explicitly named so reviewers
recognise them at a glance.

### 5. New listeners introduced in Wave 2 (R1.b)

| Listener | Subscribes (eventBus) | Re-publishes |
|---|---|---|
| `RecordMatchPlayedOnMatchEndedListener` | `MatchEnded` | use case → `eventBus.publish(StatsUpdated)` |
| `RecordMatchResultOnMatchEndedListener` | `MatchEnded` | use case → `eventBus.publish(StatsUpdated)` |
| `RewardOnMatchEndedListener` | `MatchEnded` | use case → `eventBus.publish(CurrencyChanged)` |
| `DepositOnMissionCompletedListener` | `MissionCompleted` | use case → `eventBus.publish(CurrencyChanged)` |
| `BroadcastOnLeveledUpListener` | `LeveledUp` | `broadcaster.publish(LeveledUp)` (twin-stage bridge) |

Four are pure in-process consumers; one is the only bridge to the broadcaster.

## Consequences

**Positive**

- The producing-use-case → in-process-bus → optional-bridge pattern is now
  documented, so future cross-plugin events have a single template to follow.
- Phase 4 fan-out work (Discord / Redis adapters) becomes a registration
  change inside `IBroadcaster`'s impl set; no use case or in-process listener
  needs to be edited.
- The audit script that produced
  `docs/internal/broadcaster-eventbus-audit.md` can be run on demand (or
  promoted to a Konsist rule in a later wave) to catch drift early.

**Negative / trade-offs**

- The twin-stage pattern means an event that *should* reach external observers
  silently does not unless someone remembers to register the bridge listener.
  Mitigation: bridge listeners are named `Broadcast*Listener`; reviewers learn
  to look for one whenever a `DomainEvent` is announced to be relevant to
  Discord / external systems. A future Konsist rule could check that every
  event annotated `@ExternallyVisible` has a corresponding bridge listener.
- A bridge listener is *just another in-process subscriber*: if the in-process
  bus drops events under load, the broadcaster path drops them too. This is
  acceptable because the bus is currently `InMemoryDomainEventBus` with
  unbounded delivery; if Wave 5 introduces backpressure the bridge listener
  inherits whatever guarantees the bus offers (and the persistence-audit
  observer can still be wired through a different path with stronger
  durability if required).

## ADR numbering note

ADR-0020 / 0021 / 0023 / 0024 are reserved by the radius-roadmap handoff for
Wave 3 (Persistence), Wave 4 (Declarative GUI), and Wave _ops (Secrets,
Provider routes). Wave 2 deliberately skipped to 0025 so those reservations
remain intact; ADR numbering is intended to reflect commit order, not a
contiguous index.
