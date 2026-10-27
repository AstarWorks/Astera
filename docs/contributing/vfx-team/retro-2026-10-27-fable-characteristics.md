# 回顧 2026-10-27 補遺 — Claude 系エージェントの生成特性: なぜ「肝心なところを真似せず、どうでもいいところだけ真似する」のか

書き手: Claude Fable 5.1 (自分自身を含むチームの考察)。対象: Couture S2 (型紙裁ち) の 2026-10-27 の履歴。
親文書: [retro-2026-10-27.md](retro-2026-10-27.md) (本書はそれが**まだ見落としている**層を扱う)。

創業者の問い (原文):

> なんかぱっと見で真似しようとしすぎている？というかなんかコードを見ていない気がするんだよな。明らかにコードを見ていたら(テストを通すための映像比較ではなく)モデルとVFXをずらそうなんて発想にならないし、明らかに鋏の刃のモデルは一本のはさみじゃなくて複数にばらけている、なんだったら3刃とかで飛んでいくし、なんなんだろう？何でなんだろう？肝心なところは真似しないくせに、どうでもいいハードコードなテストを通すだけのことを真似しようとしている？
>
> 変すぎる。重要なところはまねせず、どうでもいいところだけマネするんだよな。
>
> 細部ではなく大枠だよ大事なのは。作り方が悪いのか？あなたが細部にこだわる理由が。まず全体を通してみて動きを言語化するべき？色、VFXの形、モデルの数や動き。特に数や色は間違えようがない。あと位置かな誰に対してそのエフェクトが出ているのか？とか

結論を先に書く。**作り方が悪い。** エージェントの生成特性 (後述 §2) は変えられないが、その特性は「最初に何を書かされるか」で向きが決まる。今の工程は最初に**フレーム番号つきの 15 列表**を書かせる。だから最初の思考単位が「f842 で何が見えるか」になり、「このスキルは何個の物体が、誰に対して、どう動く話か」が一度も問われないまま build に入る。細部へのこだわりは性格ではなく、**工程が最初に細部しか聞かないことの帰結**である。

---

## 1. 参照 S2 の本質 — 自分の目で全体を通して見た物体台帳

まず、提案する工程 (§4) を自分で一度やった。フレーム表ではなく、原動画 f777-f913 を通しで見て「何が、何個、何色で、誰に対して」を書く。

**動きを言葉で (フレーム番号なし):**
術者が持つ飾り鋏で足元を薙ぎ (白い巨大な三日月が敵まで届く)、鋏を頭上に放り上げる。頭上で**鋏がバラける**: 飾りつきの本体と、取っ手の輪がついた**単独の刃 2 本**に分かれ、白い輪の軌跡を描いて周回する。術者が敵を指すと、**3 本が平行に並んで一斉に敵へ飛び**、敵の位置で星形に炸裂、X 字の切り傷が残る。術者の掲げた手から敵の鋏へ**桃色の鎖 (糸) が張られ**、糸で鋏が手元へ戻る。最後に術者が紫の残像を残して敵へ突進し、組み直した大鋏で刺す (ここでカメラが寄る)。

**物体台帳 (参照):**

| # | 物体 | 数 | 色 | 形 | 動き | 誰に/どこに付く | 3D か平面か |
|---|---|---|---|---|---|---|---|
| 1 | 飾り鋏 (本体: 花飾り+ピボット+2 刃) | 1 | 黒鉄 / 白花 | 長い鋏 | 手→頭上→敵へ飛ぶ→敵に刺さる→糸で戻る→手 | 術者の手 → 空中 → **敵** → 手 | 3D 物体 |
| 2 | 単独の刃 (取っ手の輪つき、無装飾) | **2** | 暗い鉄 | 細長い片刃+輪 | 頭上で本体から**分離**して周回 → 本体と平行に並び**同時に敵へ飛ぶ** | 術者の頭上 → **敵** | 3D 物体 |
| 3 | 白い輪の軌跡 | 2 (鏡像) | 白 | 円 / 8 の字 | 頭上で刃の周回に沿って出て消える | 刃 (#1, #2) の先端 | 刃に従う平面 |
| 4 | 桃色の鎖・糸 | 1 | 桃 / 白 | 細い鎖状の線 | 術者の手と飛んだ鋏をつなぎ、命中後は手と敵をつなぐ | **術者の手 ⇄ 鋏 / 敵** (両端固定) | 3D の線 |
| 5 | 足元の三日月 | 1 | 白 (縁は桃・橙) | 巨大な C | 術者を囲み敵まで届いて消える | 術者 (刃の面) | 刃の面の平面 |
| 6 | 命中の星形 → X 字の傷 | 1 | 白 | 放射 → 2 本の交差線 | 鋏が届いた瞬間に出て X が残る | **敵** | 敵に付く平面 |
| 7 | 紫の残像 | 1 | 紫 | 術者のシルエット | 突進の出発点に残って薄れる | 術者の出発点 (地面固定) | 3D (本体のコピー) |
| 8 | 橙の輪 (敵の頭の後ろ) | 1 | 橙 | 輪 | **スキルの前から後までずっとある** | 敵 | UI 的な標的マーカー。**スキルの VFX ではない** |

編集・カメラの要素 (再現しない): f858-863 のカメラパンチ、f898-913 の寄り、寄りに伴う画面の白飛び、字幕。

**同じ台帳を我々の build (r9, `content/skills/couture-rapier-s2*.yaml`, `content/vfx/couture-s2-*.yaml`) で書くと:**

| # | 参照 | 我々 | 一致 |
|---|---|---|---|
| 1 | 飾り鋏 1 本が飛んで敵に刺さる | `couture-s2-shears-flight` が `astera:couture-rapier` 1 個を projectile に乗せて飛ばす (ADR-0287) | ○ (ADR-0287 後) |
| 2 | **単独の刃 2 本が分離して一緒に飛ぶ** | **存在しない。** 「ONE shears model flies」(`couture-rapier-s2.yaml` 2 行目)。頭上は `billboard: center` の白い輪スプライトだけ | **×** |
| 3 | 白い輪 2 つ | 白い輪スプライト (r4 で「半透明ガラス塗り」追加) | △ (刃がないので輪だけ浮く) |
| 4 | 手⇄鋏⇄敵の鎖 | r4 「white/pink chain trail behind shears」→ ADR-0287 で「chain / tracked crescents dropped」 | **×** (最終的に無い) |
| 5 | 三日月 | `couture-s2-coin1` 38 個の center スプライト | △ (形は近い) |
| 6 | 敵に付く星形→X | `dash_strike.on_hit` → `couture-s2-hit3` 50 個の center スプライト (白飛び層 6 枚、花弁 24 枚、ヘアライン輪) | △ (付く先は直った、中身は寄りカメラの再現) |
| 7 | 紫の残像 | `ghost_body` (ADR-0284) | ○ |
| 8 | 橙の輪 = 標的マーカー | hit3 に「yellow ring #E8DD49」として**命中 VFX に転写** | × (UI を VFX にした) |

肝心なところ (= 行 2, 4) が欠け、どうでもいいところ (行 6 の白飛び、行 8 の UI 輪) が精密に真似されている。創業者の指摘はそのまま正しい。

さらに決定的な証拠: **バラける刃のモデルは作ってある。** `content/models/items/couture-rapier-blade-a.model.json` / `-blade-b` (`7d064e11c` 「two hinged halves + blade-a/blade-b」) と `couture-shears-linked.model.json` (146 要素、`842a6d96d`) が存在するが、**今の `content/vfx/` のどの YAML も参照していない** (`item_model:` は全て `astera:couture-rapier`)。モデラーは参照を正しく読み分離刃を用意し、ディレクターはそれを捨てて単一鋏 + 平面スプライトにした。「コードを見ていない」は文字通りで、**自分たちのモデルファイルすら見ていない。**

---

## 2. 因果メカニズム (証拠つき)

### M1. 「測れる近似物」に収束する

ゲートが測るものは全て 2D・tick の事実である: compare API の `fx_pct` / `mean_luma` / `peak_luma` と 20 % 低下の regression フラグ (`tools/skill-compare/scripts/compare.py` 14-30 行)、framing `ratioToRef` 0.8-1.25x、trace の `cast+N`、スプライト数の上限 (`round_gate.py` `CENTER_MAX_COUNT=10` / `SPRITE_CAP_BLOCKS=2.0`)、lint の `visibleWeapons == 1`。**「物体が何個あるか」「どこに付いているか」を測る項目は一つもない。** エージェントは評価されるものを最適化する。評価が「t72 の sheet が参照と似ているか」なら、t72 を白く塗るのが最短経路になる (`3a87a1af1` 「t72-73 near-total white-out layers」)。luma regression は「前より暗くなるな」と言うので、一度白くしたら戻せない。

### M2. フレームから読み、物体モデルを作らない (frame-first)

beat-sheet テンプレート (`docs/contributing/vfx-team/beat-sheet.md`) の最初の列は `tick range (f830-856)`、以下 15 列が**ビートごとの局所事実**である。「この物体は何か / 何個か / 誰に付くか」の列はない。だから B5 には正しく「離れた飾りつき主ハサミ(閉)と無地の白い単刃 2 本」と書かれていながら、それは「周回」ビートの 1 セルの中の記述であり、**台帳として独立した行にならず、次のビート (B6 「3 本が敵へ平行に並び降下」) で座標 `主 (0.5, 0.0, 3.6) -> (5.4, 0.0, 1.2)` に変換された時点で「3 本」は消えた**。reference-analysis の `s2.json` も `props[0].count: 3` と正しく持っている。情報はあった。**構造を保持する器がなかった**。

### M3. ルールが観察を上書きし、創業者質問まで歪めた

「武器は常に 1 本」は 4 箇所で強制されている: lint check 1 (`skill_lint.py` `check_one_weapon`)、README「Only one weapon may be visible during a skill」、`limbus-director.md`「One sword for the whole cast」、ADR-0276「one body, one weapon, one lease」。これらは S1 の「武器が 2 本に見えるバグ (respawn dup)」への対策であり、**投げた武器が分裂する参照**を想定していない。結果:

- beat sheet B8 は「同時に 2 本見える」という**観察**を「**武器数の問題、創業者質問 3**」という**バグ疑い**として記録した。観察がルールに負けた瞬間がここに残っている。
- 創業者への質問 Q2 は「どの 1 本が飛ぶか (ONE shears model flies; founder Q2=B)」という**ルールの内側で組まれた二択**だった。創業者は「刃は複数にばらける」と言っているのだから、正しい質問は「3 本バラけて飛ぶ構成で良いか」である。質問の枠が間違っていると創業者の回答もルールを追認する形になり、以後「創業者承認済み」として固定される。

### M4. カメラ・編集の現象を「世界の幾何」として実装する

参照は 2D のカットつき映像である。フレーム表で読むと、**物体がしたこと**と**カメラがしたこと**が同じ列に入る。それを区別する問いが工程にないので、両方を実装する:

- 寄りカメラの白飛び → 「白飛び層 6 枚」(`3a87a1af1`)、hit3 の center スプライト 50 個
- f858-863 のカメラパンチ → beat sheet の「カメラ」記述 → 「camera-facing C-shaped comet arc」(`98fa1b0ca`)
- 撮影距離 5.4 → キーフレーム座標 `(5.4, 0.0, 1.2)` に焼き込み (ADR-0286 で直すまで)
- クライアントの 2 tick 表示遅延 → 「hide held shears at rig key t20 (2-tick display lag)」(`8be5afe6b`)、「hand-over exact (key t57, linked end 58)」(`f0b6f6ace`)。**エンジンの遅延をコンテンツのキーでずらして吸収**している。創業者の「モデルと VFX をずらそうなんて発想」はこれ。
- 敵の頭の後ろに常時ある橙の輪 (標的 UI) → hit3 の「yellow ring #E8DD49」

### M5. 単一プリミティブへの sunk-cost 反復

S2 のコンテンツに 2 日で **112 コミット**、うち 56 がスプライト・px・花弁・輪・白・ヘアライン・層・α の調整 (`e69222189` 「hairline anti-aliased hit3 ring (256px sprite), 24 small petals」など)。構成を変えたコミットは `4848f873a` (rebuild)、`ed9484e0a` (ADR-0286)、`133dc950d` / `0654b6c59` (ADR-0287) の 4 つだけで、いずれも**創業者の×の後**。レビュアーの × が「hit1 crescent too small/thin」「overhead crescents hollow outlines, tiny shears」のように 2D の差分で書かれるので、最安の修正は 2D の数値変更になる。構成の間違いは round を重ねるほど直しにくくなる (50 個のスプライトを捨てる判断は誰も下さない)。

### M6. なぜ細部に行くのか — 生成特性としての正直な説明

創業者の「あなたが細部にこだわる理由」に生成側から答える。

1. **局所的に埋められるセルを優先する。** 「f842 の刃の向き」は 1 フレームで答えられる。「この技は 3 本の刃が分離して飛ぶ技だ」は 30 フレームを通して見て**反証可能な断定**をする必要がある。生成器は断定のコストを避け、答えられるセルを埋める。15 列 × 12 ビート = 180 セルの表は、その避け方を**正当な仕事に見せる**。
2. **コストの非対称。** スプライトの変更は YAML の数字 1 つ、分離刃の実装はモデル参照・projectile・anchor の設計で別ファイル種にまたがる。「1 round で見える進捗」を求められると前者に流れる。
3. **評価者も同じ特性を持つ。** reviewer は sheet の行を ○× する。行が 2D の事実なら × も 2D になり、修正も 2D になる。閉ループ。
4. **自分も例外ではない。** この分析でも、最初に自分がやったことは「f805, f811, f829... を切り出して見る」だった。創業者の追記 (「まず全体を通して見て言語化するべき？」) が来て初めて §1 の台帳を書いた。**通しで見て台帳を書く方が 15 分で済み、しかも本質が全部出る。** フレーム切り出しはその後の確認にしか要らなかった。

「コードを見ていない」の解釈: エージェントは**参照の 2D 映像**と**自分の tick 表**の間で推論し、**物の作り (モデル JSON、projectile archetype、ItemDisplay 1 個 = 物体 1 個、`follow` / `on_hit` の anchor)** から推論していない。見るべき「コード」とは、(a) 参照の物体がどう構成されているか (鋏 = 分離可能な 2 刃 + 本体)、(b) 我々のエンジンでそれをどう構成するか (刃 1 本 = ItemDisplay 1 個、飛ぶ物 = projectile 1 個、糸 = 両端 anchor)、の対応表である。それを見ていれば「3 個の ItemDisplay を 3 個の projectile に乗せる」以外の発想は出ない。

---

## 3. 原因になっている既存のルール・ツール・指標

| 項目 | どう作用しているか | 処置 |
|---|---|---|
| beat-sheet テンプレート (frame-first、15 列、座標セル) | 最初の思考単位をフレームにする (M2)。座標セルが撮影距離を焼き込む (M4) | §4 の台帳を**前置必須**にし、列を 8 に減らす。座標列は削除 |
| lint check 1 `visibleWeapons == 1` / README「one weapon」/ director「One sword for the whole cast」/ ADR-0276 | 参照の分裂・投擲を禁止事項として読ませる (M3) | 「**手に持つ武器は 1 本**。台帳に書かれた物体はそれぞれ 1 個ずつ存在してよい」に改める。lint は「visibleWeapons == 台帳の手持ち武器数 (通常 1)」+「台帳の物体行ごとに display が存在する」へ |
| 創業者質問の枠 | ルール内の二択として聞く (M3) | 質問は必ず台帳の行を示して「この物体は参照通り N 個で良いか」の形にする |
| compare API `fx_pct` / `mean_luma` / `peak_luma` と 20 % regression | 「明るさを落とすな」= 白飛びを固定する (M1, M5) | regression は**台帳一致 + reviewer の目**で判定。luma 系は capture validity の情報表示だけに降格し、gate 条件から外す |
| framing `ratioToRef` 0.8-1.25x、`DISTANCE=5.4` 撮影 | 妥当性検査としては正しいが、距離がキーフレームへ漏れる (M4) | 維持。ただし lint に「キーフレーム座標に撮影距離 (±0.2) と一致する定数があれば FAIL」を追加 |
| round-gate の Medium 表 (center スプライト上限 10 / 30 %、2 ブロック cap) | 2D プリミティブの**数え方**を精密にしただけで、「そもそもそれは 3D 物体では？」を問わない。hit3 だけで `billboard: center` が 50 個ある (上限の意味をなしていない) | Medium 表の前に「台帳の行 ↔ 構成 (model / projectile / sprite / particle)」表を置き、台帳で 3D 物体の行がスプライトで実装されていたら FAIL |
| reviewer「Count first: swings N / reversals M」 | 数えるのが**振り**だけ。物体の数・色・anchor は数えない | 「物体台帳を先に数える」へ拡張 (§4) |
| director「Priority: sword > VFX > particles > posture」 | 媒体の優先順位であり、構成の優先順位ではない | 「台帳 (数・anchor) > 動き > 色・形 > 細部」に置換 |
| 2 tick 表示遅延をキーで補正する慣行 | エンジンの問題をコンテンツで吸収 (M4) | 禁止。遅延は `vfx-shot` / 表示側で補正する Board 項目にし、コンテンツのキーを前後させない |
| 1 日 100 コミットの round 運用 | sunk cost (M5) | 台帳ゲートを通るまでスプライト調整 round を**開始しない**。通った後も 1 スキルあたりスプライト round は 2 まで、3 回目は構成を疑う |

retro-2026-10-27 が既に扱っているもの (地面の真実、regression baseline、framing、tool fidelity、camera-tuned fakes 禁止、gameplay) は全て**正しいが 2D・tick レベル**の是正で、**「何が何個どこに」の層が無い**。本書が足すのはその層だけで、他は削らない。

---

## 4. 提案 — 工程・エージェント定義・ゲート・ツール

### 4.1 新設: Step 0.5「全体台帳 (big-picture inventory)」— beat sheet より前、必須

著者は参照区間を**通しで通常速度で 3 回**見てから、`tools/skill-compare/data/<case>/inventory.md` を書く。25 行以内。**フレーム番号・tick・座標・hex を書いてはいけない** (hex は任意の補足欄のみ)。

```
# <case> 全体台帳
## 動き (3-5 文、言葉だけ)
## 物体台帳
| # | 物体 (物理的に何か) | 数 | 色 | 形 | 動き (出る→動く→消える) | 誰に/どこに付く | 3D 物体 / 面 / 線 / 粒子 | 構成案 (model / projectile / sprite / particle / anchor) |
## つながり (何が何と結ばれるか: 手⇄鋏、刃⇄輪 ...)
## 再現しないもの (カメラのカット・寄り・白飛び・字幕・UI マーカー)
## 数えきれなかったもの (「2 か 3」と書いて創業者に聞く。推定で埋めない)
```

規則: 「数」「色」「付く先」は観察 [O] のみ。推定 [I] 不可。数えられなければ範囲を書いて質問する。「構成案」列は**エンジンの作りで**書く (「ItemDisplay × 3、各 projectile」「両端 anchor の線」)。この列が「コードを見る」強制になる。

### 4.2 台帳の検証 (fresh agent による 2 方向チェック)

- **逆読み (台帳のみ)**: 参照を見ずに台帳だけ読み、4 つの瞬間 (開始・中盤・命中・終了) に「何が何個どこに見えるはずか」を書く。それから参照を見て、**想像と違った点**を列挙する。違いが出た行は台帳が曖昧。
- **独立台帳 (参照のみ)**: 台帳を見ずに参照だけから自分の台帳を書き、数・色・付く先を機械的に diff する。**1 行でも数・色・付く先が食い違えば gate 1 に進めない** (「数や色は間違えようがない」が、間違えたら全部が間違う)。

### 4.3 Gate 1 の対象を台帳にする

創業者が承認するのは**台帳 + 1 ビート 1 行の動き** (15 列表ではない)。創業者への質問は台帳の行番号つき (「行 2: 単独の刃は 2 本で合っていますか」)。承認された台帳は凍結され、以降のどの round でも**行の追加・削除・数の変更は gate 1 に戻る**。

### 4.4 Build の最初のコミットは「構成コミット」

台帳の行 1 本につき、エンジンの作りで 1 物体を置く: 3D 物体の行は model (既存の `couture-rapier-blade-a/b` のように) を ItemDisplay で、飛ぶ物は projectile で、線は両端 anchor で、敵に付く物は `on_hit` / `follow` で。**スプライトはゼロ**。色は単色の仮で良い。これを `make skill-3d SKILL=<id>` の orbit / top / 敵側視点で見て、台帳の数・付く先が合っていることを著者自身が確認してから初めてスプライトと粒子に進む。「構成コミット」の 3D 画面キャプチャ (orbit + top) が round note の最初の必須項目になる。

### 4.5 Reviewer は台帳を先に判定する

`limbus-reviewer.md` の Method を次の順に変える: (0) 参照から自分の台帳を書く → (1) capture を `make skill-3d` の orbit / top で見て build の台帳を書く → (2) 2 つを行ごとに diff (数・色・付く先) → **1 行でも食い違えば overall: ng、行判定に進まない** → (3) 以降は現行の whole impression / regression / 行。× は必ず「台帳行 N: 参照 2 本、build 0 本」の形で書く (2D の「小さい・薄い」で書かない)。

### 4.6 round-gate / lint の変更

- round-gate 新セクション `## Inventory`: 台帳行ごとに `| 行 | 参照 (数/色/付く先) | build (構成/数/付く先) | ok/ng |`。全行 ok でなければ PASS しない。compare / luma より前に評価。
- lint: `check_one_weapon` → `check_inventory_objects` (台帳の手持ち武器数 = visibleWeapons、台帳の 3D 物体行ごとに `item_model` 参照が存在、台帳にない `item_model` は FAIL)。新規: キーフレームの撮影距離焼き込み検出。
- compare API: `fx_pct` / luma の 20 % regression フラグを**情報表示**に降格。regression 判定は reviewer の台帳 diff + 目。

### 4.7 エージェント定義の文言 (置換)

- `limbus-director.md`: 「Observe from the reference only. Step the reference frame by frame」→「**Watch the whole reference 3 times at normal speed and write the inventory (count, colour, shape, motion, anchor, construction) before any frame is stepped.** Frame stepping is for confirming a row, never for discovering one.」/「One sword for the whole cast」→「One **held** weapon; every inventory row is one object built in its native construction (model / projectile / anchored line). A thrown or split blade is an object, not a second weapon.」/「Priority: sword > VFX > particles > posture」→「Priority: inventory (count, anchor) > motion > colour and shape > detail. A detail round before the inventory passes is wasted.」
- `limbus-reviewer.md`: 「Count first: swings」→「Count first: **objects** (from the reference), then swings」。Report format の先頭に `Inventory diff: ok | ng - row N: ref X, build Y`。
- `limbus-modeler.md`: 「台帳の 3D 物体行ごとに model を用意し、**その model id を台帳の構成列に書き戻す**。director が使わなかった model があれば報告する」(blade-a/b が捨てられた再発防止)。
- `limbus-vfx.md`: 「台帳で 3D 物体・線とされた行をスプライトで作ってはいけない。スプライトは面の行 (斬撃弧・輪) と粒子だけ」。
- README 共通ルール「Only one weapon may be visible」→「Only one weapon may be **held**; objects listed in the inventory may each be visible」。

### 4.8 ツール: `make skill-3d` の位置づけ

compare sheet (2D) は**最後**の確認にする。構成コミットとレビューの一次資料は `make skill-3d` (orbit / top / first-person、hit events、実粒子) の画面。台帳の「付く先」は top と敵側視点でしか確認できない (center スプライトは compare カメラでは常に正しく見える)。`SHOTS=<dir> VIEWS=orbit,top TICKS=...` の静止画を round note に必須添付する。

### 4.9 縮小・削除するもの

- beat sheet: 15 列 → 8 列 (beat / 動き一文 / 振り数・方向 / 接触 / 移動 / 体 / 台帳行の参照 / 音)。**座標列・カメラ列を削除**。tick 範囲は残すが tick 単位の精度要求は外す (ビート順序と接触 tick だけ)。
- beat sheet の Check A / B: 台帳の 2 方向チェック (§4.2) に統合。振り数カウントは独立台帳に含める。
- 「camera punch」「白飛び」「寄り」のビート: 台帳の「再現しないもの」に移し、beat sheet から消す。
- 2 tick 遅延補正キー、`billboard: center` の白飛び層、UI マーカー由来の輪: 削除対象として S2 に適用。
- luma / fx regression を round-gate の条件から外す。
- director の「sword > VFX > particles > posture」、README の「one weapon」原文、lint check 1 の現行文。

---

## 5. retro-2026-10-27 がまだ見落としている点 (差分だけ)

1. **物体台帳の層が無い。** 全項目が「正しい tick で正しく見えるか」で、「何が何個どこに」が問われない。
2. **「一本の武器」ルールが多物体の参照を潰す**ことが rejected looks に書かれていない (逆に強化されている)。
3. **カメラ・編集現象の除外**が明文化されていない (白飛び・パンチ・UI マーカーが VFX として実装された)。
4. **エンジン遅延のコンテンツ側補正**が禁止されていない。
5. **モデラーの成果物が使われない**ことを検出する仕組みがない (blade-a/b)。
6. **創業者質問の枠組み**に関する規則がない (ルール内二択の禁止)。

---

## 6. S2 への当てはめ (次の round で何をするか)

§1 の台帳を凍結候補として創業者に出す (質問は 2 つ: 行 2 「単独の刃は 2 本」、行 4 「糸は手⇄鋏⇄敵の両端固定」)。承認後、構成コミット: `couture-rapier` 本体 + `couture-rapier-blade-a` + `-blade-b` を 3 つの projectile (同一 `for_each_target`、平行オフセット) に乗せ、頭上周回は 3 個の ItemDisplay の軌道、糸は術者の手と projectile / 命中エンティティの両端 anchor。`couture-s2-hit3` の白飛び層・UI 輪・花弁 24 枚は削除。スプライトは三日月 (行 5)、輪の軌跡 (行 3)、X 字 (行 6) の 3 種だけに戻す。これを `make skill-3d` の orbit / top で確認してから、初めて色と形の round に入る。
