# AAA / アニメ / 映像の技法 → Minecraft の部品への写像

[README](README.md) / タグの意味は README。出典のある事実 ([一次]/[二次]) と、そこから考えた写像 ([推論]) を行ごとに分けて書く。
「Astera 対応」は 2026-10-23 のコード照合 (対応 / 部分 / 未対応)。

## 1. タイミングと手応え (ゲームフィール)

| 原理 | 事実と出典 | Minecraft への写像 | Astera 対応 |
|---|---|---|---|
| ヒットストップは強さの階層を作る | 軽 2-4F / 重 6-10F / ボス級 12-18F (60fps)。ブログ値 [二次] https://gamineai.com/blog/pixel-art-combat-fx-hitstop-smear-frames-impact-timing-2026 | 20tps で軽 1-2t / 重 3-4t / 決め技 6t [推論] | 対応: `hit_stop` (ticks)。重さ別に値を変えているかは skill 側 → [`next-improvements.md`](next-improvements.md) |
| 停止中も揺れ・フラッシュは別時計で進める | 同上 / https://forum.godotengine.org/t/tutorial-hit-stop-and-screen-shake-in-godot-4-autoload-trauma-camera-mit/145240 [二次、検索要約のみ] | 停止窓の間も `screen_flash` / `camera_shake` を打つ。rig の `freeze` は swing とフェードだけ止める (正本 §7) | 部分: freeze は rig のみ。揺れは別 mechanic なので時計は独立 |
| 重複ヒットは停止を足す | https://www.wayline.io/learn/game-feel/1 [二次] | 多段技は各 beat で小さな `hit_stop` を足す | 対応 (skill で積む) |
| 振動は大きく始まり停止中に縮む。停止中に攻撃側がごくゆっくり動く | 桜井コラム vol.490 [二次] https://sourcegaming.info/2015/11/11/thoughts-on-hitstop-sakurais-famitsu-column-vol-490-1/ | 減衰する揺れ・術者のスロー | 未対応 (`camera_shake` は単発) |
| 加算式の揺れ (+4 を重ねる) と指数減衰 | Vlambeer / Nuclear Throne: カメラ後退 6px + screenshake +4 + 武器 kick 2 + ヒット 10-20ms 凍結 [二次] https://infovore.org/tag/feel/ (検索要約のみ) | 連射・多段では揺れを加算。単発の大揺れを繰り返さない [推論] | 未対応 |
| 入力から 100ms 以内に最初の反応 | Swink「Game Feel」 [二次] https://lizengland.com/blog/review-game-feel-by-steve-swink/ | 発動直後 2t 以内に視覚/音の反応 (溜めは「反応済み」の上に載せる) [推論] | skill 側で守る |
| 命中確定後にだけ演出を呼ぶ | UE5 実装例 [二次] https://uhiyama-lab.com/en/notes/ue/game-feel-hit-feedback/ | `phases.hit` / `dash_strike.on_hit` 側に置く。振っただけで出さない | 対応 |
| 音のタイミング | 軽: 総長 80-150ms で立ち上がり 10ms 未満。重: 命中の 30-60ms 前に低音の落ち [二次] (同 gamineai) | 命中 tick の 1-2t 前に低い溜め音、命中 tick に transient。ミス音とブロック音は別 | 部分: `play_sound` + `delay_ticks` で書ける。ミス/ブロック音の使い分けは skill 側 |
| 永続性 | Art of Screenshake の 30 項目 [二次] https://rpgplayground.com/research-making-a-juicy-game/ | 焦げ跡・破片・ひびを 20-60t 残す | 部分: `set_block` (revert あり) / `block_crumble` 粒子 |
| 強さを設定可能に | Monster Hunter Wilds の例 [二次] https://mp1st.com/news/monster-hunter-wilds-hitstops-final-game-closer-to-people-are-looking-forward | プレイヤー別の演出強度 | 未対応 |

## 2. 構えと残心 (モーション)

| 原理 | 事実と出典 | 写像 | Astera 対応 |
|---|---|---|---|
| 構えは瞬時・極端。溜めは保持して一気に切り替え。中割りは 1 フレーム | ファミ通 [一次] https://www.famitsu.com/news/201904/22174831.html | 溜めは補間 0 (保持)、発生は 1t のスナップ、余韻は長い補間 [推論] | 部分: rig の `swing` keyframe と ease で書ける。slerp が逆回りする制約あり (正本 §7) |
| フォロースルーは行きより長く、戻りの軌跡は別 | 同上 [一次] | 戻りの rig keyframe を別 ease・別経路で | 対応 (swing keyframe) |
| 誇張は手動で足す / limited animation | Guilty Gear Xrd: 滑らかさより読めるポーズ。full animation は 3D に見えすぎるため却下 [二次] https://videohighlight.com/v/yhGjCzxJV3E , 講演 https://gdcvault.com/play/1022031/GuiltyGearXrd-s-Art-Style-The | 補間幅 0 のスナップでストップモーション感を出す [推論] | 部分: 1t ごとの keyframe で可能 |
| twos / ones の切り替えが感情を伝える | Spider-Verse [二次] https://www.pulse.ug/story/how-the-spider-verse-animators-created-that-trippy-look-2024082309072304650 (検索要約のみ) | 補間 2t 保持を基本に、決めの瞬間だけ 1t [推論] | 対応 (keyframe 間隔) |
| 打撃のエネルギーは打ち抜く一段前に入り、逃げ先を見せる | CEDEC2026 武術指導者×ゲームデザイナー講演 [一次] https://www.gamebusiness.jp/article/2026/08/31/27830.html | 命中の誇張を接触点に集め、振り抜き方向へ破片・衝撃波を流す | skill 側 |
| 技を段階 (溜め / 発射 / 飛翔 / 着弾) に分け別アセットにする | Capcom SF6 / RE ENGINE [一次] https://www.docswell.com/s/CAPCOM_RandD/538NVE-RE2023 | 段ごとに独立した vfx ID と寿命 | 対応 (`play_vfx` を段ごとに分割) |
| 着弾でだけ二次エフェクト | 同上 [一次] | 命中 tick にだけ spawn。外れたら出さない | 対応 (`hit_entity` 側) |

## 3. 視覚設計 (可読性・層・色)

| 原理 | 事実と出典 | 写像 | Astera 対応 |
|---|---|---|---|
| メイン / サブ / 賑やかしの 3 層。出る順も消える順もこの順。負荷が上がったら賑やかしから削る。色は 4 階調以内 | CC2 大塚氏 [一次] https://www.4gamer.net/games/999/G999905/20231201050/ | vfx 要素を 3 層にタグ付けし、`slot_lifetime_ticks` を主 > 副 > 賑やかしの順に | 部分: 層の概念はチェックリスト (正本) のみ。タグは未対応 |
| ハードエッジは視線を止め、ソフトエッジは流す。明度は主役に集中 | Relink / Cygames [一次] https://cgworld.jp/flashnews/01-202608-Cygames-CEDEC.html | 芯 = 不透明 block/quad、周辺 = 半透明 quad (正本 §0 の 1 と整合) | 対応 |
| VFX は判断情報。アンカー (奥行き・空間座標) を置く | 同上 [一次] | 地面の ring、点光源の ✦ | skill 側 |
| 緩急 (静と動・暗と明) | CC2 / Cygames [一次] | 溜めで `vignette` を軽く、命中で `white` | 部分: `screen_flash` の `vignette` あり |
| 脅威/命中/ステータス/環境の 4 レーン、敵予兆の色帯を予約 | https://gamineai.com/blog/top-down-combat-vfx-readability-2026-color-timing-system-busy-screens [二次] | `lane` を vfx / skill YAML に | 未対応 (`lane` 分類は無い) |
| 単発の面積上限・命中ピーク 40-90ms (1-2t)、減衰 120-220ms (3-4t)、予兆立ち上がり 120-220ms・保持 250-550ms | 同上 [二次] | 命中のピークを 1-2t にする。予兆を 2.5-4t で立ち上げ 5-11t 保持 | skill 側 |
| 重い技のみ騒がしく、弱い技は背景に溶かす | Diablo IV [二次] https://www.wowhead.com/cn/news/physical-based-rendering-and-visual-effects-diablo-iv-quarterly-update-325416 (検索要約のみ) | 派手さを配給制に (重要度 1-10) | 正本のチェックリスト 1 |
| 被ダメージを世界観に合う記号で統一 | Bayonetta (PlatinumGames) [一次] https://platinumgames.com/official-blog/article/1574 | Limbus なら血飛沫+赤い斬撃、花弁 | skill 側 |
| インパクトフレーム: 輪郭線を抽出し黒地赤エッジ / 赤地白エッジを数フレームで切替。色収差+ラジアルブラー、ネガポジ反転 | シャドバ WB [一次] https://cgworld.jp/article/202610-cedec-svwb.html 、80.lv の MHA 風 VFX (後処理 7 種 × 1F、計 12F 窓) [一次] https://80.lv/articles/my-hero-academia-style-attack-vfx-in-unreal-engine-5?amp=1 | `screen_flash` の overlay glyph を 1t ずつ切り替える [推論] | 部分: `screen_flash` を `delay_ticks` で連鎖できる。専用 overlay (反転色・赤黒) は未整備 |
| 高速回転はメッシュでなくテクスチャを回す | CyberConnect2 [一次] (4gamer 同上) | 板 1 枚+glyph/テクスチャの切替で回転感 | 部分: sprite の flipbook |
| ディゾルブ+ブルーム: 消え際だけ縁を光らせる | 同上 [一次] | ブロックラダーの最後の 1 段を発光色に [推論] | 部分: `color_over_life` / `alpha_over_life` |
| 範囲を覆うものはポリゴンで固定し UV 流し | シャドバ WB [一次] | 板 1 枚+テクスチャ流し | 未対応 (UV 流しは core shader 前提) |
| 発動を同時にせず一拍ずらして変化量を分散 | 同上 [一次] | `delay_ticks` で周囲の発光を 1-2t 遅らせる | 対応 |
| 背景オブジェクトが戦いに反応 | Capcom SF6 [一次] | 地面ブロックが一瞬揺れる / 欠ける (`block_crumble`) | 部分 |
| 正面前提の板は横から薄く | FadeByAngle (Capcom) [一次] | 視点に応じた alpha 低下 [推論] | 未対応 |
| StretchBlur: 攻撃部位の見た目を複製して引く | Capcom [一次] | rig の複製を 1-2t ずらして薄く出す (現行の残像) | 対応 |

## 4. アニメ表現 (2D/アニメ調)

| 原理 | 事実と出典 | 写像 | Astera 対応 |
|---|---|---|---|
| 少ない枚数で極端な誇張と変形 (金田伊功) | 評: https://www.bunka.go.jp/j-mediaarts-festival/award/single/kanada-yoshinori/ , https://artscape.jp/museum/nmp/artscape/topics/9911/murakami/murakami3.html [二次]。**具体的な作画手法を述べた資料は見つからず、未検証** | 「パッパッパッ」のリズム = 補間を使わず 1-2t ごとに形を切り替える [推論] | 部分 |
| ヒットエフェクト = 2D スプライトの連続切替。煙は 1 コマずつ別モデル | Guilty Gear Xrd [二次] | sprite (ADR-0239) の flipbook | 対応: `type: sprite` (`frame_ticks`, `loop`) |
| お化けブラー (崩れで速度感) は崩しすぎると逆効果 | CC2 [一次] | 残像の崩しは控えめ | skill 側 |
| スミア (smear) で速度を表現。体の一部を複製 | Spider-Verse [二次] | 1-2t だけ極端に歪んだ破片を挟む [推論] | 部分: rig の trails / boards |
| 必殺技では 3D のダイナミックなカメラ、通常は固定 | GGXrd [二次] | 決め技のみ camera 演出 (`screen_flash` 連鎖+揺れ) | 部分: cinematic camera gateway は shake/zoom が stub [実コード: `PaperCameraGateway` KDoc] |

## 5. 取得失敗・未検証

- Overwatch / Valorant / Arcane / 原神・崩壊スターレイル / Monster Hunter の VFX 設計・DMC / FromSoftware の専用資料: **一次資料が見つからなかった**。
- Hades (Supergiant) の 80.lv 記事は本文取得不可。GGXrd のスライド PDF (https://www.ggxrd.com/Motomura_Junya_GuiltyGearXrd.pdf) は取得失敗。
- Spider-Verse のインパクトフレームの具体は裏付け無し。Battlefield 6 "Game Feel is the Message" (GDC schedule) は 403。
- CGWORLD MASTERCLASS 「ヒットエフェクト」 https://cgworld.jp/special/masterclass-online/vol20/page/itou.html は 503。
- 2026 年付けの記事 (CEDEC2026 / Relink / シャドバ WB / GameBusiness 武術講演) は調査時点で WebFetch の要約を信頼している。
