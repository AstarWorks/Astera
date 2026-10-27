# 次にやる改善トップ 10

[README](README.md) / タグは README。2026-10-23 時点の `content/skills` `content/vfx` を読んだ結果に基づく提案 (**未合意。着手前に founder に確認**)。
順位は 影響 ÷ コスト。コストは「YAML だけ」を小、「新しい mechanic / 構文が要る」を大とした。
検証は共通して `make vfx-shot SKILL=<id>` (caster / opponent / side の 3 視点、1 frame = 1 tick) と `tools/skill-compare` (参照動画との同時刻比較)。
Limbus 参照武器は [統一手順書](../../contributing/limbus-skill-reproduction.md) の合格条件が優先。**参照動画に無い演出を足す前に、動画側の音・ヒットストップの有無を確認する**。

## 現状の観測 (grep / YAML 読解)

- `play_sound` を持つ skill は 84 ファイル中 59。サウンドは vanilla の key が大半で、`entity.player.attack.strong` が最多。ピッチは `1.2`〜`1.8` の単発指定が目立つ。
- `hit_stop` は全 26 箇所のうち **23 が 2 tick** (他は 3 / 4 / 30 が各 1)。`hit_stop` を持つのは bastion-greatsword の s1〜s3・ego と、各武器の ego のみ。
  couture-rapier / crimson-chain-axe / edict / ember-gauntlet / gossamer-katana / orrery / sigil-rifle / vendetta-rifle / verdict-hammer の **s1〜s3 には 1 つも無い**。
- 音ゼロの skill: `crimson-chain-axe-{s1,s2,s3,ego}`、`edict-procuration-{s1,s2,s3}`、`edict-ego`。
- `camera_shake` は 5 箇所 (sigil-rifle-ego, vendetta-rifle-ego, bastion-greatsword-s3)。`screen_flash` は 33 箇所で bastion-s3 に 12。crimson-chain-axe と edict-procuration の s1〜s3 は 0。
- `stopsound` 相当の mechanic は無い。telegraph の雛形は `content/vfx/bastion-s3-telegraph.yaml` (地面リング) のみ。

---

## 1. 音ゼロの skill に音の層を入れる — crimson-chain-axe-s1/s2/s3/ego

- 根拠: [`re-diverse-patterns.md`](re-diverse-patterns.md) 技法 4、[`failure-patterns.md`](failure-patterns.md) G-25。音は体験の半分 (Vlambeer、[二次])。
- 変更: `content/skills/crimson-chain-axe-*.yaml` に `play_sound` を足す。振り (低音 + 高音の 2 ピッチ同時)、命中 tick の transient (`delay_ticks` を damage と同 tick)、着地・多段の最終打は低音を 1 つ足す。
  `bastion-greatsword-s3.yaml` の音の積み方 (15 箇所) を手本にする。vanilla key で足りなければ `generate-sounds` skill で "sharp transient, short tail" を指定。
- コスト: 小 (YAML のみ)。 影響: 大。
- 検証: vfx-shot の動画に音は入らないため、(a) YAML で damage tick と `play_sound` の `delay_ticks` を突き合わせ、(b) 実機で聞く。skill-compare で参照動画の音の onset (`analysis.json`) と tick を比べる。

## 2. edict-procuration-s1〜s3 / edict-ego にも音を入れる

- 同上。edict-procuration-s3 は `play_vfx` 19 個なのに音ゼロで、視覚と聴覚の釣り合いが最も悪い。
- 変更: 各 beat の `play_vfx` に対応する `play_sound` を同 tick に。連続する斬撃は pitch を段階的に変える (一般原理: [`re-diverse-patterns.md`](re-diverse-patterns.md) 技法 4)。音の選択・並びは Astera 独自に設計する。
- コスト: 小。 影響: 大。 検証: 1 と同じ。

## 3. ヒットストップを重さ別に階層化する — verdict-hammer-s1/s2/s3, ember-gauntlet-s1〜s3

- 根拠: [`failure-patterns.md`](failure-patterns.md) G-11 (全ヒット同じ長さ)。実測で 23/26 が 2 tick。
- 変更: ハンマー (verdict-hammer) と拳 (ember-gauntlet) の s1〜s3 に、命中 beat で `hit_stop` を追加。重さの目安は軽 1-2t / 重 3-4t / 決め 6t ([`industry-techniques.md`](industry-techniques.md) §1)。
  verdict-hammer-ego は 4 回とも 2t → 最終打だけ 5-6t に。多段は各 beat で小さく足し、最後だけ大きく (重複ヒットは停止を足す)。
  vfx 側は `play_vfx` の `freeze` を同 tick に渡す (正本 §7)。多人数戦では短めに (止まった相手を第三者が殴れる)。
- コスト: 小。 影響: 大。
- 検証: vfx-shot の `frame_NNN.png` で命中 tick 前後の rig が止まっているか。skill-compare で参照動画の停止フレーム数と比べる (参照に停止が無ければ入れない)。game-sim で damage tick が動かないことを確認。

## 4. 最終打だけ大きく — 多段技の終端 (verdict-hammer-ego, vendetta-rifle-ego, ember-gauntlet-ego)

- 根拠: G-12、Limbus のコイン型と整合 (正本 §4 の最終行)。vendetta-rifle-ego は `hit_stop` が 5 つ・`play_sound` が 11 で、既に多段。
- 変更: 最終 beat のみ `screen_flash` (`white` 1t → `gold_burst` 2t)、`camera_shake` (intensity 0.2 以上)、`hit_stop` を 1 段上げる。途中の beat は粒子を間引く。
- コスト: 小。 影響: 中〜大。
- 検証: caster view で最終 tick の画面が埋まりすぎないか (G-4)。白フレームが連続しない (G-14)。光過敏を考え、白の間に暗転を挟む。

## 5. telegraph を大技の前に置く — bastion-s3-telegraph.yaml を雛形に横展開

- 根拠: [`re-diverse-patterns.md`](re-diverse-patterns.md) 技法 2 (外縁は実寸で即時、内塗りは着弾までかけて満ちる)。予兆の形 = 判定の形 (正本 §0.5)。
- 変更: 現状の `bastion-s3-telegraph.yaml` は 1 枚の脈動リング。**2 枚重ね (外 = 即時で全径、内 = 着弾までかけて全径)** にして「着弾までの時間」が見えるようにする。
  同じ構成を、遠距離・広範囲の候補 `content/vfx/edict-procuration-distant-fan.yaml` / `edict-procuration-distant-rakes.yaml` と `crimson-chain-axe-quake.yaml` に適用する (**各 YAML の判定範囲を読んでから**。範囲と一致しないなら適用しない)。
- コスト: 中 (Display を 2 倍にするので 60 体上限に注意。正本 §5)。 影響: 中〜大 (PvP の可読性)。
- 検証: opponent view で予兆 tick から着弾 tick まで外縁に内塗りが追いつくか。E2E で同時 Display ≤ 60。

## 6. 大技 (s3 / ego) の音と色をフェーズで上げる — orrery-ego, crimson-chain-axe-ego, ember-gauntlet-ego

- 根拠: 長い技はフェーズごとに色・密度・ピッチを上げると飽きさせない ([`re-diverse-patterns.md`](re-diverse-patterns.md) §3.2 の一般原理)。具体的な段階・数値は各武器のコンセプトから Astera 独自に決める。
- 変更: 持続のある ego (例: `crimson-chain-axe-ego-orbit.yaml` の orbit) の `color_over_life` を淡 → 濃に、`emit.count` を段階的に増やし、`play_sound` のピッチを 3 回に分けて上げる。最後に一度無音にしてから最終打 (stopsound 相当は未対応なので、溜め音を短く切って `delay_ticks` で空白を作る)。
- コスト: 中。 影響: 中〜大。 検証: skill-compare で時間軸に沿った色・密度の変化をコマ送り。

## 7. 命中点の sprite を横展開する — bastion / edict / verdict / vendetta の impact

- 根拠: ADR-0239。命中 sprite を使っているのは `couture-rapier-burst` (glint) と `crimson-chain-axe-finish` (blood-splash)、`couture-patterning-impact` 等に限られる。`content/textures/generated/sprites/` には burst / fire-burst / frost-shatter / holy-glint / orrery-impact など既製がある。
- 変更: `bastion-impact.yaml` / `edict-impact.yaml` 等の命中 vfx に、既製 sprite を 1 つ足す (`frame_ticks` 1-2、`loop` 無し)。ヒットスパークは 1 エンティティで足りる (1 sprite ≒ 数百粒より軽いことが多い、[実コード: 観測])。
- コスト: 小〜中。 影響: 中。 検証: side view で接触点に絵が出るか。Display 数の増分を E2E で確認。

## 8. 決め技の画面演出を連鎖させる — crimson-chain-axe-s3 / edict-procuration-s3 / couture-rapier-s3

- 根拠: `screen_flash` が 0 の高重要度 skill。vfx-demo-cutin の連鎖 (`white` 1t → `gold_burst` 2t → `speed_diag` / `speed_radial` 2t) と、インパクトフレームの連鎖切替 ([`industry-techniques.md`](industry-techniques.md) §3)。
- 変更: 最終 beat に `screen_flash` を `delay_ticks` で 1t ずつずらして 2-3 本。対象は caster と target のみ。title を使うので既存 title と競合しないこと (正本 §2)。
- コスト: 小。 影響: 中。 検証: caster view で連続フレームを確認。参照動画に画面フラッシュが無ければ入れない。

## 9. 白黒レビューを手順に入れる (value contrast)

- 根拠: 桜井「キャラを立てるデザイン」(白黒で確認、[題のみ])、[`failure-patterns.md`](failure-patterns.md) G-3。
- 変更: skill / vfx 追加時のレビューで、vfx-shot の `frame_NNN.png` を `ffmpeg -vf format=gray` で白黒にして、主役が残るか・暗い縁が効いているかを見る。正本のチェックリストに 1 行足す (済)。
- コスト: 小 (手順のみ)。 影響: 中 (全武器の可読性)。 検証: 白黒で読めない tick を記録して直す。

## 10. TextDisplay の背景 alpha 補間を実機で検証する (T-3)

- 根拠: Minecraft Wiki は `background` / `text_opacity` を "Interpolated" と記載。正本 §5 は「クライアントは色を補間しない」。**食い違い**。
- 変更: `demo-quad-sparks` か専用の 1 枚で、`interpolation_duration` を 10t にして背景色 alpha を 255→0 に 1 回だけ送り、vfx-shot で毎 tick のフレームを見る。滑らかに落ちれば `sample_ticks` ごとの更新パケットを減らせる (quad 粒子・風切りのフェード)。段階的なら現行のまま。結果を正本 §5 に反映。
- コスト: 小 (検証のみ)。 影響: 中 (全 quad 粒子の負荷)。 検証: 上記の frame 列。

---

## 技の格ラダー (レアリティ別の表現ラダー) の適用

Astera の `Rarity` (COMMON〜LEGENDARY) は人格武器ではほぼ epic で、s1〜ego の格を分けない ([`re-diverse-patterns.md`](re-diverse-patterns.md) §3.5)。
そこで **技の格 = importance 1-10** を軸にして、階層表を適用する。

| 格 | 想定 | 今の穴 (観測) | 次の一手 |
|---|---|---|---|
| 4-5 (s1) | Rare 相当: 2 層 + 2 音 + 予備動作 | crimson-chain-axe-s1 / edict-procuration-s1 は音ゼロ | 改善 1, 2 |
| 6-7 (s2/s3) | Epic 相当: telegraph 必須 + 溜め → 解放 → 余韻 + 音 3-5 | telegraph は bastion-s3 のみ。hit_stop は bastion のみ | 改善 3, 5 |
| 10 (ego) | Legendary 相当: 3 フェーズ + 専用音 + 色相遷移 + 専用画面演出 | ego の音・画面は武器ごとに差。crimson-chain-axe-ego は音ゼロ | 改善 6, 8 |

- コストの連動: 尺を伸ばしたら `cooldown_ticks` / `ego_cost` / `resource_cost` も上げる (演出が長い = 隙も長い)。参考サーバーの観測倍率は参考値で、そのまま使わない。
- 参考サーバーで見られたアンチパターンを避ける: 完全固定 70-100t、粒子の暴力 (1 tick 数百〜数千粒)、威力が小さいのに演出だけ大きい。
- **未対応で必要になるもの (大コスト、今回の 10 件には入れない):** 音の弧の宣言構文、`stopsound` 相当、telegraph 専用要素、`lane` 分類、viewer ごとの出し分け、演出強度のプレイヤー設定、減衰する camera shake。

## 候補 (ピクセル作法 P7 / P12 / lint, 2026-10-28)

出典と考え方は [`pixel-vfx-craft.md`](pixel-vfx-craft.md)。どれも未合意の候補で、着手は別の Board item。

- **P7 命中対象の白シルエット 2 フレーム**: 現状 no knob yet -> candidate。対象を一瞬白く塗る primitive (命中 tick = `hit_stop` の tick に合わせる) が要る。
- **P12 足りないレイヤ語彙**: 魔法陣 (描画してから回る)、地面の亀裂、せり上がる結晶スパイク、六角セルのシールドドーム、2 フレームごとに再生成する稲妻、跳ねて浮くダメージ文字、三本爪の筋、竜巻の漏斗。まず sprite 列 + `formation` / `emit` で近似できるか試し、足りないものだけ primitive 化する。
- **P4 lint**: skill の `hit_stop` / `camera_shake` の tick と、vfx の最初のフラッシュ要素の開始 tick が一致するかを検査する。
- **P1 / P10 lint**: sprite PNG ごとの色数 (帯の数) とアルファ値分布 (半透明の縁) を検査する。
- **P6 lint**: pixel クラスの sprite の `size` と要素 `scale` から texel/block を概算し、約 8 未満 / 32 超を警告する。
