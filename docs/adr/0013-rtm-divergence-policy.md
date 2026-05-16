---
status: Accepted
date: 2026-05-17
deciders: ryuzu
---

# ADR-0013: RTM divergence policy — reference, don't follow upstream

## Status

Accepted

## Context

`Y-RyuZU/RyuZUTechnicalMagic` (RTM) is the closed-source predecessor of Astera.
It contains a lot of carefully designed mechanics — Easing curves, Configured*
data class hierarchy, Provider pattern for external plugins, EventBus, the
arena gameplay loop — but it also contains design choices that, with hindsight
and current tooling, we can do better:

- triple-stack DI (Dagger 2 + Koin + Spring Boot) is operationally fragile
- Bukkit imports leak into `core/impl` modules that should be platform-free
- `paper-plugin.yml` requires every external plugin, so any single soft-dep
  going stale breaks the whole server
- damage events are split into 8 near-identical variants
- `org.reflections` is used heavily, including at runtime, in ways the
  domain shouldn't tolerate
- MongoDB schema is fragmented (one collection per concept)
- null + exceptions are used as expected-failure signals
- there is essentially no test coverage

Astera has already diverged from RTM in foundational ways
([[adr/0001-clean-architecture-with-mc-as-edge]],
[[adr/0002-vendor-neutral-mc-adapter-layer]],
[[adr/0004-koin-single-di-not-dagger-spring]],
[[adr/0005-postgres-not-mongo]],
[[adr/0009-soft-dep-only-paper-plugin-yml]]).
This ADR records the **policy** that governs future divergence so contributors
and AI agents know when to copy RTM and when to redesign.

## Decision

**Astera references RTM but does not follow it.** Concretely:

1. **Mechanics and gameplay intent** carry over verbatim — Easing curves,
   weapon archetypes, star generators, anomalies, the 3-pillar Siege concept,
   loadout slot taxonomy (MAIN/SUB/MOVE/SPECIAL/ARMOR/ULTIMATE).
   *Copy the spirit and the math.*

2. **Type shapes / DTOs** carry over with light renames — `WeaponSpec`,
   `DamageProfile`, `LoadoutSlot`, `MatchPhase`, etc. Astera's versions are
   immutable data classes / value classes; RTM's may have had mutability.
   *Copy the shape, lock it down.*

3. **Architecture patterns are redesigned** — see [[architecture/rtm-divergence]]
   for the full keep / redesign / add matrix. Key redesign points:
   - DI: triple → Koin single
   - Storage: Mongo → Postgres + Exposed + JSONB
   - Damage events: 8 variants → 1 type with sealed `DamageSource`
   - Skill params: reflection-based → typed generics `Skill<P : SkillParams>`
   - Element matrix: hardcoded → `content/balance/element-matrix.yaml`
   - Configuration loaders: per-type Module → generic `ContentLoader<T>`
   - Outcome signaling: null + exceptions → `Result<T, E>` + sealed Error
   - Event bus: reflection scanner → explicit registration + sealed dispatcher
   - GUI: direct InventoryFramework → `IGuiAdapter` port

4. **Test coverage is non-negotiable** — RTM had ~0 tests. Astera targets
   `domain` ≥ 90% line coverage, `application` ≥ 80%. See
   [[architecture/testing-strategy]].

5. **Reflection in `domain` / `application` is forbidden** — Konsist
   architecture-test enforces this. Reflection is allowed only in
   `adapter-*` layers, and even there it must be a last-resort.

## Consequences

### Positive
- Future Astera contributors (human or AI) know exactly when to copy and
  when to redesign — no ambiguity, no cargo-culting of RTM patterns that
  don't fit Astera's principles.
- Phase 2 mid / Phase 3 implementation can move fast: "look at RTM for the
  math, look at this ADR + rtm-divergence for the shape."

### Negative / Trade-offs
- More upfront design work than a literal port would have required.
- Less RTM code is directly reusable — most of it is patterns + ideas, not
  copy-paste fodder.

### Mitigations
- The Easing port already proved the model: RTM gave the enum names,
  Astera supplied the math; the resulting `Easing.kt` is a single 130-line
  pure-Kotlin file that needs zero Bukkit context to test.

## Alternatives Considered

- **Full port of RTM to Kotlin 2.3 / Paper 26**: faster initial throughput
  but inherits RTM's weaknesses. Rejected as accumulating technical debt
  before the first match.
- **Greenfield with zero RTM reference**: throws away RTM's hard-won
  mechanics insights (the Easing curves, the loadout slot taxonomy, the
  Provider pattern). Rejected as wasteful.

The chosen middle path — *spirit + shape from RTM, architecture from Clean
Architecture / SOLID / our own ADRs* — is the working strategy.

## References

- [[adr/0001-clean-architecture-with-mc-as-edge]]
- [[adr/0002-vendor-neutral-mc-adapter-layer]]
- [[adr/0004-koin-single-di-not-dagger-spring]]
- [[adr/0005-postgres-not-mongo]]
- [[adr/0009-soft-dep-only-paper-plugin-yml]]
- [[architecture/rtm-divergence]]
- [[architecture/principles]] §  "RTM 由来とは違う Astera 原則"
- 計画書 §13 (survey + RTM 設計差異)
- RTM: https://github.com/Y-RyuZU/RyuZUTechnicalMagic
