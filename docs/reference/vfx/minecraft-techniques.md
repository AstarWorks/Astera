# Minecraft 技法カード (Display / 粒子 / glyph / 音 / Paper API)

[README](README.md) / タグの意味は README。各カードに **コスト・落とし穴・Astera 対応** を書く。
Astera 対応は 2026-10-23 のコード照合: **対応** = YAML か runtime にある / **部分** / **未対応** / **未確認** (調べきれていない)。
調査の限界: Wynncraft / Hypixel / MCC / Origin Realms / 日本サーバーの内部実装を書いた一次情報は見つからなかった。Qiita / Zenn の記事も検索に出なかった。X / Grok 検索は使えなかった。

## 1. Display entity

| ID | 技法 | 効果 / 実装 | コストと落とし穴 | Astera 対応 | 出典 |
|---|---|---|---|---|---|
| T-1 | transformation 補間 (`start_interpolation` + `interpolation_duration`) | 斬撃・拡大・回転を 1 パケットで滑らかに。終点を 1 回設定して放置 (長い補間 1 回の方が毎 tick 更新より軽い [社内参考]) | 変更は 1 tick に 1 回だけ送信。spawn 直後の同 tick 補間は飛ぶ。回転は最短経路で逆回りしうる (failure-patterns M-3, M-6, M-7) | **対応** (VfxTimeline / rig。sample_ticks で刻む) | https://docs.papermc.io/paper/dev/display-entities , https://minecraft.wiki/w/Display [確認] |
| T-2 | `teleport_duration` (0-59) | 位置・回転を補間移動。粒子的な軌道 | 1.20.2 以降。範囲外は例外 | **対応** (quad 粒子が使用) | https://jd.papermc.io/paper/1.21.8/org/bukkit/entity/Display.html [確認] |
| T-3 | TextDisplay の `background` / `text_opacity` も補間対象か | Wiki は両方 "Interpolated" と記載。通れば alpha フェードをブロックラダー無しで、色更新パケットも減らせる | **要実機検証**: 正本 `vfx-art-direction.md` §5 は「クライアントは色を補間しない」と書く。食い違い。text_opacity は 0-3 が不透明扱い、4-26 は描画されない | 現状は `sample_ticks` ごとの更新で近似 | https://minecraft.wiki/w/Display [確認] |
| T-4 | per-player 表示 (`setVisibleByDefault(false)` + `Player#showEntity/hideEntity`) | 術者には薄い版 (一人称で邪魔しない)、観戦者・被弾者には派手な版 | エンティティ数は視聴者別に増える | **部分**: `IPlayerVisibilityGateway` (show/hide) はある。vfx の出し分け指定 (YAML) は **未対応** [実コード: gateway 実装のみ確認] | https://docs.papermc.io/paper/dev/display-entities [確認] |
| T-5 | Display の glow 輪郭 (`setGlowColorOverride`) | 板やブロックに色付き外周線 (暗い縁取りを shader 無しで) | スコアボードチーム色の上書き。輪郭は Glow 描画に依存し**見え方は未確認**。参考サーバーではビームの縁取りに不向きと判断されていた [社内参考] | **部分**: vfx に `glow_color`、`glow` mechanic あり | https://jd.papermc.io/paper/1.21.8/org/bukkit/entity/Display.html [確認] |
| T-6 | 巨大実体を振る (ItemDisplay scale 2.8-9 + クォータニオン積) | 実体が見えるので迫力が出る。ピボット = translation オフセット、スイング = left、モデル向き補正 = right | remove は更新の次 tick (D-A9)。重い (1 体だが大きい) | **対応**: rig (pivot / rest / tilt / swing) + `item` 要素 | [`re-diverse-patterns.md`](re-diverse-patterns.md) 技法 8 [社内参考] |
| T-7 | 十字断面クアッド・ビーム | TextDisplay 背景のみの quad 4-6 枚 (縦横の十字) で長いビーム。外縁+芯の 2 本。長さ = scale | 1 本 4-6 体。一人称で視線と平行だと点になる (正本 §2 も同様) | **部分**: `line` formation + quad で近い構成は可能。「十字 4 枚ビーム」専用の語彙は **未対応** | [`re-diverse-patterns.md`](re-diverse-patterns.md) 技法 5 [社内参考] |
| T-8 | カリング対策 (`width/height`, `view_range`) | 下端原点にする / 大技の予兆は view_range を上げる | width/height=0 でカリング無効 (負荷増) | **部分**: vfx に `view_range` あり。規模から自動設定は **未対応** | https://minecraft.wiki/w/Template:Nbt_inherit/display_entity/template [確認] |
| T-9 | `item_model` + `custom_model_data` (floats/flags/strings/colors) | 1 つの item_display をサーバーが切替・tint。`range_dispatch` で段階的な見た目 | モデル定義はパック側 | **部分**: vfx `item_model` は対応。`custom_model_data` colors での tint は **未確認** | https://minecraft.wiki/w/Items_model_definition [確認] |
| T-10 | `special: end_cube` の item model | 星空・ポータル質感の面 (亀裂・転移・異界の芯) | ブロック大の固定ジオメトリ | **未対応** | 同上 [確認] |
| T-11 | Blockbench → Display のアニメ authoring | Animated Java (datapack) / BDEngine / DisplayEntityUtils / BetterModel (Paper 側、IK、Molang) | Paper 直結は DisplayEntityUtils / BetterModel。Animated Java は datapack 前提 | **未対応** (`tools/model-forge` は別系統。取り込み経路は無い [実コード: ディレクトリ名のみ確認]) | https://github.com/Animated-Java/animated-java , https://modrinth.com/mod/displayentityutils , https://github.com/toxicity188/BetterModel [確認] |
| T-12 | ModelEngine VFX | MythicMobs から bone 単位の VFX モデルを追従 | 有償・softdep が増える | **未対応** (Astera は外部プラグイン前提にしない) | https://wiki.mythiccraft.io/modelengine/Skills/Mechanics/VFX [確認] |

## 2. 粒子

| ID | 技法 | 効果 / 実装 | コストと落とし穴 | Astera 対応 | 出典 |
|---|---|---|---|---|---|
| T-13 | `DUST_COLOR_TRANSITION` | 1 粒の寿命内で色遷移 (炎 → 煤、白 → 属性色) | scale は 0.01-4 に clamp。寿命は 8-40t | **対応**: `PaperParticleData` が DustTransition を扱う。`content/particles/bastion-s3-ember-dust.yaml` が使用中 | https://minecraft.wiki/w/Particle_format [確認] |
| T-14 | `Particle.FLASH` に Color | 白固定でない属性色の閃光 | Paper 1.21.11 Javadoc は DataType = Color。1.21.8 は無記載。26.x で型を確認 | **未確認** (26.x での型を要確認) | https://jd.papermc.io/paper/1.21.11/org/bukkit/Particle.html [確認] |
| T-15 | `ENTITY_EFFECT` + alpha 付き Color | 半透明で色の付く煙・オーラ | dust は alpha 無し、entity_effect は alpha 有り | **未確認** (`Spell` data は対応 [実コード]) | 同上 [確認] |
| T-16 | `TRAIL` / `VIBRATION` / `SCULK_CHARGE` / `SHRIEK` / `DUST_PILLAR` / `BLOCK_CRUMBLE` | 目標へ流れる線・輪・柱・破片 | vibration は block 目標のみ | **部分**: `trail`、`block_crumble` は content で使用。他は **未確認** | https://jd.papermc.io/paper/1.21.11/org/bukkit/Particle.html [確認] |
| T-17 | 粒子の viewers 指定 / force | 術者・被弾者だけに送る、32 超でも出す | 通常 32 ブロック、force で 512。Minimal 設定で多くが消える (force は貫通) | **未確認** | https://minecraft.wiki/w/Commands/particle [確認] |
| T-18 | 接線ベクトル付き粒子 (count=0 で offset が方向) | 渦・衝撃波。接線方向 + 輪ごとの位相ずらし | 数百粒/tick は重い (D-A1, D-A2) | **部分**: emit の `direction: tangent` あり (quad 粒子)。vanilla 粒子での count=0 方向指定は **未確認** | https://minecraft.wiki/w/Particle_format [確認] / [`re-diverse-patterns.md`](re-diverse-patterns.md) 技法 6 [社内参考] |
| T-19 | 粒子図形ライブラリ (EffectLib 等) | 円・螺旋・星・球・ベジェ | Astera は自前 formation (ADR-0220) | **対応** (自前) | https://github.com/elBukkit/EffectLib [確認] |

## 3. 画面・視点・ヒット感

| ID | 技法 | 効果 / 実装 | コストと落とし穴 | Astera 対応 | 出典 |
|---|---|---|---|---|---|
| T-20 | `camera_shake` | 視点を 1 回ずらす | **実装は 1 回の `setRotation` に乱数** (`PaperPlayerFeedbackGateway.cameraKick`) で、減衰する揺れではない。`ICameraGateway.shakeCamera` は Phase 1 stub (入力検証のみ) [実コード]。Java 版にバニラの camera shake は無い | **部分** (単発の kick のみ。使用: sigil-rifle-ego, vendetta-rifle-ego) | [実コード] / Bedrock の `/camerashake` は Java 非対応: https://digminecraft.com/game_commands/camerashake_command.php |
| T-21 | hurt animation による方向付き画面傾き | 打撃方向に視点が傾く低コストの kick | `LivingEntity#playHurtAnimation(yaw)`。yaw 0=正面 / 90=右 / 180=後ろ / 270=左。本人の「ダメージによる傾き」設定で弱まる。ダメージ源で向きが誤る既知バグ MC-271326 | **部分**: `BukkitEventAdapter` が `playHurtAnimation(0f)` を使用 (被弾時)。skill YAML から方向付きで呼ぶ mechanic は **未対応** | https://meta.denizenscript.com/Docs/Mechanisms/EntityTag.play_hurt_animation , https://bugs.mojang.com/browse/MC-271326 [確認] |
| T-22 | ヒットストップ (対象固定 + 揺れ + 音を同時に) | 一撃の重さ | Paper 向けの既製プラグインは見つからず、自前実装。Mod の Savaru's Hitstop は「アニメ停止・画面揺れ・被弾 mob 停止」を同時に行う | **対応**: `hit_stop` (対象と `include_caster`)。vfx 側は `freeze` 窓。強度から自動導出は **未対応** | https://moddex.gg/mod/savarus-better-combat-hitstop [確認] |
| T-23 | `screen_flash` (title に overlay glyph) | 全画面の白閃・暗転・衝撃枠・速度線 | title を使うので既存 title を上書き (文字は subtitle / action bar へ)。見せる相手は術者と被弾者 | **対応**: white / vignette / gold_burst / speed_diag / speed_radial / impact_star (`MechanicYaml.kt`) | 正本 §2 |
| T-24 | FOV 変化 (移動速度属性) | 突進・溜めで視野を 1-2t 変える | 酔い・操作妨害。決め技限定 | **未対応** [推論: 裏付け URL なし] | [未確認] |
| T-25 | title / subtitle / action bar | 技名の表示、警告 | title は screen_flash と競合 | **対応**: `send_title`, `send_action_bar`, `send_message` | — |
| T-26 | 演出強度のプレイヤー設定 | 混雑した戦闘でも読める。Wynncraft では Spell Hider / Boltslinger Debloat をプレイヤーが自作 | viewer ごとに Display 数・粒子量を間引く設計が要る | **未対応** | https://modrinth.com/project/2l0OORAX , https://modrinth.com/project/uIzw1lUo [確認] |

## 4. glyph / resource pack / shader

| ID | 技法 | 効果 / 実装 | コストと落とし穴 | Astera 対応 | 出典 |
|---|---|---|---|---|---|
| T-27 | font provider (bitmap / space) | 任意 PNG を glyph 化 (ADR-0239 の基盤)。space で字送り調整 | bitmap 1 枚 ≤ 256×256 px。height / ascent で縦位置・倍率が変わる | **対応**: sprite (ADR-0239)、`screen_flash` overlay | https://minecraft.wiki/w/Font , https://github.com/AmberWat/NegativeSpaceFont [確認] |
| T-28 | スプライトシート Display 粒子 | TextDisplay + 独自フォントの 4-n フレームを 2t 刻みで差し替え。1 粒 = 1 エンティティで、数百粒を毎 tick 出すより軽いことが多い ([社内参考]) | 1 エンティティ/インスタンス (ADR-0220 の display 上限)。binary alpha | **対応**: `type: sprite` (`frame_ticks`, `loop`, `color`) | ADR-0239 |
| T-29 | core shader `rendertype_text` の色キー | glyph / TextDisplay に wobble / jump / rainbow / blink | 他の text shader pack と競合。バージョンで壊れる (M-15)。**画像 glyph にも色キーが効くかは未確認** | **未対応** (shaders ディレクトリ無し) | https://docs.nexomc.com/addons/core-shaders/text-effects-by-akis [確認] |
| T-30 | core shader `text_background` | quad 板に加算風・グラデ・揺れ | **加算ブレンド化できるかは未確認** (ブレンド状態は RenderType 側) | **未対応** | https://github.com/McTsts/Minecraft-Shaders-Wiki/blob/main/Core%20Shader%20List.md [確認] |
| T-31 | core shader による画面全体の演出 | 画面遷移・歪み・色調 | 製品ページ未取得 (403)。仕組みは未確認。onnowhere/core_shaders (CC0) が土台 | **未対応** | https://github.com/onnowhere/core_shaders [確認] / https://builtbybit.com/resources/screenshader.34990/ [403・検索結果のみ] |

## 5. 音

| ID | 技法 | 効果 / 実装 | 落とし穴 | Astera 対応 | 出典 |
|---|---|---|---|---|---|
| T-32 | 音の層 (同じ音を 2 ピッチ同時 / 低音ベース + 高音トップ) | 厚み | 音のインフレ (D-A6)。重要度で格を決める | **対応** (`play_sound` を同 tick に複数) | [`re-diverse-patterns.md`](re-diverse-patterns.md) 技法 4 [社内参考] |
| T-33 | ピッチの弧 (チャージ = 上昇、スイング/減衰 = 下降、頂点で無音 → 一撃) | 期待を作る。最も安く効果が大きい | pitch は 0.5-2.0 に clamp | **部分**: `play_sound` に `pitch` / `volume` / `delay_ticks` / `period_ticks` があり、弧は `delay_ticks` 違いの `play_sound` を並べて手書きできる (`pitch` は literal か `cel` 式。loop 内の添字を式で参照できるかは **未確認**。上昇弧の宣言構文は **未対応**)。clamp は `SoundSpec.MIN_PITCH..MAX_PITCH` [社内参考] | 同上 |
| T-34 | `stopsound` で溜め音を切って着地音を際立たせる | 無音 → 一撃 | — | **未対応** (stop 系 mechanic が無い [実コード: `MechanicYaml` の type 一覧]) | [`re-diverse-patterns.md`](re-diverse-patterns.md) [社内参考] |
| T-35 | 関係者だけに再生 / `SoundCategory` | 迷惑音の回避 | `volume 10000` は他者に迷惑 (D-A3) | **部分**: `play_sound` の `targets` で対象を絞れる | — |
| T-36 | 音階テーブル (`2^(n/12)`) で和音・アルペジオ | 音階 chime、3 和音 | — | **未対応** (宣言構文なし。pitch の literal を手計算で入れれば書ける) | [`re-diverse-patterns.md`](re-diverse-patterns.md) [社内参考] |
| T-37 | 現状の音の使い方 | skill 59 / 84 ファイルが `play_sound` を使い、ほぼ vanilla の sound key (`entity.player.attack.strong` が約 80 回で最多)。crimson-chain-axe の s1-s3 / ego は音ゼロ | — | [`next-improvements.md`](next-improvements.md) の 1, 2 | [実コード: `content/skills` を grep] |

## 6. 調査の限界・取得失敗

- Hypixel / MCC (Noxcrew) / Origin Realms / 日本サーバーの手法: 一次情報なし。YouTube 個別動画・r/MinecraftCommands の個別スレ・Qiita/Zenn: 取れていない。
- 検索要約でしか確認できなかったもの: BetterHurtCam (https://modrinth.com/mod/betterhurtcam、存在のみ)、Epic Fight Impactful (存在のみ)、ScreenShader (403)。
- pgcquake (https://modrinth.com/mod/pgcquake) はクライアント MOD で、サーバーのみのプラグインでは使えない。
