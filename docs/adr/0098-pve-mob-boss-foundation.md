---
status: Accepted
date: 2026-05-23
deciders: orchestrator (Wave 5pve-boss)
supersedes: null
superseded_by: null
---

# ADR-0098: PvE Mob/Boss Foundation (Phase 8 §Add)

## Status

Accepted

## Context

Astera roadmap (Wave 1-77) は前身 RTM を継承して **PvP arena match-only** で
組まれており、PvE (mob / boss / dungeon / raid) が一切計上されていない。
本 ADR は ADR-0013 (RTM divergence policy) §Add カテゴリで PvE foundation を
追加する。

リサーチ根拠は `docs/competitive-analysis/` 横断分析 (HSB / 原神 / PoE / OSRS /
Warframe / MH)。User 戦略意図: **既存 PvP 武器を PvE に流用** して content
lifecycle を延長 + 厳選軸の grind target を作る。

ADR-0058 (Universal Attribute Container) のおかげで player と mob を **同じ
container で扱える** ので、新 plugin は作らず既存 `plugin/combat` + `plugin/match`
に bolt-on する選択肢が成立。

## Decision

Wave 5pve-boss で **ADR-0085 の Mob foundation を拡張** する形で
land する (新 plugin は作らず `plugin/core` に bolt-on)。基本 Mob /
MobSpec / IMobAdapter / IMobSpawner / IMobRegistry は既に ADR-0085
で commit 済み — 本 ADR は boss / encounter / loot / raid layer を
追加する:

### Domain types (新規、すべて `core.domain.mob.*` 配下)

- `boss/BossId` (namespaced lower-kebab-case value class)
- `boss/BossPhase` (index + i18n nameKey + immunityTicks + transitionCast +
  periodic / onHit skill list)
- `boss/BossPhaseTransition` (sealed; Phase 1 ships `HpThreshold` variant)
- `boss/BossSpec` (id + display + baseMobType reference + phases +
  transitions + raidLock + lootTable + recordKill flag)
- `boss/BossError` (sealed; UnknownBoss / UnknownMob / RaidLocked /
  PartyRequired / WrongPhase / BackendError)
- `encounter/EncounterZoneId` + `encounter/EncounterZone` +
  `encounter/EncounterMobEntry`
- `encounter/DifficultyCurve` (sealed; Flat / Polynomial)
- `loot/LootDrop` (itemId + chance + minCount + maxCount)
- `loot/LootRollContext` (tableId + killer + mob/boss/zone refs +
  partyMemberIds + encounterLevel)
- `loot/LootRoll` (drops + seed for replay)
- `raid/RaidLockPolicy` (sealed; Open / PartyLock)
- `raid/RaidClaim` (mobId + partyId + firstHitter + claimedAt)
- `runtime/MobId` (runtime UUID-wrapping value class, distinct from
  template-level `MobTypeId`)

### Outbound ports (新規、`core.application.port.outbound.mob.*`)

- `IBossLifecycleService` — register spec / spawn / instanceOf / onDamage /
  onDeath、`BossInstance` 読取値も同 file
- `IEncounterZoneRegistry` — register / get / all / size
- `ILootRollService` — registerTable / tableOf / roll(context, seed, atTick)
- `IBossKillAuditWriter` — record(BossDefeated) idempotent / count

### In-core service impls (`core.application.service.mob.*`)

- `InMemoryEncounterZoneRegistry` — LinkedHashMap insertion order
- `InMemoryBossKillAuditWriter` — Phase 1 default; persistence plugin が
  Wave 6c で `PostgresBossKillAuditWriter` を priority 100 で overlay
- `DeterministicLootRollService` — `kotlin.random.Random` を seeded、
  `(seed XOR tableHash XOR mobHash XOR killerUuidBits XOR bossHash?)` で
  per-call mixing。`LootDropped` 公開
- `InMemoryBossLifecycleService` — spawn / damage / death の単 thread
  orchestration、HpThreshold transition を declaration order で評価、
  PartyLock claim 自動付与、recordKill=true のとき audit writer 呼び出し

### Domain events (新規、`core.domain.event.*`)

- `BossSpawned(mobId, handle, bossId, zoneId?, world, position, atTick)`
- `BossPhaseChanged(mobId, bossId, fromPhase, toPhase, trigger, atTick)`
- `BossDefeated(mobId, bossId, killerId?, partyId?, fightDurationTicks,
  finalPhase, atTick)`
- `LootDropped(mobId, bossId?, tableId, killerId, drops, seed, atTick)`

### Persistence (V30 migration)

`plugin/persistence/src/main/resources/db/migration/V30__boss_kills.sql`:
- `boss_kills` table (`id` UUID PK + `mob_id` VARCHAR(64) UNIQUE +
  `boss_id` VARCHAR(128) + `killer_id` UUID nullable + `party_id`
  VARCHAR(64) nullable + `final_phase` SMALLINT + `fight_duration_ticks`
  BIGINT + `killed_at` TIMESTAMP + `at_tick` BIGINT)
- 3 indexes: `boss_recent (boss_id, fight_duration_ticks, killed_at)` for
  leaderboard / speedrun query、partial `party (party_id, killed_at)` for
  weekly raid clears、partial `killer (killer_id, killed_at)` for first-
  clear history
- Idempotency via `mob_id UNIQUE` + impl-side `INSERT ON CONFLICT DO
  NOTHING` (Phase 1 in-memory writer is naturally idempotent on duplicate
  `mob_id`)

### Konsist 強化 (+1 rule)

- `core domain mob boss encounter loot raid runtime must stay platform-free
  (ADR-0098)` — boss / encounter / loot / raid / runtime 5 subpackage が
  Bukkit family / JDBC / Exposed / `java.util.concurrent.*` を import しない
  ことを保証 (`java.util.concurrent.*` は boss state が Paper main thread
  single-threaded である invariant を守るため)

### Skill cast / damage flow 流用

既存 `ApplyDamageUseCase` には触らない (PvE damage routing は Wave 5pve-fix /
Wave 6b の damage hijack layer で対応予定 — `MatchKind` parameter の追加は
本 ADR scope 外、後続 ADR で扱う)。Boss は通常の mob 同様 `MobKilled` /
`MobSpawned` を ADR-0085 経路で発火しつつ、追加で boss-specific 4 events を
発火する duplex 構造。

### Wave 6b / Phase 4 担当 (本 ADR が commit する port skeleton の実装)

- `PaperBossLifecycleService` (Wave 6b) — real ArmorStand boss bar + HP
  listener + phase transition timer 駆動
- `PostgresBossKillAuditWriter` (Wave 6c, persistence plugin) — priority
  100 で in-core impl を overlay
- `ContentLootRollService` (Phase 4) — `content/loot-tables/*.yaml` 読み込み、
  priority 100 で deterministic impl を overlay
- Encounter zone YAML registry (Phase 4) — `content/encounters/*.yaml` →
  `IEncounterZoneRegistry` impl swap

Konsist 整合: 全 boss / encounter / loot / raid 型は `core.domain.mob.*`
subpackage に置き、`plugin/combat` `plugin/match` `plugin/stats` から参照可。
sibling-isolation matrix (ADR-0022) と整合。

## Consequences

### Positive

- PvP 武器 = PvE 武器 で content lifecycle が乗算 (HSB の最大の発明)
- ADR-0058 IAttributeContainer の汎用性検証
- Wave 12c (Refinement) / Wave 12d (多軸 skill) / Wave 12b (PvE Content) の依存元

### Negative / Trade-offs

- `ApplyDamageUseCase` に `MatchKind` parameter 追加で既存 caller 全更新
- Paper 側で `EntityDamageEvent` の hijack range が広がる (ADR-0030 §8 と整合)

### Mitigations

- Phase 1 PoC closing (Wave 5a-f → 6a-b) は変更しない、Wave 12a は最早 Wave 7c
  完了後
- `MatchKind` default を `PvP` にして既存 caller の挙動保持 (Phase 1 PoC 期間中
  は破壊的変更可、リリース後は default 化を再評価)

## Alternatives Considered

- **新 `plugin/pve` を作る**: sibling-isolation matrix を 1 軸増やす。今は
  `plugin/combat` + `plugin/match` で十分、将来 PvE content 量が爆発したら検討。
- **MythicMobs adapter で外部依存**: ADR-0015 D (Decline) と整合せず、self-built
  Astera 方針に反する。
- **PvE 武器と PvP 武器を別 type で分離**: User 要求 "武器共用" と矛盾、HSB の
  retention 設計とも矛盾。

## References

- 関連 ADR: [[adr/0013-rtm-divergence-policy]] §Add / [[adr/0022-multi-plugin-portable-architecture]]
  / [[adr/0030-combat-depth-and-damage-event-decomposition]] §8 (Bukkit damage
  hijack) / [[adr/0058-universal-attribute-container]] / [[adr/0073-item-centric-content-model-and-skill-binding]]
- 関連 docs: [[competitive-analysis/01-hypixel-skyblock]] / [[competitive-analysis/07-cross-cutting]] §5
- Plan: `/home/node/.claude/plans/hypixcel-skyblock-peppy-nygaard.md`
