# Cross-cutting patterns — 6 ゲーム横断分析 → Astera 適用候補マトリクス

> 作成: 2026-05-21
> 入力: [01-hypixel-skyblock.md](01-hypixel-skyblock.md) / [02-genshin-hsr.md](02-genshin-hsr.md)
> / [03-path-of-exile.md](03-path-of-exile.md) / [04-osrs.md](04-osrs.md)
> / [05-warframe.md](05-warframe.md) / [06-monster-hunter.md](06-monster-hunter.md)
> 出力: Phase 8 PvE Retention (Wave 12a-g, ADR 0080-0086) の設計根拠

長寿ゲーム 6 タイトルの retention デザインから、Astera に **流用 / 模倣 / 拒否**
すべき pattern を抽出する。各 pattern は単一ゲームではなく **複数ゲーム横断で
証明された** 設計に絞る (単発の特殊事例は除外)。

---

## 1. 厳選システムの数学的構造

長寿ゲームすべてに共通する **"完成形に到達不能"** の仕組み。Astera Wave 12c
(Item Refinement Layer, ADR-0082) の設計基盤。

### 1.1 厳選の 4 構造類型

| 構造 | 採用ゲーム | 退屈にならない理由 | Astera 適用 |
|---|---|---|---|
| **乗算 layer** | HSB (8 layer), 原神 (5 部位 × stat), MH (charm × decoration) | 各 layer の確率掛け算で組合せ空間が爆発、99 点が常に出る | ✅ **流用** — `ItemInstance` に複数 layer (refinement: gemstone + reforge + sub_stat_rolls + upgrade) を ADR-0073 ItemYamlConfig に inline |
| **RNG roll + 上限** | 原神聖遺物 (sub stat × 5 roll), Warframe Riven (3+1 affix) | 完璧 roll の確率分布右端を引くゲーム化 | ✅ **流用** — `RerollSubStatsUseCase` で sub stat 値 range + roll 回数上限 |
| **削って戻れない craft** | PoE Chaos Orb, MH Charm 上書き | sunk cost で次の roll を促す | ⚠️ **慎重採用** — Warframe Riven の "上書き型 re-roll" は 9 年間 community に嫌われた負債。**Astera は swap 可能 / 過去 roll キープ** を default にする (Warframe 05 の learnings) |
| **時間軸の dilution** | OSRS rare drop 1/3000, Warframe Riven cooldown | 1 セッションで完結しない、login 動機継続 | ✅ **流用 (緩めに)** — Astera 規模 (population 数十-数百) では OSRS 1/34025 級は許容不可。**1/100〜1/2000 帯** から開始 (04-osrs §population スケーリング論) |

### 1.2 Resource sink design (re-roll コスト)

re-roll するための currency は **escalating cost** + **resource diversity** の
両方を持つことで grind 強度が安定する。

| ゲーム | re-roll コスト構造 | Astera 適用 |
|---|---|---|
| Warframe Riven | 900 → 1000 → 2000 → 2500 → 3000 → 3500 Kuva (cap) | ✅ **escalation 流用** (4-5 step で plateau) |
| 原神聖遺物 strongbox | 3 個 → 1 個 (1/3 resource sink) | ✅ **bundling 流用** (低 rarity item 3個 → 1個 上 rarity) |
| PoE Divine Orb | 1 個 / re-roll、Divine 自体が市場価値持つ | ✅ **市場連動流用** — Wave 5f Marketplace で refinement currency も tradable |
| HSB Hot Potato Books | 線形 cost (10 HPB 上限) | ❌ 拒否 (escalation がないので grind が浅い) |

### 1.3 ItemYamlConfig 拡張案 (Wave 12c で確定)

```yaml
# content/items/fire-sword.yaml (ADR-0073 拡張)
id: fire-sword
material: NETHERITE_SWORD
rarity: LEGENDARY
# ... 既存 fields ...
refinement:
  enabled: true
  gemstone_slots: 3                   # HSB 風
  reforge_pool: [sharp, fierce, demonic]  # HSB reforge stone 風
  upgradeable_to: 10                  # HSB Star 風 (1-10)
  sub_stat_rolls:                     # 原神聖遺物 風
    count: 4                          # 4 sub stat 持てる
    max_rolls_per_stat: 5             # 各 stat 最大 5 回 roll
    candidate_stats: [attack, crit_chance, crit_dmg, defense]
```

---

## 2. 多軸 progression の設計

単一線形 progression は **詰まり点** ができる (combat 苦手層に逃げ場がない)。
長寿ゲーム全て多軸採用。Astera Wave 12d (`SkillKind` sealed, ADR-0083) の設計基盤。

### 2.1 軸数の比較

| ゲーム | 軸数 | Lv99 / max XP | 完了率 (1+ skill max) |
|---|---|---|---|
| OSRS | 23 | 13M XP / skill | 不明 (overall < 1%) |
| Hypixel SkyBlock | 12 | 55M XP (Combat 60) | 数% (Combat 60+ は誇示対象) |
| PoE | 1 (Lv) + Atlas | Lv 100 = 4.25B XP | < 0.1% |
| Genshin/HSR | キャラ単位 | Lv 90 + 才能 + 聖遺物 | キャラ完凸は monetize 軸 |
| Warframe | MR + Frame Lv | MR 30 = 1000h+ | < 5% |
| Monster Hunter | HR + MR + 武器熟練 | 不明 | gear-progression baseline |

### 2.2 Astera 軸数の設計判断

**OSRS の 23 軸は多すぎる** (HR 500h+ のプレイヤーでも未達 skill が多数)。
**HSB の 12 軸が sweet spot** (各軸の独立性 + 全達成への現実的 path)。

Astera 初期は **10 軸** で開始 ([04-osrs.md] §"5-8 軸並走" 推奨より少し多め):

| Skill | XP source | 既存 plugin 連携 |
|---|---|---|
| Combat | PvP DamageDealt, Match win | `plugin/combat` |
| BossSlayer | Mob/Boss kill (Wave 12a/12b) | Wave 12a 連携 |
| Mining | BlockBroken (Wave 5d 連携) | `plugin/match`/`plugin/combat` |
| Fishing | (新規 event) | (新規) |
| Farming | (新規 event) | (新規) |
| Refining | `ItemRefined` event (Wave 12c) | Wave 12c 連携 |
| Trading | Marketplace 取引額 (Wave 5f) | `plugin/economy` |
| Exploration | EncounterZone 踏破 (Wave 12b) | Wave 12b 連携 |
| Tournament | Wave 7b 試合数 | `plugin/match` |
| Crafting | 後回し可、Phase 9 候補 | (新規) |

### 2.3 Perk tree (HSB 風)

各 skill には XP threshold で unlock される perk YAML を 1 ファイル添付:

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
  # ... up to level 100
```

`AwardSkillXpUseCase` で level up 時に perk activate。

---

## 3. 承認欲求 multi-surface の layer 設計

長寿ゲーム共通: **見える資産** を多層に散らばらせる。1 surface (例: Cape のみ)
だと飽きるが、5-7 surface に散らばっていると "次の surface" がある。Astera
Wave 12e (`_flex`, ADR-0084) の設計基盤。

### 3.1 7 layer matrix (詳細)

| Layer | HSB | 原神 | OSRS | PoE | Warframe | MH | Astera 現状 | Astera Add 候補 |
|---|---|---|---|---|---|---|---|---|
| 装備自体の glow | Mythic glow + Recomb 効果 | — | — | Mirror tier shimmer | — | — | ❌ | **Wave 12e**: `IItemRenderer` で rarity / refinement tier に応じた particle |
| Cape / Helmet skin | Hypixel Cape | キャラ衣装 | Cape of Accomplishment | — | Syandana | Layered Armor | Wave 8e ✅ | (済) |
| 数値 ID | Networth / SkyBlock Lv | 戦闘力 / CV | Total Lv 1-2277 | Lv 100 % | MR 0-30+ | HR + MR | ❌ | **Wave 12e**: Power Index aggregate |
| Title | Combat XX | 名刺 Title | Quest Cape 称号 | Challenge title | Founder | Hunter title | Wave 5e ✅ | (済) |
| Public Profile | sky.shiiyu.moe | キャラ詳細画面 | hiscore | poe.ninja profile | warframe.market | Guild Card | ❌ | **Wave 12e**: `/api/players/<id>/profile` |
| Match nameplate badge | Bingo icon | — | — | — | — | — | ❌ | **Wave 12e**: tier-aware nameplate (Wave 5b `ITabList` 拡張) |
| Leaderboard tier icon | Bedwars Prestige | Spiral Abyss / MoC tier | Hiscore tier | Race tier | — | Crown collection rank | Wave 11d ✅ | (済、Wave 12e で integrate) |

### 3.2 流用判断 (Fashion Frame 原則)

[05-warframe.md] が抽出した **"性能と完全独立な cosmetic"** は承認欲求 surface
として最もコスパが高い。Astera 既存 plan の Wave 8e Cosmetics と整合。

- ✅ **流用**: Fashion Frame slogan ("Fashion Frame is endgame") は性能 ItemSpec
  と cosmetic ItemSpec の **完全分離** に依拠。Astera は `plugin/items` で最初から
  この分離を確立済 (ADR-0073)。
- ✅ **流用**: MH Layered Armor の "性能装備 / 見た目装備" 切替も同パターン。
- ✅ **流用 (Wave 12e)**: HSB Networth 計算ロジック (装備総額 + 倉庫 + Bank +
  Bazaar listing) を Astera Power Index に直訳。

---

## 4. 限定時間 cadence の 3 重設計

長寿ゲーム共通: **daily + weekly + seasonal** の 3 重で login 動機を持つ。

| Timescale | HSB | 原神 | OSRS | Astera 現状 | Astera Add |
|---|---|---|---|---|---|
| Daily | Jacob's Contest 3x/日 | 樹脂 180/12h + Daily Commission | Daily PvM tasks | Wave 5e Norma ✅ | (済) |
| Weekly | Catacombs weekly chest | Trounce Domain | Bounty reset | Wave 5e Mission ✅ | (済) |
| Bi-monthly | Mayor Election 6 日 | — | — | ❌ | Wave 9c Seasonal event 拡張 |
| Seasonal | Bingo / Spooky | 期間限定 banner | DMM / Leagues | Wave 9c ✅ | (済) |
| League / Reset | — | — | OSRS Leagues 3ヶ月 | Wave 7b Season ✅ | (済) |

→ Astera は 4/5 timescale カバー済。**Mayor Election 風 bi-monthly のみ未計上**
だが、Wave 9c (Seasonal events automation) で吸収可能。**新規 Wave 不要**。

---

## 5. PvE 武器共用設計 (User 最大関心軸)

User の戦略「PvP 武器をそのまま PvE に流用」は **HSB の最大の発明**。HSB は
PvE 武器一本で **player vs mob / mob vs player / dungeon boss / slayer boss /
crimson kuudra raid** すべてカバー。武器の "PvE 性能" と "PvP 性能" を分離
しないことで content lifecycle が乗算される。

### 5.1 武器共用を成立させる前提

| 前提 | HSB | Astera 既存対応 | Astera Add 必要 |
|---|---|---|---|
| Mob domain が player と同じ damage path | `EntityDamageEvent` 統一 | ADR-0058 `IAttributeContainer` ✅ | **Wave 12a**: `Mob` domain に `IAttributeContainer` を持たせる |
| Mob HP / status effect / element も player と同型 | StatusEffect 共通 | Wave 5a StatusEffect ✅ | Wave 12a で `Mob` を `IStatusEffectContainer` 適用対象に |
| Drop table が weapon に紐付かない (weapon は universal) | weapon は drop に influence しない (例外: Fortune Ammo) | (PvE 未着手) | **Wave 12b**: drop table は `EncounterZone` + `Mob` に紐付く、weapon 非依存 |
| Damage attribute matrix (element) が PvE にも適用 | Combat 計算式は共通 | ADR-0030 element matrix ✅ | (既存で対応可) |

### 5.2 PvE と PvP の差分 (HSB 流)

- **PvE**: damage 値そのまま、headshot multiplier あり、element matrix 適用
- **PvP**: damage reduction ~70-90% (HSB Bedwars 等の PvP モード)、status 効果は弱化、healing は弱化

Astera は ADR-0030 で **vanilla damage event hijack** を採択済。Wave 12a で
`MatchKind` (PvP / PvE) parameter を `ApplyDamageUseCase` に渡し、damage formula
内で reduction modifier を切替。**新規 port 不要**。

---

## 6. プレイヤー駆動経済 (Marketplace の retention 寄与)

長寿ゲーム共通: 経済自体が **独立メタゲーム** になることで戦闘苦手層も engaged。

| ゲーム | 経済 surface | Astera 適用 |
|---|---|---|
| HSB Bazaar (即時) + AH (出品) | Wave 5f Marketplace ✅ | (済) |
| PoE Trade site (out-of-game) | Wave 8b REST 拡張 | **Wave 12e の `/api/players/<id>/profile`** + Wave 8a Web admin で out-of-game 取引 view |
| OSRS Grand Exchange (4h buy limit) | Wave 5f Marketplace | rate limit は ADR-0035 で議論余地 |
| Warframe Platinum (paid currency tradable) | ADR-0035 JP currency compliance | (Phase 6 monetization 待ち) |

→ **Wave 5f Marketplace は十分**。Wave 12c Refinement layer の ItemInstance を
**marketplace listing 可能にする** ことが Phase 8 で重要 (refined item の取引が
経済を厚くする)。

---

## 7. 1v1 Betting (RTM Writerside backlog 具体化)

RTM `コンテンツ案一覧.md` 由来。ADR-0029 で Phase 4+ backlog 化されていたが、
具体 Wave 未割当だったものを **Wave 12f** で具体化。

### 7.1 他ゲーム参照

- **OSRS Stake (Duel Arena)** — historic, 2007 discontinued due to RWT 問題、
  2024 Bounty Hunter Worlds で類似機能を復活。**重要な教訓**: 賭け content は
  RWT / scam の **moderation 負債** が大きい。
- **PoE Race** — 期間限定 leaderboard、stake は不可、報酬は cosmetic のみ。

### 7.2 Astera 設計判断

- ✅ **流用**: 1v1 Betting は **ゲーム内通貨のみ**、real money 換金不可 (Wave 5f
  JP CurrencyPolicy 整合: 6ヶ月 expiry / ¥10M cap / 非換金)
- ✅ **流用**: ELO 影響は **別 ladder** にする (ranked queue Wave 7b と Betting
  Match を混在させない、cheating incentive 低減)
- ✅ **流用**: `BettingPolicy` (cap per match / cool-down / loss-streak limit) で
  compulsion 対策 (依存症 / 過剰 betting への規制 — JP 風営法的考慮)
- 🟨 **慎重**: moderation 負担を考え、Phase 8 後半 (Wave 12f) で導入、Wave 8f
  (Moderation tooling) が稼働後

---

## 8. プレイヤー分析基盤 (User 要求)

User の「プレイヤー分析基盤がある程度ほしい (バランス調整的に)」を Wave 12g
(ADR-0086) で実現。

### 8.1 既存 plan との連携

- Wave 7c — Balance analytics events (DamageDealt aggregation → balance.csv) ✅
- Wave _ops-4 — Micrometer + Prometheus exporter ✅
- Wave _ops-5 — Structured JSON logs (ECS) ✅
- Wave _ops-6 — Grafana dashboards ✅
- Wave 8b — REST API + WebSocket events ✅

Wave 12g はこれらの **横断 view** + **per-player drill-down** + **engagement
metric 専用 dashboard** を追加する位置づけ。

### 8.2 retention metric の標準セット (industry 参考)

- **D1 / D7 / D30 retention** — installer cohort analysis
- **Stickiness (DAU / MAU)** — > 20% で healthy
- **Time-to-second-session** — onboarding 質の指標
- **Per-skill engagement** — 多軸 skill の偏り検出 (e.g., Combat だけ盛り上がり、
  Fishing 過疎なら balance 問題)
- **Refinement attempt rate per player** — 厳選 grind の engagement 強度
- **Cosmetic equip diversity** — 承認欲求 surface の充足度

---

## 9. Phase 8 (Wave 12a-g) 全体 sequencing

依存関係を considering した実行順序:

```
Wave 12a (PvE Foundation, ADR-0080)
    ↓ (Mob domain + IMobSpawnService)
Wave 12b (PvE Content, ADR-0081)        ← critical path
    │
    ├─ Wave 12c (Item Refinement, ADR-0082)   [Wave 5f Marketplace 後並列可]
    ├─ Wave 12d (多軸 skill, ADR-0083)        [Wave 8e と並列可]
    ├─ Wave 12e (承認欲求, ADR-0084)          [Wave 8 系と並列可]
    └─ Wave 12f (1v1 Betting, ADR-0085)       [Wave 7b 後 + Wave 8f 後並列可]
            ↓
        Wave 12g (Analytics, ADR-0086)        [Wave 7c + 8b + _ops-4 後並列可]
```

最早着手: **Wave 7c 完了後** (Phase 3 Siege 完成、~85% RTM parity 達成後)。
**Wave 12a + 12b は critical path**、それ以外は Phase 4-5 並列可。

---

## 10. 適用候補マトリクス (集約版)

| 元ゲーム | 流用元素 | Astera Wave |
|---|---|---|
| HSB | 12-skill 並走 (10 軸化) | Wave 12d |
| HSB | 武器 PvE/PvP 共用 (Mob = IAttributeContainer) | Wave 12a |
| HSB | Slayer Tier 1-5 個別 boss farm | Wave 12b |
| HSB | Networth 計算 (見える資産) | Wave 12e |
| HSB | 8 layer 乗算厳選 | Wave 12c |
| 原神 | 聖遺物 sub stat × roll up to 5 | Wave 12c |
| 原神 | 戦闘力 / CV 可視化 | Wave 12e |
| PoE | Currency-driven craft (Divine / Chaos) | Wave 12c |
| PoE | League 3ヶ月 reset cycle | Wave 7b Season (済) |
| OSRS | 23 → 10 軸 (sweet spot) | Wave 12d |
| OSRS | Boss rare drop 1/100〜1/2000 帯 (population スケール) | Wave 12b |
| OSRS | Cape of Accomplishment (見える grind 証明) | Wave 12e |
| Warframe | Riven cost-escalation (900→3500) | Wave 12c |
| Warframe | **Swap 可能 re-roll** (Warframe の負債からの学び) | Wave 12c (拒否事例) |
| Warframe | Fashion Frame (性能 ≠ cosmetic 完全分離) | Wave 8e ✅ + Wave 12e |
| Warframe | Mastery Rank (統合 progression surface) | Wave 12d / 12e |
| MH | Charm 厳選 (skill × Lv × slot 3 次元 RNG) | Wave 12c |
| MH | Layered Armor (performance ≠ cosmetic 分離) | Wave 8e ✅ |
| MH | Crown (個体サイズ厳選 — RNG mob variation) | Wave 12b 候補 |

---

## 11. 拒否 / 慎重採用 リスト

| 元ゲーム | 拒否元素 | 理由 |
|---|---|---|
| Warframe | "削って戻れない" Riven re-roll | 9 年間 community 嫌悪、Astera は swap 可能を default に |
| Warframe | Disposition (人気 weapon penalty) | Astera 規模に複雑性過剰 |
| Warframe | Riven trade external market | Wave 5f 内部 GE で十分 |
| OSRS | 1/34025 級の超 rare drop | Astera population スケール不適合 |
| OSRS | RWT (Real World Trading) を許容する Bond 系 | JP currency compliance + monetization 設計と整合不可 |
| HSB | Pet rarity multi-stage (Common → Mythic 上昇) | ADR-0073 Item rarity 設計と重複、複雑性過剰 |
| 原神 | Constellation (同キャラ重複 power scaling) | 単一キャラ多重保有 = monetize 専用、Astera は character-less |
| 原神 | 90 連 hard pity | Wave 8e で 50% pull-up が決定済、別アルゴリズム不要 |
| PoE | Atlas passive tree 完全 | Astera 規模に複雑性過剰、Phase 12+ で再評価 |
| MH | Master Rank ↔ Hunter Rank の重複構造 | Astera は単一 player level + 多軸 skill で代替 |

---

## 結論

Phase 8 (Wave 12a-g) は 6 タイトル横断分析から導出された **以下の 7 軸** を
ADR-0013 §Add カテゴリで Astera に追加:

1. **Wave 12a + 12b** — PvP 武器を流用する PvE foundation + content
   (HSB Slayer / 原神 World Boss / OSRS PvM)
2. **Wave 12c** — Item Refinement (HSB 8-layer × 原神 sub stat × MH charm)
3. **Wave 12d** — 多軸 skill progression (HSB 12-skill / OSRS 23-skill の 10 軸版)
4. **Wave 12e** — 承認欲求 multi-surface (Fashion Frame 原則 + HSB Networth)
5. **Wave 12f** — 1v1 Betting (RTM `コンテンツ案一覧.md` backlog 具体化)
6. **Wave 12g** — Player Engagement Dashboard (data-driven balance + retention metric)

最早着手: Wave 7c 完了 (Phase 3 Siege 完成) 後。**Phase 1 PoC ship 順序は変更
しない**。
