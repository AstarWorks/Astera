---
status: Accepted
date: 2026-10-22
deciders: founder + Claude (orchestrator)
supersedes: null
superseded_by: null
---

# ADR-0174: Host-neutral wiring seam + headless game simulator

## Status

Accepted

## Context

Until 2026-10-22 Astera had never been booted and played end to end. The
first docker playtest surfaced only integration-seam bugs — shaded-library
LinkageError between plugin jars, a relative `dataFolder` NPE, a kaml
discriminator mismatch that made every skill fail to decode, double damage
paths, no `PlayerKilled` on rerouted damage. Unit tests + Konsist cannot see
these: each part was correct, nobody verified the assembled game.

Causes in the structure:

- Each `platform/Astera*Plugin.kt` is one monolithic `onEnable` mixing pure
  use-case construction with Paper adapter construction (Core: ~2,700 lines,
  188 registrations). It can only run inside a `JavaPlugin`.
- Scenario harnesses (`stats/.../AsteraTestHarness`, `match/.../MatchScenarioHarness`)
  hand-copy the plugin wiring and drift from it — the root cause of the
  repeated "listener not wired" ADRs (0118, 0126, 0128).
- Physics-bearing ports (projectiles, nearby-target query) only exist as
  Paper adapters, so skill behaviour cannot be observed without a client.

Goal: an environment where the AI (and CI) can verify game systems and skill
behaviour without a Minecraft client, and a trace a web viewer can replay.

## Decision

1. **Wiring seam.** Each plugin's wiring becomes host-neutral functions in its
   `platform/wiring/` package taking a `WiringContext`
   (`core.platform.wiring.WiringContext`: `ServiceRegistry`, `contentDir`,
   logger, registration/subscription lists). Host-specific port
   implementations (Paper adapters / simulator fakes) are **injected** into
   the wiring as a per-plugin *platform ports* bundle (values or factories
   over `ServiceRegistry`), not constructed inside it. Bukkit listener
   registration stays in a thin Paper-only step. `Astera*Plugin.onEnable`
   becomes: build Paper ports → call wiring → register Bukkit listeners.
2. **Headless simulator.** New non-shipped module `:tools:game-sim` boots
   core → items → combat → match → stats → economy through the *same*
   wiring functions with simulator ports: virtual-time scheduler, recording
   world gateway (tick-stamped particles / sounds / teleports), a tick-stepped
   projectile service, a position-backed nearby-target query. Scenarios are a
   Kotlin DSL run as JUnit tests; each run can emit a JSON trace (per-tick
   effects, projectile positions, damage, status, deaths).
3. **Skill viewer** (separate repo, AsteraStudio) replays traces; rendering
   of particles is approximate per particle type — Minecraft client particle
   code is not ported.
4. **Real-Paper E2E** (later): a dev-only testkit plugin spawning fake
   players driven over RCON, for final verification on real Paper.

## Consequences

### Positive
- Simulator and production share one object graph → wiring bugs are caught
  by scenario tests in CI, and hand-copied harnesses can be deleted.
- Splits the 3.2k-line `AsteraCorePlugin.kt` along a meaningful boundary.
- Skill behaviour becomes observable (trace + viewer) without a client.

### Negative / Trade-offs
- Large mechanical refactor of every `Astera*Plugin.kt`; order-sensitive
  `ServiceRegistry` lookups must be preserved.
- Simulator physics (projectile stepping, hit tests) is an approximation of
  Bukkit's; final fidelity still needs real Paper (point 4).

### Mitigations
- Behaviour-preserving moves first, verified by a docker boot (all plugins
  enabled, 0 boot errors) before simulator work lands.
- Physics shared where possible: hit dispatch logic is extracted from
  `ProjectileHitListener` into application code used by both hosts.

## Alternatives Considered

- **Reimplement a Minecraft-like server on the web**: diverges from real
  behaviour and catches none of the seam bugs above. Rejected.
- **MockBukkit**: lags Paper versions and still requires `JavaPlugin`-bound
  wiring. Rejected for now.
- **Keep per-feature hand-written harnesses**: the status quo that drifted.

## References
- ADR-0022 (multi-plugin layout), ADR-0118 / 0126 / 0128 (wire-up fixes),
  ADR-0149 / 0151 (HP container + Paper adapters).
- Commit `ad6a149` (playtest-ready stage ①).
