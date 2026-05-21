# Path of Exile (PoE)

> 出典確認: 2026-05-21

## 概要

Grinding Gear Games の Path of Exile (2013-10 正式リリース、2026-05 現在 13 年目)
は ARPG ジャンルの hack & slash + 厳選 + 経済 を極限まで深掘りした作品。**Astera
の「削って戻れない craft」**の最大の prior art。Currency item が経済の通貨でも
あり craft 試行回数のもとでもある **deflation-driven economy**、3 ヶ月サイクル
の league システム、Atlas (map) endgame、Mirror tier item の天井経済。

PvE 中心 (PvP race は marginal)、ソロ / party / 経済勢の 3 つのプレイスタイルが
並列で成立、全プレイヤーが同じ武器プールを共有 = Astera と同じ「武器共用 PvE」
モデル。

## なぜ長寿か (retention 5 軸へのマッピング)

### 1. 多軸 progression

- **Character level**: 1-100 (Lv 95+ は数十時間オーダー)
- **Passive Skill Tree**: 中央から放射状の **1325 ノード**、123 ポイント程度を
  振る (キャラ + Bandit + クエスト報酬)
- **Ascendancy**: 各 class に 3 sub-class、Lab で 8 ポイント取得
- **Skill Gem level**: 1-20 + Quality 0-23%
- **Atlas Passive Tree** (Map 専用 passive): 約 130 ノード、上限 ≈ 130-140
- **Pantheon power** (boss 別 perk slot)
- **Crafting bench unlock** (recipe を集める meta progression)

### 2. 厳選レイヤー乗算

下記「Affix system + currency craft」が核心。**1 item に乗る軸が PoE は最も多い**
(prefix 3 + suffix 3 + implicit + enchant + corrupted mod + influence + synthesis
implicit + eldritch implicit + ...)。

### 3. 承認欲求の可視化

- **Challenge League**: 各 league で 40 challenge、達成数に応じ **季節限定 MTX**
  (例: 24/40 でアーマー、36/40 でポートレートフレーム、40/40 で武器エフェクト)
- **Mirror tier item**: 完璧 craft 済みの top-end item は **Mirror of Kalandra**
  でしか複製不可、市場では数百-数千 mirror = 数兆 chaos の取引対象。所有自体が flex
- **Race ladder** (公式): 短期 race league (1 week, 1 day, 12 hour, 3 hour) の
  to-level leaderboard
- **PoB (Path of Building) export string**: 自分の build を文字列化して
  community に貼り付けて DPS 自慢
- **Trade site / Stash tab**: 公開 stash の item を全プレイヤーが検索可能

### 4. 限定時間 cadence

- **League: 約 13 週 (3 ヶ月) サイクル** — 各 league で:
  - 新 mechanic 1 個 (Heist / Sentinel / Crucible / Necropolis 等)
  - 新 unique item 数十個
  - balance overhaul + skill rework
  - **economy reset** (新 league = 全員 0 から)
- standard league (永続) と temporary league (期間限定) が並列、ほぼ全プレイヤー
  が temporary に集中、終了後に standard へ merge
- **economy reset の儀式性**: 13 週ごとに「白紙から最効率 build を選んで
  early game を駆け抜ける」のが core loop

### 5. プレイヤー駆動経済

PoE 経済は **gold 通貨が無い**: currency item (Chaos Orb, Divine Orb, Exalted
Orb, Mirror of Kalandra 等) が **そのまま craft 道具でもあり通貨でもある**。

- **Chaos Orb**: rare item の全 mod を re-roll、1 chaos が基準通貨
- **Divine Orb**: rare item の mod 値を re-roll (mod は変わらず数値のみ)、最終
  仕上げに使用、現在 1 divine ≈ 100-200 chaos
- **Exalted Orb**: rare item に新 affix 追加、craft で使う
- **Mirror of Kalandra**: item 1 個を完全 copy、流通量極小、1 mirror ≈ 200-300
  divine

**1 craft = 1 currency 消費**で、orb は drop でしか入手不可、流通量が厳しく
管理されている → **デフレ通貨**として機能。

## システム詳細 (core mechanic 深掘り)

### A. Affix system + currency craft (Astera の最大の reference)

PoE rare item の構造:

| Layer | 個数 | 備考 |
|---|---|---|
| Implicit | 1-2 | base item 固有、craft で増減可 |
| Prefix | 0-3 | offensive 系が多い (% damage, +life, etc.) |
| Suffix | 0-3 | defensive / utility 系 (resistance, attack speed, etc.) |
| Enchant | 0-1 | helmet / boots / gloves に lab で付与 |
| Corrupted mod | 0-1 | Vaal Orb で破壊的に追加 |
| Influence mod | 0-2 | Shaper / Elder / Crusader / Redeemer / Hunter / Warlord |
| Eldritch implicit | 0-2 | Eater of Worlds / Searing Exarch |

**Tier 分布**: 各 affix は Tier 1 (最強) - Tier 8 程度の階層、Tier 1 mod は
**item level 84+ かつ確率 0.5-2%** オーダーで roll。

**craft の resource sink**:
- Alchemy Orb: white → rare (4-6 affix random)
- Chaos Orb: rare の全 affix を re-roll (前の affix は **完全消滅**)
- Exalted Orb: rare に新 affix 追加 (random、tier も random)
- Divine Orb: 既存 affix の数値だけを re-roll
- Annulment Orb: random 1 affix を削除 (**戻せない**)
- Eternal Orb: かつて affix freeze 用、現在は廃止 (経済的に強すぎた歴史)

**"削って戻れない" の核心**: Chaos Orb で hit した affix は確定保存できず、
さらに re-roll すれば消える。Annul で削ろうとして欲しい affix が消える事故が常態。
**craft 1 試行に物理的に currency が燃える → 失敗が確定的に痛い** = 厳選の中毒
構造。

### B. League system (3 ヶ月 cadence)

各 league cycle:

- Week 0: league launch、全員 Lv1 から start、新 mechanic 試運転
- Week 1-2: **race-to-100 / race-to-end-game**, streamer 文化のピーク
- Week 3-6: 中盤、経済が形成、Mirror item が出始める
- Week 7-10: 後期、ほとんどの player が build 完成、challenge 詰め
- Week 11-13: 終盤、次 league 待ち、investment 凍結

**economy reset の意義**: 13 週ごとに**「過去資産が無効化される」**ことで、
old player と new player の格差を週次でリセット。これは Astera のような長期
運用サーバーで「インフレで late join player が参加不可能になる」問題への
答えとして最重要。

### C. Atlas progression (endgame)

- **Map: Tier 1-17** (T17 は Crucible league で追加)
- 各 Map に固有 boss、Atlas Passive Tree で farming strategy をカスタマイズ
- **Atlas keystone**: 例 "Strand of Catarina" "Singular Focus" など、特定
  mechanic を強化 / 排他
- **Voidstone** (旧 Awakening Bonus): map tier scaling、4 個 collect で T16 に
  T17-equivalent mod
- **Pinnacle bosses**: Maven, Sirus, Eater of Worlds, Searing Exarch, Uber
  Elder, Shaper, Uber Uber 各種

**Atlas tree の意義**: endgame で何を farm するかを **player が選ぶ** 構造。
strategy diversity が build diversity と並列に存在 → 「同じ build でも farming
strategy が違えば retention 違う」

### D. Mirror tier + Challenge MTX (承認欲求の頂点)

- **Mirror item の市場**: T1 全 affix + 高 % + Eternal-style 完璧 craft は
  **1 league で 数個-数十個しか作られない**。1 個 200+ divine (= 2-4 万 chaos)
- **Mirror service**: craft 主に手数料 (例: 200 divine + 1 mirror) を払うと
  自分の base に mirror 適用してくれる商売
- **Challenge MTX**: 各 league の challenge 報酬 cosmetic は league 終了後に
  二度と入手不可 → 「過去 league 参加者の証」として承認欲求 surface

## Astera 適用候補 (流用 / 模倣 / 拒否 の 3 段階で)

| 要素 | 判断 | 理由 |
|---|---|---|
| Affix system (prefix N + suffix N) | 流用 (核心) | Astera 武器の「削って戻れない craft」の骨格。prefix 2-3 + suffix 2-3 程度に縮約。 |
| Chaos Orb 等価 (全 affix re-roll、戻らない) | 流用 (核心) | "削って戻れない" の道具。Astera では reforge stone と等価機能で実装可能。 |
| Divine Orb 等価 (affix 数値だけ re-roll) | 流用 | 仕上げ用 rare currency。最終 god roll への最後の運試し layer。 |
| Exalted Orb 等価 (affix 追加) | 流用 (慎重) | 1 affix 増加は強すぎるので Astera では「affix slot unlock consumable」として制限。 |
| Annulment Orb (random 1 affix 削除) | 模倣 (慎重) | 「事故で大事な affix が消える」体験は中毒性高いが、Minecraft player にはストレス過大の懸念。希少 consumable として導入し、event 報酬等で配布。 |
| Tier 1-8 確率分布 | 流用 | Astera affix の確率分布の数学的 prior art。Tier 1 は 1-2%、Tier 8 は 30-50% など。 |
| League system (3 ヶ月 cadence + economy reset) | 模倣 (慎重) | **economy reset は Astera の long-term retention の核心**。ただし Minecraft community は「全部消える」に強い反発があるので、季節 server (3-6 ヶ月 fresh start) と本鯖の 2 系統で。 |
| 季節限定 Challenge MTX | 流用 | 過去 league cosmetic 入手不可 = 承認欲求 surface の頂点。Astera でも season MTX を non-tradeable で実装可能。 |
| Atlas Passive Tree (farming strategy 選択) | 模倣 (将来) | endgame 拡張時の design 参考。Phase 1 では scope 過大。 |
| Currency = craft resource = 通貨 の三位一体 | 流用 | gold 通貨を廃し、craft material 自体を通貨化することで自然に sink が機能。Astera economy plugin で検討価値大。 |
| Mirror of Kalandra (完璧 item を複製) | 模倣 | top tier item の流通制限 + 承認欲求頂点。Astera では運営限定 drop で象徴化。 |
| PoB (Path of Building) export 文字列 | 模倣 (community 委任) | community が自然に作る tool なので Astera 側は in-game inspect API を提供すれば community が build。 |
| 1325 ノード Passive Tree | 拒否 | 過剰。Astera は Skill / Class 軸で 20-50 ノードで十分。 |
| Atlas T1-T17 17 段階 | 拒否 (Phase 1) | scope 過大。Phase 1 は instance 3-5 tier で十分。 |
| Trade site (公開 stash 検索) | 模倣 (Phase 2+) | Astera Auction House の design 参考、HSB と PoE のハイブリッドが理想。 |

## Source / References

- https://www.pathofexile.com/forum/ — 公式 forum (patch note, league announcement)
- https://pathofexile.fandom.com/wiki/ — community wiki (affix tier / 確率)
- https://www.pathofexile.com/leagues — 公式 league archive
- https://www.poewiki.net/wiki/Currency — currency item 一覧 + 効果
- https://www.poewiki.net/wiki/Modifier — affix mod / tier 一覧
- https://www.pathofexile.com/atlas — 公式 Atlas / endgame
- https://pathofbuilding.community/ — PoB community tool (build export 文化)
- https://www.poewiki.net/wiki/Mirror_of_Kalandra — Mirror tier 経済
