# VFX art direction — which part reproduces what

Limbus Company 風の 2D 演出を Minecraft で再現するときの「部品選び」の正本。
Display entity (block / item / text) と particle は役割が違う。**形と面は Display、動きと粒立ちは particle**。
どちらか片方だけで作らない。

## 0. 原則 (桜井政博「ゲーム作るには」エフェクト回より)

出典: 「モヤっとさせるな」「閃光､爆発､残煙」「パーティクルの限界」「エフェクトをスローで見てみよう」「風切り」(YouTube, 桜井政博のゲーム作るには)。

1. **モヤっとさせない。** 加算 (発光) だけの効果は手応えとメリハリを失い、暗い画面でしか見えない。
   主体は**パリッとした描き割り (ベタ塗り)**。発光は添えるだけ。
   → 芯は不透明 (concrete / alpha 1 の TextDisplay 板)。色付きガラス + brightness 15 は縁と余韻にだけ使う。
2. **相反するもので引き立てる。** 明るい効果に**暗いものを混ぜる** (明るい背景でも見える)。曲線には直線を添える。
   → 斬撃・閃光には黒/暗色の縁 (dark rim) か暗い粒を必ず混ぜる。
3. **爆発 (大きな命中) は 4 段:** 閃光 (一瞬、爆発範囲より大きく、黒も混ぜる) → 爆炎 (中心から勢いよく、まだパリッと)
   → 黒煙にバトンタッチ → 収束 (短すぎると手応えが無い)。消え際に小さな閃光を混ぜて「パパッと消える」。
   画面振動は手応えを大きく変える。地上の命中は上への飛散 + 地面の衝撃波。判定は爆炎の間ずっと出さない。
4. **パーティクルは少なく賢く:** 寿命は短く、**出すタイミングをずらす**、**位置を重ねない** (重なりは無駄)、
   **1 枚の板に複数の粒を描く** (TextDisplay 1 体に `✦ · ✧ ·` を並べる)、白と黒の粒を混ぜてどの背景でも見せる。
   動きは座標パターンの事前計算で軽く作れる (本プランナがそれ)。
5. **風切り (剣の軌跡):** 主流は**刃が通った軌跡に扇形の面を張る** (要 = 手元、少し遅れて追従、尾は半透明で消える)。
   ただ追うだけだと間延びするので**モーション側を直す**。真横の水平斬りは見えないので傾ける。
   高速の振り抜きは**板 1 枚を一瞬** + ヒットストップで見せる。武器の性格に合わせる
   (先端が強い = エッジ強調 / 豪剣 = 荒々しく / 打撃 = ガタガタ / ビーム = 消えず発光体 / 炎 = 炎の残像)。
6. **スローで観察する。** 判断はコマ送り (比較ページ: 動画 | 意図 3D | Minecraft) で行う。

構成の基本形: **武器そのもの = BlockDisplay の組み物 (rig) を手元を軸に実際に振る**、
**風切り = TextDisplay の半透明の扇を刃の通過に沿って自動生成**、**命中 = パーティクルの色・閃光・飛散 + 暗い残煙**。
ItemDisplay はほぼ使わない。

## 0.5 業界の知見 (出典つき)

- **溜め → 発生 → 余韻。** 主動作は寿命の**最初の 1/4 で終える**、線形タイミングは使わない、余韻は徐々に弱める。
  命中の演出はダメージと**同じ tick** に出す。([VFX Apprentice: timing](https://www.vfxapprentice.com/blog/the-soul-of-effects-what-is-timing-in-vfx),
  [style guides](https://www.vfxapprentice.com/blog/creating-vfx-style-guides-for-games))
- **重要度 = 見た目の大きさ。** 技の重要度を 1–10 で付け、強い技ほど大きく明るく鋭く。同じ効果 (スタン・回復) は同じ見た目言語。
  ([LoL VFX の解剖](https://realtimevfx.com/t/anatomy-of-a-league-of-legends-missile-part-2-what-are-we-communicating-with-our-vfx/13062))
- **当たり判定と見た目を一致させる。** 命中 tick の演出の広がり = ダメージ範囲。予兆の形 = 判定の形。([Riot: clarity](https://riotgames.com/en/news/dev-clarity-in-league))
- **形:** 主要素 1 つ + 副要素。脅威は鋭い形、補助は丸い形。太い線と細い線を混ぜる。矛盾する形を重ねない。([LoL VFX tips](https://www.vfxapprentice.com/blog/10-league-of-legends-vfx-design-tips))
- **明度と色:** 0/100 の極端を避け中間を広く使う。補色は一方を従に。パレットは類似色/三角配色で 3–4 色、支配色 1 つ。
  明るさは「キャラより上、UI より少し下」。([LoL VFX tips](https://www.vfxapprentice.com/blog/10-league-of-legends-vfx-design-tips))
- **ヒットストップ:** 攻撃側・被弾側を同時に止める、強い攻撃ほど長く、上限を設ける、多人数戦では短めに (止まった相手を第三者が殴れるため)。
  ([桜井政博 ファミ通コラム要約](https://sourcegaming.info/2015/11/11/thoughts-on-hitstop-sakurais-famitsu-column-vol-490-1/),
  [critpoints](https://critpoints.net/2017/05/17/hitstophitfreezehitlaghitpausehitshit/))
  目安: 格闘ゲームで弱 7 / 強 11 フレーム程度 (60fps)。Minecraft の 20tps では弱 2 / 強 4 tick。
- **風切りの欠け:** 高速の振りは毎フレームの刃の位置の間が空く (180° を 4 フレームで振ると 4 本の筋になる)。
  → 振りの最中は 1 tick に 1 回以上更新し、先端に smear の破片を足す。([realtimevfx](https://realtimevfx.com/t/ue4-weapon-slash-meshes-vs-animtrail-data/5628))

### 1 スキルのチェックリスト
1. 重要度 1–10 を決め、大きさ・明るさ・長さをそれに合わせる。
2. 溜め / 命中 tick / 余韻 の tick を書く。予兆は命中より先に読める。
3. 命中 tick の演出の広がり = ダメージ範囲。
4. 主要素 1 つ (脅威 = 鋭い)、副要素は 1 つまで。
5. 色は 3–4 色、支配色 1 つ。暗い芯か縁を必ず入れる。
6. 線形のタイミングは使わない。主動作は寿命の最初の 1/4。頂点で 1–2 tick 止める。
7. 層: 芯 / 閃光 / 火花 / 煙 / 地面 / (決め技のみ) 画面。情報を足さない層は削る。
8. 命中: 接触点に火花、ヒットストップ (強さで 2–4 tick)、被弾側の揺れ、音。
9. 余韻で要素を減らしてから消す。次の技や相手の姿を隠さない。
10. Display 数・寿命・視聴者あたりの上限を守る。比較ページ (動画 | 意図 | Minecraft) で確認する。

## 1. 二つの素材の性質

| | Display entity | Particle |
|---|---|---|
| 得意 | はっきりした形・面・線、巨大化、回転、文字、武器の投影、持続する構造物 | 動きの軌跡、粒立ち、飛び散り、煙・炎・光の揺らぎ、量感 |
| 苦手 | 透明度 (無い → ブロックラダーで疑似フェード)、大量の細かい粒、柔らかい輪郭 | 形の正確さ、大きさ (dust 最大 4)、向き、持続する線 |
| 動き | クライアント補間 (線形 + slerp)。キーフレームで滑らか | 1 発ずつ。速度・寿命は粒子種で固定、`trail`/velocity で方向付け |
| コスト | 1 体ずつエンティティ (1 キャスト ≤ 60) | 送信量のみ。数百粒でも軽い |
| 視点 | 面は板。真横から見ると消える → 板は 2 枚交差か billboard | 全方向から同じ見え方 |

## 2. 部品選び表 (Limbus の語彙 → Minecraft)

| 動画で見えるもの | 芯 (Display) | 縁・余韻 (Particle) | 注意 |
|---|---|---|---|
| 斬撃線 (細く鋭い一閃) | block `white_concrete` / 色付き glass を `scale (0.05,0.05,L)`、brightness 15、`out_expo` で 0→L に 2-3t で伸ばす | `trail` を線に沿って根元→先端、`end_rod` velocity along_tangent、先端に `crit` | 一人称では線が視線と平行だと点になる → **斜め/横方向に傾ける**。2 枚目を 90° roll で交差させると全方向で見える |
| 三日月・弧の斬撃 | formation `arc` に沿って細い block 12-18 本、align tangent、stagger 1t で描画 | 弧に沿って `sweep_attack` 2-3 発、`dust_color_transition` (芯色→白) を弧上に | 弧の面を術者の視線に対して 30-60° 傾ける (真横・真正面は読めない) |
| 刺突 (突き) | 長い bar を前方に、根元から out_expo、先端に text `✦` center billboard | 先端から前方へ `end_rod`/`electric_spark` velocity、根元に `poof` | 一人称では奥行き方向 → **先端の ✦ と粒子の飛散で読ませる** |
| 衝撃フレーム (白閃・ヒットストップ) | text `✦` を scale 0→6 を 2t (out_back) → 3t で 0、色は白→属性色 | `flash` 1 発、`crit` 20 粒 burst、`hit_stop` 2-4t、`camera_shake` | 画面全体を塗る演出は MC では不可。**被弾点で大きく光らせる** + `title` は最終手段 |
| 衝撃波リング | 平たい block の ring formation (y scale 0.02)、group scale 0.3→3 out_cubic、ブロックラダーで消す | `sweep_attack` / `dust` を ring で同時に、`gust` 1 発 | 地面すれすれ (y+0.05) は z-fight するので +0.1 |
| ダッシュ・残像 | 武器の item display を 3-4 個 stagger 2t、ブロックラダー無し → scale 縮小で消す | `trail` を始点→終点 (色=人格色)、`cloud`/`dust_plume` を足元、`end_rod` の速度線 | 残像は術者の後方。一人称の術者からは見えない → 相手視点用 |
| 巨大武器の投影 | item display scale 3-5、`item_display_transform: fixed`、剣スプライトは斜め描画なので **roll -45 で縦** | 降下中は `end_rod` を下向き、着地で `block_crumble` (地面ブロック) + `explosion` | 着地点に 10t 前から地面 ring で予兆 |
| 地割れ | 薄い block (`black_concrete` y 0.01) を放射状 line formation、stagger | `block_crumble` / `dust_pillar` 地面ブロックで | |
| 光柱・ビーム | 芯 block (white/quartz) + 外側 glass (色) の 2 層、外側を oscillate で揺らす | 芯に沿って `end_rod` 上昇、根元に `flash`、ビーム上に `electric_spark` | 外側は少し太く・暗く (bloom) |
| 炎 | (Display は使わないか、橙 glass の揺らぎ) | `flame` / `soul_fire_flame` / `small_flame` / `lava`、上昇は velocity up | 炎を block で作らない (硬く見える) |
| 血・花弁 | (無し) | `dust` 赤 scale 1.5 を velocity outward + 落下、`cherry_leaves`、`falling_dust redstone_block` | |
| 糸・鎖 | 極細 block (0.02) の line、または item display (chain) を line formation | `dust` 小粒を線上、`trail` で張る瞬間 | |
| 魔法陣・時計陣 | 地面平行の ring + polygon (星) formation、group spin、text 記号 (◎ ☸) | `enchant` を陣に吸い込む、`end_rod` を陣の縁 | 地面平行 = rotate pitch -90 (text) / scale y 薄く (block) |
| 文字演出 (斬・CLEAR) | text display、center billboard、scale 3-6、背景 `#00000000`、shadow false | 出現時に `flash`、消える時に `poof` | 1 文字 ≈ 0.25 block × scale |
| 黒い棘・翼 | 細長い black block を fan formation (polygon / arc)、根元から伸ばす | `squid_ink` / `smoke` | 暗い背景で消える → 縁に `dust` 白/赤 |

## 3. 視点ルール (Limbus は横、Minecraft は一人称)

- 術者 (一人称): 視界の中心 ±30°、距離 2-6 ブロックが主役。足元・背後は見えない。**斜めの斬撃線・前方の ✦・画面手前を横切る弧**が効く。
- 相手 (三人称、術者を見る側): 術者の体の周り・頭上が見える。残像・巨大武器・地面の陣・光柱が効く。
- 板 (平面の block) は面が見える向きに置く。迷ったら 2 枚を直交させるか billboard center。
- 予兆 (大技の予告) は**相手から見える地面と頭上**に出す (PvP の可読性)。

## 4. 1 ビートの重ね方 (テンプレート)

1. 予兆 (3-10t): 地面 ring / 武器の光 / 粒子の収束 (`enchant`, `end_rod` 吸い込み)
2. 動き (1-4t): `dash_strike` / `propel` + `trail`・`cloud`
3. 命中 (0t): damage + 芯の Display (斬撃線 or ✦) + `crit`/`flash` + `hit_stop` (重い一撃のみ)
4. 余韻 (4-12t): ブロックラダーで芯を消す + 粒子の飛散 (`dust` outward, `smoke`)

コイン (ヒット) ごとに 3 を繰り返し、最後のコインだけ 3+4 を大きくする。

## 5. 予算

- 1 キャストの同時 Display ≤ 60 (E2E 実測で確認)。粒子は 1 tick 合計 ≤ 300。
- 演出は ≤ 60t (決め技・E.G.O ≤ 100t)。
- 迷ったら Display を減らして粒子を増やす。

## 6. 検証

`tools/skill-viewer` で **術者一人称・相手三人称・横** の 3 視点を出し、ダメージ tick と重ねて確認する。
近似プレビューでの確認だけで完了にしない。

ゲーム内では `/astera skill preview <skill-id> view=side|opponent|behind speed=0.25` でダミーに発動させ、
術者の一人称以外の視点から実機で確認する (`again` で再生、`freeze` / `step` でコマ送り。詳細は `docs/contributing/playtest.md`)。
