---
status: Draft
date: 2026-05-17
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0027: Phase 1 PoC Closing Scope

## Status

Draft

## Context

After Wave 4 (R3 Declarative GUI) landed (commit `537fdeb`), an audit was run
across the codebase (three Explore agents in parallel) to answer the question
**"is the game core actually implemented enough to justify Wave 5 (Paper
runtime) next?"**.

The audit found that Wave 1-4 built **infrastructure**, not gameplay:

- ✅ Multi-plugin Clean Arch (Core + 6 feature plugins)
- ✅ Match state machine + commands (manual phase advance only — no auto-timer)
- ✅ Stats / XP / Norma pipeline (Norma progress in-memory only — lost on restart)
- ✅ Currency / Wallet / 3 use cases (Deposit / Withdraw / Transfer) wired
- ✅ Permission / Role (RBAC with Postgres persistence, 14 keys)
- ✅ GUI runtime (Wave 4) — port + Paper adapter + intent router + 1 demo
- ✅ 6 Postgres tables (stats / matches / stages / wallets / role_definitions / role_assignments)

But several **core game systems** are entirely or substantially missing:

- ❌ **Shop** — 0 lines (domain, port, use case, YAML, GUI all absent)
- ❌ **Loadout persistence** — `Loadout` domain exists but never persisted; Wave 4 demo toggles an in-memory indicator only
- ❌ **Norma progress persistence** — in-memory only
- ❌ **Phase auto-timer** — `scheduler.runRepeating` used 0 times in match code; phases require manual admin commands to advance
- ❌ **Winning condition evaluator** — `MatchEnded.winningTeam` always null
- ❌ **SpawnPoint domain** — not in Stage YAML schema; no teleport wired
- ❌ **`IGuiBindingResolver`** — Wave 4 deferred reactive slot resolution
- ❌ **`PaperWorldGateway`** — port-only, no Paper impl (lands in Wave 5)
- ❌ **`IDamageGateway` / `IStatusEffectContainer` / `IProjectileService`** — Wave 5 scope
- ❌ **Armor / Anomaly / BossBar / Scoreboard / Schematic / Per-match world** — Phase 3 Siege Warfare scope
- ❌ **EnderChest / Bank / Achievement / Web admin / metrics** — Phase 4+ scope

Calling Wave 1-4 "Phase 1 PoC complete" overstates what's there. The user
challenged this directly: "ゲーム自体のコアシステムは全部実装が終わったの?
そんなわけないでしょ?"

Two options for next session:

- **Path A**: Proceed straight to Wave 5 (Paper runtime PoC) on the existing
  base. Result: "1 weapon fires, damage applies, status ticks", but Shop /
  Loadout persist / Norma persist / phase timer / SpawnPoint are still
  missing — so currency has nowhere to spend, loadout doesn't survive
  re-login, matches hang in COUNTDOWN forever.
- **Path B**: Insert **Wave 4.5 = Phase 1 PoC closing** as a gap-fill wave
  *before* Wave 5. Adds the 6 missing pieces that turn Wave 1-4
  infrastructure into a coherent Phase 1 PoC; then Wave 5 lands Paper
  runtime onto a meaningful base.

## Decision

Adopt **Path B**. Reclassify the roadmap as:

| Phase | Scope | Mapped Waves |
|---|---|---|
| **Phase 0 Infrastructure** | multi-plugin / Clean Arch / Match state machine / Stats/XP/Norma / Currency / Permission / GUI runtime / 6 Postgres tables | Wave 1, 2, 3, 4 |
| **Phase 1 PoC** | Shop + Loadout persist + Norma persist + SpawnPoint + Phase auto-timer + GUI binding resolver + Paper runtime (damage / projectile / status) | **Wave 4.5** (gap fill) + **Wave 5** (Paper) |
| **Phase 1.5 Ops** | KEDA / Sealed-Secrets / Provider ADR / metrics / kind e2e | Wave _ops (independent, parallel) |
| **Phase 2 mid Combat polish** | armor reduction / knockback / critical / hit detection refinements | folded into Wave 5+ |
| **Phase 3 Siege Warfare** | Star Generator / Anomaly / BossBar / Scoreboard / Schematic loading / Per-match world / Armor / Achievement / Spectator mode / Discord webhook | **Wave 6** (separate plan, build from zero) |
| **Phase 4 UGC / Web admin / Shop / Gacha / Vault / Bank / EnderChest** | UGC marketplace / Discord webhook / Nuxt 4 admin UI / Gacha / Vault | Post-Wave 6 |
| **Phase 5 Metaverse** | Persistent plots / SNS / seasonal events / tutorial | Post-Phase 4 |
| **Phase 6 Gen AI assets** | texture-gen (partial, in flight) / sound-gen / model-gen | Wave _assets, parallel |
| **Phase 7 Ops automation** | release-noter / incident-responder / season automation / voting / affiliate | Post-Phase 4 |

### Wave 4.5 (this session) scope

The audit identified 6 worker-parallelisable streams; Wave 4.5 ships all of
them in one session (Phase A scaffolding by me, Phase B 6 worker parallel,
Phase C integration by me):

1. **Shop** — `ShopSpec / ShopOffer / ShopPrice / ShopStock` domain in
   `plugin/core/domain/shop/` (cross-plugin classloader rule), impls in
   plugin/economy: `InMemoryShopRegistry` + `ShopLoaderService` +
   `ShopBrowseUseCase` (dynamic GuiSpec from offers) + `ShopPurchaseUseCase`
   (weapon-limited delivery via existing `IItemRegistry<IMcItemTemplate>` +
   `IMcPlayer.giveItem`; deliberately avoids importing `:plugin:weapons` per
   the existing Konsist rule) + `ShopIntentHandler` + `/astera-shop browse`
   + 1 example shop YAML
2. **Loadout persistence** — V5 migration + `LoadoutsTable` +
   `PostgresLoadoutRepository` (priority 100) + `InMemoryLoadoutRepository`
   (priority 0 in plugin/weapons) + `LoadoutSaveUseCase` /
   `LoadoutFetchUseCase` + `MatchStartLoadoutApplyListener` that subscribes
   to `MatchStarted` and delivers the saved MAIN-slot weapon. Wave 4's
   `LoadoutToggleHandler` now calls `LoadoutSaveUseCase` so toggles are
   persisted.
3. **Norma progress persistence** — V6 migration + `NormaProgressTable` +
   `PostgresNormaProgressRepository` + `InMemoryNormaProgressRepository`,
   with `InMemoryNormaService` refactored to delegate progress reads/writes
   to the new repository.
4. **SpawnPoint** — `SpawnPoint` sealed type in plugin/core/domain/stage/ +
   `Stage.spawnPoints` field + `StageYamlConfig.spawn_points` extension +
   `MatchSpawnUseCase` + `SpawnRotationStrategy` interface (Random impl).
   `IWorldGateway.teleport` added to the port (Paper impl deferred to Wave
   5; Fake impl ships in test-fixtures).
5. **Phase auto-timer + Victory** — `PhaseTimerUseCase.tickAll()` wired
   into `AsteraMatchPlugin.onEnable` via
   `scheduler.runRepeatingTicks(1, 20) { ... }` (1 second cadence).
   `VictoryConditionEvaluator(score threshold = 10)` integrated into
   `EndMatchUseCase` so `MatchEnded.winningTeam` is set.
6. **`IGuiBindingResolver`** — port + `DefaultGuiBindingResolver` impl that
   resolves `player.loadout.<slot>` bindings (lazy provider for
   `ILoadoutRepository` because it's published by a feature plugin that
   loads after core). Number-valued bindings (`player.coins.star` etc.)
   return null + WARN — Wave 5+ extends the resolver return type to
   `ResolvedReactiveSlot` with displayOverride/loreOverride for numeric
   readouts. `PaperItemTemplateRenderer.resolveReactive` now goes through
   the resolver.

### Cross-plugin classloader follow-throughs

Two additional moves were required during Phase A to satisfy ADR-0020's
"cross-plugin KClass identity" rule (the same rule that bit Wave 3):

- **Norma 6 file** (`Norma / NormaId / NormaTarget / NormaReward /
  PeriodKind / MissionProgress`) moved from `plugin/stats/domain/norma/` →
  `plugin/core/domain/norma/`. Required because the new
  `PostgresNormaProgressRepository` (in plugin/persistence) handles
  `MissionProgress` instances and must see the same `KClass` that
  plugin/stats handles. Without the move,
  `services.register<INormaProgressRepository>(impl)` and
  `services.get<INormaProgressRepository>()` would refer to different
  `Class` objects across classloaders.
- **`MatchStarted` event** moved from `plugin/match/domain/event/` →
  `plugin/core/domain/event/`. Required because Wave 4.5 adds two new
  subscribers in different plugins:
  `MatchStartLoadoutApplyListener` (plugin/weapons) and the inline spawn
  listener (plugin/match), and `weapons must not import combat or match or
  stats or economy or persistence` Konsist rule forbids weapons → match.
  Moving the event to core satisfies both subscribers and the rule.

These moves match Wave 3's precedent (`PlayerStats / Match / Stage /
Currency / Wallet` and friends migrated to core for the same reason).

### Phase 1 PoC completion criteria (after Wave 5 also lands)

When Wave 5 (Paper runtime) lands on top of Wave 4.5, the following end-to-end
demo MUST work:

- `/astera-shop browse example-shop` → 3-offer GUI opens
- Click an offer → currency debited → weapon delivered to inventory
- `/astera-loadout save MAIN example-pistol` (Wave 5 introduces this command)
  → row in `loadouts` table → re-login restores it
- `/astera-match start example-arena` → 30 seconds later auto-advances to
  ACTIVE → entered players teleport to attacker/defender spawn
- Left-click → `WeaponFired` published → Paper projectile traces → mob HP
  decreases (Wave 5 `PaperDamageGateway`) → status `burn` applied → ticks
  expire (Wave 5 `PaperStatusEffectContainer`)
- Kill 10 mobs → `VictoryConditionEvaluator` returns the team →
  `MatchEnded.winningTeam` set → currency reward published
- Norma progress increments and survives restart
- Reactive GUI slot bound to `player.loadout.main` shows the equipped item
  live

### Phase 3 Siege Warfare (Wave 6) is a green-field build

The audit confirmed Phase 3 deliverables are entirely absent: Star
Generator, Anomaly scheduler, Block tracking, BossBar, Scoreboard,
ActionBar, TabList, ITeamScoreboard, Schematic loading, per-match world
isolation, armor stats, knockback formula, friendly-fire toggle, etc.
Wave 6 will start from a new plan file (`docs/plan/phase-3-siege-warfare.md`
or similar), not from incremental extension of the current plugins.

## Consequences

### Positive

- Wave 5 lands on a meaningful base. "1 weapon fires" demo includes Shop
  → buy → equip → match-spawn → fire → damage → status → win condition →
  reward — a coherent end-to-end story.
- The roadmap is now honest: Wave 1-4 are Phase 0, not Phase 1.
- Cross-plugin classloader rule applies uniformly (Norma + MatchStarted
  now match Wave 3's Currency/Wallet/Match precedent).
- `IGuiBindingResolver` lazy-provider pattern is established; future
  reactive bindings (Wave 5+ numeric readouts) plug in without touching
  the renderer.
- Phase auto-timer + victory evaluator unblock match progression for
  Phase 3 work.

### Negative / trade-offs

- One extra session before Wave 5 (predicted ~5-6 hours). The user
  considered Path A, C, and D; Path B was selected for the merge-friction
  minimum and the "Shop-less PoC = hollow" judgment.
- The Shop's `ShopPurchaseUseCase` deliberately uses
  `IItemRegistry<IMcItemTemplate>` + `IMcPlayer.giveItem` rather than the
  `IWeaponRegistry` + `IWeaponDelivery` pair the original plan
  envisioned. This is forced by the `economy must not import weapons or
  combat or persistence` Konsist rule. Wave 5+ may introduce a
  vendor-neutral `IItemDelivery` port in core for richer typed failure
  signaling; the current path works for Wave 4.5's weapon-only demo.
- `MatchStartLoadoutApplyListener` only applies the MAIN slot. SUB /
  MOVE / SPECIAL / ARMOR / ULTIMATE are persisted but ignored until Wave
  5+ skill / armor / movement systems land.
- `DefaultGuiBindingResolver` only supports `player.loadout.*` keys in
  Wave 4.5. Numeric bindings (`player.coins.*`, `player.kills`) are
  deferred to a Wave 5+ `ResolvedReactiveSlot`-shaped return type.
- `IWorldGateway.teleport` Paper impl is deferred to Wave 5. Wave 4.5
  exercises `MatchSpawnUseCase` only through `FakeWorldGateway` in unit
  tests; live teleport works after Wave 5.
- `LoadoutToggleHandler` constructor gained a `LoadoutSaveUseCase`
  parameter — non-additive breaking change for any caller outside the
  weapons plugin (none today, but worth noting for Wave 5 wiring).

### Operational

- 2 new Postgres migrations (V5__loadouts, V6__norma_progress) extend
  the persistence schema; total tables go from 6 → 8.
- 7 plugins continue to ship as separate shadow jars; no new plugin
  module was created (Shop was nested inside plugin/economy to avoid
  the 8th-plugin classloader configuration cost).
- 1 new ADR (this one). Number 0026 stays reserved for Wave 5's "Paper
  Runtime Topology".
- ~100 new i18n keys (en + ja symmetric) for shop / loadout / norma /
  match / spawn / gui.binding / armor placeholder.

## Alternatives considered

- **Path A** (Wave 5 PoC割り切り, accept gaps): rejected because the
  user explicitly asked "ゲームコアが全部揃ったの?" — running Wave 5
  on top of missing Shop / Loadout / Phase timer perpetuates the
  misconception that Phase 1 PoC is complete.
- **Path C** (Roadmap re-build session first): rejected as a 1-session
  delay for documentation rework when the audit already identified the
  6 concrete gaps; this ADR plus the updated handoff doc accomplish the
  same documentation cleanup in Phase C of Wave 4.5.
- **Path D** (compress Wave 4.5 + Wave 5 into one 8-10 worker session):
  rejected for merge-friction risk. Wave 3 maxed at 5 workers; jumping
  to 8-10 multiplies the `AsteraXxxPlugin.kt` wire-conflict surface and
  re-creates Wave 3's classloader debugging window. 6 workers + Phase
  C integration is the proven envelope.

## Relationship to other ADRs

- ADR-0001 (Clean Architecture) — Wave 4.5 honors the
  domain/application/adapter split exactly as Wave 1-4 did
- ADR-0002 (Vendor-neutral MC adapter) — `IGuiBindingResolver`,
  `IShopRegistry`, `ILoadoutRepository`, `INormaProgressRepository`,
  `IWorldGateway.teleport` are all vendor-neutral
- ADR-0014 (JSONB) — no new JSONB columns; Wave 4.5 schemas (loadouts,
  norma_progress) are pure columnar
- ADR-0016 (Content schema SSoT) — shop schema auto-generated by
  schema-gen
- ADR-0019 (Unified Scheduler) — Wave 4.5 makes the first production
  use of `runRepeatingTicks` (phase timer)
- ADR-0020 (Persistence Architecture) — Wave 4.5 reuses the priority +
  loadbefore overlay pattern; Norma + MatchStarted moved to core for
  the same reason Currency/Wallet/Match were moved
- ADR-0021 (Declarative GUI) — `IGuiBindingResolver` fills the
  "Reactive binding resolver" TODO left open by ADR-0021
- ADR-0022 (Multi-plugin) — no new plugin modules; Shop nested in
  plugin/economy
- ADR-0025 (Event wiring) — Wave 4.5 adds 1 new event publisher
  (`LoadoutChanged` in plugin/weapons/domain/event/) and 2 new cross-
  plugin subscribers (`MatchStartLoadoutApplyListener`,
  `MatchSpawnUseCase` inline subscriber)

## Notes for Wave 5

Wave 5 (Paper runtime, ADR-0026 placeholder) lands `PaperDamageGateway`,
`PaperStatusEffectContainer`, `PaperProjectileService`, and
`PaperWorldGateway` on top of Wave 4.5. Phase 1 PoC is complete when
Wave 5's demo runs end-to-end through Wave 4.5's shop → loadout →
match → spawn pipeline.
