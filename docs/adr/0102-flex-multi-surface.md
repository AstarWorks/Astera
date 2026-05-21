---
status: Draft
date: 2026-05-21
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0084: 承認欲求 Multi-surface (Flex Layer)

## Status

Draft

## Context

User 戦略意図:

> 承認欲求を刺激する系。これを散らばらせて辞めない理由を作る。

長寿ゲーム共通: **見える資産** を多層に散らばらせる (Fashion Frame 原則)。
1 surface (例: Cape のみ) だと飽きるが、5-7 surface に散らばっていると
"次の surface" がある。

Astera は **Title (Wave 5e) + Cosmetic (Wave 8e) + Certification (Wave 11d) +
Leaderboard (Wave 7b)** で **4 surface 計上済**。HSB レベルの 7 layer に
**追加で 3-4 surface** を land する必要がある。

リサーチ根拠: `docs/competitive-analysis/07-cross-cutting.md` §3
(承認欲求 multi-surface の layer 設計)、HSB Networth + 原神 戦闘力 + OSRS
Cape of Accomplishment + Warframe Fashion Frame + MH Layered Armor 横断分析。

## Decision

Wave 12e で以下を land する:

### 1. Item rarity glow effect (Paper-side particle / display)

- `IItemRenderer` adapter (Paper-side particle / glow 制御)
- Item rarity / refinement tier (Wave 12c) に応じた particle frequency
- Mythic / Recomb tier は他 player から見える発光

### 2. Nameplate badge (tier-aware)

- Wave 5b `ITabList` + Adventure Component を拡張
- Title (Wave 5e) + Certification tier (Wave 11d) + Total Power Index を
  match nameplate に表示
- HSB Bingo profile icon / Bedwars Prestige tier icon 相当

### 3. Public profile card REST

- `/api/players/<id>/profile` (Wave 8b REST 拡張)
- 内容: equipment (refined item with stats) / skill progression (Wave 12d) /
  achievement (Wave 5e) / cosmetic / power index aggregate
- Out-of-game view (HSB sky.shiiyu.moe / OSRS hiscore / 原神 名刺 相当)
- Wave 8a Web admin に embed view 提供

### 4. Networth / Power Index aggregate

- `PowerIndex` domain (computed value: 装備総額 + skill 合計 + cosmetic value)
- HSB Networth 計算ロジックを Astera に直訳:
  - Equipment estimated value (Wave 12c ItemInstance 価値)
  - Personal vault value (Wave 5e PersonalVault)
  - Marketplace listing value (Wave 5f)
- Leaderboard 順位 (Wave 7b と integrate)

### 5. Match nameplate milestone show

- `kill 1000` / `boss defeat 100` 等 milestone を Wave 5e Achievement と integrate
- match 中 nameplate に動的表示

### Reuse first (新 plugin 不要)

すべて既存 plugin で実装可能:
- `plugin/items` — Item glow effect
- `plugin/core` — `IItemRenderer` port + Profile card REST helper
- `plugin/match` — match nameplate
- `plugin/economy` — PowerIndex aggregate (Marketplace 連動)

## Consequences

### Positive

- Fashion Frame 原則 + HSB Networth 級の "見える資産" 散らばし
- 辞めない理由を 7+ surface に多層化
- Wave 5e Title / Wave 8e Cosmetic / Wave 11d Certification の investment
  visibility が劇的に向上

### Negative / Trade-offs

- Paper-side particle / glow の performance 負荷 (mob 数 × particle frequency)
- Profile card REST は public endpoint なので rate limit / privacy 設定が必要

### Mitigations

- Particle frequency は per-rarity tunable (Common → no glow, Mythic → 高 tick)
- Profile card は player setting で公開可否選択 (Wave 5e PlayerSettings 拡張)
- Wave _ops-4 Prometheus で particle render time 計測

## Alternatives Considered

- **HSB Networth literal 模倣**: 採用 (`docs/competitive-analysis/01-hypixel-skyblock.md`
  分析で最もコスパ高い)
- **原神 CV (Crit Value) score**: 部分流用、Power Index に統合
- **OSRS Cape of Accomplishment**: Wave 5e Title + Wave 11d Certification で
  カバー済、追加不要

## References

- 関連 ADR: [[adr/0034-session-social-tutorial-enderchest-reward-bridge-inventory-snapshot]] (Title/Achievement) / [[adr/0043-...]] (Cosmetics、Wave 8e) / [[adr/0057-...]] (Certification、Wave 11d) / [[adr/0040-...]] (REST、Wave 8b)
- 関連 docs: [[competitive-analysis/07-cross-cutting]] §3 / [[competitive-analysis/05-warframe]] (Fashion Frame) / [[competitive-analysis/01-hypixel-skyblock]] (Networth)
