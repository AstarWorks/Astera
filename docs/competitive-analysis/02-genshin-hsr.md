# Genshin Impact / Honkai: Star Rail (HoYoverse Gacha PvE)

> 出典確認: 2026-05-21

## 概要

HoYoverse の Genshin Impact (2020-09) と Honkai: Star Rail (2023-04) は、ともに
**gacha + 聖遺物 (Artifact / Relic) RNG 厳選 + キャラ凸**を 3 本柱とする PvE
gacha RPG。年間売上 1B USD オーダー (Genshin 2021 ピーク時 ≈ 3-4B USD)、累計
プレイヤー 数千万。**厳選 RNG の数学的構造**と**承認欲求の可視化** (キャラ詳細
スコア / 名刺 / Achievement) が Astera の厳選レイヤー設計の最大の reference。

PvP は両者とも事実上存在しない (Genshin の "敵討伐タイムアタック" Abyss、HSR の
"Memory of Chaos / Pure Fiction / Apocalyptic Shadow" は score 競技だが対人なし)。
**「対 RNG」「対自分の damage 数」**が retention の核心。

## なぜ長寿か (retention 5 軸へのマッピング)

### 1. 多軸 progression

- **冒険ランク / 開拓レベル**: 全コンテンツ unlock ゲート (Genshin AR60, HSR
  Trailblaze Level 70)
- **キャラレベル**: 1-90 (5 段階の「突破」素材ゲート)
- **武器レベル**: 1-90 (同様)
- **天賦 (Talent) レベル**: 各キャラ 3 天賦 × Lv1-10 (10 で素材は週ボス drop)
- **聖遺物 / 遺物レベル**: +0 - +20 (4 段階 sub stat roll)
- **凸 (Constellation/Eidolon)**: 0 - 6 凸
- **命の星座 / 軌跡ツリー**: HSR は passive ツリー
- **冒険 / 任務 / 探索 progression**: マップごとに地霊探索 100%、宝箱、地脈

これらが**全部並列**で、daily Resin (Genshin: 240/日)・Trailblaze Power (HSR:
240/日) で消費先を選ぶ意思決定が日課化。

### 2. 厳選レイヤー乗算

下記「聖遺物 sub stat 厳選数学」が核心。完璧 roll は天文学的確率なので**到達
できない仮想 ceiling** として retention を引っ張る。

### 3. 承認欲求の可視化

- **キャラ詳細画面**: 戦闘力 (HSR) / DMG 数値 / CRIT 率・ダメ表示
- **名刺**: 取得 Achievement / 探索率 / 螺旋星 9-12 ☆☆☆ などを名刺背景に
- **HoYoLAB プロフィール**: キャラ build を URL で公開可能
- **コミュニティツール**: Akasha Terminal (Genshin), Prydwen (HSR), MihomoUID
  などの **DPS leaderboard** が UID で外部に公開、CV (Crit Value) で序列化
- **CV (Crit Value)**: 聖遺物 1 個の Crit Rate × 2 + Crit DMG の合計が標準指標
  (40+ で god roll、50+ で flex-tier)

### 4. 限定時間 cadence

- **Version update**: 6 週間 (42 日) per major patch (1.0, 1.1, ...)、各 patch で
  新キャラ × 2 + 新 event 4-6 個
- **Banner**: 3 週間ピックアップ、限定キャラは復刻まで半年-1 年待ち。**「今逃すと
  半年戻ってこない」**が課金圧力の核心
- **季節 event** (海灯祭, 風花祭, リトゲー event 等) は復刻なしのものも多い
- **Abyss / Memory of Chaos**: 2 週間ごと敵編成更新

### 5. プレイヤー駆動経済

ほぼ無い (account-bound、trade 不可)。代わりに **gacha 1 連 = 16,000 円相当の
仮想経済**を HoYoverse が完全に管理。これは Astera の参考としては「やらない側」
の reference。

## システム詳細 (core mechanic 深掘り)

### A. 聖遺物 sub stat 厳選数学 (Astera 厳選の最大の prior art)

Genshin の聖遺物 1 個の構造:

| 要素 | 値 |
|---|---|
| 部位 | 花 / 羽 / 砂 / 杯 / 冠 の 5 部位 |
| Main stat | 部位ごとに候補 (花は HP 固定、砂は ATK% / HP% / Def% / EM / ER の 5 種, 杯は 11 種, 冠は 5 種) |
| Sub stat 数 | 初期 3 or 4 個 (5★ 聖遺物は 80% で 4 sub) |
| Sub stat 候補 | 10 種 (HP, ATK, DEF, HP%, ATK%, DEF%, EM, ER, Crit Rate, Crit DMG) |
| Sub stat roll 回数 | 強化 +0 → +20 で 4 回 (4 sub の場合) |
| Roll 値 range | 各 stat 最大値の 70% / 80% / 90% / 100% の 4 段階 |

**完璧 (god roll) 確率の計算例**:
ATK% 砂を main stat にし、sub stat 4 つに Crit Rate / Crit DMG / ATK% / ER を
引き、その 4 つの中で **Crit Rate と Crit DMG に全 4 回 roll が偏る**:

- 初期 sub stat が 4 個である確率: 80%
- 4 sub stat に Crit Rate **and** Crit DMG が両方入る確率: 約 C(8,2)/C(10,4) =
  28/210 ≈ 13.3%
- 4 回追加 roll が全て Crit Rate or Crit DMG に振られる確率: (2/4)^4 = 6.25%
- 全 roll が 100% roll: 0.25^8 = 1/65,536 (max roll はさらに細かい)

複合すると**完璧 1 個 ≈ 1/200,000 - 1/400,000 オーダー**。Resin 上限 240/日、
1 day = 聖遺物 ≈ 4-5 個。**1 年 farm して god roll 1 個出れば運がいい**水準。

**resource sink**:
- **Strongbox**: 不要 5★ 聖遺物 3 個 → 任意 set 1 個 craft (週 3 回)
- **聖遺物廻聖**: 余り聖遺物 → 経験値 (一度入れたら戻らない sunk cost)
- **HSR の遺物再錬丹**: 不要 sub stat を別 sub stat に変換 (HSR 2.x 追加)

### B. Gacha pity system (心理メカニクス)

| Banner | Soft pity 開始 | Hard pity | 50/50 |
|---|---|---|---|
| Genshin Limited 5★ | 75 連付近から確率急上昇 | 90 連 | あり (はずれ → 次は確定) |
| Genshin 4★ | 8-9 連付近 | 10 連 | あり (次 4★ pickup 確定) |
| HSR Limited 5★ | 75 連付近 | 90 連 | あり |
| HSR Light Cone | 65 連付近 | 80 連 | あり (75% pickup) |

**Soft pity の体感確率**: 公称 0.6% の 5★ 出現率が 75 連目あたりから線形に
上がり、80-89 連で実質 30-100% に達する (community datamine による)。
**hard pity 90 連 = 16,200 円 相当**。**50/50 で外しを引くと最大 180 連 = 32,400
円**で確定。

これは Astera の課金設計の reference としては「やらない側」だが、**「保証ライン
までの距離が見える」「外しても次は確定」**の心理構造は **drop rate ceiling
guarantee** として PvE drop に応用可能。

### C. CV (Crit Value) 可視化と承認欲求

- 聖遺物 1 個の CV = `Crit Rate × 2 + Crit DMG` (sub stat の合計のみ)
- 5 部位合計 CV: 200+ で実用、220+ で良 build、240+ で god (max ≈ 290)
- Akasha Terminal などで UID 指定すると **全 server 中の rank** が見える
- in-game でも「戦闘力」「キャラ詳細画面の DMG 表記」がそのまま spec の証明

**Astera 適用**: CV と等価な「武器評価値」を 1 個の数値に集約し、UI とコマンド
(`/astera-items inspect <player>`) で公開すれば flex の surface ができる。

### D. キャラ凸 (Constellation / Eidolon)

- 同一 5★ キャラを再 gacha で引くと「凸」(constellation stone)
- 0 凸 → 6 凸の 6 段階、各凸で talent unlock or stat 強化
- 6 凸キャラは 0 凸の 1.5-3x の DPS (キャラ依存)
- **金銭的には 6 凸 ≈ 1,500-3,000 USD 1 キャラ**

**Astera 適用**: 同一 item を重ねる凸構造は **rarity tier に collapse** 可能
(HSB の Master Star ✪✪→✪✪✪✪✪ と等価)。**模倣不要**: gacha の「重複時の救済」
として進化した構造なので、Astera のような non-gacha PvE には不要。rarity tier
で済む。

## Astera 適用候補 (流用 / 模倣 / 拒否 の 3 段階で)

| 要素 | 判断 | 理由 |
|---|---|---|
| 聖遺物 sub stat 4 個 × 4 roll RNG | 流用 (核心) | Astera 厳選の数学的骨格として最強の reference。sub stat 数 × roll 回数 × roll 値 range の 3 軸で確率分布を設計可能。 |
| Sub stat 値 range 70-100% (4 段階) | 流用 | 「max roll が出るかどうか」の 2 段目の RNG が retention を作る。Astera の affix system に直接組み込み可能。 |
| Resin / Trailblaze Power (日次上限) | 模倣 (慎重) | 1 日の進捗上限は core loop を限定するが、Minecraft community は free-time が長いプレイヤーが多く反発しうる。Astera では「特定 boss は日 N 回」程度の soft cap に。 |
| Strongbox (3 in → 1 out 変換) | 流用 | 不要 item を任意 set に変換する resource sink は dust accumulation を防ぐ。 |
| Soft pity (確率上昇 + hard ceiling) | 流用 | PvE 超 rare drop に **drop rate guarantee** として導入可能 ("100 boss kill 以内に 1 つは確定")。 |
| 50/50 system | 拒否 | gacha 文化の象徴で Minecraft player には不評。Astera は使わない。 |
| Gacha 課金構造 | 拒否 | Astera の収益モデルとは反する (Minecraft network の cosmetic / rank モデルが標準)。 |
| キャラ凸 (Constellation / Eidolon) | 拒否 | gacha の重複救済構造。Astera は rarity tier で collapse 可能、模倣不要。 |
| CV (Crit Value) 単一指標可視化 | 流用 | 武器評価値を 1 数値に集約 → UI / inspect コマンドで公開。承認欲求 surface の最効率。 |
| 名刺 / プロフィール (Achievement 集約) | 模倣 | Astera profile 機能の design 参考。 |
| 6 週間 patch cadence | 模倣 (緩く) | 完全な 42 日サイクルは scope 過大。Astera は 8-12 週で大型 + 隔週 hotfix が現実的。 |
| 螺旋星 / Memory of Chaos (高難度 score 競技) | 模倣 | rotating boss + score leaderboard は厳選 build を試す場として優秀。Astera の endgame instance design に。 |
| HoYoLAB 公式 community プラットフォーム | 模倣 (将来) | 公式 community + UID profile API は player retention の hub。 |

## Source / References

- https://genshin-impact.fandom.com/wiki/Artifact — 聖遺物 sub stat 値 range / 確率
- https://honkai-star-rail.fandom.com/wiki/Relic — HSR 遺物仕様
- https://genshin-impact.fandom.com/wiki/Wish — gacha pity / 50-50 公称仕様
- https://www.hoyolab.com/ — 公式 community
- https://genshin.hoyoverse.com/en/news — 公式 patch note
- https://akasha.cv/ — 非公式 CV leaderboard (承認欲求 surface の好例)
- https://www.prydwen.gg/ — HSR キャラ build / tier list
- https://genshin-impact.fandom.com/wiki/Constellation — 凸仕様
