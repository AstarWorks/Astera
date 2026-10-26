# あるある失敗事例 (症状 → 原因 → 修正 → 検出)

[README](README.md) / タグの意味は README。レビュー時のチェックリストとして使う。
ID: **G-** = ゲーム一般 (CEDEC / Capcom / ブログ等)、**M-** = Minecraft 固有、**D-** = Re:DIVERSE (社内参考サーバー) の観測 (アンチパターン)。

## 検出手段 (今あるもの)

自動で失敗を検出する仕組みは現状無い (content-lint は参照 ID と schema のみ)。下の「検出」は**人が既存ツールの出力を使ってやる手順**。

| 手段 | 何が見えるか |
|---|---|
| `make vfx-shot SKILL=<id> [VIEW=…] [TICKS=…]` | 実クライアントの `frame_NNN.png` (1 フレーム = 1 tick)・`sheet.png` (contact sheet)・gif/mp4。出力 `build/vfx-shot/<skill>/<view>/` (`tools/vfx-shot/README.md`) |
| `tools/skill-compare` (`make skill-compare`) | 参照動画・意図 3D・Minecraft フレームを同一 ms 時計で並べ、コマ送り (Shift+←/→ = 1 tick)、focus slow-mo (F)、ヒット位置の記録 |
| `make skill-preview SKILL=<id>` (compare camera, reference | preview | client strip) | rig / 風切りの形を視点別に |
| `/astera skill preview <id> view=… speed=0.25` | ダミーを相手に実機再生。`freeze` / `step` でコマ送り |
| E2E / game-sim | Display 数・damage tick など数値側 |
| 自前の補助 | `frame_NNN.png` を `ffmpeg -vf format=gray` で白黒にして value contrast を見る / 面積を目測する / 音は録画を別途聞く |

「ポーズの列挙」で止まり動き (軌道・回転方向・振り数) を書かない失敗は [contributing/vfx-team/beat-sheet.md](../../contributing/vfx-team/beat-sheet.md) (beat sheet + reverse check + top-down 確認) で防ぐ。

---

## A. ゲーム一般 (G)

出典の性格: CEDEC 講演レポート (CGWORLD / 4Gamer / GameBusiness) は [一次]、英語ブログは [二次]。数値は目安。

| ID | 症状 | 原因 | 修正 (Minecraft 化) | 検出 | 出典 |
|---|---|---|---|---|---|
| G-1 | 画面が全部明るいのに何も読めない | 全 effect が同じコントラストのピークを奪い合う | 「脅威 / 命中 / ステータス / 環境」の 4 レーンに分け、ピークはレーンごと 1 つ。レーンの無い演出は削る | `sheet.png` で各 tick の最も明るい要素を数える。2 つ以上が同 tick に競合すれば違反 | https://gamineai.com/blog/top-down-combat-vfx-readability-2026-color-timing-system-busy-screens [二次] |
| G-2 | 敵の予兆を見逃す | プレイヤー演出と危険色の系統・強度が同じ / 命中演出が長く残る | 危険色は敵予兆専用に予約。命中の減衰は 3-4t 以内。予兆の立ち上がり 2.5-4t・保持 5-11t | 相手視点 (opponent) の frame で、予兆 tick の面が命中演出の残りに隠れていないか | 同上 [二次] |
| G-3 | 色だけで区別していて、色覚差・配信圧縮で崩れる | レーン分離が色相頼み | グレースケールでレビュー。明度・形・タイミングでも分離 | frame を白黒化して主役が残るか | 同上 [二次] / 桜井「白黒で確認」[題のみ] |
| G-4 | 画面全体が埋まる | 単発の命中が視野を覆う / 放射状でアルファとブルームを一緒に上げた | 単発の面積上限 (視野の 12-18% 目安 [二次])。放射状は立ち上げを別に | eye view の frame で目測。D-A7 参照 | 同上 [二次] |
| G-5 | 環境の霧・塵が予兆を隠す | 戦闘中も環境レーンを落としていない | 技の発動中は ambient 粒子を絞る品質プロファイルを持つ | 予兆 tick の frame に環境粒子が残るか | 同上 [二次] |
| G-6 | 加算の演出が明るい背景で消える | 加算 (glow) だけで作った | 暗い芯・縁を混ぜる (正本 §0 の 2)。realtimevfx の作者も「明るい背景で加算の粒が埋もれる」と報告 | 昼の背景 (vfx-shot の既定シーン以外の明るい場所) でも撮る | https://realtimevfx.com/t/lessons-learned-i-remade-one-of-my-vfx-after-2-years/22808 [二次] |
| G-7 | 色が褪せて見える | 色空間 / blend の設定ミス (Godot の例)。Minecraft では brightness override と半透明ガラスの併用で濁る | 色と blend 設定を最初に疑う | 設計色と frame の実色を比べる | 同上 [二次] |
| G-8 | 溜めが機能していない | 溜めの光が mesh 上を滑るだけで読めない | 粒の収束で「集まって見える」演出にするか削る | skill-compare で溜め区間だけ見て「何かが集まる」と読めるか | 同上 [二次] |
| G-9 | 動きが少なく単調 | 全要素が同じ速度・イージング | 要素ごとに変化を非一様に。芯と支援要素を別 slot に | ease が全要素同じ (YAML を grep) | 同上 [二次] |
| G-10 | 放射状の板が不自然 | 板が中心を向いていない | 各板を放射方向に回転させる | top view | 同上 [二次] |
| G-11 | 全ヒットで同じヒットストップ | 攻撃の階層が潰れる | 攻撃クラス別に ticks を変える (軽 1-2 / 重 3-4 / 決め 6) | YAML の `hit_stop` ticks が同値だらけ。[`next-improvements.md`](next-improvements.md) 参照 | https://gamineai.com/blog/pixel-art-combat-fx-hitstop-smear-frames-impact-timing-2026 [二次] |
| G-12 | コンボの全フレームに粒子 | キャンセル窓が隠れる | コンボ中は間引き、最後の 1 打だけ大きく (Limbus のコイン型と整合) | 各コインの tick ごとの Display 数の差 | 同上 [二次] |
| G-13 | 画面揺れが多重に重なる / 酔う | 敵の数だけ揺れが加算 | 注視対象・距離で絞る。酔いが出たら水平を先に削る | 多段技で camera_shake の数を数える | 同上 [二次] |
| G-14 | フラッシュが「スタン」に見える | 長すぎる | 1-2 フレーム (`screen_flash` は fade 0/1/0 を基本に) | 白フレームの連続数 | 同上 [二次] |
| G-15 | 敵が増えると読めなくなる | 低密度シーンだけでレビュー | 最も騒がしい戦闘を固定レビューシーンに。密度 2 倍で比較。足す前に削る | 2 人以上のダミーで撮る | 同上 [二次] |
| G-16 | 可読性の修正が遅すぎる | ルールが仕上げ工程扱い | レーンとタイミングの規約を最初に決める。「ブラインド可読性テスト」(演出を消して何が起きたか当てる) | 演出 OFF の frame と並べて意味が伝わるか | 同上 [二次] |
| G-17 | 印象だけで薄くした結果、情報が消える | 「派手すぎる」を理由に薄く小さくした | VFX を判断情報として設計。ハードエッジ = 芯、ソフトエッジ = 周辺。アンカーを置く。明度は主役に集中 | 削った後に位置・方向・タイミングが読めるか | https://cgworld.jp/flashnews/01-202608-Cygames-CEDEC.html [一次] |
| G-18 | 重なりの順序が破綻 | 描画順の制御不足 | シャドバ WB は加算用・ブルーム用の専用レイヤー計 4 本で厳密化。Display は発生 tick と `view_range` で前後を管理 [推論] | 重なる半透明板の frame | https://cgworld.jp/article/202610-cedec-svwb.html [一次] |
| G-19 | 範囲を覆うはずのものが他に被る | パーティクルの霧は制御できない | ポリゴンで範囲を固定し UV 流しで流体感。Display なら板 1 枚+テクスチャ [推論] | frame | 同上 [一次] |
| G-20 | 白飛び | 輝度を上げすぎ | 輝度を抑え、グローに幅、細いラインを置く | frame の白飛び面積 | 同上 [一次] |
| G-21 | 攻撃と同時に全部光って変化量が過大 | 同時発生 | 攻撃と同時に装置、一拍置いて周囲を光らせ変化量を分散 (`delay_ticks` で 1-2t ずらす) | 命中 tick の frame と +2 tick の差 | 同上 [一次] |
| G-22 | エフェクトの寿命が長く、何度も見たくない | 1 回の長さがテンポに合わない | 小さなヒットは 4-8t。濁る原因は寿命が長い or 粒が多い | 命中から消えるまでの tick を数える | https://gamineai.com/blog/how-to-create-game-vfx-particle-systems-visual-effects [二次、検索要約のみ] |
| G-23 | 発動が遅すぎ / 速すぎる | 重要度に尺が合っていない | 大技は溜めを足し、小技は引く | 重要度と全長の対応表 | https://realtimevfx.com/t/trying-to-develop-my-first-showreel/4701 周辺スレ [未確認、検索要約のみ] |
| G-24 | 最後に負荷予算が残っていない | 演出は開発の最後に積まれる | 最初から Display 数・粒子数の予算を引く (正本 §5) | E2E の Display 数 | https://www.docswell.com/s/CAPCOM_RandD/538NVE-RE2023 [一次] |
| G-25 | ヒットの手応えが確かめられない | 当たった瞬間が見えない / 聞こえない | 命中 tick にだけ transient (音+火花+短い停止) を出す。振っただけでは出さない | 命中 tick の frame で接触点に何かあるか | https://note.com/rr_r612/n/nbc165f80899e [二次] / https://uhiyama-lab.com/en/notes/ue/game-feel-hit-feedback/ [二次] |
| G-26 | 攻撃が消えて何も残らず軽い | 永続性が無い | 焦げ跡・破片・地面のひびを短時間残す (Vlambeer の「permanence」) | 余韻 4-12t に地面側の要素があるか | https://rpgplayground.com/research-making-a-juicy-game/ [二次] |
| G-27 | 刃が見えた位置と判定の tick がずれる | 見た目の通過と damage tick が別時計 | 見た目の通過 tick = damage tick (桜井「攻撃判定を常に意識」[題のみ]) | skill-compare で hit 位置と damage の tick を重ねる | [`sakurai.md`](sakurai.md) |
| G-28 | 盛りすぎてゲームを壊す | 全項目を一度に足した | 1 要素ずつ足して違いを見る (Juice it or lose it の運用) | 要素ごとに ON/OFF の frame を撮って比較 | https://rpgplayground.com/research-making-a-juicy-game/ [二次] |
| G-29 | 強すぎるヒットストップ/揺れが操作を邪魔する | 一律に強い | 強さを調整可能に。MH Wilds は強すぎ→弱め→弱すぎで引き上げと揺れた | プレイテスト | https://mp1st.com/news/monster-hunter-wilds-hitstops-final-game-closer-to-people-are-looking-forward [二次] |

## B. Minecraft 固有 (M)

[確認] = Wiki / Paper docs / Mojang バグトラッカーの本文で確認。[未確認] = URL に根拠なし。

| ID | 症状 | 原因 | 修正 | 検出 | 根拠 |
|---|---|---|---|---|---|
| M-1 | 視線を下げると板・ブロックが消える | 縦のカリングボックスは entity 位置から +height の上方向のみ。中心原点のモデルは下半分が箱の外 | 原点を板の下端に置く or translation で持ち上げる。width/height=0 でカリング無効 (負荷増) | eye view で pitch を下げて撮る | https://minecraft.wiki/w/Template:Nbt_inherit/display_entity/template , https://feedback.minecraft.net/hc/en-us/community/posts/32535223674893-Make-the-height-value-of-display-entities-cull-box-work-like-width [確認] |
| M-2 | 遠距離の display が消える | 距離 > view_range × entityDistanceScaling × 64 で非描画 | `view_range` を上げる (大技の予兆)。vfx YAML に `view_range` あり | 遠距離から撮る | 同上 [確認] |
| M-3 | スポーン直後に補間が効かず瞬間移動する | 新規 display は初回データ受信まで非描画。同 tick に補間を設定すると飛ぶ。ラグ時は補間が丸ごとスキップ (MC-277546 Won't Fix)。ゼロ duration 更新直後の補間開始は一度デフォルトへ戻る (MC-260857) | spawn の次 tick 以降に transformation を送る。Astera の planner が stagger 済みか要確認 [未確認] | 最初の 2 tick の frame | https://bugs-legacy.mojang.com/browse/MC-277546 , https://bugs-legacy.mojang.com/browse/MC-260857 [確認] |
| M-4 | 再入場・チャンク再ロードで補間が飛ぶ/戻る | 補間状態が復元されない (MC-259964 未解決, MC-260208) | 短寿命の演出は再入場で死んでよい設計に。`setPersistent(false)` | 手動 | https://bugs.mojang.com/browse/MC-259964 , https://bugs-legacy.mojang.com/browse/MC-260208 , https://docs.papermc.io/paper/dev/display-entities [確認] |
| M-5 | ラグ/ポーズで複数クライアントの再生がずれる | クライアント時間に依存 (MC-261600 Won't Fix) | 同期が要る演出は短く。ずれても破綻しない構図に | 複数クライアント撮影 (現状は 1 クライアント) | https://bugs-legacy.mojang.com/browse/MC-261600 [確認] |
| M-6 | 回転補間が逆回り・遠回りする | quaternion 補間の最短経路 | 微小オフセット / 180° 超は分割。Astera は rig で 60° 超を細分化、1 tick 170° 超は planner が拒否 (正本 §7) | rig の top view | https://docs.papermc.io/paper/dev/display-entities [確認] |
| M-7 | 回転を同 tick 内で何度送っても反映されない | 変更は 1 tick に 1 回のみ送信 | 1 tick 1 更新に集約 | 手動 | https://minecraft.wiki/w/Display [確認] |
| M-8 | TextDisplay が片面しか見えない | text は片面のみ | billboard center か 2 枚交差 (正本 §1) | 裏から撮る | 同上 [確認] |
| M-9 | 薄い alpha の板が出ない/想定外に濃い | vanilla shader は alpha < 0.1 を discard。background は A < 26 で完全透明、text_opacity は 4-26 が描画されず 0-3 は不透明扱い | 背景 alpha は 27 以上、text_opacity は 4 未満を避ける (正本 §5 の「下限 4」と関連) | 低 alpha 板が frame に写るか | 同上 [確認] |
| M-10 | テキスト/glyph に不要な影 | glyph の影は RGB/4 を 1px ずらして描く | `shadow=false` を明示。画像 glyph は影用の暗縁を持たない設計 | frame | https://minecraft.wiki/w/Font , https://minecraft.wiki/w/Display [確認] |
| M-11 | 遠くの粒子が出ない/設定で消える | 通常 32 ブロック。Minimal 設定で多くが非表示 (force は貫通) | 重要な合図は Display 側にも持たせる | 粒子設定を変えて撮る (現状未実施) | https://minecraft.wiki/w/Commands/particle , https://minecraft.wiki/w/Particles_(Java_Edition) [確認] |
| M-12 | dust が大きくできない | scale は 0.01-4 に clamp | 大きな面は Display、粒立ちだけ dust | — | https://minecraft.wiki/w/Particle_format [確認] |
| M-13 | BlockDisplay に水・溶岩が描画されない | block display は流体を描画しない | glass/ice 系ブロックか quad | — | https://minecraft.wiki/w/Display [確認] |
| M-14 | spawn chunk の display が残り続ける | spawn chunk は unload されない | 必ず `remove()`。`isValid()` false で繰り返しタスクを止める | E2E で残存 Display を数える | https://docs.papermc.io/paper/dev/display-entities [確認] |
| M-15 | core shader pack 同士が競合/バージョンで壊れる | 複数が `rendertype_text` を上書き。z 値の仕組みが 1.19.4 / 1.20 / 1.21.1 / 24w21a で変化 | shader は 1 つのパックで統合管理。Paper 更新時に再検証 | パック更新後の撮影 | https://docs.nexomc.com/addons/core-shaders/text-effects-by-akis , https://github.com/McTsts/Minecraft-Shaders-Wiki/blob/main/Core%20Shader%20List.md [確認] (Astera は現在 core shader 未使用 [実コード: リポジトリ内に `shaders` ディレクトリ無し]) |
| M-16 | 一人称で効果が視界を塞ぐ | (一般論) | 斜め配置、術者は薄く、per-player 表示 | eye view | [未確認]。Wynncraft で演出を小さくするプレイヤー製 MOD がある (Spell Hider / Boltslinger Debloat) ことが傍証: https://modrinth.com/project/2l0OORAX , https://modrinth.com/project/uIzw1lUo [確認(存在のみ)] |
| M-17 | 明るい昼に粒子・発光板が見えない | (一般論) | 暗い縁/暗い粒を混ぜる | 昼に撮る | [未確認] |
| M-18 | 半透明板の描画順が乱れる (z-fight) | (一般論) | 地面すれすれは +0.1 (正本 §2) | top/side | [未確認] |
| M-19 | `camera_shake` が「揺れ」に見えない | Astera の実装は 1 回の視点ずらし (`setRotation` に乱数) で、減衰する揺れではない [実コード: `PaperPlayerFeedbackGateway.cameraKick`] | 決め技の命中 tick に 1 回、多段なら複数 tick に分けて呼ぶ。減衰する揺れは未対応 | vfx-shot の caster view | 本リポジトリ |

## C. Re:DIVERSE (社内参考サーバー) の観測 (D) — アンチパターン

社内参考サーバー Re:DIVERSE の観測から抽出した一般的なアンチパターン [社内参考]。詳細は [`re-diverse-patterns.md`](re-diverse-patterns.md)。

| ID | 観測 | 影響 | Astera での回避 |
|---|---|---|---|
| D-A1 | 毎 tick `count 100 offset 4` の粒子を 300t | 画面ノイズ + パケット爆発 | 詠唱中は低頻度 (4-10t) のパルス + Display 1 個の見た目で代替 |
| D-A2 | 魔法陣を 30 点 × 2 粒 × 6t で 3 重に粒子描画 | 5 秒で数千パケット | 魔法陣は板 1 枚のフェードイン。粒子は縁のみ |
| D-A3 | 全員に聞こえる `volume 10000` | 音割れ/迷惑 | 関係者だけに再生。`SoundCategory` を使い分け |
| D-A4 | 毎 tick の近傍エンティティ検索 | CPU | 1 キャスト 1 回 or 判定を 2-4 tick に間引く |
| D-A5 | 判定が演出と別 (外輪が実判定と無関係) | 読めない | 判定半径 = 見た目半径 |
| D-A6 | 同じ爆発音を多用 | 音のインフレ | 音の「格」を決め、大技のみ |
| D-A7 | 技が重なって画面が真っ白 (boom scale 90 + flash + flame 200) | 視認性ゼロ | 明度 15 の Display を重ねすぎない。面積上限 (scale ≤ 30 目安) |
| D-A8 | 演出中に完全固定 (velocity=0 を 70t) | 被弾リスク・テンポ悪 | 固定は 20t 以内。固定中は無敵 or 軽減 |
| D-A9 | Display 削除タイミングのバグ (「なぜかバグる」) | 残骸 | 更新の次 tick で remove。タグ+ラウンド番号で一括掃除 |
| D-A10 | 毎 tick `execute console command "particle trail …"` | コマンドパース負荷 | Paper API の `spawnParticle(Particle.TRAIL…)` (Astera は `Particle.Trail` 対応済み [実コード]) |
| D-A11 | ピッチを 0.5-2 の外に出すとクランプされる | 意図通り鳴らない | ラッパで clamp (Astera の `play_sound` は pitch を `SoundSpec.MIN_PITCH..MAX_PITCH` に clamp する [実コード: `MechanicDispatcher`]) |

## 取得失敗・未確認

- realtimevfx は個別スレッドを数本確認しただけ。Diablo IV (Blizzard) の「騒がしい FX は大技のために取っておく」は検索要約のみ
  (https://www.wowhead.com/cn/news/physical-based-rendering-and-visual-effects-diablo-iv-quarterly-update-325416 、本文未取得) [二次]。
- Suicide Squad (Rocksteady) の可読性講演 https://schedule.gdconf.com/session/attention-overload-creating-readable-vfx-for-suicide-squad-kill-the-justice-league/899824 は 403 で内容未取得。
