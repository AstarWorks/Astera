# Monster Hunter (World / Rise / Wilds)

> 出典確認: 2026-05-21

## 概要

Monster Hunter は Capcom の hunting action series で、World (2018) で
worldwide 累計 2500 万本超、Rise (2021) + Sunbreak で 1500 万本超、最新の
Wilds (2025) と続く長寿 franchise。1 つのモンスター素材を周回 farm して
装備を作る → 上位モンスターに挑む → 素材を集める、という **討伐 → 解体 →
装備生成** のループを 20 年以上更新し続けている。

retention 設計の核心は (1) **Charm 厳選** (skill 組合せの個別 RNG drop)、
(2) **Decoration 厳選** (装飾品の skill RNG)、(3) **Crown** (個体 size 厳選)、
(4) **Layered Armor** (性能と見た目の分離) の 4 軸。**Astera 厳選設計の最重要
reference**: PvE-only / 周回 farm / RNG / completionist 承認欲求が Astera と
最も近い design space を共有する。

## なぜ長寿か (retention 5 軸へのマッピング)

### 1. 多軸 progression

**Hunter Rank (HR)** → **Master Rank (MR)** の rank cap が DLC で段階解放
(World HR 100+, Iceborne MR 100+, Rise HR 100+, Sunbreak MR 100+, Wilds 進行中)。
別軸として **武器熟練度** (Rise の Switch Skill / Wilds の Weapon Mastery)、
**装飾品 stock**、**料理スキル**、**護石厳選 progress** が並走。

### 2. 厳選レイヤー乗算

- **Charm 厳選**: skill 1-3 種 × Lv 1-5 × slot 0-3 の組合せが完全 RNG
- **Decoration 厳選**: melding pot で skill 装飾品を抽選 (Tailraider Safari /
  Awakened Melding / Qurio crafting で派生)
- **Talisman 厳選** (Rise): Melding Pot Rare 7 で 1/数百 - 1/数千 で god roll
- **Augment 厳選** (Iceborne): 装備毎の augment slot を RNG で抽選 (Streamstones)
- **Qurious Crafting** (Sunbreak): 既存装備の skill / slot を再 roll、cost は
  Anomaly material

軸が **乗算的** (charm × deco × augment × armor melding × weapon decoration) に
重なるのが MH の特徴で、Astera 厳選設計の構造的 reference になる。

### 3. 承認欲求の可視化

- **Layered Armor**: 性能 armor と見た目 armor の **完全分離**。Layered set 1
  揃え = 1 dressup outfit、Coral Orchestra / Anjanath α / 全 special quest 衣装
  等が独立 collection 軸
- **Crown** (王冠): モンスター個体 size record、Mini (小金冠) / Silver (銀冠) /
  Gold (金冠) の証明、**全モンスター金冠** で **Crown Master 称号**
- **Guild Card**: 称号 / 表彰 / 討伐数 / プレイ時間が他プレイヤーに見える card
- **Argosy / Tailraider** 等の支援系 progression
- **Trophy / Steam achievement**: discoverable 完了度

### 4. 限定時間 cadence

- **Event Quest** (週次 rotation, World 200+, Rise 100+ 種類)
- **Seasonal Festivals** (Winter Star / Spring Blossom / Summer Twilight /
  Autumn Harvest, 各 2 週間)
- **Collaboration quest** (Devil May Cry / Witcher / Final Fantasy XIV /
  Street Fighter 等の crossover)
- **Title Update** (無料大型 update, MR モンスター追加 cadence は 1-2 ヶ月毎)

### 5. プレイヤー駆動経済

- **MH には player-to-player trade なし** (Zenny は NPC 経済のみ)
- 代わりに **multiplayer co-op** が経済役割を担う (友人の周回に乗る ≒
  経済的相互作用)
- **SOS Flare** (世界中の野良に救援要請) は co-op 流動性の核

→ Astera 適用上、**経済は OSRS / Warframe を参照**、**厳選 + 承認欲求は MH を
参照** という分担が妥当。

## システム詳細 (core mechanic 深掘り)

### Charm (護石) 厳選

| 作品 | 仕様 | Drop source |
|---|---|---|
| World | Charm Lv 1-3 tier、固定 skill (Attack Charm III 等) — RNG なし | Quest 報酬 / 採取 |
| Iceborne | Awakening Charm 系で部分 RNG 化 | MR Quest 報酬 |
| **Rise** | **Talisman** で完全 RNG: skill 1-2 種 × Lv 1-5 + slot 0-3 個 × slot Lv 1-3 | **Melding Pot** (素材 cost で抽選) |
| **Sunbreak** | Melding Pot **Rare 7** で god roll 排出可能 | Anomaly Investigation 素材 |
| Wilds | Charm 系統 (Rise type の RNG melding が継承される見込み) | 進行中 |

**god roll 期待値** (Rise Talisman Rare 7):
- 2 skill (Attack 7 + Crit Boost 3 等の特定組合せ) × 3-3-3 slot
- 1/1000 - 1/数千 帯 (組合せ depth 次第)、コミュニティが期待値 sheet を作る

これが Astera 厳選 RNG 設計の **最重要 reference**。skill 組合せ × Lv × slot
の三次元 RNG は Astera Refinement / Affix 設計に直接転用可能。

### Decoration (装飾品) 厳選

- World/IB: Decoration が **drop only** (melding 限定 + Streamstone melding)、
  人気 skill (Attack Jewel 4 等) は 1/100+ オーダー
- Rise/SB: melding pot で **狙い撃ち抽選** 可能、cost で確率調整
- 各装備の slot に hammer in する、複合運用

### Crown (王冠 = 個体 size 厳選)

| 種類 | 確率 | 取得条件 |
|---|---|---|
| Mini Crown (小金冠) | ~1% (Tempered では確率上昇) | 該当モンスターを規定サイズ以下で討伐/捕獲 |
| Silver Crown (銀冠) | ~5-10% | 該当サイズ範囲内 |
| Gold Crown (大金冠) | **~0.5-1%** | 規定サイズ以上で討伐/捕獲 |

- 各モンスターで Mini + Gold 両方取得が必要
- **全モンスター金冠コンプリート** = **Crown Master trophy** (PS4/PS5 platinum
  の難関)
- 性能影響なし、純粋な collectionist 承認欲求

### Layered Armor

- **性能装備** と **見た目装備** が完全分離
- Layered set を 5 部位 (頭 / 胴 / 腕 / 腰 / 脚) で組み合わせ自由
- DLC / event quest / collaboration / endgame challenge で限定 Layered 排出
- "見た目だけのために MR 100 endgame quest を周回する" 行動が成立 = 性能
  関係ないが「見せる」目的だけで grind を回す surface

## Astera 適用候補 (流用 / 模倣 / 拒否 の 3 段階)

| 要素 | 判断 | 理由 |
|---|---|---|
| **Charm 厳選 (skill 1-2 種 × Lv × slot)** | **流用 (最優先)** | Astera Refinement / Affix 設計の base に直接採用。skill 組合せ × Lv × slot の 3 次元 RNG は学習可能な複雑度 + god roll 体験の良さが実証済み。 |
| Melding Pot (素材 cost で抽選) | 流用 | 「素材を投入して RNG 抽選」は Astera 内 economy sink として優秀。cost で確率調整できる knob を残しておく。 |
| Crown (個体 size 厳選) | **流用** | Astera で **mob 個体差 (size / variant / shiny)** を厳選軸として導入する reference。性能影響なし、純粋 collectionist surface はコスパ高い。 |
| 全 Crown コンプ = Crown Master 称号 | 流用 | 軸を全部埋めた時の称号は OSRS Cape と同型の "見える grind 証明"。 |
| **Layered Armor (性能と見た目の完全分離)** | **流用 (最優先)** | Warframe Fashion Frame と同方向、MH では「同じ性能 armor を着続けても見た目だけ変える」が成立。Astera `items` plugin で **performance ItemSpec ≠ cosmetic ItemSpec** の設計を最初から分離するべき。 |
| Decoration 厳選 (装飾品 skill RNG) | 模倣 | Astera では Charm 厳選で十分、Decoration は重複感が出る。**Charm + 1 軸** に留めるべき (MH は 4-5 軸あるが学習コスト高い)。 |
| Qurious Crafting (既存装備 re-roll) | 模倣 (Phase 5+) | "既に持っている装備を再 roll で進化させる" は厳選を二重化する優秀 design だが、Phase 1 PoC では Charm 厳選で十分。 |
| Augment 厳選 (装備毎の augment slot RNG) | 拒否 (現段階) | 軸を増やしすぎると新規プレイヤーが脱落する。MH 自身が「軸が多すぎて新規が辛い」と community で議論される。 |
| Guild Card (称号 / 表彰 / 討伐数) | 流用 | Astera player profile の base 設計。各 progression 軸 / Crown / Layered 揃え / boss 討伐数を 1 枚の card に統合し他プレイヤーに見せる surface。 |
| Event Quest (週次 rotation 限定) | 流用 | OSRS Leagues / Warframe Nightwave と同型の seasonal cadence。週次 quest rotation は Astera early-phase でも実装容易。 |
| Player-to-player trade なし | 拒否 | Astera は `economy` plugin で player-driven 経済を作る方向 (OSRS GE 参照)。MH の closed economy 設計は採用しない。 |
| SOS Flare (世界中の野良 co-op 救援) | 模倣 | Astera multiplayer matching の reference。stage-based co-op の流動性を上げる仕組みとして直接転用可能。 |

## Source / References

- https://monsterhunter.fandom.com/wiki/Monster_Hunter_Wiki — series 全体 wiki
- https://monsterhunterworld.wiki.fextralife.com/Monster+Hunter+World+Wiki — MHW / Iceborne 詳細
- https://monsterhunterrise.wiki.fextralife.com/ — Rise / Sunbreak (Talisman / Melding / Qurious)
- https://monsterhunter.fandom.com/wiki/Crown — Crown 仕様 + 確率
- https://monsterhunter.fandom.com/wiki/Talisman_(MHRise) — Rise Talisman 厳選
- https://monsterhunter.fandom.com/wiki/Decoration — Decoration 厳選
- https://monsterhunter.fandom.com/wiki/Layered_Armor — Layered Armor 仕様
- https://www.capcom-games.com/en-asia/monster-hunter/ — Capcom 公式 series page
