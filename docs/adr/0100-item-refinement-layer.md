---
status: Accepted
date: 2026-05-21
accepted_date: 2026-05-23
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0100: Item Refinement Layer (厳選レイヤー乗算)

## Status

Accepted (Wave 5refine landed 2026-05-23)

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

Wave 5refine (2026-05-23 landed; **Wave 12c was the originally-planned slot —
fast-forwarded to PoC-closing wave to unblock retention design earlier**) で
以下を land した:

### YAML schema (standalone profile per item)

ADR-0022 sibling-isolation を保つため、ADR-0073 の `ItemYamlConfig` (Core
所有) を変更せず、Items plugin 所有の **standalone profile file** を採用:

```yaml
# content/refinement/<id>.yaml
id: example-sword
profile:
  enabled: true
  gemstone_slots: 3                       # HSB Gemstone
  reforge_pool: [sharp, fierce, demonic]  # HSB Reforge Stone
  upgradeable_to: 10                      # HSB Star 1..10
  sub_stat_count: 4                       # 原神聖遺物 sub-stats
  max_rolls_per_stat: 5
  candidate_stats: [attack, crit_chance, crit_dmg, defense]
```

`content/items/<id>.yaml` に inline する案は、Items domain 型 (`RefinementSpec`,
`SubStatKey`) を Core の `ItemYamlConfig` が直接見る形になり ADR-0022 を破る
ため**不採用**。Profile が無い item は `RefinementSpec.DISABLED` (=
non-refinable) として扱う。

### 新規 domain (items.domain.refinement)

- `ItemInstanceId` (value-class UUID wrapper)
- `ItemInstance` — per-player container; layers + sub-stat rolls + reroll count
  を保持し、内部 `IAttributeContainer` (ADR-0058) に projection。
- `Refinement` sealed (`Reforge` / `Enchant` / `Gemstone` / `Star` / `Recomb`)
- `RefinementSpec` — per-item capability declaration (HSB+原神+MH+PoE 4軸)
- `SubStatKey` enum (ATTACK / ATTACK_SPEED / CRIT_CHANCE / CRIT_DAMAGE /
  DEFENSE / MAX_HEALTH / MOVEMENT_SPEED) — 厳選 candidate whitelist
- `SubStatRoll` — 原神聖遺物 sub-stat roll record
- `RefinementMath` — 4-category multiplier table + escalating reroll cost
  (`900 / 1000 / 2000 / 2500 / 3000` plateau)
- `RefinementError` sealed (UnknownItem / NotRefinable / LayerNotAllowed /
  SubStatNotCandidate / InsufficientFunds / UnknownInstance)

### 新規 application (items.application)

- `IItemInstanceRepository` outbound port (InMemory + Postgres priority 100)
- `IRefinementRandomizer` outbound port (Kotlin random impl + Fake for tests)
- `RefinementProfileRegistry` — `(ItemId) -> RefinementSpec` resolver
- `ApplyRefinementUseCase` — layer validation + addLayer + event publish
- `RerollSubStatsUseCase` — escalating cost withdraw + keep-or-discard
  predicate (**過去 roll keep がデフォルト** — Warframe 反例)
- `RefinementYamlConfig` + `RefinementProfileYaml` DTOs (kaml-annotated;
  domain-free per Konsist)

### 新規 cross-plugin domain events (core.domain.event)

- `ItemInstanceRefined` — apply 成功時に publish
- `ItemInstanceRerolled` — reroll 成功時に publish (keep / discard 両方記録 —
  analytics signal)

### Persistence

- V30 migration: `item_instances` (id UUID PK / item_id varchar / owner_id
  UUID / created_at timestamp / layers JSONB / sub_stat_rolls JSONB /
  reroll_count integer)
  - `idx_item_instances_owner` (owner_id) — inventory inspect の hot path
  - `idx_item_instances_item_id` (item_id) — analytics + leaderboard join
- `PostgresItemInstanceRepository` — priority 100 overlay; polymorphic
  Refinement variants は kotlinx-serialization discriminator (`kind`) で JSONB
  encode。`RawJsonbColumnType` pattern を `ItemInstancesTable` 局所に複製
  (`ModerationActionsTable` と同じ pattern)。

### Konsist 追加 rule

- `items refinement domain stays pure (ADR-0100)` —
  `items.domain.refinement.*` MUST NOT import Bukkit / kotlinx.serialization /
  exposed / postgres / sql / lettuce / koin。`scopeFromProject().files` ベース
  (新規 module 限定 scope より project-wide のほうが将来の plugin rename を
  吸収しやすい)。

### Layer composition / multiplication math

ADR-0058 既存の `IAttributeContainer.effectiveNumber` に**そのまま乗る**:

1. 各 `Refinement` layer は `RefinementMath.modifierFor(layer)` で
   `AttributeModifier(operation = MULTIPLY, key = item.refined_power_multiplier)`
   を 1 個 emit。
2. ItemInstance の container は `addModifier` し、`effectiveNumber(key)` が
   resolution order (SET → ADD → MULTIPLY compounding) で合成。
3. Sub-stat rolls は base [NumberValue] として `item.substat.<yamlKey>` キーに
   set。Combat 側は `container.effectiveNumber(key)` を読むだけ。

Phase 1 coefficients (`RefinementMath` 内 table; balance review で tunable):

| Layer    | Multiplier per unit  |
|----------|----------------------|
| Reforge  | 1 + tier × 0.10      |
| Enchant  | 1 + level × 0.05     |
| Gemstone | 1 + tier × 0.08      |
| Star     | 1 + stars × 0.08     |
| Recomb   | 1 + score / 100      |

### Resource sink (re-roll cost)

- **Escalating cost** Warframe Riven pattern (900 / 1000 / 2000 / 2500 / 3000
  plateau)。`RefinementMath.rerollCost(rerollIndex)` で resolve。
- Currency は Wave 5f Marketplace と tradable (PoE Divine Orb 風)。

### Wave 5f Marketplace 互換 (deferred to Wave 6)

`ItemInstance` の marketplace listing 対応は Wave 5refine では実装せず。
ItemInstance + ItemId 両方を listing できる `MarketplaceListing` 拡張は
Wave 6 inspect GUI と同じ wave で land 予定 (drop policy / escrow 設計の
方向性が出てから)。本 ADR は domain 基盤を完成させ、marketplace 側は
追って取り込む形に分割。

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
