---
status: Accepted
date: 2026-05-27
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0109: Effect Orchestration Foundation (ParticleScheduler + SoundScheduler + EffectOrchestrator + per-viewer sound + sound registry + countdown timer + rate limiter)

## Status

Accepted (Wave 5effects delivery, 2026-05-27)

## Context

Wave 5f (ADR-0035) landed atomic particle / sound primitives —
`IParticleRegistry`, `ParticleSpec`, `IWorldGateway.spawnParticle`,
`SoundId`, `SoundSpec`, `IWorldGateway.playSound`. Wave 5skills-2
(ADR-0077) added the `Mechanic.SpawnParticle` / `Mechanic.PlaySound`
mechanic-system surface but parked the actual dispatch behind a
`"Phase 6 work"` log because no scheduling shape existed:

| Gap | Why it blocks Wave 6 |
|---|---|
| **Tick-based composite particle emission** (Loop / Burst / Pulse / RingExpansion) | Wave 5pve-boss phase tells, Wave 5combat-depth AOE tick rings, Wave 5h Hologram dynamic, Wave 5j HUD cooldown rings all assume a `ScheduledParticleSpec`-shaped Core port exists. |
| **Tick-based composite sound emission** (Delayed / Loop / Sequence) | Charged-skill sustain loops, multi-stage cast cues, betting-match private countdowns assume the same shape on the sound side. |
| **Composite chain (particle + sound + status + damage chained / parallel)** | Boss phase-transition cinematics, combat finishers, tutorial step "look here!" cues all need atomic-cancel semantics across mixed primitives. |
| **Per-viewer sound** | Team-only cues, ally radar pings, "your skill is off cooldown" confirmation. |
| **Sound registry** | Sounds had `SoundId` value class only — no catalog. `MechanicDispatcher.PlaySound` could not resolve a `SoundId` to a default volume / pitch / resource-key. |
| **HUD ↔ audio synchronised countdown** | Match timers, cooldown rings, betting opt-in windows: HUD ring + sound blip + chat warning must share one source of truth. |
| **Per-player rate limiter** | Wave 5q anti-cheat (ADR-0083) needs O(1) per-player action-rate gating; Wave 5e skill quickcast and chat-spam guard reuse the same primitive. |

Per the user-confirmed Round 5 audit (`.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §Round 5),
shipping these as Paper adapters in Wave 6 without a Core port would
mean Wave 6 invents the scheduling shape, defeating ADR-0022's
"Paper layer is a pure executor" invariant. Phase 1 PoC closing
requires the ports + InMemory defaults + Fakes here so Wave 6 stays
purely "Paper API wrap".

The pre-release posture (CLAUDE.md §Pre-release posture) lets us
introduce new sealed hierarchies and registry-backed ports without
worrying about `@Deprecated` aliases or dual-decoders.

## Decision

Land seven cross-cutting outbound ports in `plugin/core` with
InMemory defaults registered at priority 0 by `AsteraCorePlugin`:

1. **`IParticleScheduler`** — `schedule(ScheduledParticleSpec): EffectHandle`.
   Sealed spec: `EmitOnce / Loop / Burst / Pulse / RingExpansion`.
   Default `InMemoryParticleScheduler` composes `IScheduler` +
   `IWorldGateway`.
2. **`ISoundScheduler`** — `schedule(ScheduledSoundSpec): EffectHandle`.
   Sealed spec: `EmitOnce / Delayed / Loop / Sequence`. Default
   `InMemorySoundScheduler` mirrors particle-side composition.
3. **`IEffectOrchestrator`** — `play(EffectChain): EffectHandle`.
   `EffectChain` sealed: `EmitOnce / Sequence / Parallel / Loop`.
   `EffectStep` sealed leaves: `ParticleStep / SoundStep /
   StatusEffectStep / DamageStep / DelayStep`.
4. **`IPlayerSoundGateway`** — `playForViewer(playerId, sound, position?, world?)`.
   Default `LoggingPlayerSoundGateway` no-ops + debug-logs; Paper
   adapter (Wave 6) registers at priority 100.
5. **`ICountdownTimer`** — `start(id, durationTicks, onTick, onComplete) -> TimerHandle`
   with `pause / resume / cancel` + `pauseAll / resumeAll`. Default
   `InMemoryCountdownTimer` runs each timer on a 1-tick repeating
   scheduler task; pause/resume cancel + re-arm preserving `remaining`.
6. **`IRateLimiter`** — `tryConsume(playerId, action, maxPerWindow, windowTicks)`.
   Default `InMemoryRateLimiter` is an `ArrayDeque<Long>` per
   `(playerId, action)`; lazy-prune on consume keeps it O(1) amortised.
7. **`ISoundRegistry`** — `Contents<SoundId, SoundDefinition>` backed
   by `SoundContents` which loads `content/sounds/<category>/<id>.yaml`
   (one YAML per sound, recursive subdir walk for category fan-out).
   Hot-reloadable via `/astera reload sounds` through the existing
   `IReloadableContentsRegistry`.

Cross-plugin bridges for status / damage application live in core too:

8. **`IStatusEffectApplier`** — `apply(target, effectId, durationTicks)`.
   Core ships `NoopStatusEffectApplier` at priority 0; combat
   registers an `ApplyStatusUseCase`-backed adapter at priority 100.
9. **`IDamageApplier`** — `apply(target, profile)`. Same pattern with
   combat's `ApplyDamageUseCase`.

`MechanicDispatcher.SpawnParticle` and `Mechanic.PlaySound` branches
stop warn-logging and route through the new `IParticleScheduler` /
`ISoundRegistry` + `IWorldGateway.playSound`. The dispatcher
constructor gains three nullable params (`particleScheduler`,
`particleRegistry`, `soundRegistry`) all with `null` defaults so
existing test wiring (e.g. `MechanicDispatcherTest`) keeps compiling.

Two YAML reference orchestrations (`content/effects/orchestrations/{boss-phase-transition,combat-finisher-chain}.yaml`)
pin the wire schema for the follow-up YAML→EffectChain decoder (lands
with Wave 5pve-boss / Wave 5combat-depth adoption work).

One Konsist rule lands in `tools/architecture-test/.../ArchitectureTest.kt`:
`effect domain stays pure (ADR-0109)` — fails the build on any
Bukkit / kotlinx.serialization / Exposed / koin import under
`core.domain.effect.*` (including the new `scheduler/`, `chain/`,
`sound/`, `timer/`, `ratelimit/` subpackages).

## Consequences

### Positive

- **Wave 6 stays a pure Paper wrap.** Particle / sound / orchestration
  scheduling shape is now Core SSOT; Paper adapter implements
  `IWorldGateway.spawnParticle` / `playSound` only and registers
  `PaperPlayerSoundGateway` for the per-viewer side.
- **Wave 5pve-boss + Wave 5combat-depth + Wave 5h** can author
  `EffectChain` instances against Core types without dragging combat
  state into their phase tells (status/damage steps route through the
  applier SPI).
- **HUD ↔ audio drift gone.** `ICountdownTimer` gives every subscriber
  the same `remainingTicks` value, removing per-subscriber math
  divergence.
- **Anti-cheat + skill quickcast + chat-spam share one rate-limit
  primitive.** Wave 5q reaches into `IRateLimiter` instead of
  reinventing token buckets per category.
- **MechanicDispatcher debt cleared.** `Mechanic.SpawnParticle` /
  `PlaySound` are no longer "Phase 6 work" stubs — they fire through
  real ports today (against `NoopWorldGatewayForEffects` until Wave 6
  swaps in Paper-backed `IWorldGateway`).
- **Konsist rule count 42 → 43** (the new `effect domain stays pure`
  rule joins the per-category purity family).

### Negative / Trade-offs

- Six new ports + nine new InMemory services + six new Fakes inflate
  Core's surface area. Mitigated by keeping every variant in
  category subpackages (`effect/scheduler/`, `effect/chain/`,
  `effect/sound/`, `effect/timer/`, `effect/ratelimit/`) so a single
  Konsist scope filter covers all of them.
- `InMemoryEffectOrchestrator.Loop` with `durationTicks == 0` is
  silently dropped with a warn-log rather than running forever. This
  is intentional (infinite-loop guard for malformed YAML) but means
  a "truly infinite" loop has to be modelled as
  `Loop(chain, durationTicks = Long.MAX_VALUE / 2, intervalTicks = N)`.
- The `Pulse` / `RingExpansion` variants sample points on the XZ
  plane only. Sphere / arbitrary-plane variants are out of scope for
  this wave; callers compose multiple `Parallel` `RingExpansion`
  branches when they need 3D coverage.
- The boss-phase-transition / combat-finisher-chain YAMLs ship as
  reference-only — a YAML decoder is not part of this wave. Combat
  and pve-boss waves construct `EffectChain` programmatically until a
  later wave adopts the YAML format.

### Mitigations

- The Konsist rule catches accidental edge-creep into the new
  packages at build time; even sub-agent reviewers can't drift the
  layering without the test failing.
- The orchestrator's no-op `IStatusEffectApplier` / `IDamageApplier`
  defaults mean Core boots cleanly without combat — every existing
  test that boots Core in isolation keeps passing.
- `MechanicDispatcher`'s new params are nullable + defaulted to
  `null`, so call sites that don't yet resolve the scheduler /
  registries via `services.get()` continue to fall back to
  `IWorldGateway.spawnParticle` / `playSound` directly (matching the
  pre-5effects shape, but with the SoundId → resource-key resolution
  improved when `soundRegistry` is wired).

## Alternatives Considered

- **Defer to Wave 6 + write the scheduling shape in the Paper
  adapter.** Rejected: violates ADR-0022 "Paper is a pure executor"
  and forces every Core-side caller (Mechanic dispatcher, HUD ring,
  match timer) to depend on `:plugin:adapter:paper`.
- **Reuse `IScheduler.runRepeating` directly at each call site.**
  Rejected: each call site would re-implement composite-cancel
  semantics, and the Mechanic dispatch debt would re-appear elsewhere.
- **Push status / damage steps into `:plugin:combat` only
  (orchestrator drops them).** Rejected: boss phase tells need status
  AOE on phase entry; without the bridge they'd have to assemble two
  `EffectHandle`s and synchronise cancellation manually. The SPI
  bridge keeps `EffectChain` the single source of truth.
- **One unified scheduler port instead of separate Particle / Sound /
  Orchestrator.** Rejected: the three concerns share a `IScheduler`
  substrate but their sealed-spec shapes differ enough (particle
  `RingExpansion` has no sound equivalent, sound `Sequence` of
  `TimedSound` differs from particle `Burst`) that a unified port
  would force every caller to wrap in helpers anyway.
- **kotlinx.serialization on `EffectChain` directly.** Rejected:
  ADR-0022 forbids `kotlinx.serialization` in `*.domain.*` (Konsist
  enforces). YAML DTOs belong in `core.application.config.effect.*`;
  the new Konsist rule keeps the domain pure.

## References

- 関連 ADR:
  - [[adr/0022-multi-plugin-portable-architecture]] — sibling
    isolation matrix the new ports live inside.
  - [[adr/0035-marketplace-jp-currency-cel-particles]] — Wave 5f
    landed the atomic particle / sound primitives this wave composes.
  - [[adr/0077-mechanic-targeter-phase-architecture]] — the Mechanic
    dispatcher whose `SpawnParticle` / `PlaySound` debt this closes.
  - [[adr/0083-anti-cheat-and-anti-afk]] — `IRateLimiter` is the
    foundation Wave 5q hardening will compose against.
  - [[adr/0088-combat-aoe-environment-effects]] — `IAreaEffectGateway`
    is a sibling Core-port + InMemory-default pattern; this wave
    follows the same shape.
- 関連 docs:
  - `.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §Round 5,
    §Wave 5effects.
  - `docs/RTM-PARITY-TRACKER.md` Category 1 (Effect orchestration).
