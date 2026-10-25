# Competitive Analysis — 長寿ゲームの retention 設計

> 作成: 2026-05-21
> 目的: Astera roadmap への **PvE + 厳選 + 多軸 progression + 承認欲求 multi-surface** 追加提案のための競合リサーチ
> 政策: [[../adr/0013-rtm-divergence-policy]] §"Add" カテゴリ
> 参照計画: `/home/node/.claude/plans/hypixcel-skyblock-peppy-nygaard.md`

---

## なぜ書いたか

Astera 現状の roadmap (Wave 1-77、退役した旧 IMPLEMENTATION-ROADMAP。git 履歴参照) は前身 RTM
を ground truth として設計されているが、RTM 自体が **PvP arena match-only**
の設計だったため:

- PvE (mob / boss / dungeon / raid) **未計上**
- 厳選 (RNG roll / re-roll / gemstone) **未計上**
- 多軸 skill progression (HSB-style 12 skill 並走) **未計上**
- 承認欲求 multi-surface (HSB networth / Mythic glow 級) **未計上**

これらは **長寿ゲーム共通の retention 軸** であり、AI で content 量産する Astera
ほど **gameplay system 側の設計** が retention に直結する。本リサーチは Astera
roadmap に何を **Add** すべきかを根拠付ける。

ADR-0010 (Generative AI Phase 6) は content 生成を Phase 6 まで deferring するが、
本リサーチ doc 自体は **AI 量産可能** (調査 doc であって runtime asset でない)。

---

## 5 retention 力学 framework

長寿ゲーム横断で共通する 5 軸。Astera 適用判断のグリッド。

### 1. 多軸 progression

単一 "level" ではなく **複数の独立した進捗軸**。1 軸詰まっても他軸に逃げられる
ので burnout 出口がある。

| ゲーム | 軸数 | 軸の例 |
|---|---|---|
| OSRS | 23 | Attack / Strength / Mining / Fishing / ... |
| Hypixel Skyblock | 12 | Combat / Mining / Foraging / Dungeoneering / ... |
| 原神 / HSR | キャラ単位 | キャラ × Lv / 才能 / 武器 / 聖遺物 |
| PoE | 1 (Lv) + Atlas | Character Lv (1-100, 急減速) + Atlas Tree |
| Warframe | Mastery Rank + Frame Lv | MR + 各 frame Lv |
| Monster Hunter | Hunter Rank + Master Rank | HR + MR + 武器熟練 |

**Astera 現状**: 単一線形 (`PlayerStats` + `Level` + `LevelUpMission`)。
**Add 候補**: Wave _multi-skill で HSB 12-skill 並走に拡張。

### 2. 厳選レイヤー乗算

1 装備に対し **複数の独立な強化レイヤー** が乗ることで、組み合わせ空間が爆発し、
"完成形" が事実上到達不能になる。

| ゲーム | 厳選レイヤー | 完璧 roll 期待値 |
|---|---|---|
| HSB | base × reforge × 5 enchant × HPB × 5 gemstone × 5 star × dye × recomb | 数億パターン |
| 原神 | 5 部位 × main stat × 4 sub stat × roll up to 5 | 1 セット完璧 ≈ 1/数十万 |
| PoE | prefix 3 + suffix 3 (rare item) + currency craft | 1 完璧 item ≈ Mirror tier |
| Warframe | 10 slot + polarity + Riven (3 pos + 1 neg) + Riven re-roll | Riven roll 数百回 |
| MH | Charm (skill 2-3 + Lv 1-5 + slot 1-3) + Decoration | 神 charm 数千時間 |

**Astera 現状**: ADR-0073 で `rarity` + `AttributeModifier` までは inline 済 (土台のみ)。
**Add 候補**: Wave _grind-1 で `ItemInstance` + `Refinement` sealed (Reforge /
Enchant / Gemstone / Star / Recomb) を追加。

### 3. 承認欲求の可視化

**他人から見える形で** 投資が "資産化" される surface を多層に散らばらせる。
辞めると失う sunk cost。

| Surface | HSB 例 | 原神例 | OSRS 例 | Astera 既存 | Astera Add 候補 |
|---|---|---|---|---|---|
| 装備自体の glow | Mythic rarity glow / Recomb | — | — | ❌ | Item rarity glow (Wave _flex) |
| Cape / Helmet skin | Hypixel Cape | キャラ衣装 (Skin) | Cape of Accomplishment | Wave 8e Cosmetics ✅ | (済) |
| 数値 ID | Networth / SkyBlock Lv | 戦闘力 / Achievement | Total Lv | ❌ | Power Index aggregate (Wave _flex) |
| Title | Combat XX Player | 名刺 Title | Quest Cape 称号 | Wave 5e Title ✅ | (済) |
| Public Profile | sky.shiiyu.moe | キャラ詳細画面 | hiscore.runescape.com | ❌ | `/api/players/<id>/profile` (Wave _flex) |
| Match nameplate badge | Bingo profile icon | — | — | ❌ | tier-aware nameplate (Wave _flex) |
| Leaderboard tier icon | Bedwars Prestige | Spiral Abyss / MoC | hiscore tier | Wave 11d 称号 + Wave 7b Leaderboard ✅ | integration (Wave _flex) |

### 4. 限定時間 cadence

login 動機を **複数 timescale** で持つ。

| Timescale | HSB | 原神 | OSRS | Astera 既存 | Astera Add 候補 |
|---|---|---|---|---|---|
| Daily reset | Bazaar daily / Jacob's Contest 3x/日 | 樹脂 (180 / 12h) / Daily Commission | Daily PvM tasks | Wave 5e Norma ✅ | (済) |
| Weekly reset | Catacombs weekly chest | Weekly boss (Trounce Domain) | Bounty Hunter reset | Wave 5e Mission ✅ | (済) |
| Bi-monthly | Mayor Election (6 日) | — | — | ❌ | Wave 9c Seasonal event 拡張 |
| Seasonal | Bingo, Easter, Spooky | 期間限定 banner (gacha) / 期間限定 event | Seasonal D&D | Wave 9c Seasonal event ✅ | (済) |
| League / Reset cycle | — | — | DMM / Leagues | Wave 7b Season ✅ | (済) |

### 5. プレイヤー駆動経済

経済自体が **独立メタゲーム** になる。flipping / market / trade だけで遊ぶ層が
存在することで、戦闘苦手層も engagement に取り込める。

| ゲーム | 経済 surface | 特徴 |
|---|---|---|
| HSB | Bazaar (即時) + AH (出品) + Bit Shop | flipping 専業層 |
| PoE | trade site + bulk exchange + currency tier | currency = item |
| OSRS | Grand Exchange | 4h buy limit per item |
| Warframe | warframe.market (external) + in-game trade | Platinum (paid 通貨) tradable |
| MH | (経済軸薄い、co-op 中心) | — |

**Astera 現状**: Wave 5f Marketplace (RTM #12 由来) + Wave 5g Shop access surfaces 計上済。
**Add 候補**: Wave _grind-1 ItemInstance を marketplace に乗せる (refined item の
flipping を成立させる)。

---

## ファイル index

| File | テーマ | 状態 |
|---|---|---|
| [00-overview.md](00-overview.md) | 本ファイル — 5 retention 軸 framework + index | ✅ |
| [01-hypixel-skyblock.md](01-hypixel-skyblock.md) | HSB 主軸 — PvE 武器共用 + 厳選乗算 + Networth | 📋 |
| [02-genshin-hsr.md](02-genshin-hsr.md) | 原神 / HSR — 聖遺物 RNG 厳選 + Gacha pity + 戦闘力 | 📋 |
| [03-path-of-exile.md](03-path-of-exile.md) | PoE — currency craft + league cycle + atlas | 📋 |
| [04-osrs.md](04-osrs.md) | OSRS — 23 skill 並走 + boss rare drop + 99 cape 文化 | 📋 |
| [05-warframe.md](05-warframe.md) | Warframe — Riven re-roll + Mastery Rank + Fashion Frame | 📋 |
| [06-monster-hunter.md](06-monster-hunter.md) | MH — Charm 厳選 + Layered Armor + Crown | 📋 |
| [07-cross-cutting.md](07-cross-cutting.md) | 横断パターン抽出 → Astera 適用候補マトリクス | 📋 |

---

## Astera への提案サマリ (詳細は 07-cross-cutting.md)

7 つの新規 Wave を ADR-0013 §"Add" カテゴリで roadmap に追加:

| Wave | テーマ | ADR # |
|---|---|---|
| **_pve-1** | PvE Mob / Boss Foundation (武器 PvP/PvE 共用基盤) | 0080 |
| **_pve-2** | PvE Content (Encounter zones + Drop tables + Boss mechanic) | 0081 |
| **_grind-1** | Item Refinement Layer (gemstone / reforge / sub-stat roll) | 0082 |
| **_multi-skill** | 多軸 skill progression (HSB 12-skill 並走) | 0083 |
| **_flex** | 承認欲求 multi-surface (networth / glow / nameplate / profile) | 0084 |
| **_bet-1** | 1v1 Betting Match (RTM Writerside 由来、ADR-0029 backlog 具体化) | 0085 |
| **_analytics-1** | Player Engagement Dashboard (per-player view) | 0086 |

詳細な配置 / 依存 / scope は各 ADR と Board を参照 (旧 IMPLEMENTATION-ROADMAP は退役、git 履歴)。

---

## 政策上の境界

- 本リサーチは **gameplay system 設計** のみを扱う。content 量産 (texture /
  model / sound) は ADR-0010 通り Phase 6 (Wave 10a-f) 保留。
- Phase 1 PoC closing (Wave 5a-f → 6a-b) は **変更しない**。新規 Wave はすべて
  Phase 4+ への追加で、PoC ship 順序を遅らせない。
- ADR-0013 (RTM divergence policy) の supersede は不要。本提案は同 ADR の
  "Add" カテゴリに正規に収まる。
- HSB / 原神 / PoE / OSRS / Warframe / MH のソースコードは clone しない (HSB
  server / 原神 / HSR は closed-source)。公式 wiki / patch notes / dev blog の
  public 情報のみ source とする。
