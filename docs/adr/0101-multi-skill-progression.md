---
status: Accepted
date: 2026-05-21
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0101: 多軸 Skill Progression (HSB 12-skill 並走の Astera 版)

## Status

Accepted (Wave 5multi-skill landed 2026-05-23)

## Context

Astera 既存 progression は **単一線形** (`PlayerStats` + `Level` +
`LevelUpMission`)。単一軸は **詰まり点で逃げ場がない** (combat 苦手層が
engaged を維持できない)。

長寿ゲームすべてが多軸 progression を採用:
- OSRS: 23 skill
- Hypixel SkyBlock: 12 skill
- Warframe: MR + Frame Lv + Weapon Lv
- 原神: キャラ × 武器 × 聖遺物

リサーチ根拠: `docs/competitive-analysis/07-cross-cutting.md` §2 (多軸
progression の設計)。**HSB 12-skill が sweet spot** (OSRS 23 は多すぎ)、Astera
初期は **10 軸** で開始。

## Decision

Wave 5multi-skill で以下を land した (元の計画では Wave 12d 予定だったが、
roadmap pull-forward により Wave 5 帯で先行実装):

### Domain

- `SkillKind` sealed (10 軸):
  - Combat (PvP DamageDealt, Match win)
  - BossSlayer (Mob/Boss kill, Wave 12a 連携)
  - Mining (BlockBroken, Wave 5d 連携)
  - Fishing (新規 event)
  - Farming (新規 event)
  - Refining (`ItemRefined` event, Wave 12c 連携)
  - Trading (Marketplace 取引額, Wave 5f 連携)
  - Exploration (EncounterZone 踏破, Wave 12b 連携)
  - Tournament (Wave 7b 試合数)
  - Crafting (新規、後回し可)
- `SkillProgression(playerId, kind, xp, level)` 値オブジェクト
- `ISkillProgressionRepository` port + InMemory + Postgres priority 100
- V21 migration: `skill_progressions` (player_id + skill_kind + xp + level)

### Use case

- `AwardSkillXpUseCase` (event 受信 → XP gain → level up 判定 → `SkillLevelUp`
  publish)
- 既存 `IDomainEventBus` 経由で全 plugin から XP source publish:
  - `MobKilled` → Combat (PvP は除外) + BossSlayer (boss flag)
  - `BlockBroken` → Mining
  - `LootDropped` → BossSlayer / Mining (zone別)
  - `ItemRefined` → Refining
  - `MarketplaceListingPurchased` → Trading
  - etc.

### Perk tree YAML

```yaml
# content/perks/combat.yaml
skill: combat
perks:
  - level: 5
    id: combat-damage-1
    effect: { stat: damage, multiplier: 1.05 }
  - level: 25
    id: combat-crit-chance-2
    effect: { stat: crit_chance, additive: 0.10 }
```

### HUD

- Skill XP 表示は Wave 5b `IBossBar` or `IScoreboard` 流用 (新 port 不要)
- Level up 時に `IBroadcaster` で player に通知

### XP 曲線

- 指数 (HSB 風)、Lv 1 → 100 で 累計 ~100M XP オーダー
- 各 Lv の XP threshold は YAML で tunable (`content/balance/skill-xp-curve.yaml`)

## Consequences

### Positive

- PvP 苦手層も grind に engaged (Fishing / Farming / Mining 専業可)
- Wave 12a (PvE) / 12b (Content) / 12c (Refinement) すべての XP source が
  自然に統合される
- HSB 12-skill / OSRS 23-skill レベルの retention 軸開通

### Negative / Trade-offs

- 10 軸の balance 調整負担 (XP 曲線 + perk + reward の整合)
- 既存 `PlayerStats` との関係を明確にする必要 (PlayerStats は match 内 ephemeral、
  SkillProgression は永続)

### Mitigations

- Wave 12g Analytics で per-skill engagement metric 計測 (偏り検出)
- 初期は perk 効果を控えめに (multiplier 1.05 等)、Wave 12g feedback で調整
- 10 軸は YAML で disable 可、Phase 9 で軸追加余地

## Alternatives Considered

- **OSRS 23 軸**: 拒否 (Astera 規模 / content 量で OSRS 級は実現不可)
- **HSB 12 軸そのまま**: ほぼ採用、但し Carpentry / Runecrafting / Social /
  Dungeoneering は Astera 設計と乖離するので除外
- **Single Mastery Rank only** (Warframe 流): 統合 surface は Wave 12e で別途実装

## References

- 関連 ADR: [[adr/0058-universal-attribute-container]] / [[adr/0034-session-social-tutorial-enderchest-reward-bridge-inventory-snapshot]] (PlayerStats)
- 関連 docs: [[competitive-analysis/01-hypixel-skyblock]] / [[competitive-analysis/04-osrs]] / [[competitive-analysis/07-cross-cutting]] §2

## Implementation notes (Wave 5multi-skill, 2026-05-23)

- `SkillKind` enum (10 軸) は `plugin:core` の `core.domain.progression` に
  配置。`token` 文字列が DB / YAML / event payload の wire key で、入れ替え
  禁止 (V30 migration スキーマ依存)。
- `ISkillProgressionRepository` port (Core) + InMemory (Stats, prio 0) +
  Postgres (Persistence, prio 100) は ADR-0020 priority overlay の標準形を
  踏襲。V30 migration がテーブル `skill_progressions(player_id, skill_kind,
  xp, level, updated_at)` を作成。
- `AwardSkillXpUseCase` (Stats) は repo + curve + bus を引数に取り、`amount
  <= 0` no-op + `SkillXpGained` 常時 publish + `SkillLevelUp` は level 上昇
  時のみ publish。
- 4 source listener (Combat / BossSlayer / Mining / Trading) を Stats に
  配置。Refining / Tournament / Exploration / Fishing / Farming / Crafting
  は対応する event がまだ存在しないため Phase 2+ で feed 追加予定 (axis 自
  体は本 wave で declare 済)。
- `SkillProgressionContents` (Stats) が `content/skills-progression/*.yaml`
  を読み込み、`ISkillXpCurveRegistry` + `ISkillPerkRegistry` の両 port を
  提供。`SkillXpCurve.exponential()` がデフォルト fallback。
- `GrantPerksOnSkillLevelUpListener` は perk 解決と `SkillPerkUnlocked`
  event の publish までで止める。実 perk 効果適用 (StatMultiplier 等を
  PlayerAttributeContainer に push) は Phase 2 の attribute-modifier work と
  合流予定。

実装 commit は `wave-5multi-skill` branch に単一 commit として land。
