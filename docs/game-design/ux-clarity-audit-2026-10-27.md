# Astera 分かりにくさ監査 (2026-10-27)

[`bad-ux-checklist.md`](bad-ux-checklist.md) を現在の Astera に当てた監査。新規プレイヤーの journey を、コード / YAML / ADR / handoff の読み取りで再構成して判定した。

## 前提と凡例

- 調査は READ-ONLY。**実機は未確認**。証拠は match-ux track の worktree (HEAD `2f25189e4`)。
- 【事実】= コード/YAML/ADR で直接確認 / 【推測】= 読んだものからの推論 (実機未確認)。
- パスは worktree 相対。`ja.yaml` = `content/languages/ja.yaml`。
- 判定: 不合格 / 要注意 / 軽微 / 保留 / 好み。

## 判定サマリ

| 項目 | 判定 |
|---|---|
| SP-01 / BX-04 目的・主眼 | 不合格 (最重) |
| BX-03 / SP-05 情報過多・注意分散 | 不合格 |
| BX-01 / SP-04 学習コスト・オンボーディング | 不合格 |
| SP-06 用語不統一 | 不合格 |
| SP-03 隠れたルール | 不合格 |
| SP-02 フィードバック不足 | 不合格 |
| BX-06 選択の結果が見えない | 不合格 |
| BX-07 進行状況・次の一手 | 要注意 |
| BX-08 迷子 / BX-05 移動 | 要注意 (実機未確認) |
| BX-09 前提条件の手間 | 軽微 |
| BX-02, 10, 13 | 保留 |
| BX-11, 12, 14 | 好み → founder 判断 |

---

## 項目別

### SP-01 目的が3秒で言えない / BX-04 主眼が揺れる — 不合格 (最重)

- 証拠:
  - 【事実】勝利条件が二重。モード目的 (例: TNT を届ける) と Norma Lv5 到達が同時に勝ち条件になる。`norma_win: true` が全モードで既定。(`content/norma-layer/layer.yaml`, `NormaConfig.kt`)
  - 【事実】Norma Lv は力を与えず、勝利・タイムアップ順位・HUD にのみ効く。(`.claude/state/handoffs/match-ux.md`)
  - 【事実】Norma サイドバーは「ゴール無しでスコアのみ」が既知ギャップ。(`playable-loop-checklist.md`)
  - 【事実】Norma 方針は match-ux track で founder 判断待ち。案 A: 専用モードのみ / 案 B: RTM 風アンロック。
- 直し方向: 勝利条件を1つに揃えて1行で常設表示する。どちらに揃えるかは Norma 方針の決定次第 (本書では方針を推さない。A/B は founder 判断)。

### BX-03 不要な情報 / SP-05 注意の分散 — 不合格

- 証拠:
  - 【事実】試合開始約10秒に次が同時に出る: カウントダウン (5..1) + 目的タイトル + チャット briefing (勝ち方・初回ガイド・Norma/制限時間行) + Norma 選択 GUI の自動オープン (10秒で自動クローズ) + サイドバー最大15行 + 目的バー + 満腹度バー(=エネルギー) + HP/弾薬ライン + クールダウンアイコン。(`MatchBriefingListener.kt`, `MatchCountdownListener.kt`, `NormaChoicePrompter.kt`, `MatchSidebarModel.kt`)
  - 【事実】briefing はチャットのみで流れる。GUI が戦闘前の視界を塞ぐ。
  - 【推測】新規は一度に処理しきれず、重要情報 (目的) が埋もれる。
- 直し方向: 開始時の情報を段階化 (後述)。常時必要なものと一度きりのものを分ける。

### BX-01 学習コスト / SP-04 オンボーディング過多 — 不合格

- 証拠:
  - 【事実】概念は約30 (概念一覧参照)。通貨/レベル系が5種 (GOLD, GEM, STAR, プレイヤー Lv/XP, Norma Lv)。
  - 【事実】epic 武器は 5 入力 + E.G.O ゲージ。ステータス効果は16種 (bleed, burn, tremor, rupture, poise …)。
  - 【事実】チュートリアルは10ステップ (menu, range, rack, primary, secondary, reload, staff, hud, norma, queue)。ADR-0242 は Proposed で、CI と live playtest 待ち。実プレイ検証されていない。
  - 【事実】チュートリアル報酬 50 GOLD の出処と achievement `tutorial-graduate` の reward_gold 25 が食い違う。
- 直し方向: 初回に見せる概念を絞り段階解放。チュートリアルを短く検証してから出す。

### SP-06 用語不統一 — 不合格

- 証拠:
  - 【事実】「ノルマ」が3つの別物を指す: 試合内レベル層 (Norma Lv) / デイリー・週間ミッション (`content/norma/*.yaml`) / レガシーのレーン名 (`queue.yaml`)。
  - 【事実】リロードキーが矛盾: チュートリアル/ピストルは Q、`combat.cast.insufficient.ammo` は「Shift+Qでリロード」。
  - 【事実】`tip.2` は「Fでバインドしたスキルを発動」と言うが、実際の F はメインメニュー。(`ja.yaml` tip.2, `lobby-kit.yaml`)
  - 【事実】スターと通貨、プレイヤー Lv と Norma Lv も名前・役割が近く紛らわしい。
- 直し方向: 用語集を決め、3つの「ノルマ」を別名にする。キー表記を1箇所に集約して全画面で揃える。

### SP-03 隠れたルール — 不合格

- 証拠:
  - 【事実】ロビー操作が隠しキー依存 (F / Shift+F / コンパス Q / クラフト枠)。常時ヒントはなく、アイテム lore のみ。
  - 【事実】武器制限 (gun1/sword1/staff1/total2)、死亡時スター50%ドロップ (`death_drop_ratio 0.5`)、スターは通貨ではなく持ち出せない、の説明は `match_menu.help.*` というメニュー奥にある。
  - 【推測】属性/要素マトリクス、ジェネレーター/アノマリー (time_warp 等) はゲーム内説明が見つからない。
- 直し方向: 必要なルールを使う場面の近く (HUD / lore / 初回ガイド) に出す。

### SP-02 フィードバック不足 — 不合格

- 証拠:
  - 【事実】武器 lore は「トリガー: スキル名」のみで、ダメージ/射程/コスト/効果の説明がない。ツールチップ provider は Rarity と RefinementTier のみ。
  - 【事実】epic 武器の en lore が生 id (`left_click: Thread Cut`)。
  - 【事実】終了理由が「スコア閾値到達により終了」(`match.score.threshold_reached`)。Norma 勝利でも「目標達成」と出る既知ギャップ。「目標 x{count}」「スコア閾値」は未説明のジャーゴン。
  - 【事実】結果表示はチャットのみで、`returnDelaySeconds=8` 後にロビーへ転送。後から見るには `/astera stats last` が必要。
  - 【推測】status の意味 (tremor/rupture/poise 等) の説明テキストが見つからない。
- 直し方向: 武器効果の可視化、終了理由の言い換え、結果をチャット以外にも残す。

### BX-06 選択の結果が見えない — 不合格

- 証拠:
  - 【事実】試合開始時に Norma 選択 GUI (「ノルマを選ぼう」) が自動で開く。ゴールは STAR (10,30,60,100) か KILL (1,2,3,4) を Lv ごとに選ぶ。
  - 【推測】新規は STAR / KILL の意味 (スター=アイテム、ノルマ=レベル) も、選ぶと何が変わるかも分からない。
- 直し方向: 選択前に結果を示す。Norma 方針の決定とセットで検討。

### BX-07 進行状況・次に何をするか — 要注意

- 証拠:
  - 【事実】メインメニューに12以上のタイルがあり、順序付けも「まずここ」の誘導もない。(`content/gui/main-menu.yaml`)
  - 【事実】GEM の入手・用途の in-game 説明が見つからない。報酬 XP とプレイヤーレベルの意味付け、Norma Lv との違いも未説明。
  - 【推測】tip が指す Refinement / Hotbar タイルはメニューに無く、古い可能性。
- 直し方向: 次の1手を示す導線 (例: 初回は「遊ぶ」を目立たせる)。

### BX-08 迷子 / BX-05 移動 — 要注意 (実機未確認)

- 証拠:
  - 【事実】マップが140〜259ブロック四方で、目的物の場所が分かりづらい。world shrink は保留中。(match-ux handoff)
  - 【推測】スポーンから最初の交戦までの時間は実測していない。
- 直し方向: 実機で計測してから判断。目的方向の手がかり (マーカー等) も検討。

### BX-09 前提条件の手間 — 軽微

- 証拠: 【事実】新規は GOLD 0 で開始し、最初の購入まで約2試合必要 (【推測】)。PvP レーンは1人では始まらない (min 2、annihilation は3)。ボット戦はピッカーの端にあるだけ。
- 直し方向: ボット戦の見せ方の改善。

### 保留 / 好み

- BX-02, 10, 13: 今回の調査範囲では判定保留。
- BX-11, 12, 14: 好みの問題 → founder 判断。

---

## すぐ直せるバグ級 (quick wins)

1. welcome dialog の i18n キー欠落。`astera.dialog.welcome.astera-welcome.title/body` が ja.yaml に無い (`dialog.welcome.ok` のみ、`content/dialogs` は shopkeeper-greeting のみ)。生キーまたは空表示の可能性 → **要実機確認**。
2. `tip.2` の「Fでスキル発動」記述 (実際の F はメニュー)。
3. リロードキー表記の矛盾 (Q と Shift+Q)。
4. en の epic 武器 lore が生 id。
5. 終了理由文言 (「スコア閾値到達により終了」)、Norma 勝利でも「目標達成」と出る点。

## 構造的な課題

1. **勝利条件の一本化** — founder 判断待ち (Norma 方針 A/B)。
2. **試合開始時の情報の段階化** — briefing / Norma GUI / サイドバー / 各 HUD の出し分け。
3. **通貨/レベルの整理** — GOLD, GEM, STAR, プレイヤー Lv, Norma Lv の関係と数。
4. **武器効果の可視化** — lore に効果説明、status の意味、トリガー/クールダウンの表示。
5. **実機での新規プレイヤー観察** — 最新 e2e (2026-10-22, 27/27 PASS) は機械チェック (日本語 HUD 表示、死亡→リスポーン、終了→ロビー復帰) のみで、分かりやすさの検証ではない。新規プレイヤー体験のスクリーンショット記録も見つからない。

## 未確認事項

- 実機での表示 (welcome dialog のフォールバック、GUI と briefing の重なり、HUD 同時表示)。
- en.yaml の同等性 (epic lore が生 id と確認できた範囲のみ)。
- `pillars.md` / weapons docs の内容、`HitFeedbackListener` の実装詳細 (ダメージ/状態フィードバックの強さ)。
- ロビーのサイドバー実内容 (`LobbySidebarService` の構造のみ確認)。
