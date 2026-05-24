---
status: Accepted
date: 2026-05-24
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0108: Astera Annihilation Mode Foundation (3-pillar Nexus + IGameMode framework integration)

## Status

Accepted (Wave 5annihilation delivery, 2026-05-24)

## Context

Astera's core identity, per 2026-05-20 user confirmation captured in
`.claude/plans/idea-annihilation-vision.md`, is "**銃剣魔法の Annihilation
server**" — a Hypixel Annihilation derivative where:

- 3 teams (Astera lighter than Hypixel's 4) each defend a **3-pillar Nexus**
  (Astera's twist: graceful degradation defensive checkpoint vs Hypixel's
  single Nexus block).
- Combat is 銃/剣/魔法 hybrid driven by ItemSpec + SkillTrigger (Wave 5a) +
  per-class resource pools (Wave 5k Ammo / Stamina / Mana).
- No class system — full weapon-driven freedom (Astera-specific divergence
  from Hypixel's fixed Warrior / Archer / Mage / Vampire / etc.).
- Combo + focus-fire + target-lock combat depth (Wave 5combat-depth)
  layered on top.

Wave 5gamemode (ADR-0106) landed the `IGameMode` behavioural framework as
the seam for Phase 3+ mode additions (Siege, Deathmatch, CTF, Battle
Royale, Annihilation). The wave handoff for 5gamemode shipped exactly one
impl — `NormaGameMode` (façade over the Wave 5c control-objective
pipeline). The Round 4 plan calls for **Annihilation as the second impl**
ahead of any Phase 6 Paper-side work, so the Wave 6 sweep finds a stable
Core port + Match application surface ready to wire to actual blocks /
listeners.

This is therefore a *foundation* delivery: domain shapes + use-case glue +
events + Konsist guard + Postgres overlay schema + YAML schema, all
testable headlessly. **No Paper adapter** (deferred to Wave 6); **no UI
overlay** (deferred to a HUD-side wave that consumes the events landed
here).

## Decision

Land an Annihilation game-mode foundation across `:plugin:core` (Nexus
domain + cross-plugin events) and `:plugin:match` (Annihilation-specific
domain + IGameMode impl + tracker + YAML overlay) with these shapes:

### Core domain (`core.domain.nexus.*`, pure Kotlin)

- **`Nexus`** — single-pillar value object: `id`, `matchId`, `owningTeam`,
  `tier`, `maxHp`, `hp`, `state`. Carries `applyDamage(amount)` → returns
  `(Nexus, NexusDamageOutcome)`; `openForCombat()` moves `BUILDING` →
  `VULNERABLE` (Phase transition entry).
- **`NexusId`** — `JvmInline value class` lower-kebab-case id; mirrors
  `MatchId` / `TeamId` to keep audit / SQL / compass HUD strings uniform.
- **`NexusPillarTier`** — enum `TIER_1 / TIER_2 / TIER_3` (1/2/3 ordinal);
  the three pillars per team.
- **`NexusState`** — enum `BUILDING / VULNERABLE / DESTROYED` (linear; no
  back-edge in Phase 1 PoC).
- **`NexusDamageOutcome`** — sealed: `Damaged(prev, curr)` /
  `Destroyed(prev)` / `Ignored(reason)` + `IgnoreReason` enum
  (`ZERO_AMOUNT` / `BUILDING` / `ALREADY_DESTROYED`).
- **`NexusPhase`** — sealed interface, 4 data objects: `Phase1Build`,
  `Phase2Engage`, `Phase3Tier3`, `Phase4SuddenDeath` (each carries a
  stable string id; `ordered()` / `byId(...)` / `next(...)` helpers).
- **`INexusEntity`** — vendor-neutral port wrapping a `Nexus` + a
  world-space `Position3D` (Triple-shaped value object; mapped to
  `org.bukkit.Location` only in Wave 6 paper adapter).

### Core cross-plugin events (`core.domain.event.*`)

Per CLAUDE.md rule "sealed `DomainEvent` hierarchy lives in `plugin:core`",
all four annihilation events live here:

- **`NexusDamaged`** — non-lethal damage applied; carries attacker (nullable
  for environment / DoT), damage amount, prev/curr HP.
- **`NexusDestroyed`** — lethal hit; carries attacker + previous HP.
- **`TeamEliminated`** — fires after the Tier 3 pillar of a team is
  destroyed; carries the eliminated team + roster snapshot.
- **`AnnihilationPhaseTransitioned`** — old/new `NexusPhase` pair + tick;
  distinct from `match.domain.event.PhaseChanged` because Annihilation has
  4 internal phases all living inside `MatchPhase.ACTIVE`.

### Match domain (`match.domain.gamemode.annihilation.*`, pure Kotlin)

- **`AnnihilationConfig`** — typed knobs: `pillarCount` (always 3 in PoC,
  enforced), `pillarHp`, four phase-duration ticks, `suddenDeathDotPerTick`,
  `respawnDelaySecondsTier1Lost`, `resourceRegenMultTier2Lost`. Domain
  invariants live in `init` block.
- **`AnnihilationMatchState`** — per-match aggregate: matchId, config,
  current `NexusPhase`, `phaseEnteredAtTick`, pillar map, eliminated
  teams. Mutations return new instances. Owns `seed(matchId, config,
  teams)` factory that generates `3 × teams.size` pillars in `BUILDING`
  with deterministic id `<match>-<team>-<tier>`.
- **`AnnihilationScore`** — per-team score = sum of own-team HP +
  pillarsAlive + tier3Alive. Implements `Comparable` so the sudden-death
  tiebreaker resolves deterministically.
- **`AnnihilationPhaseRules`** — pure decision logic: time-driven Phase
  1→2 / Phase 3→4 / Phase 4 terminal; time-OR-cascade-driven Phase 2→3.
- **`WinConditionRules`** — pure resolver: `Outcome.InProgress / Winner(team)
  / Draw`. Elimination outcome from `survivingTeams.size <= 1`; sudden-
  death tiebreaker from `AnnihilationScore.computeAll`.

### Match application (`match.application.gamemode.annihilation.*`)

- **`AnnihilationGameMode`** — `IGameMode` impl. Owns its `AnnihilationMatchTracker`;
  lifecycle hooks: `onMatchStarted` seeds the tracker, `onTick` advances
  phase + applies sudden-death DoT + evaluates win condition, `onMatchEnded`
  unregisters. Mirrors NormaGameMode's no-arg constructor pattern for
  reflective `GameModeContents` resolution.
- **`AnnihilationMatchTracker`** — per-match in-memory state owner.
  `recordDamage(match, nexus, amount, attacker, tick)` is the single
  damage-application entry; publishes `NexusDamaged` (non-lethal) OR
  `NexusDestroyed` + `TeamEliminated` (lethal Tier 3) atomically.
  `advancePhasesIfNeeded(match, tick)` consults `AnnihilationPhaseRules`,
  opens the next tier for combat (Phase 2 → Tier 1, Phase 3 → Tier 2 +
  3), publishes `AnnihilationPhaseTransitioned`. `applySuddenDeathTick`
  loops surviving Tier 3 pillars in Phase 4 and damages each by the
  configured per-tick DoT.

### Match application config (`match.application.config.gamemode.annihilation.*`)

- **`AnnihilationYamlOverlay`** — parses the `default_config: Map<String,
  String>` from `content/game-modes/annihilation.yaml` into the typed
  `AnnihilationConfig`. Soft failures (unparseable values fall back +
  warning; domain validation errors fall back + warning).
- **`NexusTemplateYamlConfig`** — Wave 6-bound schema for
  `content/nexus-templates/<id>.yaml`: per-tier block material + offset
  from team base spawn + optional HP override. Not consumed by the
  Phase 1 PoC tracker (deferred to Wave 6 Paper adapter).

### Wiring (Phase C, `AsteraMatchPlugin.onEnable`)

- Construct `AnnihilationMatchTracker(eventBus)`.
- Register a bus-wired `AnnihilationGameMode(tracker, eventBus)` into
  `IGameModeFrameworkRegistry`. This replaces any reflection-built
  tracker-less + bus-less instance that `GameModeContents` produced from
  `annihilation.yaml` — mirrors the Norma "replace YAML-resolved with
  bus-wired" pattern.
- The existing Wave 5gamemode subscriptions (`MatchStarted` /
  `PhaseChanged` / `MatchEnded` → `IGameMode` lifecycle hooks) already
  dispatch into the registered mode. The wiring does NOT add a separate
  bridge listener for Annihilation; the framework handles it.

### YAML

- **`content/game-modes/annihilation.yaml`** — mode declaration: `id:
  annihilation`, `title_key: astera.gamemode.annihilation.title`,
  `impl_class: com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationGameMode`,
  `tick_period_ticks: 20`, `default_config` with all 8 tunables at their
  Phase 1 PoC defaults.
- **`content/nexus-templates/default-3pillar.yaml`** — example Wave 6
  template: 3 tiers × (block_material + offset + optional hp_override).

### Postgres (V40 migration)

- **`annihilation_match_stats`** — per-team-per-match audit overlay:
  `(match_id, team_id)` PK, `pillar_count_started / destroyed`,
  `final_aggregate_hp`, `final_phase`, `eliminated` (boolean) +
  `eliminated_at_tick`, `final_outcome` ('winner' / 'loser' /
  'eliminated' / 'draw'). Constraints: destroyed ≤ started; eliminated
  iff tick is set; outcome ∈ allowed set. Two indexes for the
  leaderboard scan + per-match audit read.

### i18n

- `astera.gamemode.annihilation.title` + `description` (en/ja parity
  holds).
- `astera.gamemode.annihilation.template.default` (template name).
- `astera.gamemode.annihilation.phase.{build,engage,tier3,sudden_death}`
  (per-phase HUD labels).
- `astera.gamemode.annihilation.pillar.{tier_1,tier_2,tier_3}` (per-pillar
  HUD labels).
- `astera.gamemode.annihilation.event.{nexus_damaged,nexus_destroyed,
  team_eliminated,phase_transitioned}` (per-event broadcast templates).

### Konsist (+1 rule)

`annihilation domain stays pure and routes through IGameMode framework
(ADR-0108)` — two assertion blocks:

1. `core.domain.nexus.*` stays pure: no Bukkit / persistence / koin /
   kotlinx.serialization / java.io/nio/net imports.
2. `match.domain.gamemode.annihilation.*` stays pure (same forbid list +
   `org.joml`) AND the parent gamemode rule already enforces "no legacy
   `match.domain.mode.*` import (except `GameModeId`)". A subpackage
   inherits the parent rule because the existing test uses `startsWith`.

## Consequences

### Positive

- Annihilation is the second `IGameMode` impl; the framework's seam holds
  (no plugin-wiring change beyond a 1-block registration). Phase 3 Siege /
  Deathmatch / CTF add files alongside this one with zero new wiring.
- Pure-domain `AnnihilationMatchState` + `AnnihilationPhaseRules` + 
  `WinConditionRules` are headless-testable; the tracker drives them
  through the bus without any Paper coupling. Wave 6 Paper adapter wires
  block-break listeners → `tracker.recordDamage(...)` with zero further
  domain change.
- 4 new `DomainEvent` variants give downstream consumers (HUD overlay,
  audit log, Battle pass progression) a stable subscription surface. The
  distinction between `NexusDamaged` (every hit) / `NexusDestroyed` (lethal
  only) / `TeamEliminated` (Tier 3 specifically) avoids any consumer
  needing to filter or re-derive lifecycle state.
- V40 schema is established now so Wave 6 + Wave 7 (Battle pass) can
  query `annihilation_match_stats` for a per-team result without needing
  to project from the audit event log.
- Hypixel Annihilation's defensive-tier debuff structure (respawn /
  resource regen / elimination) is encoded in `AnnihilationConfig` from
  day one; Wave 5k integration points (`resourceRegenMultTier2Lost`) and
  Wave 6 respawn-listener integration points (`respawnDelaySecondsTier1Lost`)
  exist as config knobs ready to consume.

### Negative / Trade-offs

- Two domain surfaces (`core.domain.nexus.*` + `match.domain.gamemode.annihilation.*`)
  for one feature. Justified by ADR-0022 sibling isolation (events live in
  Core because Match cannot publish a Core event from a Match-only type)
  but it does mean the Nexus value object is split across plugin boundaries.
  Documented in the kdoc of both packages.
- The tracker holds in-memory state only — no crash-recovery in Phase 1.
  V40 schema is established but not populated mid-match. Wave 6 lands
  the projection (write per-team row on match-end) + Wave 6/7 considers
  whether to checkpoint mid-match.
- The wiring's tracker uses a single shared `eventBus` — fine for Phase 1
  PoC where one server runs one or two matches. Multi-match contention
  not addressed; the tracker's single-thread contract (Paper main thread)
  keeps it correct under the PoC's workload.
- `AnnihilationGameMode.inferTeams` reads `GameModeContext.scoringState`'s
  `annihilation.teams` slot, which the wiring populates before
  `onMatchStarted`. This slot-based contract is informal — it works because
  the wiring controls both ends. Phase 3 may formalise via a typed
  `GameModeContext` slot once the second IGameMode-owning-state mode
  exists.

### Mitigations

- The Konsist rule plus the existing `match domain gamemode` rule cover
  the framework discipline. Any drift (Annihilation introducing its own
  registry, or Nexus domain pulling in Bukkit) fails the build.
- The `AnnihilationGameMode()` no-arg constructor produces a tracker-less
  + bus-less mode that is a clean no-op on every hook — same fallback
  pattern as `NormaGameMode()`. Reflective resolution via
  `GameModeContents` works without crashing, then the wiring re-registers
  the bus-wired instance.
- Domain validation lives in `init` blocks (`AnnihilationConfig` rejects
  out-of-range tunables; `Nexus` rejects cross-field inconsistencies);
  the YAML overlay catches them at boot rather than at first-match.
- 30 unit tests across 8 test classes cover the value-object state
  machine + match aggregate + phase rules + win-condition resolver +
  tracker event emission + YAML overlay parse + template validation +
  `AnnihilationGameMode` lifecycle. No Paper harness needed.

## Alternatives Considered

- **Single domain surface in `match.domain.*` (no `core.domain.nexus.*`)**.
  Cleanest if Nexus were Match-only. Rejected: the cross-plugin events
  (`NexusDamaged` etc.) need a Core-side type to satisfy the
  `DomainEvent` "lives in plugin:core" rule, and the audit-log + HUD
  subscribers in other plugins read those events directly. Putting only
  the events in Core would split the Nexus vocabulary unnaturally.
- **Reuse `match.domain.mode.GameMode` sealed class as the Annihilation
  carrier** (the way the Wave 5c `BattleLoyal` / `BreakCore` value carriers
  work). Rejected: the legacy `GameMode` carriers are *config* (per-mode
  knobs for the existing Norma pipeline); Annihilation is *behaviour* (its
  own state machine). The Wave 5gamemode ADR-0106 §"Why two registries"
  rationale applies — Annihilation belongs on the behavioural framework,
  not the legacy config side.
- **Skip the per-team `annihilation_match_stats` overlay; project from the
  audit event log at leaderboard query time**. Cheaper to ship; pays back
  on every leaderboard query (joining + folding events vs reading a single
  row per team). Rejected: 3 rows/match is trivial; the leaderboard read
  is hot; established schema now avoids a Wave 7 migration.
- **Hard-wire Annihilation lifecycle as a global pipeline (mirror Wave 5c
  Norma's StarGenerator/Anomaly scheduler model) instead of through
  IGameMode.onTick**. Cheapest to wire (no per-match scheduler
  registration); pays back in test isolation (every Annihilation test
  would need the global scheduler running). Rejected: the IGameMode
  framework's whole reason for existing is to *not* do this — every
  global pipeline added is one Phase 3 mode that can't ship without
  rewiring `AsteraMatchPlugin`.
- **Defer the Konsist rule until Wave 6** (Paper-side work). Rejected:
  the foundation IS the place to lock in domain purity; deferring would
  let a Wave 6 implementer pull in Bukkit / persistence through the
  Annihilation domain by accident and the regression would not surface
  until a separate "audit" wave.

## References

- 関連 ADR: [[adr/0022]] (multi-plugin sibling isolation), [[adr/0027]]
  (match phase auto-timer — the `MatchPhase` enum Annihilation maps to),
  [[adr/0032]] (Wave 5c game-mode config registry — the legacy `GameMode`
  carriers the framework coexists with), [[adr/0073]] (ItemSpec —
  weapon-resource binding that Wave 5k consumes for Annihilation
  per-class load-out), [[adr/0103]] (Wave 5bet — the Postgres migration
  numbering precedent), [[adr/0105]] (Wave 5combat-depth — combo /
  focus-fire / target-lock that this mode integrates with), [[adr/0106]]
  (Wave 5gamemode IGameMode framework foundation — this ADR's second
  impl).
- 構想 md: `.claude/plans/idea-annihilation-vision.md` (Astera =
  Annihilation server core identity vision).
- 親 plan: `.claude/plans/wave-5g-core-hardening-giggly-sunset.md`
  §"Round 4 — User-elevated 待機解除 waves".
- 関連 RTM 行: TBD — new row "Astera Annihilation mode foundation
  (3-pillar Nexus + IGameMode integration)" added by Phase C.
