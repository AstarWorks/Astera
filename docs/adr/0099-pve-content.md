---
status: Accepted
date: 2026-05-24
deciders: orchestrator (Wave 5pve-content)
supersedes: null
superseded_by: null
---

# ADR-0099: PvE Content (Encounter Zones + Drop Tables + Boss Mechanics)

## Status

Accepted

## Context

Wave 5pve-boss (ADR-0098) で foundation (Mob domain + IBossLifecycleService +
IEncounterZoneRegistry port skeleton + ILootRollService + IBossKillAuditWriter
+ V31 boss_kills migration) が landed。Wave 5pve-content (本 ADR) はその上に
**実コンテンツ + YAML 駆動 registry** を載せる:

- 1+ playable PvE zone (HSB Slayer / 原神 World Boss / OSRS PvM の Astera 版)
- Drop table (HSB Slayer Tier 1-5 風の rare drop)
- Boss mechanic primitive (phase transition / immunity window / mechanic AoE)
- Group raid lock (party 必須 / max 4 人 — Astera convention)
- Death penalty rule (per-zone YAML で safe death / item drop on death を切替)

リサーチ根拠: `docs/competitive-analysis/01-hypixel-skyblock.md` Slayer Tier
設計 + `04-osrs.md` Boss rare drop 1/100〜1/3000 帯 (Astera population スケール
で 1/34025 は許容不可、上限 **1/2000** 程度から開始)。

## Decision

Wave 5pve-content で以下を land する:

### Content YAML schema (substrate)

- `content/pve/encounters/<zone>.yaml` — EncounterZone (region + mob pool +
  difficulty curve + optional boss anchor + death penalty rule)
- `content/pve/drop-tables/<table>.yaml` — Loot table (item ref + per-row
  drop chance + count range)
- `content/pve/bosses/<boss>.yaml` — Boss spec (base mob template + phases
  + transitions + raid lock + dedicated loot table)

### New domain types (additive on ADR-0098 base)

- `core.domain.mob.encounter.DeathPenaltyRule` (sealed: `SafeDeath` /
  `ItemDropOnDeath(graceTicks)`) — co-located with `EncounterZone` in the
  `encounter` subpackage so the existing ADR-0098 Konsist platform-free
  guard applies without widening the rule set
- `EncounterZone.deathPenalty: DeathPenaltyRule = SafeDeath` (additive
  defaulted field — existing pre-ADR-0099 zones keep their behaviour)

### New outbound ports (`core.application.port.outbound.mob.*`)

- `IBossSpecRegistry` — `register / get / all / size` parallel to
  `IEncounterZoneRegistry`; backed by `BossSpecContents` (YAML-driven)
- `IDeathPenaltyPolicy` — `ruleFor(player, zoneId?) → DeathPenaltyRule`;
  read-only resolver (the Wave 6b Paper death-handler applies the effect)

### New impls (`core.application.contents.pve.*` +
### `core.application.service.mob.pve.*`)

- `EncounterZoneContents : YamlDynamicContents<EncounterZoneId, EncounterZone,
  EncounterZoneYamlConfig>` — also implements `IEncounterZoneRegistry`,
  so the YAML-backed registry **replaces** the Phase 1 empty in-memory
  default at boot time. ADR-0098's `IEncounterZoneRegistry.all` return type
  was widened from `List` to `Collection` so the YAML substrate can satisfy
  the port without a defensive copy (pre-release breaking change allowed
  per CLAUDE.md §"Pre-release posture").
- `BossSpecContents : YamlDynamicContents<BossId, BossSpec, BossYamlConfig>`
  — also implements `IBossSpecRegistry`. YAML-loaded specs are forwarded
  into the existing `InMemoryBossLifecycleService.registerSpec()` at
  bootstrap so the lifecycle service keeps a single mutable spec map.
- `LootTableLoaderService` — stand-alone loader (loot tables are a
  `(LootTableId, List<LootDrop>)` shape that does not fit
  `YamlDynamicContents<K, V>`); reads `content/pve/drop-tables/` and
  calls `ILootRollService.registerTable()` per file.
- `ZoneScopedDeathPenaltyPolicy` — `IDeathPenaltyPolicy` impl that reads
  the rule off `IEncounterZoneRegistry.get(zoneId)?.deathPenalty`, falling
  back to `DeathPenaltyRule.SAFE` for null / unknown zones.
- `BossDefeatedLootRoller` — `IDomainEventBus` subscriber on `BossDefeated`.
  Resolves the boss spec via `IBossSpecRegistry`, constructs a
  `LootRollContext`, and calls `ILootRollService.roll()`. Skips null-killer
  kills and `LootTableId.EMPTY` sentinel to avoid loot-event noise.

### YAML DTOs (`core.application.config.mob.pve.*`)

- `LootTableYamlConfig` + `LootDropYaml` — `id` + `drops: List<{item_id,
  chance, min_count, max_count}>`; rejects duplicate `item_id` per table
- `EncounterZoneYamlConfig` + `EncounterMobEntryYaml` + `DifficultyCurveYaml`
  + `DeathPenaltyYaml` — full encounter shape with discriminated unions for
  difficulty + death penalty
- `BossYamlConfig` + `BossPhaseYaml` + `BossPhaseTransitionYaml` +
  `RaidLockYaml` — phase + transition + raid-lock with `type` discriminators

### Ship content (PoC)

- 2 encounter zones: `astera:blazing-pit` (party-locked raid + item-drop
  penalty + polynomial difficulty + Scorched Titan anchor) +
  `astera:whispering-cavern` (open mob grinder + safe death + Forest Wolf
  pool — re-uses Wave 5pve mob)
- 1 boss spec: `astera:scorched-titan` (2 phases: molten phase 0 → enraged
  phase 1 @ HP 50%; party_lock max 4; transition cast `titan-roar` + 60-tick
  immunity; periodic + on-hit skill bindings)
- 4 loot tables: scorched-titan-loot (5 drop tiers: 1.0/0.5/0.1/0.005/0.0005
  hitting the ADR cap) + ember-imp-loot + flame-jackal-loot +
  forest-wolf-drops
- 3 supporting mob templates: `astera:ember-imp` (Blazing Pit basic) +
  `astera:flame-jackal` (Blazing Pit mid-tier with periodic howl) +
  `astera:scorched-titan-base` (boss body, 2400 HP)

### Persistence (V34)

`plugin/persistence/src/main/resources/db/migration/V34__pve_encounter_clears.sql`:
- `pve_encounter_clears` table — schema-only ship; the writer impl lands in
  Wave 5pve-fix / Wave 6c (mirroring the V31 pattern of ADR-0098 — schema
  first, Paper impl second)
- Columns: `id` UUID PK + `player_id` + `zone_id` VARCHAR(128) +
  `boss_kill_id` UUID FK to `boss_kills` (ON DELETE SET NULL) + `party_id` +
  `cleared_at` + `at_tick`
- 3 indexes: `player_recent` (timeline) + `zone_first` (leaderboard) +
  `(player, zone, cleared_at DESC)` for weekly-reset gate

### Konsist 強化 (+1 rule)

- `pve content yaml DTOs must stay platform-free (ADR-0099)` —
  `core.application.config.mob.pve.*` must not import Bukkit family / JDBC /
  Exposed / `java.util.concurrent.*`. The YAML layer is pure data +
  conversion; Minecraft / persistence / threading concerns belong in
  service or adapter layers.

### Drop chance ceiling

ADR-0099 caps rare drops at **1/2000** (0.0005) to keep grind frustration
bounded for the Astera population scale. The cap is a design convention
(documented here + in `LootTableYamlConfig` KDoc); not enforced in the data
layer — `LootDrop` validates only `chance in [0.0, 1.0]`. Content reviewers
catch violations at YAML-PR time.

### Raid lock (max party size)

Astera convention: **4-player cap** (HSB Slayer is 1; OSRS raid is 5;
Annihilation PoC sits in between to match the Phase 1 party-sizing of the
existing `IPartyRepository`). The `RaidLockPolicy.PartyLock(maxPartySize)`
invariant enforces 1..8 at construction; the boss YAML carries the actual
chosen size per-encounter. Phase 1 PoC ships only `max_party_size: 4` for
the Scorched Titan example.

## Consequences

### Positive

- HSB Slayer / 原神 World Boss / OSRS PvM の retention 軸が Astera に開通
- Item Refinement (Wave 5refine, ADR-0100) の grind target が確保される
  (drop した item を厳選)
- 10-axis skill progression (Wave 5multi-skill, ADR-0101) の PvE XP source
- YAML-first content authoring — no Kotlin needed to add a new zone /
  boss / loot table

### Negative / Trade-offs

- Content YAML 量が増える (encounter / loot table / boss mechanic 3 系列)
- Drop balance 調整は data-driven (Wave 5engage Analytics の actual / expected
  drop rate metric が前提)
- `IEncounterZoneRegistry.all()` signature widened (`List` → `Collection`)
  — pre-release breaking change, no caller in main but Wave 6b Paper
  impl must use `Collection` going forward

### Mitigations

- Wave 5refine (Refinement) + Wave 5multi-skill (10-axis skill) は本 ADR と
  並列開発可、依存はしない (それぞれ別 YAML schema)
- Wave 5engage Analytics で drop rate の actual / expected を Prometheus 計測
- `List → Collection` widening is documented in port KDoc + this ADR;
  Wave 6b will inherit naturally

## Alternatives Considered

- **Boss は instance ベース (Dungeon)**: Wave 5d (World geometry) の追加負荷
  大、Phase 8 後半 (Wave 12b-2 分割) で検討。
- **Drop table を item にひも付け**: ADR-0073 item-centric model と矛盾しないが、
  HSB は zone-centric drop table を採用しており universal 武器設計と整合性高い。
- **OSRS 級 1/34025 drop**: 拒否 (Astera population で実現不可、frustration
  一辺倒)
- **`pve` を独立 subpackage に分離 (`core.domain.mob.pve.*`)**: ADR-0098 の
  Konsist platform-free rule が `boss / encounter / loot / raid / runtime`
  にしか効かない。`DeathPenaltyRule` を `encounter` に co-locate する方が
  既存 guard を流用でき、YAML DTOs だけ `application.config.mob.pve` で分けて
  新 Konsist rule で守る方が分離度が高い (採用案)
- **`IBossLifecycleService.registerSpec()` を廃止して `IBossSpecRegistry`
  単独にする**: ADR-0098 が shipped 済で lifecycle service が spec map を
  持っている。今 ripping out すると lifecycle service の test 全書き直し
  になり、forward-only breaking change としては invest 過剰。Phase 1
  PoC closing 中は両 surface を維持し、YAML 読み込みが両方に伝播する
  bridge を boot wiring で組む方が低コスト。Wave 6b Paper impl 着手時に
  再評価。

## References

- 関連 ADR:
  - [[adr/0098-pve-mob-boss-foundation]] (本 ADR の foundation)
  - [[adr/0085-pve-mob-foundation]] (mob 層)
  - [[adr/0073-item-centric-content-model-and-skill-binding]] (`ItemId` consumer)
  - [[adr/0058-universal-attribute-container]] (mob / player container 統一)
  - [[adr/0022-multi-plugin-portable-architecture]] (sibling isolation)
  - [[adr/0017-adopt-datadriven-contents-pattern]] (Contents substrate)
- 関連 docs:
  - [[competitive-analysis/01-hypixel-skyblock]] Slayer Tier
  - [[competitive-analysis/04-osrs]] PvM rare drop
  - [[competitive-analysis/07-cross-cutting]] §1.1
- Plan: `/home/node/.claude/plans/hypixcel-skyblock-peppy-nygaard.md`
