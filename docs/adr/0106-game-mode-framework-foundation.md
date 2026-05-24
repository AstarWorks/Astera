---
status: Accepted
date: 2026-05-24
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0106: IGameMode Framework Foundation (Norma refactor)

## Status

Accepted (Wave 5gamemode delivery, 2026-05-24)

## Context

Phase 1 PoC ships exactly one match flavour — the Wave 5c "Norma" control-
objective pipeline (Star Generator + Anomaly + Sudden Death + 3-team scoring),
wired globally in `AsteraMatchPlugin.onEnable`. The Phase 1 plan was always
"one mode, hard-wired" because before the pipeline existed there was nothing
to abstract over.

Phase 3 Siege is the *second* match flavour. The naive path — add a second
hard-wired pipeline next to Norma — has three costs that compound as Phase 3
adds Deathmatch, TDM, CTF, Battle Royale, and Co-op PvE raid (per
`idea-game-mode-framework.md`):

1. **`AsteraMatchPlugin.onEnable` becomes a list of `if (mode == X) wire Y`**
   blocks. Each new mode forces a touch on the same wiring file → high merge
   contention + Konsist can't catch a missing wire-up.
2. **Mode discovery has no single source of truth**. The lobby HUD, the
   `/astera-match start` tab-completion, the matchmaker (Wave 5w), and the
   queue UI each rediscover "what modes exist" independently.
3. **The lifecycle contract is implicit** — each pipeline subscribes to
   `MatchStarted` / `PhaseChanged` / `MatchEnded` directly, and there's no
   shared seam for cross-cutting concerns (HUD overlay per mode, audit
   tagging per mode, post-match reward policy per mode).

The user-supplied `idea-game-mode-framework.md` proposes the framework — a
behavioural `IGameMode` interface, a registry, a per-mode-aware scheduler,
YAML-driven discovery — and explicitly asks for the *foundation* to land in
Phase 1 ahead of Phase 2 framework planning so Phase 3 Siege has a stable
contract to copy.

The constraint from the wave handoff: **the Wave 5c pipeline behaviour MUST
NOT change**. NormaGameMode is therefore a *façade* over the existing global
pipeline, not a rewrite. Equivalence is asserted by a dedicated unit test
(`NormaGameModeEquivalenceTest`) showing the façade publishes only the new
framework events and otherwise leaves the global pipeline untouched.

## Decision

Land an `IGameMode` framework foundation in `:plugin:match` with these shapes:

**Domain (`match.domain.gamemode.*`, no Bukkit / persistence / serialization
imports — Konsist-enforced)**:

- `IGameMode` — behavioural interface with `id()`, `titleKey()`,
  `defaultPhases()`, `tickPeriodTicks()`, and four lifecycle hooks
  (`onMatchStarted` / `onPhaseChanged` / `onTick` / `onMatchEnded`).
- `IGameModePhase` — sealed (`Lobby`, `Countdown`, `Active`, `SuddenDeath`,
  `Ended`) plus `fromCore` / `toCore` helpers against `core.domain.match.MatchPhase`.
- `GameModeContext` — immutable per-match scratch (matchId / modeId /
  participants / typed `Map<String, Any>` scoringState).
- `GameModeEvent` — sealed `DomainEvent` family: `GameModeStarted`,
  `GameModePhaseChanged`, `GameModeEnded` (additive to the existing core
  `MatchStarted` / `PhaseChanged` / `MatchEnded`).

**Application (ports + impls)**:

- `IGameModeFrameworkRegistry` outbound port; default
  `InMemoryGameModeFrameworkRegistry`. Distinct from the legacy
  `IGameModeRegistry` (which keys the Wave 5c `sealed class GameMode` config
  carriers). The two registries coexist; eventual merge deferred until two
  non-Norma impls inform the unified shape.
- `IGameModeScheduler` outbound port; default `DefaultGameModeScheduler`
  (wraps `IScheduler.runRepeatingTicks`, one task per active match per mode,
  `tickPeriodTicks == 0` → tracked but not scheduled).
- `GameModeContents` (subclass of `YamlDynamicContents`) — loads
  `content/game-modes/*.yaml`, resolves `impl_class` FQN via reflection
  (no-arg constructor), registers the resulting `IGameMode` into the
  framework registry. Participates in `/astera reload game-modes`.

**Implementations**:

- `NormaGameMode` — wraps the Wave 5c pipeline. The pipeline's global
  schedulers are NOT moved into NormaGameMode (they remain wired globally in
  `AsteraMatchPlugin`); the façade publishes framework events on
  `onMatchStarted` / `onPhaseChanged` / `onMatchEnded` and is a no-op on
  `onTick`. Equivalence test pins this contract.

**Wiring (Phase C, `AsteraMatchPlugin.onEnable`)**:

- Bootstrap `GameModeContents` from `content/game-modes/`; reflectively
  register every YAML-declared mode into the framework registry. Replace the
  registered NormaGameMode with a bus-wired instance so framework events
  publish in production.
- Subscribe `MatchStarted` / `match.domain.event.PhaseChanged` / `MatchEnded`
  to dispatch through the resolved mode's hooks + the scheduler.
- Hardcoded Norma resolution at the subscription seam — Phase 3 Siege
  replaces this single resolution step with "read mode from match config"
  and inherits the rest of the wiring.

**YAML schema (`content/game-modes/<id>.yaml`)**:

```yaml
id: norma
title_key: astera.gamemode.norma.title
impl_class: com.astarworks.astera.match.application.gamemode.NormaGameMode
tick_period_ticks: 20
default_config: {}
```

`default_config` is a free-form `Map<String, String>` for Phase 1 PoC; typed
per-mode config carriers land in Phase 3 once two non-Norma impls exist to
inform the cross-mode shape (per Phase 1 pre-release posture in CLAUDE.md
§Pre-release posture: we don't pre-type a contract we don't yet have two
data points for).

**Konsist (+1 rule)**:

`match domain gamemode must be pure and avoid legacy mode coupling` —
`match.domain.gamemode.*` may not import Bukkit, persistence drivers,
`org.joml`, `java.io/nio/net`, `kotlinx.serialization`, or
`match.domain.mode.*` (except `GameModeId`, which both registries reuse).

**i18n keys**:

- `astera.gamemode.norma.title` (en/ja parity holds).
- `astera.gamemode.norma.description` (en/ja parity holds).

## Consequences

### Positive

- Phase 3 Siege adds one `IGameMode` impl + one YAML file; zero edits to
  `AsteraMatchPlugin.onEnable` once the mode-resolution step graduates from
  hardcoded Norma to a `match-config -> mode-id` lookup.
- Mode discovery has a single source of truth (the registry). Lobby HUD,
  matchmaker, and `/astera-match` commands query it instead of hand-coding
  the mode list.
- Cross-cutting concerns (audit, HUD overlay, reward policy) subscribe to
  framework events once, regardless of how many `IGameMode` impls exist.
- The Wave 5c pipeline behaviour is unchanged (equivalence test pins).
  Today's match flow is exactly today's match flow; tomorrow's adds get
  the framework seam without paying any migration cost.
- The framework events are additive to the existing core events.
  Subscribers that don't care about the framework don't change. Subscribers
  that want "did the framework see this transition" subscribe to the new
  events without re-deriving from raw match events.

### Negative / Trade-offs

- Two registries coexist (legacy `IGameModeRegistry` for config carriers +
  new `IGameModeFrameworkRegistry` for behaviour). Naming overlap is
  inherent and called out in the kdoc of `IGameMode` + the framework
  registry. The merge is intentionally deferred until two non-Norma impls
  exist; collapsing now would mean re-shaping when the second impl lands.
- The Phase 1 reflective `impl_class` resolution path is a thin reflection
  layer (`Class.forName` + no-arg ctor). A misconfigured YAML logs a warning
  and skips; the rest of the registry still loads. Phase 3 (Koin DI)
  replaces with proper DI lookup.
- `NormaGameMode` is a façade — `onTick` does nothing. A future maintainer
  reading the implementation might wonder why; the kdoc + equivalence test
  document the rationale (Wave 5c pipeline is global; Phase 3 Siege will be
  the first impl whose `onTick` actually drives work).
- The wiring subscription resolves Norma by hard-coded id. This is a
  *known intentional* gap closing in Phase 3 when match config carries
  mode id; flagged in the wiring kdoc.

### Mitigations

- Konsist rule `match domain gamemode must be pure and avoid legacy mode
  coupling` ensures the framework domain doesn't accidentally couple to the
  legacy config-side hierarchy beyond reusing `GameModeId`.
- The equivalence test (`NormaGameModeEquivalenceTest`) covers all four
  lifecycle hooks + the null-bus seam, so any future change to NormaGameMode
  that breaks Wave 5c equivalence fails the test.
- `GameModeContents` registers `(id == impl-reported id)` parity at load
  time → typo'd YAML surfaces as a load warning, not as a silent first-match
  failure.

## Alternatives Considered

- **Hard-wire each future mode like Norma is today** (no framework). Cheapest
  for Phase 1; pays back every Phase 3+ mode wiring as ad-hoc subscriptions
  in `AsteraMatchPlugin.onEnable`. Rejected: Phase 3 has ≥4 modes planned,
  the linear cost dominates.
- **Make the framework `IGameMode` interface own the per-mode schedulers
  end-to-end (no global StarGeneratorScheduler / AnomalyScheduler)**.
  Cleaner long-term but requires rewriting the Wave 5c pipeline as part of
  Wave 5gamemode → blows the wave handoff's "Norma behaviour unchanged"
  constraint and ships a regression risk on a PoC-critical pipeline.
  Rejected: deferred to Phase 3 where the pipeline owner can choose to
  refactor on a per-feature basis.
- **Unify `IGameModeRegistry` + `IGameModeFrameworkRegistry` immediately**
  by widening the legacy `sealed class GameMode` to include behaviour.
  Forces an opinion on per-mode config shape before any non-Norma impl
  exists; rejected as premature typing. Re-evaluated when Siege lands.
- **Drop the YAML-driven `GameModeContents` and require every mode be
  manually `register()`'d in `onEnable`**. Cuts the reflection path but
  forces every new mode to re-touch `AsteraMatchPlugin.onEnable` — which is
  the cost the framework is meant to eliminate. Rejected.

## References

- 関連 ADR: [[adr/0022]] (Phase 4 multi-plugin layout), [[adr/0027]] (Match
  phase auto-timer), [[adr/0032]] (Wave 5c game-mode config registry +
  Star Generator pipeline), [[adr/0065]] (`IReloadableContentsRegistry` +
  `/astera reload` dispatcher), [[adr/0079]] (`IPlayerSessionRepository` +
  disconnect/reconnect — the framework's event-driven seam mirrors this
  pattern).
- 構想 md: `.claude/plans/idea-game-mode-framework.md` (Phase 2 plan
  starter; refined into this ADR + Wave 5gamemode handoff).
- 親 plan: `.claude/plans/wave-5g-core-hardening-giggly-sunset.md`
  §"Deferred concepts".
- 関連 RTM 行: #58 (custom game mode framework foundation — Phase 1
  delivery).
