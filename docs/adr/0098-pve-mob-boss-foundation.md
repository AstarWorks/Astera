---
status: Draft
date: 2026-05-21
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0080: PvE Mob/Boss Foundation (Phase 8 §Add)

## Status

Draft

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

Wave 12a で以下を land する:

- `Mob` domain (id / archetype sealed / level / maxHp / `IAttributeContainer`)
- `IMobSpawnService` port (inbound + outbound), InMemory + Paper (Bukkit
  `World.spawnEntity`) 2 impl
- `EncounterZone` domain (region + spawn rule + difficulty curve)
- `LootDrop` domain (item ref + drop chance + RNG)
- 4 new domain events in `core.domain.event`:
  - `MobSpawned(mobId, archetype, location, atTick)`
  - `MobKilled(mobId, killerId?, atTick)`
  - `BossDefeated(bossId, partyId?, atTick)`
  - `LootDropped(mobId, items, atTick)`
- 既存 `IDamageGateway` + `ApplyDamageUseCase` を mob target に流用 (PvP 武器の
  attack profile を mob にもそのまま適用)
- `MatchKind` (PvP / PvE) parameter を `ApplyDamageUseCase` に追加し、damage
  formula 内で reduction modifier を切替 (PvE: full damage / PvP: ~70-90% reduced)

Konsist 整合: `Mob` は `core.domain` に置き sealed、`plugin/combat` と
`plugin/match` から参照可。sibling-isolation matrix (ADR-0022) と整合。

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
