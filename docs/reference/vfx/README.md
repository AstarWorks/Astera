# VFX 資料集 (reference/vfx)

> **Re:DIVERSE の Skript・素材は参考のみ。コード・演出・素材の転用/模倣は禁止 (ライセンス)。原理を抽象化して Astera 独自に設計する。**

スキル演出に「迫力」と「Minecraft ならではの表現」を足すための、出典つきの参照集。
人間も AI エージェントも、**skill / vfx YAML を作る・直す前に**ここを読む。
規則の正本は [`docs/contributing/vfx-art-direction.md`](../../contributing/vfx-art-direction.md)、制作と合格条件の正本は
[`limbus-skill-reproduction.md`](../../contributing/limbus-skill-reproduction.md)。この資料集は「なぜそうするか」「他にどんな手があるか」を持つ。
規則と食い違う記述を見つけたら、資料集を直さずに正本側へ提案する (研究メモは仮説を含む)。

調査日 2026-10-23。調査メモの原本は調査セッションの scratchpad にあり、リポジトリには入れていない。

CPU sprite の YAML 制作・ノード一覧・プレビュー手順は [sprite graph](../../contributing/vfx-team/sprite-graph.md) (ADR-0304)。

## 読む順 (skill を作る / 直す前)

1. [`failure-patterns.md`](failure-patterns.md) — 先に「やりがちな失敗」を知る。レビューのチェックリストとして使う。
2. [`minecraft-techniques.md`](minecraft-techniques.md) — 使える部品と、Astera が今対応しているか (対応 / 部分 / 未対応)。YAML に書けないものを書かない。
3. [`industry-techniques.md`](industry-techniques.md) — 溜め・ヒットストップ・音・緩急などの原理を、Minecraft の部品に写像したもの。
4. [`re-diverse-patterns.md`](re-diverse-patterns.md) — Re:DIVERSE (社内参考サーバー) の観測から抽出した一般原理(音のピッチ弧、telegraph、ビーム、レアリティ別の格)。参考のみで模倣しない。
5. [`sakurai.md`](sakurai.md) — 桜井政博の回ごとの要点 (既存の規則に入っていない回を含む)。
6. [`next-improvements.md`](next-improvements.md) — 既存の武器・skill に適用する改善案トップ 10。着手前にここを見て重複を避ける。
7. [`e3-s10129-techniques.md`](e3-s10129-techniques.md) — 外部クリエイター E3 (@s10129) の投稿から抽出したスプライト/パーティクル技法と Astera とのギャップ。値は推論を含む。

8. [`patterns.md`](patterns.md) — 上の全資料を 7 軸・32 パターンに束ねたパターン言語。組み合わせレシピ、E3 の差分、表現できないもの (gaps) の順位つき。全体像をつかみたいときは最初にここを読んでもよい。
9. [`pixel-vfx-craft.md`](pixel-vfx-craft.md) — ピクセル VFX / スプライトの作法 P1-P12 (帯状の熱、形が先、タイミング雛形、衝撃フレーム = ヒットストップ、熱の予算、texel 密度、スプライト衛生など)。外部投稿の原理を自分の言葉で書き直したもの。director / vfx / pixel-artist / reviewer の手順が P 番号で参照する。

作業の流れ: 重要度 (1-10) を決める → 上の 1-4 で部品と失敗を確認 → YAML を書く →
`tools/skill-compare` と `make vfx-shot` で検証 (検証手順は正本側)。

## 出典の信頼度タグ (全ファイル共通)

| タグ | 意味 |
|---|---|
| [一次] | 講演者・制作者本人の資料、または講演レポート記事を実際に開いて確認した |
| [二次] | ブログ・まとめ・解説・要約ページ。数値は「目安」であって規格ではない |
| [題のみ] | 回タイトルと分類は確認できたが本編の中身は取得できていない。タイトルや要約からの推測を含む |
| [推論] | 出典の根拠が無く、調査者が Minecraft への写像として考えたもの。実機検証が要る |
| [確認] | そのURLの本文 (Minecraft Wiki / Paper docs / Javadoc 等) で確認した |
| [未確認] | URL に根拠が無い一般知識・推測。実機で検証すること |
| [実コード] | 本リポジトリのコードを読んだ |
| [社内参考] | 社内参考サーバー Re:DIVERSE での観測 (原理のみ記録。コード・素材は転用しない) |

出典 URL は削らない。取得に失敗した資料は各ファイル末尾に列挙してあり、本文の根拠にしていない。
Astera の対応状況は 2026-10-23 時点のコード (`MechanicYaml.kt`, `VfxYamlConfig.kt`, `content/`) を読んだ結果。変わりうるのでコードが正。

## 現在の語彙 (要約。詳細は contributing 側)

- skill mechanic: `play_sound` (volume/pitch/delay)、`play_vfx`、`screen_flash` (white / vignette / gold_burst / speed_diag / speed_radial / impact_star)、
  `camera_shake` (単発の視点ブレ)、`hit_stop` (対象を固定)、`spawn_particle`、`schedule_particle_animation`、`play_animation`、`glow`、`dash_strike`、`propel` 他。
- vfx 要素: block / item / text / sprite (ADR-0239) / quad 粒子 / formation (line, ring, arc, polygon, spiral, helix, sphere, bezier, lightning) / rig + trails + boards + emitters / `group_keyframes`。
- 未対応の主なもの: skill 内の音の弧 (ピッチ上昇) を宣言する構文、telegraph 専用要素、viewer ごとの出し分け、演出強度のプレイヤー設定、`lane` 分類。各 md の「Astera 対応」欄を参照。

## 目次メモ

- 迫力を出す最短: 音 (層とピッチ弧) → 命中 tick のヒットストップ+フラッシュ → 溜めを見せる (telegraph) → 余韻。
- 「薄い・軽い」と言われたら: [`next-improvements.md`](next-improvements.md) の 1-4 と [`failure-patterns.md`](failure-patterns.md) の G-11, G-12 を見る。
10. [`neac-shader-techniques.md`](neac-shader-techniques.md) — NeAc_ / EMagipi 由来のシェーダー系技法 NA-1〜10 (魚眼床・スキャン帯・床の暗転ほか) と、(a)/(b)/(c) 分類、26.3 の core shader で届く範囲。原理のみ。
- [`スプライトギャラリー`](../../../tools/sprite-gallery/README.md) — `make sprite-gallery-up` で LAN 上の全スプライトをアニメーション比較 (`:8098`)。

- [Shader effects authoring](../../contributing/vfx-team/shader-effects.md) — add an isolated effect in 3 steps; markers, hooks and playable demos (ADR-0305).
