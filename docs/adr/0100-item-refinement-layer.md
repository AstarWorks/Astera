---
status: Draft
date: 2026-05-21
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0082: Item Refinement Layer (厳選レイヤー乗算)

## Status

Draft

## Context

長寿ゲーム共通の **厳選レイヤー乗算** (HSB 8-layer / 原神聖遺物 sub stat / MH
charm) を Astera に追加。User 戦略意図の中核軸:

> 厳選要素は間違いなく大事

ADR-0073 (Item-Centric Content Model) で `rarity` + vanilla `AttributeModifier`
までは inline 済 (土台のみ)。本 ADR は **複数の独立な強化レイヤー** を ItemSpec
に乗せ、`ItemInstance` (per-instance container) を作る。

リサーチ根拠: `docs/competitive-analysis/07-cross-cutting.md` §1
(厳選システムの数学的構造 4 類型) + §1.2 (Resource sink design)。

**重要な拒否事例**: Warframe Riven の "削って戻れない re-roll" は 9 年間
community に嫌われた負債 (`docs/competitive-analysis/05-warframe.md` §Astera
適用候補)。Astera は **swap 可能 / 過去 roll キープ** を default にする。

## Decision

Wave 12c で以下を land する:

### ItemYamlConfig 拡張 (ADR-0073 upward compatible)

```yaml
# content/items/<id>.yaml
refinement:
  enabled: true
  gemstone_slots: 3                   # HSB 風
  reforge_pool: [sharp, fierce, demonic]  # HSB reforge stone 風
  upgradeable_to: 10                  # HSB Star 風 (1-10)
  sub_stat_rolls:                     # 原神聖遺物 風
    count: 4
    max_rolls_per_stat: 5
    candidate_stats: [attack, crit_chance, crit_dmg, defense]
```

### 新規 domain

- `ItemInstance` (instance_id UUID + item_id + per-instance `IAttributeContainer`)
- `Refinement` sealed (Reforge / Enchant / Gemstone / Star / Recomb)
- `ApplyRefinementUseCase` (currency 消費 + RNG roll + ItemInstance 書き換え)
- `RerollSubStatsUseCase` (**過去 roll は保持**、新 roll が良ければ swap、悪ければ
  破棄を player が選択 — Warframe 教訓)

### Persistence

- V20 migration: `item_instances` (instance_id UUID PK / item_id / attributes
  JSONB / owner_id / created_at)
- `IItemInstanceRepository` port + InMemory + Postgres priority 100 (Wave 5g
  pattern 流用)

### Resource sink (re-roll cost)

- **Escalating cost** 4-5 step then plateau (Warframe Riven 900→1000→2000→2500→3000
  pattern 流用)
- Currency は Wave 5f Marketplace と tradable (PoE Divine Orb 風の 経済参加)

### Wave 5f Marketplace 互換

- `ItemInstance` を marketplace listing 可能にする (refined item flipping 成立)
- `MarketplaceListing` の `itemRef` を `ItemInstanceRef` 受け付け可能に拡張

## Consequences

### Positive

- HSB / 原神 / PoE / Warframe / MH レベルの厳選 retention 軸が Astera 開通
- Marketplace 経済が厚くなる (refined item の取引)
- Wave 12d (Refining skill) の XP source

### Negative / Trade-offs

- ItemInstance per-player storage が増える (V20 + JSONB)
- Drop table (Wave 12b) と refinement parameter の balance 調整が複雑化

### Mitigations

- JSONB は ADR-0014 (JSONB usage policy) と整合 (queryable column + JSONB hybrid)
- Wave 12g Analytics で refinement attempt rate / success rate 計測

## Alternatives Considered

- **ADR-0073 を supersede**: しない。本 ADR は ADR-0073 の `refinement:` optional
  block を追加する **upward compatible** な拡張。
- **Warframe Riven 風 "削って戻れない"**: 拒否 (`docs/competitive-analysis/05-warframe.md`
  9 年負債事例)
- **PoE 風 currency-driven craft**: 部分流用 (Currency tradable 部分のみ、
  affix system 完全模倣は複雑性過剰)

## References

- 関連 ADR: [[adr/0073-item-centric-content-model-and-skill-binding]] / [[adr/0014-jsonb-usage-policy]] / [[adr/0035-marketplace-jp-currency-compliance-cel]] / [[adr/0058-universal-attribute-container]]
- 関連 docs: [[competitive-analysis/01-hypixel-skyblock]] / [[competitive-analysis/02-genshin-hsr]] / [[competitive-analysis/05-warframe]] (Warframe 教訓) / [[competitive-analysis/06-monster-hunter]] / [[competitive-analysis/07-cross-cutting]] §1
