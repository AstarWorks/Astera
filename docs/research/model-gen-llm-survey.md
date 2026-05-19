# 3D Model Generation × LLM — Industry Survey and Astera Improvement Roadmap

**最終更新**: 2026-05-19  
**著者**: Claude (Web 調査エージェント B+C + コードベース調査エージェント A)  
**目的**: Astera の `tools/model-gen/` + `tools/studio/web/` + `.claude/skills/build-model/` パイプラインを、業界 SOTA (ForgeCAD, T2BM, AIDL, Onshape×Claude MCP, LL3M, CADCodeVerify 等) と突き合わせて、改善ロードマップを提示する。

> 本ドキュメントは SSOT (single source of truth) として、後続の改善 wave / Agent 委譲タスクが参照する。
> セッション固有の実行戦略 (誰がどう実装するか) は `/home/node/.claude/plans/astera-3d-tools-forcecad-3d-llm-forgeca-crystalline-neumann.md` を参照。
> **CAD-LLM 領域は 2024-2025 で論文数倍増中**。6 ヶ月以上経過後は再調査推奨。

---

## 1. Context — なぜこのサーベイか

Astera は Phase 1 で **LLM (Claude) に 3D モデルを作らせる** パイプラインを構築済み。中間表現は **ASDSL** (Astera Shape DSL) という独自 JSON で、parts dict + anchors + 6 種の meso-primitive (cuboid / tapered_prism / cylinder / sphere / ring / chamfered_cuboid) を declarative に並べる方式。

ASDSL は自然発生的に Minecraft の cuboid + 22.5° 制約に合わせた最小表面積設計に到達したが、**業界の CAD×LLM 研究 (2024-2026) と比較したときに何が足りないのか** は明示的に整理されていなかった。本サーベイは:

- (a) 業界 SOTA の体系的レビュー
- (b) Astera 現状と SOTA の gap 分析
- (c) Tier A/B/C の網羅的改善ロードマップ

を提示し、後続の改善着手 (skill prompt 改修 / ASDSL schema v2 / ModelBench 構築 など) の判断材料を提供する。

---

## 2. Astera 現状パイプライン

### 2.1 5 段パイプライン

```
User /build-model <slug> --reference <url>
    ↓
Claude が ASDSL JSON を build (place_part / move_part / set_texture …)
    ↓
anchor.ts (243 行) が相対配置を絶対座標に解決
    ↓
compile.ts (586 行) が meso-primitive を MC cuboid に展開 + 22.5° 回転 + UV
    ↓
content/models/<category>/<name>.model.json (MC Java 標準 model フォーマット)
```

参照: `tools/studio/web/server/asdsl/anchor.ts`, `tools/studio/web/server/asdsl/compile.ts`, `.claude/skills/build-model/SKILL.md`

### 2.2 ASDSL の表現力

| 書ける | 書けない |
|---|---|
| 6 種 meso-primitive | 自由角度 (22.5° 固定 — MC 制約) |
| 相対配置 (`relative_to + align + delta`) | Non-cuboid geometry (MC 制約) |
| パラメータ参照 `@blade_length` | Animation keyframe (v2+) |
| 22.5° increment 回転 | 複雑な UV 個別調整 |
| ドメイン名付きテクスチャスロット | 自由な式 (`width/2+1` 等) |
| `low/medium/high` 品質階級 | per-primitive な品質指定 |

### 2.3 LLM プロンプト戦略 (`.claude/skills/build-model/SKILL.md`)

- **HARD RULES キャッシュ**: 単位 1/16 block / 22.5° enum / テクスチャスロット名
- **Vision decompose**: 参照画像を Markdown で分解 → tool 呼び出し
- **Render-first protocol**: 毎ツール呼び出し → `iter-screenshot` で iso + item_gui + front の 3 PNG → Claude が読んで次手判断
- **Iteration discipline**: 5+ 反復必須、`decisions.md` に記録
- **Truth envelope on every call**: `{ok, status, hints:[]}` で API レイヤから修正案を返す

### 2.4 既知の弱点

1. **anchor resolver の依存解決が複雑**, 部品 kind ごとに bounding box 計算が分岐
2. **Sphere/curve の voxelization が粗い** (low/medium は stair-step)
3. **22.5° 固定**: MC 制約のため変更不可 — **正しい設計**であって弱点ではない
4. **テクスチャ UV 自動計算のみ**: face 単位の手動調整不能
5. **LLM cost 管理なし**: `iter-screenshot` 連打でコスト膨張、cap/warning なし
6. **state の永続化が localStorage**: undo/redo なし、git 管理外
7. **Critic は人間 (ユーザ) 依存**: 自動 vision critic agent がない
8. **評価指標がない**: ModelBench 相当のスイートなし

---

## 3. 業界 SOTA サーベイ

### 3.1 ForgeCAD (https://forgecad.io, KoStard 氏)

TS code-first parametric CAD。Astera が **直接学ぶべき独自性**:

- **Agent-skill-as-product**: `forgecad skill install` で Claude Code / Codex / OpenCode の skill dir に専用 skill 配置。`forgecad-make-a-model`, `forgecad-render-inspect` 等フェーズ別。
- **CLI 自体が agent feedback 構造**: `forgecad run` + `forgecad render inspect` で render-inspect loop が CLI primitive
- **LLM benchmark archive 同梱**: GPT-4 / Opus / Gemini 3 / Qwen / DeepSeek を「家庭用 AC ユニット作って」等の prompt で評価し、GIF アニメで版管理
- **STEP/BREP の厳密エクスポート**: 製造可能 CAD 寄り (OpenSCAD より一段上)

**Astera への示唆**: `build-model` skill の "skill installer 化" + `bun run studio render inspect` のような **CLI から呼べる render-inspect ツール** の独立コマンド化。

### 3.2 T2BM — 3D Building Generation in Minecraft via LLMs (IEEE CoG 2024, arXiv 2406.08751)

**Minecraft 領域の直接同型**。`prompt refining → JSON interlayer → repair module` の 3 段構成。Astera の ASDSL は無自覚に同じパターンに収束している。**学ぶべきは repair module の中身** — どの不整合 (geometry / semantic / aesthetic) をどの順序で直すか。

### 3.3 AIDL — solver-aided hierarchical DSL (ICLR 2025, arXiv 2502.09819)

**Astera ASDSL に最も近い学術手法**。`parts + anchors + 制約` の宣言を LLM に書かせ、**幾何ソルバが空間計算を肩代わり**する設計。Astera の `anchor.ts` を「静的計算」から「制約ソルバ」に進化させる根拠。

### 3.4 Onshape MCP × Claude Opus 4.7 (Reshef Elisha, 2026)

- repo: https://github.com/ReshefElisha/jarvis-onshape-mcp
- **Anthropic 公式ではない**。コミュニティ実装 (community MCP server)
- 60 MCP tools (Document / Sketch / Feature / Assembly / Introspection / Variables / FeatureScript / Rendering の 8 カテゴリ)
- 各 mutating tool が **structured truth payload + multi-view PNG** を返す (Astera の `{ok, hints}` と同型)
- **定量結果**: 自律分解 40 分 0.53 vs 人間が plain-English で記述 7 分 1.00 → **ボトルネックは「視覚 → 仕様への推論」**

**最重要示唆**: 「画像 → plain-English design spec → ASDSL」の **2 段プロンプト分割** に大きな改善余地がある。

### 3.5 LL3M — Large Language 3D Modelers (arXiv 2508.08228, 2025)

6 エージェント multi-agent (Planner / Retrieval / Coder / Critic / Verification)。**コード再生成でなく差分編集**を強制。BlenderRAG 付与で error rate -26%、複雑度 5×。**役割別に最適モデル割当** (GPT-4o planning / Claude 3.7 Sonnet code / Gemini Flash visual)。

### 3.6 CADCodeVerify (ICLR 2025, arXiv 2410.05340)

**VLM が validation question を自動生成して自分で答える**。「対称性 OK? アンカー全部接続済? 主要パーツ全部ある?」を critic 側が問いに変換 → 自答 → 差分。Astera が即導入できる pattern。

### 3.7 BlenderLLM (arXiv 2412.14203)

`bpy script 生成 + self-improvement loop`。**モデル自身が新サンプル生成 → 評価 → 再学習**。同時公開の **CADBench** 評価スイートが参考になる。

### 3.8 EvoCAD (ICTAI 2025, arXiv 2510.11631)

LLM が **集団 (population) を初期化** → VLM が fitness 評価 → 進化的選択・変異。**Euler characteristic ベースのトポロジー metric** を意味類似度に使う。

### 3.9 PartCrafter (NeurIPS 2025, arXiv 2506.05573)

単画像 → 構造化マルチパート mesh。**Hierarchical attention** (パート内 + パート間)。**HoloPart 18 分 → PartCrafter 34 秒**。Astera の "parts + anchor グラフ" の生成順を **"先に global、後で part 内"** に階層化する根拠。

### 3.10 その他参考 (簡潔)

| 名前 | 系統 | Astera への寄与 |
|---|---|---|
| CAD-Recode (ICCV 2025) | point→CadQuery Python | Phase 2+ で参照 |
| Text2CAD (NeurIPS 2024) | 多階層 text → CAD seq | プロンプト階層化 |
| CAD-MLLM (2024) | multimodal CAD | 壊れ方分類 metric |
| CAD-Coder (MIT 2025) | VLM→CadQuery | データ工学事例 |
| CAD-Llama (CVPR 2025) | hierarchical SPCC | parts 局所→大局 prompt |
| LLaMA-Mesh (NVIDIA, NeurIPS 2024) | mesh as plain text | voxel 直接 text 化に近い |
| MeshGPT (CVPR 2024) | quantize vocabulary | 将来の token 化 |
| 3D-GPT (2023) | procedural multi-agent | LL3M の祖 |
| ShapeAssembly (Siggraph Asia 2020) | cuboid parts + attachment | ASDSL parts の祖型 |
| DreamCraft (FDG 2024) | NeRF 量子化 → MC block | 別パラダイム比較 |

### 3.11 手法カテゴリ比較表

| カテゴリ | 中間表現 | LLM フレンドリー度 | 編集可能性 | 物理整合性 | Minecraft voxel 適合度 | 代表 |
|---|---|---|---|---|---|---|
| Direct mesh as text | 頂点+face を plain text | 中 | 低 | 低 (self-intersection 多) | △ | LLaMA-Mesh, MeshGPT |
| Implicit function / NeRF | MLP / SDF | 低 | 低 | 中 | × | Shap-E, DreamFusion, DreamCraft |
| Code / DSL (Python) | CadQuery / OpenSCAD / bpy | **高** | **高** | 中 | ○ | CAD-Recode, BlenderLLM, ForgeCAD |
| Parametric sequence | sketch-extrude-fillet 列 | 高 | 高 | **高** | ○ | Text2CAD, CAD-MLLM, CAD-Llama |
| **Voxel / discrete grid** | block type × 座標 | **高** | 中 | 高 | **◎ ネイティブ** | T2BM, DreamCraft |
| **Primitive composition + anchors** | parts + joints (JSON) | **最高** | 高 | 高 | **◎** | Onshape feature tree, AIDL, ASDSL |
| Multi-agent + vision critic | 上記 + critique log | 高 | 高 | **最高** | **◎** | LL3M, CADCodeVerify, EvoCAD |

---

## 4. Astera の強み (自覚なき SOTA 収束) と Gap

### 4.1 既に SOTA 並み

| 業界知見 | Astera の状態 |
|---|---|
| JSON interlayer + repair (T2BM) | ASDSL JSON で実現 |
| Truth envelope `{ok, hints}` (Jarvis-MCP) | 各 tool 呼び出しが同形を返す |
| Render-in-the-loop (LL3M) | `iter-screenshot` 3 view |
| Iteration discipline (CADCodeVerify) | 5+ 反復 + `decisions.md` |
| Vendor 非依存 cuboid DSL (T2BM 同型) | MC 制約と整合 |
| Agent-friendly DSL (ForgeCAD) | `.claude/skills/build-model/` |

### 4.2 主要 Gap

| Gap | 該当 SOTA | 短評 |
|---|---|---|
| Planner agent 前段がない | Reshef Elisha / CAD-Llama | 「画像 → plain-English → ASDSL」未分離 |
| 自動 vision critic がない | CADCodeVerify / LL3M | critic は人間 (ユーザ) 依存 |
| 差分編集が強制されてない | LL3M | LLM が全体再生成しがち |
| anchors が静的計算のみ | AIDL | solver-aided 制約なし |
| 式言語がない | Grizzly Peak | `@var` のみ、`width/2+1` 不能 |
| parts graph が implicit | ShapeAssembly / ShapeCraft | joint が独立配列でない |
| Quality が global 軸 | — | per-primitive 調整不能 |
| Texture × shape が片方向 | (研究 gap) | 双方向ループなし |
| Benchmark スイートなし | CADBench / Omni-CAD | ModelBench 不在 |
| Self-improvement loop なし | BlenderLLM | synthetic corpus なし |

---

## 5. 改善ロードマップ — Tier A / B / C

### Tier A — 短期 (skill prompt 改修 + Studio 小幅追加)

**狙い**: コード変更が `tools/studio/web/` 内で完結。新規 ADR 不要。

**A1. Planner / Spec 段の分離 (最重要)**
- 現状: build-model skill が「画像 → ASDSL」を 1 段で要求
- 改善: 「画像 → **plain-English design spec** (Markdown) → ASDSL」の 2 段に分離
- 根拠: Reshef Elisha の Onshape MCP 定量結果 (自律 0.53 → plain-English 記述 1.00)
- 触る場所: `.claude/skills/build-model/SKILL.md` の Phase 1 に "write spec.md first" step を追加

**A2. 自動 Vision Critic agent (CADCodeVerify pattern)**
- `iter-screenshot` 後に validation question を Claude 自身に出させる:
  - 「対称性 OK? アンカー全部接続済? 主要パーツ全部ある? 比率は妥当?」
- 答えを `critic-report.md` に蓄積、red-flag があれば次反復で修正
- 触る場所: build-model skill + Studio `/api/chat` route に critic mode 追加

**A3. Cost meter + cap**
- Studio /models のチャット欄に既存 LLM cost meter に **soft cap warning** 追加
- 触る場所: `tools/studio/web/src/components/ModelChat.vue` 相当

**A4. `decisions.md` 構造化**
- critic feedback / 差分 / 反復番号を別 section に
- 触る場所: build-model skill prompt のみ

**A5. ForgeCAD 風 CLI コマンド**
- `bun run studio render inspect <run-id> --step <n>` を独立 CLI に
- 触る場所: `tools/studio/web/scripts/` + `package.json`

### Tier B — 中期 (ASDSL schema 改訂、1-2 wave 必要、ADR 1-2 本)

**狙い**: ASDSL の表現力を上げる。マイグレーションが必要。

**B1. Parts graph を first-class に (joints 独立配列)**
- 現状: anchors が parts 内に埋め込み
- 改善: `joints: [{type, a, b, relation, offset, ...}]` を schema トップに
- 根拠: ShapeAssembly / ShapeCraft GPS / Grizzly Peak furniture
- 触る場所: ASDSL schema + `anchor.ts` + samples migration
- 必要 ADR: 「ASDSL v2: joint-graph representation」

**B2. Parametric formula 言語**
- 現状: `@blade_length` 参照のみ
- 改善: `width / 2 + 1`, `max(@a, @b)` のような小式言語
- 安全な eval (mathjs サブセット or 自前 recursive-descent)
- 触る場所: `compile.ts` の variable resolver
- 必要 ADR: 「ASDSL v2: parametric formula sub-language」

**B3. Quality 軸の per-primitive 化**
- 現状: global low/medium/high
- 改善: `quality.sphere = "high"`, `quality.tapered_prism = "medium"` の per-primitive 指定
- 触る場所: `compile.ts:127-160` (sphere voxelize 周辺)

**B4. Texture face semantic hint (`face.uv_intent`)**
- 現状: face → atlas UV 自動計算のみ
- 改善: face に `uv_intent: "blade_steel_horizontal_grain"` のような semantic tag → `generate-textures` skill に渡してテクスチャ生成時に使う
- 触る場所: ASDSL schema + generate-textures skill prompt

**B5. AIDL 風 constraint solver の試作**
- 現状: anchor.ts は静的計算
- 改善: 簡単な constraint propagation (例: 「対称性: blade.left ≡ -blade.right」)
- 試作レベルで OK、効果測ってから本格化
- 必要 ADR: 「ASDSL v3 constraint solver (試作)」

### Tier C — 長期 (新 wave 案件、ADR + ベンチマーク + 研究的領域)

**狙い**: Astera 独自貢献領域 + 自己改善ループ。

**C1. ModelBench — 評価スイート**
- 20-50 個の参照画像 + 期待 ASDSL diff
- 自動 vision critic でスコア (CADCodeVerify metric)
- Metric: 形状一致 / 対称性 / 部品数一致 / VLM-as-judge 5-shot 平均
- 触る場所: `tools/model-gen/benchmark/` (新規)
- 根拠: CADBench (BlenderLLM) / Omni-CAD (CAD-MLLM)

**C2. Self-improvement loop (BlenderLLM 流)**
- Claude が新サンプル生成 → 自動 vision critic → 合格したものを synthetic corpus に
- 次世代 prompt の few-shot に組み込む
- 触る場所: `tools/model-gen/synthetic/` (新規) + ModelBench に依存

**C3. Multi-agent オーケストレーション (LL3M 流)**
- Studio に Planner / Coder / Critic / Verifier の役割を formalize
- 各 agent に別モデル割当 (planner: Sonnet, coder: Opus, critic: Haiku)
- 触る場所: `tools/studio/web/server/agents/` (新規)
- 必要 ADR: 「Studio multi-agent orchestration」

**C4. Texture × shape 双方向ループ (Astera 独自貢献候補)**
- 現状: 形状先 → テクスチャ後
- 改善: 形状の `face.uv_intent` をテクスチャ生成に渡し、生成された texture が形状側の微調整 (UV stretch / face split) を fire-back
- **本サーベイで見つかった唯一の研究 gap**。論文化候補。
- 触る場所: build-model + generate-textures 両 skill の協調プロトコル
- 必要 ADR: 「Bidirectional shape-texture co-design protocol」

**C5. ForgeCAD 風 skill installer**
- `bun run studio skill install` で `.claude/skills/build-model/` を別環境にもインストール可能化
- Astera 外への展開を見据え

### 推奨順序 (judgement call)

1. **Tier A1 → A2 を逐次** (build-model skill prompt を両方触るため)
2. **A3 / A4 / A5 を並列**
3. **Tier A 完成後に効果測定** (反復回数 / cost / 完成品質) → これが Tier C1 (ModelBench) のベースライン取得を兼ねる
4. **Tier B / C は各々 ADR + plan セッションを別途開く**

### 5.4 Tier 拡張 — 2026-05 follow-up 監査 / Deep-dive / Sweep に基づく

2026-05 の追加調査 (§8 OSS absorption audit, §9 ForgeCAD / Onshape MCP / BlenderLLM deep-dive, §10 broader OSS sweep) で発見された pattern を Tier roadmap に増分追加。本来の §5.1/§5.2/§5.3 は維持。

#### Tier A 拡張 (skill prompt 改修 + Studio CLI 微増)

- **A6** T2BM repair module ポート (§3.2c → §8.2) — `decisions.md` に modular fix-order (geometry → semantic → aesthetic) を skill prompt で要求
- **A7** PartCrafter **global-first generation order** (§3.9b → §8.2) — build-model iteration に「先に envelope の大筋 (3-7 parts, round numbers) → 後で part 内詳細」の段階制約
- **A8** Onshape MCP vision-decompose pre-build gate (§9.2.5) — 現行 Phase 0 spec.md を user-confirmation gate に強化 (ユーザレビュー回避禁止)
- **A9** ForgeCAD "You are building blind unless you render" 不変条件 (§9.1.5) — skill HARD RULES に「no render-inspect bundle = not done」を明示
- **A10** ForgeCAD 8-step inspection protocol + "report evidence" (§9.1.6) — iter-screenshot 後の structured evidence emission を skill 義務化、decisions.md の各 step block に `evidence` sub-section
- **A11** ForgeCAD channel table for `iter-screenshot` (§9.1.4) — `--channels` フラグで question-tailored view set (iso/front/top/exploded/wireframe/scale)
- **A12** ForgeCAD 3-field minimalist SKILL.md frontmatter (§9.1.2) — `name`/`description`/任意 public flag のみに簡素化、description に明示的 "Use when..." trigger
- **A13** Onshape MCP "think out loud before mutating call" (§9.2.5) — `asdsl place/move/set-tex` 前に 1-3 文の "WHY / WHAT I expect" を skill prompt 義務化

#### Tier B 拡張 (ASDSL schema / compiler / Studio API)

- **B6** LL3M **diff-editing 強制** (§3.5b → §8.2) — `asdsl patch <part> {…}` verb 追加、`asdsl place` の re-call を禁止 (差分編集の prompt enforcement) → **ADR-0062 で統合**
- **B7** cad-khana **diagnostics-first 宣言的 assertions** (§10.1.1) — ASDSL part に `assertions: [no_self_intersection, fits_within_voxel_envelope, …]` を declarative に書ける拡張 + `{ok, status, hints, diagnostics}` 形に envelope 拡張 → **ADR-0062 で統合**
- **B8** Onshape MCP **5-status truth envelope** (§9.2.1) — 現行 `{ok: bool}` を `{status: OK|INFO|WARNING|ERROR|UNKNOWN}` に拡張、INFO は「成功したが auto-adjust 入った」シグナル → **ADR-0062 で統合**
- **B9** Onshape MCP `image_id` + `crop_image` 間接化 (§9.2.3) — iter-screenshot の戻り値を inline PNG → `image_id` (SHA256-keyed cache) に、`crop_image(id, bbox)` で部分拡大を再 render 無しで提供 → **ADR-0062 で統合**
- **B10** Onshape MCP 構造化 geometry diff (§9.2.2) — `changes` を string list から `{parts_added/removed, bbox_before/after_voxels, voxel_count_delta, texture_refs_added/removed}` に変更 → **ADR-0062 で統合**
- **B11** text-to-cad `@cad[name]` handle syntax (§10.1.3) — ASDSL emit 時に `@cad[blade]` で既存 part 参照、全体再生成を強制せず B6 と整合的に diff edit 可能化 → **ADR-0062 で統合**
- **B12** flowful-ai **6-view fixed-camera render bundle** (§10.1.2) — iter-screenshot を 3 view → 6 view (front/back/left/right/top/iso) に拡張
- **B13** ForgeCAD skill chain split (§9.1.3) — `build-model` を `build-model-spec` (HLD) + `build-model-blockout` (rough cuboid 3-7 parts) + `build-model-detail` (final ASDSL) の 3 sub-skill に分離、各 sub-skill に明示的 stop rule

#### Tier C 拡張 (新 wave + ADR + 研究的)

- **C6** LL3M **BlenderRAG → Astera ASDSL exemplar RAG** (§3.5c → §8.2) — `tools/model-gen/asdsl-examples/` を retrieval index 化、build-model skill が "sword っぽいパーツ事例" を取り出して prompt に注入
- **C7** EvoCAD **population-based N-candidate** (§3.8a → §8.2) — N=5 並列 candidate を Studio で生成 → 自動 vision critic で fitness 評価 → 上位選択
- **C8** EvoCAD **Euler characteristic 位相 metric** (§3.8b → §8.2) — ModelBench (C1) の評価軸に位相同型距離を追加
- **C9** BlenderLLM **CADBench 4-dim 評価軸** (§9.3.3) — C1 ModelBench の scoring rubric を `Attr` / `Spat` / `Inst` / `E_syntax` の 4 軸に正式化
- **C10** ForgeCAD timestamped GIF benchmark archive (§9.1.7) — `tools/model-gen/benchmark/<slug>-<model>-<timestamp>-vN.gif` の軽量 manual eval。**Phase 1 PoC closing posture に整合** (重い C1 ModelBench より先行投入候補)
- **C11** openscad-studio パターン (§10.1.4) — Studio HTTP API を MCP server として wrap、外部 agent からの操作を可能化
- **C12** mindcraft **per-task LLM split** (§10.2.4) — planner=Sonnet / coder=Opus / vision-critic=Haiku の role-based model assignment (Studio multi-agent C3 の細則化)
- **C13** ForgeCAD skill installer (§9.1.1) — `bun run studio skill install --target {claude|codex|opencode}` で `.claude/skills/build-model/` + `.claude/skills/generate-textures/` を各 runtime の skill dir に配置 (既存 C5 を具体化)

### 5.5 着手順序の追加判断 (2026-05 follow-up)

§5.4 追加分の優先度 (judgment call、本来の §5 推奨順序 §5.1-§5.3 と統合する):

1. **即時着手すべき (skill prompt のみ、no schema change)**: A6 / A7 / A9 / A10 / A13 — Tier A1 の延長線上、build-model skill 内に組み込み可能
2. **Studio CLI 増分 (低リスク)**: A11 (channel flag) / B12 (6-view) / C10 (GIF benchmark archive)
3. **skill 構造改修**: A12 (frontmatter simplify) / B13 (skill chain split into spec/blockout/detail) — A12 → B13 の順で
4. **schema 変更を伴う (ADR 必要)**: B6 / B7 / B8 / B9 / B10 / B11 — Tier B 着手前に「ASDSL v2」 ADR を 1 本切る (`assertions` + `diff edit verb` + `@handle syntax` を統合)
5. **研究 / 長期 (新 wave)**: C6 / C7 / C8 / C9 / C11 / C12 / C13

**重要**: A2 (CADCodeVerify auto-critic、本来の §5.1) と A6/A7 は同じ build-model skill prompt 改修のため bundle 推奨。bundle 順 = A2 → A6 → A7 → A9 → A10 → A13。

---

## 6. 引用 URL

### ForgeCAD
- https://github.com/KoStard/ForgeCAD
- https://forgecad.io/

### Onshape × Claude
- https://github.com/ReshefElisha/jarvis-onshape-mcp/blob/main/RESEARCH.md
- https://hardwarefyi.substack.com/p/claude-learns-design-intent
- https://reshef.io/a/20260420_onshape_mcp/

### Minecraft / voxel
- T2BM: https://arxiv.org/abs/2406.08751
- DreamCraft: https://arxiv.org/abs/2404.15538

### CAD code / DSL
- CAD-Recode: https://arxiv.org/abs/2412.14042 / https://github.com/filaPro/cad-recode
- Text2CAD: https://arxiv.org/abs/2409.17106 / https://github.com/SadilKhan/Text2CAD
- CAD-MLLM: https://arxiv.org/abs/2411.04954
- CAD-Llama: https://arxiv.org/abs/2505.04481
- CAD-Coder: https://arxiv.org/abs/2505.14646 / https://github.com/anniedoris/CAD-Coder
- BlenderLLM: https://arxiv.org/abs/2412.14203 / https://github.com/FreedomIntelligence/BlenderLLM
- AIDL: https://arxiv.org/abs/2502.09819

### Vision critic / iteration
- LL3M: https://arxiv.org/html/2508.08228v1
- CADCodeVerify: https://arxiv.org/abs/2410.05340
- EvoCAD: https://arxiv.org/abs/2510.11631
- 3D-GPT: https://arxiv.org/abs/2310.12945

### Parts decomposition
- PartCrafter: https://arxiv.org/abs/2506.05573 / https://github.com/wgsxm/PartCrafter
- HoloPart: https://arxiv.org/abs/2504.07943
- ShapeAssembly (Siggraph Asia 2020, Stanford)

### Survey
- LLM for CAD: A Survey — https://arxiv.org/html/2505.08137v1

### Direct mesh (参考)
- LLaMA-Mesh: https://github.com/nv-tlabs/LLaMA-Mesh
- MeshGPT: https://arxiv.org/abs/2311.15475

### Practitioner articles
- Grizzly Peak: https://www.grizzlypeaksoftware.com/articles/p/craftsman-meets-code-building-furniture-with-parametric-design-and-llms-DOMLfv
- 3D Printer Academy (OpenSCAD × Claude): https://3dprinteracademy.com/blogs/news-1/ai-cad-design-with-openscad-and-anthropic-s-claude-3-5-sonnet

### 2026-05 follow-up deep-dive (§9) — raw source URLs
- ForgeCAD skill index: https://raw.githubusercontent.com/KoStard/ForgeCAD/mainline/skills/README.md
- ForgeCAD make-a-model: https://raw.githubusercontent.com/KoStard/ForgeCAD/mainline/skills/forgecad-make-a-model/SKILL.md
- ForgeCAD render-inspect: https://raw.githubusercontent.com/KoStard/ForgeCAD/mainline/skills/forgecad-render-inspect/SKILL.md
- ForgeCAD high-level-spec: https://raw.githubusercontent.com/KoStard/ForgeCAD/mainline/skills/forgecad-high-level-spec/SKILL.md
- ForgeCAD blockout-model: https://raw.githubusercontent.com/KoStard/ForgeCAD/mainline/skills/forgecad-blockout-model/SKILL.md
- ForgeCAD prepare-prompt: https://raw.githubusercontent.com/KoStard/ForgeCAD/mainline/skills/forgecad-prepare-prompt/SKILL.md
- Onshape MCP RESEARCH.md: https://raw.githubusercontent.com/ReshefElisha/jarvis-onshape-mcp/main/RESEARCH.md
- Onshape MCP feature_apply.py: https://raw.githubusercontent.com/ReshefElisha/jarvis-onshape-mcp/main/onshape_mcp/api/feature_apply.py
- Onshape MCP geometry_diff.py: https://raw.githubusercontent.com/ReshefElisha/jarvis-onshape-mcp/main/onshape_mcp/api/geometry_diff.py
- Onshape MCP rendering.py: https://raw.githubusercontent.com/ReshefElisha/jarvis-onshape-mcp/main/onshape_mcp/api/rendering.py
- Onshape MCP server.py: https://raw.githubusercontent.com/ReshefElisha/jarvis-onshape-mcp/main/onshape_mcp/server.py
- Onshape MCP vision-decompose SKILL: https://raw.githubusercontent.com/ReshefElisha/jarvis-onshape-mcp/main/skills/vision-decompose/SKILL.md
- Onshape MCP onshape SKILL: https://raw.githubusercontent.com/ReshefElisha/jarvis-onshape-mcp/main/skills/onshape/SKILL.md
- BlenderLLM infer.py: https://raw.githubusercontent.com/FreedomIntelligence/BlenderLLM/main/scripts/infer.py
- BlenderLLM blender_runner.py: https://raw.githubusercontent.com/FreedomIntelligence/BlenderLLM/main/scripts/blender_runner.py

### 2026-05 follow-up broader sweep (§10)
- cad-khana (diagnostics-first build123d): https://github.com/cyberchitta/cad-khana
- flowful-ai/cad-skill (CadQuery, 6-view render): https://github.com/flowful-ai/cad-skill
- earthtojake/text-to-cad (multi-skill, `@cad[]` handle): https://github.com/earthtojake/text-to-cad
- zacharyfmarion/openscad-studio (HTTP + localhost MCP): https://github.com/zacharyfmarion/openscad-studio
- clawd-maf/cad-agent (Docker REST+MCP): https://github.com/clawd-maf/cad-agent
- amenti-labs/vibecraft (Fabric client MCP): https://github.com/amenti-labs/vibecraft
- yuniko-software/minecraft-mcp-server (Mineflayer MCP): https://github.com/yuniko-software/minecraft-mcp-server
- mindcraft-bots/mindcraft (per-task LLM split): https://github.com/mindcraft-bots/mindcraft
- iancanderson/openscad-agent (DUPLICATE): https://github.com/iancanderson/openscad-agent

### Sweep triangulation sources
- Snyk: Top 8 Claude Skills for 3D Modeling — https://snyk.io/articles/top-claude-skills-3d-modeling-game-dev-shader-programming/
- Snyk: 9 MCP Servers for CAD with AI — https://snyk.io/articles/9-mcp-servers-for-computer-aided-drafting-cad-with-ai/
- ChatForest: CAD & 3D Modeling MCP Servers — https://chatforest.com/reviews/cad-3d-modeling-mcp-servers/
- arXiv 2512.10501 (dual-agent Actor/Critic PCG, no public repo yet) — https://arxiv.org/abs/2512.10501

---

## 7. Caveats

1. **「ForgeCAD」の名称コリジョン**: KoStard 氏 ≠ GadatheGod/ForgeCAD (STEP/IGES viewer)。本ドキュメントは KoStard 氏のものを指す。
2. **Onshape × Anthropic 公式デモは存在しない**: Reshef Elisha 氏のコミュニティ MCP が「事実上のデモ」。
3. **「PartGPT」「VoxLM」**: ユーザ言及あったが、本調査では同名の確立した研究は見つからず。最も近いのは Part-X-MLLM / PartCrafter / PartGen。
4. **CAD-LLM 領域は 2024-2025 で論文数倍増中**: 6 ヶ月以上経過後に再調査推奨。
5. **本ドキュメントは設計提案のみ**: 採択 / 順序 / 着手判断は別 plan セッション + ADR で。
6. **ForgeCAD default branch is `mainline`**, not `main` (2026-05 follow-up §9.1)。raw URL は `raw.githubusercontent.com/KoStard/ForgeCAD/mainline/...` を使う。`forgecad-cli/` / `evals/` / `benchmark/` ディレクトリは repo に存在しない (CLI は npm binary 配布、benchmark archive は forgecad.io ホスト)。
7. **BlenderLLM repo is far thinner than paper claims** (2026-05 follow-up §9.3): no `prompts/` directory、no `benchmark/`、no RAG corpus、no multi-agent code、no self-improvement loop in inference path。Paper の "multi-agent + self-improvement + CADBench" 主張は **paper-only**、code 観点では fine-tuned model weights + Blender execution harness のみ open-source。Astera 引用時は paper の eval methodology に anchor、code patterns には依存しない。
8. **License watch on sweep finds (§10)**: `cad-skill` (PolyForm Noncommercial 1.0.0)、`cad-agent` (PolyForm Small Business 1.0.0)、`openscad-studio` (GPL-2.0)。Astera は **copy patterns not code** を原則。GPL は OpenSCAD inherited、Astera が GPL code を embed しない限り無関係。
9. **§5.4 で 24 件の新 Tier 項目を追加**: roadmap 拡張案であり、採択判断は別 plan セッション。Tier B 着手前に「ASDSL v2」 ADR を 1 本切る予定 (B6/B7/B11 を統合)。
10. **Voxel × DSL × LLM intersection は post-2024 で OSS 不在**: §10 sweep の結論。Astera ASDSL は fresh territory — novelty 観点はプラス、external benchmark 不在はマイナス (= C9 MC-Bench 自前構築の必要性を裏付け)。

---

## 8. OSS absorption audit (2026-05 follow-up)

§3 で挙げた 9 OSS のうち、Astera が「ハーネス / 実装 pattern として」取り込めたものを監査。**実装済みは Tier A1 (Onshape MCP 2 段プロンプト分離) のみ**。§5 Tier roadmap にもマップされず取りこぼした pattern が 6 件あったため、本 §8 で明示化し、§5.4 で roadmap に組み込み済。

### 8.1 Audit matrix

| §3 OSS | 抽出 pattern | §5 Tier mapping | Status | 備考 |
|---|---|---|---|---|
| §3.1 ForgeCAD | skill installer / CLI render-inspect / benchmark archive (+ §9.1 で 7 patterns) | A5 / C5 (→ §5.4 で C10/C13 に具体化) | PENDING | §9.1 で deep-dive、A9/A10/A11/A12/B13 派生 |
| §3.2 T2BM | 3-stage prompt / JSON interlayer / **repair module** | (a)(b) 既に同型 / (c) **未マップ** | PARTIAL | **(c) MISSED → A6 (§5.4)** |
| §3.3 AIDL | constraint solver | B5 | PENDING | — |
| §3.4 Onshape MCP | 2-stage prompt / multi-view PNG / 60 tools / truth envelope (+ §9.2 で 5 patterns) | A1 / (b)-(d) 同型済 (§9.2 で詳細化) | **DONE (A1)** | A1 完了。§9.2 で A8/A13/B8/B9/B10 派生 |
| §3.5 LL3M | 6-agent / **diff editing** / **BlenderRAG** / role-based model | C3 / (b)(c) **未マップ** / C3 | PENDING + MISSED 2 件 | **(b)→B6, (c)→C6 (§5.4)** |
| §3.6 CADCodeVerify | VLM auto-validation | A2 | PENDING | A2 → A6 → A7 bundle 推奨 |
| §3.7 BlenderLLM | self-improvement / CADBench (paper claim) | C1 / C2 | PENDING | **§9.3 で repo に benchmark/prompt 不在を確認、paper-only** |
| §3.8 EvoCAD | **population-based** / **Euler metric** | 両方未マップ | **MISSED** | **→ C7, C8 (§5.4)** |
| §3.9 PartCrafter | hierarchical attention / **global-first order** | (a) 未マップ / (b) 未マップ | **MISSED** | **(b) → A7 (§5.4)** — skill prompt で簡単導入可 |

### 8.2 Missed patterns 一覧 (本来 §5 にあるべきだった)

§3 で識別済みだが §5 Tier roadmap にマップされていなかった patterns (本 follow-up で §5.4 に組み込み):

1. T2BM **repair module** プロトコル → **A6**
2. PartCrafter **global-first generation order** → **A7**
3. LL3M **diff-editing 強制** → **B6**
4. LL3M **BlenderRAG** equivalent → **C6**
5. EvoCAD **population-based exploration** → **C7**
6. EvoCAD **Euler characteristic metric** → **C8**

### 8.3 結論

- **A1 のみ実装済み**。残り §5 Tier 旧 13 種 (A2-A5, B1-B5, C1-C5) は pending。本 follow-up でさらに 24 種追加 (§5.4) — 計 37 種 (1 done / 36 pending)。
- §3 は paper / blog 抽象化のみで、**OSS 実コードは未読だった**。§9 で 3 repo (ForgeCAD / Onshape MCP / BlenderLLM) を deep-dive、§10 で 9 個の新規 OSS を sweep して補完。
- BlenderLLM は paper の "multi-agent + self-improvement + BlenderRAG" framing と実 repo が乖離 (§9.3 / §7 #7) — **paper-only claim として扱う**。引用時は eval methodology のみ anchor。
- §3 既出の 9 OSS は **harness 観点では概ね尽くされている** (§9 で深掘りしても新規 abstraction は出ず、concrete pattern が深まる)。**新規価値は §10 sweep にある** (4 NOVEL finds)。

---

## 9. Deep-dive findings — ForgeCAD / Onshape MCP / BlenderLLM (2026-05 follow-up)

§3 の抽象化を超えて 3 repo の実コードから抽出した concrete pattern。各 pattern に file path + 引用 + Astera 適用先を記載。

### 9.1 ForgeCAD (KoStard/ForgeCAD)

**Repo 注記**: default branch は `mainline` (not `main`)。flat layout (`README.md`, `LICENSE`, `examples/`, `skills/` のみ)。`forgecad-cli/` / `evals/` / `benchmark/` ディレクトリ無し — CLI は npm binary、benchmark archive は forgecad.io でホスト。skills は 13 個 (12 sub-skill + 1 index)。

#### 9.1.1 Skill installer with per-runtime target

`README.md`:
```
forgecad skill install --target claude    # ~/.claude/skills
forgecad skill install --target opencode  # ~/.config/opencode/skills
forgecad skill install --target codex     # ~/.codex/skills
```
Default は all-known runtime に fan out。shell-less agent 向け fallback: `forgecad skill one-file ~/Desktop/forgecad-context.md`.

**Astera 適用先**: `tools/studio/web/scripts/install-skills.ts` (新規)、`bun run studio skill install --target claude` で `.claude/skills/build-model/` + `.claude/skills/generate-textures/` を copy。`--one-file` フラグも追加。→ **Tier C13** (§5.4)

#### 9.1.2 Three-field minimalist SKILL.md frontmatter

`skills/forgecad-render-inspect/SKILL.md`:
```yaml
---
name: forgecad-render-inspect
description: Run and interpret ForgeCAD `render inspect` bundles for model verification
forgecad-public: true
---
```
全 skill が `name` / `description` / 任意 public flag の 3 field のみ。`allowed-tools` / `model` / `version` 不採用。Description は 1 sentence + "Use when..." trigger 句 (例: forgecad core: *"Use when building or modifying ForgeCAD geometry, structuring multi-file projects, validating scripts, or using ForgeCAD export/render tooling."*).

**Astera 適用先**: 現行 `.claude/skills/build-model/SKILL.md` は `allowed-tools: Bash, Read, Write, Edit, WebSearch, WebFetch, AskUserQuestion, Glob` を持つ。Anthropic skill-matcher routing は description の trigger 文に依存するため、description 簡素化 + 明示的 trigger 句を導入。→ **Tier A12** (§5.4)

#### 9.1.3 Skill chain: prepare-prompt → spec → blockout → make-a-model

4 段 staged skills with hand-off:
1. `forgecad-prepare-prompt` → master prompt 化
2. `forgecad-high-level-spec` → 8-section HLD (Problem / Approach / Key Interfaces / Dictionary / Alternatives / Usage / Concerns / Decisions)
3. `forgecad-blockout-model` → 3-7 parts、round numbers、simple primitives、明示的 stop rule: *"When the blockout has answered the high-level questions, stop."*
4. `forgecad-make-a-model` → 詳細 parametric build

**Astera 適用先**: 現行 `build-model` skill を `build-model-spec` (HLD) + `build-model-blockout` (rough cuboid massing) + `build-model-detail` (final ASDSL) に分割。各 sub-skill の **stop rule を明文化**。**重要**: Astera の iteration loop には現在 documented termination criterion がない (「looks right」のみ)。→ **Tier B13** (§5.4)

#### 9.1.4 Channel table for render verbs

`skills/forgecad-render-inspect/SKILL.md`:
```
forgecad render inspect model.forge.js /tmp/model-inspect \
  --channels rgb,mask,collisions --force --size 700
```
| 確認したい質問 | Channel set |
|---|---|
| Quick orientation and object naming | `rgb,mask` |
| Hidden internals, cavities, pockets | `rgb,mask,section` |
| Multi-part interference, fit checks | `rgb,mask,collisions` |
| Floating parts, accidental fusion | `rgb,mask,connectivity` |
| Air gaps between components | `rgb,mask,distance` |

各 render run は bundle directory + JSON manifest を返す。`python skills/forgecad-render-inspect/summarize_manifest.py` で summarize。

**Astera 適用先**: 現行 `iter-screenshot` は常に iso/item_gui/front の 3 view を返すのみ。`--channels` フラグで question → view set tailoring (iso/front/top/exploded/wireframe/scale)。`manifest.json` を PNG と並べて出力 (camera/bbox/voxel count メタデータ)。Single-file summarizer helper を skill dir に同梱。→ **Tier A11 + B12** (§5.4)

#### 9.1.5 "You are building blind unless you render" — mandatory render gate

`skills/forgecad-make-a-model/SKILL.md`:
> "You are building blind unless you render. `forgecad run` only validates syntax — it cannot detect misaligned holes or fitting issues."

Mandatory cycle: write → `forgecad run` (syntax) → `forgecad render inspect --channels …` (semantics) → iterate.

**Astera 適用先**: skill HARD RULES に追加: "ASDSL compile = syntax only; proportions, voxel-overlap, item_gui readability are only visible post-render. No render-inspect bundle in `decisions.md` = not done." Astera の `verify:wave3` 12/12 が syntax-side のみ check しているのと整合的に補完。→ **Tier A9** (§5.4)

#### 9.1.6 8-step inspection protocol + "report evidence"

`skills/forgecad-render-inspect/SKILL.md`:
1. Identify inspection question
2. Choose output dir (`/tmp/<model>-inspect`)
3. Select channel set
4. Run command
5. Summarize manifest (jq / python)
6. Inspect PNGs (start `rgb/iso.png`)
7. Evaluate (intentional vs unintentional)
8. **Report**: command + bundle path + manifest highlights + visual findings

**Astera 適用先**: 現行 truth-envelope `{ok, hints, changes}` は「何が変わった」しか報告せず、「なぜ妥当か」の証拠が無い。Step 8 "report evidence" を skill iteration 義務に追加 → 各 iteration の `decisions.md` の `## Step NN — <label>` block に `evidence` sub-section を必須化。Step 7 ("all intentional?") は natural stop signal にもなる (Astera の現状欠陥)。→ **Tier A10** (§5.4)

#### 9.1.7 Benchmark archive: timestamped per-model GIF (no automated metric)

`README.md` benchmark table、naming convention:
```
amazon-nova-2-lite-v1-2026-02-13-00-15-44-v1.gif
<model-slug>-<model-version>-<YYYY-MM-DD-HH-MM-SS>-<run-version>.gif
```
Free-text prompts ("Make a home AC unit", "Make a fully functional robot hand")。**自動 metric 無し** — 手動 inspection。README 明示: *"These rows are not recommendations and are not the approved model list"* — 履歴 archive であって regression rig ではない。

**Astera 適用先**: `tools/model-gen/benchmark/` (新規) に `(prompt, model, timestamp, run-version) → final-render.gif` の軽量 archive。Phase 1 PoC closing posture に整合 (release 前重い eval は overkill)。例: `iron-sword-claude-opus-4-7-2026-05-19-14-30-00-v1.gif`。→ **Tier C10** (§5.4) — C1 ModelBench の代替として **先行投入候補**

#### 9.1.8 ForgeCAD で借りるべきでないもの

- **SaaS sync model** (`forgecad project init/push/pull/login`) — ForgeCAD は SaaS、Astera は local-only Studio。SHA-256 content-hash sync / member role / `forgecad.io/m/<shareId>` publish flow は overhead。
- **8-section HLD literal application** — 32×32 voxel model に Key Interfaces / Alternatives (2-3 strategies) / Concerns / Decisions は over-scoped。Compress to 2 sections (Intent / Constraints) — A12 で実現。
- **`forgecad-api-dogfood` 風 friction journal as permanent skill** — one-off audit には useful、recurring practice には PoC 段階で重い。

#### 9.1.9 Cross-cutting observation

ForgeCAD の CLI は conventional shell exit code + stderr text + bundle directory を返す。**Astera の `{ok, status, hints, changes}` envelope のような構造化 envelope を持たない**。Skill が "collisions ≠ face-touching"、"thickness gray = unresolved, not safe" のような hint を明示で agent に伝えて補う形。Astera の truth envelope は **より良い primitive** — keep it, don't downgrade. ただし §9.2.1 の 5-status enum 拡張は採用 (B8)。

### 9.2 Onshape MCP (ReshefElisha/jarvis-onshape-mcp)

Repo 確認済 live。~22 API modules under `onshape_mcp/api/`、14 feature builders under `onshape_mcp/builders/`、2 SKILL.md under `skills/`、+ `RESEARCH.md`。"60 tools" framing は README で 8 categories × 3-6 each = ~37 highlighted、"roughly 60" total claim。

#### 9.2.1 Typed Pydantic truth envelope (5-status enum)

`onshape_mcp/api/feature_apply.py`:
```python
class FeatureApplyResult(BaseModel):
    ok: bool
    status: FeatureStatus    # "OK" | "INFO" | "WARNING" | "ERROR" | "UNKNOWN"
    feature_id: str
    feature_name: str
    feature_type: str
    error_message: Optional[str] = None
    changes: Optional[Dict[str, Any]] = None
    raw: Dict[str, Any] = Field(default_factory=dict)
```
`ok = status in ("OK", "INFO")` — INFO は「成功したが regenerator が auto-adjust した」を表現。WARNING は ok=False だが feature は build した。Astera の binary `ok: bool` より granular。

**Astera 適用先**: `tools/studio/web/server/asdsl/types.ts` の `TruthEnvelope` を `{ok, status: OK|INFO|WARNING|ERROR|UNKNOWN, hints, changes}` に拡張。INFO state 例: palette 量子化で color が clamp された、part が voxel grid fit のため reshape された。現状 non-fatal は全て flat `hints` list に入り「succeeded but auto-adjusted」signal を loss。→ **Tier B8** (§5.4)

#### 9.2.2 Opt-in geometry diff (2 round-trip)

`onshape_mcp/api/geometry_diff.py` の `apply_feature_and_check(..., track_changes=True)` が pre/post `bodydetails + massproperties` を取得、`changes` に attach:
```
{
  volume_before_mm3, volume_after_mm3, volume_delta_mm3,
  faces_added, faces_removed,
  edges_added, edges_removed,
  bbox_before_mm, bbox_after_mm,
  summary  // human-readable one-liner
}
```

**Astera 適用先**: `tools/studio/web/server/asdsl/compile.ts` の `changes` field を string list (現状 "added cube 'leg_lf'") から構造化 `{parts_added, parts_removed, bbox_before_voxels, bbox_after_voxels, voxel_count_delta, texture_refs_added, texture_refs_removed, summary}` に。Astera compile は <100ms なので default-on (opt-in 不要)。→ **Tier B10** (§5.4)

#### 9.2.3 Rendering returns `image_id` not bytes

`onshape_mcp/api/rendering.py`:
```python
@dataclass
class RenderedView:
    view: str            # "iso" | "top" | "front" | "right" (default 4)
    image_id: str        # "img_<sha256-hex>"
    width: int           # default 1200
    height: int          # default 800
    bytes: int           # size only, not the bytes themselves
```
PNG は in-process cache (SHA256-keyed)。MCP client は `image_id` だけ受け取り、`get_image(image_id)` で pixel 取得。**`crop_image(imageId, x1, y1, x2, y2)`** で再 render 無く zoom — vision-decompose が cheap になる primitive。

**Astera 適用先**: `iter-screenshot` の戻り値を inline PNG → `image_id` indirection に切り替え + cache + `crop_image(id, bbox)`。Astera MC-native 拡張として `crop_model_view(viewId, partAnchor)` — named part の bbox に crop。32×32 texture preview には over-engineering だが、3-view model capture には明確な win (vision-review が limb 単位 crop で inspect 可能に)。→ **Tier B9** (§5.4)

#### 9.2.4 Tool category as comment-delimited monolith

`onshape_mcp/server.py`:
```python
@app.list_tools()
async def list_tools() -> list[Tool]:
    return [
        # === Assembly Tools ===
        Tool(name="create_assembly", ...),
        # === Sketch Tools ===
        Tool(name="create_sketch_circle", ...),
        ...
    ]
```
~60 tools が 1 つの return list にコメント分けで並ぶ。Builders は `builders/` (extrude.py / fillet.py 等) に per-file 分割、ただし tool registry は monolithic。

**Astera 適用先**: 現行 `tools/studio/web/server/routes/*.ts` は per-file 分割 (asdsl.ts / chat.ts / composite.ts / cuboids.ts / effects.ts / jobs.ts / models.ts / paint.ts...)。**adopt nothing** — Astera が既に上位。

#### 9.2.5 Vision-decompose pre-build SKILL + think-out-loud rule

`skills/vision-decompose/SKILL.md`: 3-phase pipeline (Analysis with `crop_image` → Decomposition with feature tree `{type, role, size, position, face, orientation, count, dim_source, notes}` → Build only after user confirm "Does this match what you intended?")。

`skills/onshape/SKILL.md` の **think-out-loud gate**:
> "Before every non-trivial tool call (sketch, extrude, boolean, fillet, chamfer, describe, export), emit a short plan text (1-3 sentences, plain assistant output) saying **WHY** you're making this call and **WHAT you expect to see / build**."

**Astera 適用先**:
- (a) Tier A1 で導入した Phase 0 spec.md を **user-confirmation gate** に強化 (現状は Claude 単独で書くだけ、ユーザレビュー回避禁止) → **Tier A8** (§5.4)
- (b) `asdsl place/move/set-tex` 前に 1-3 文 "WHY / WHAT I expect" を skill prompt で要求 → **Tier A13** (§5.4)

#### 9.2.6 Where Astera matches / lags (Onshape MCP)

- **Matches**: truth envelope concept (`{ok, status, hints, changes}`)、multi-view PNG return (`iter-screenshot` 3 views)、per-file tool/route split (Onshape monolith より cleaner)。
- **Lags**: INFO status 不在 / 構造化 geometry diff 不在 (string list のみ) / `image_id` indirection + `crop` tool 不在 / vision-decompose pre-build user-confirm gate 弱 / think-out-loud rule 不在 / `RESEARCH.md` 風 empirical variant scoring (v001-v005 composite scores 0.155→0.193) 不在。

### 9.3 BlenderLLM (FreedomIntelligence/BlenderLLM)

**重要な発見**: BlenderLLM の repo は **paper framing より far thinner**。Top-level: `chat.py` / `modeling.py` / `requirements.txt` / `assets/` / `scripts/{infer.py, blender_runner.py, config.py, geometry_utils.py}` のみ。**`prompts/` / `planner.md` / `coder.md` / `critic.md` / `verifier.md` / `benchmark/` / `data/` / `corpus/` / RAG retrieval / self-improvement loop in inference path 全部 不在**。Repo は fine-tuned model weights + Blender execution harness を出荷、その他 (BlendNet 12k dataset、CADBench 700 samples、"self-improvement") は paper-only。→ §7 #7 caveat.

#### 9.3.1 Single system prompt, single forward pass

`scripts/infer.py` の `generate_response(model_name, prompt, max_new_tokens=512)`:
- system: "You are an expert in using bpy script to create 3D models. Based on the following instruction, your task is to write the corresponding bpy script that will generate the desired 3D model in Blender."
- `tokenizer.apply_chat_template(messages, ...)`
- `model.generate(...)` → 1 forward pass、decoded text
- **No iteration. No critic. No verifier. No retrieval.** "Self-improvement" は **training-time** dataset curation のことであり、inference-time multi-agent loop ではない。

**Astera 適用先**: 現行 `build-model` skill は既に iter-screenshot loop + vision-review step を持つ。**adopt nothing** — Astera が上位。

#### 9.3.2 Multi-view PNG via per-camera subprocess

`scripts/blender_runner.py` が `camera_locations × camera_rotations` を iterate、Blender headless を 1 view ずつ起動、`{name}_view{i}.png` を保存。`bpy.context.scene.render.film_transparent = True`。**Verification 無し、`subprocess.run()` の return-value check 無し**、temp script は success 関係なく削除。

**Astera 適用先**: 現行 `iter-screenshot` は適切な error propagation 付。**adopt nothing** — Astera の harness が上位。

#### 9.3.3 CADBench is closed (paper-only)

README は CADBench-Sim (500) + CADBench-Wild (200) = 700 samples、`Attr.` / `Spat.` / `Inst.` / `E_syntax` の 4 軸 GPT-4o-as-judge scoring を主張。**Dataset 本体は repo に無い**。BlenderLLM の主張スコア (Sim 0.748 / Wild 0.664) は repo 単独で再現不可。

**Astera 適用先**: 4-dim scoring rubric (attribute fidelity / spatial correctness / instruction compliance / syntax-error rate) は MC-Bench (C1 ModelBench) の初期 metric として妥当。50 prompt fixed set ("a wooden sword", "a stone pickaxe", "a chicken mob model") + reference voxel-grid + Opus-as-judge on 4 dims を Astera で構築。→ **Tier C9** (§5.4)

#### 9.3.4 No RAG corpus

Paper は BlenderRAG に言及するが repo に存在せず。Vector store / embedding code / retrieval entry-point 全て不在。System prompt は hardcoded string。

**Astera 適用先**: `.claude/skills/build-model/refs/` (新規) または `tools/model-gen/asdsl-examples/` を retrieval index 化し、ASDSL exemplar を part archetype indexable で配置、retrieval-lite を構築。ユーザが "build a chicken" と言ったら近い prior build を retrieve して prompt に挿入。→ **Tier C6** (§5.4)

#### 9.3.5 No multi-agent role split

`chat.py` / `infer.py` / `modeling.py` / `blender_runner.py` 全 grep で planner / coder / critic / verifier の mention 無し。Paper の "multi-agent self-improvement" framing は code に存在しない。

**Astera 適用先**: BlenderLLM から copy できる template 無し。multi-agent role split を設計するなら **Onshape MCP の vision-decompose vs build split (§9.2.5)** を template にする方が良い。Mindcraft (§10.2.4) の per-task LLM split も参考。

#### 9.3.6 Where Astera matches / lags (BlenderLLM)

- **Matches**: multi-view PNG harness (iter-screenshot ≥ blender_runner.py, error propagation 付)、single-role skill structure (parity)。
- **Lags**: eval benchmark with quantitative scoring (no MC-Bench)、exemplar corpus / RAG-lite directory — **ただし BlenderLLM は両方とも code template を提供しない**ため、Astera は scratch から設計必要 (C6 / C9)。

---

## 10. 2026-Q2 broader OSS sweep

§3 missed OSS、§9 deep-dive 3 repo を除き、harness 観点で post-2024 に登場した OSS を sweep。Verdict 別に整理。

### 10.1 NOVEL — adopt 候補

#### 10.1.1 cad-khana (cyberchitta/cad-khana)

- URL: https://github.com/cyberchitta/cad-khana (Apache-2.0, 40 commits, early-stage)
- Claude Code skill + Python CLI で build123d を **diagnostics-first** に wrap
- CLI verb set (`khana build`, `khana check`, `khana view`, `khana render`, `khana diff`) が Astera の `bun run studio <verb>` と同型 — 共に小さい verb set で構造化 JSON を返す
- **核心**: スクリプトに **declarative assertions** (`assert_no_interference`, `assert_clearance`, `assert_min_wall`) を inline 宣言、それを `diagnostics.json` に集約して agent に数値で読ませる。PNG render は assertion で express できない shape ambiguity のみの fallback。
- SKILL.md + CLAUDE.md + `references/` 構成が Astera 既存 convention と一致

**Astera angle**: Direct mapping to Astera's truth-envelope philosophy。ASDSL part に `assertions: [aligned_with_anchor(blade.base, guard.top), no_self_intersection, fits_within_voxel_envelope(32,32,32)]` を declarative に宣言可能化、`{ok, status, hints, diagnostics}` 形に envelope 拡張。Astera の `verify:wave3` 12 checks も declarative assertion 化可能。→ **Tier B7** (§5.4)

**Verdict: NOVEL — adopt.**

#### 10.1.2 flowful-ai/cad-skill

- URL: https://github.com/flowful-ai/cad-skill (PolyForm Noncommercial 1.0.0)
- Polished Claude Code skill for parametric 3D printing via CadQuery
- Files: `SKILL.md` + `run_cadquery_model.py` (subprocess wrapper with **JSON error capture for self-correction loops**) + `preview.py` (headless **6-view** PNG renderer using trimesh/pyrender) + `mesh_io.py` (STL validation) + `design-review.md` (printability checklist)
- 6-view fixed-camera bundle は Astera 現行の 3-view より disciplined

**Astera angle**: (a) iter-screenshot を 6 view に拡張 (front/back/left/right/top/iso) — visual critic に canonical 角度を強制 → **Tier B12**。(b) 明示的 `design-review.md` 風 checklist file を skill 内で reference (Astera の場合: voxel readability、bbox 制約、texture 制約、symmetry の checklist)。License は PolyForm Noncommercial、**copy patterns not code**。

**Verdict: NOVEL — adopt patterns.**

#### 10.1.3 earthtojake/text-to-cad

- URL: https://github.com/earthtojake/text-to-cad (MIT, ~3k stars, 27 commits)
- Multi-skill collection ("CAD skill", "step.parts skill")
- **`harness/` directory** (AGENTS.md + CLAUDE.md instruction files) と **`skills/` directory** を明確分離 (Astera 現行は monolithic SKILL.md)
- Cross-runtime installer (`agent-skills-cli`) で Claude Code / Codex / Gemini CLI / OpenClaw
- Output format ladder: STEP / STL / 3MF / DXF / GLB + **URDF / SDF / SRDF (robotics)** — broadest format range observed
- **`@cad[handle]` syntax** for cross-iteration geometry references — `@part[barrel].rotate(15deg)` で全体再生成回避

**Astera angle**: (a) `@cad[name]` を ASDSL 拡張に取り入れて diff edit (B6 + B11 統合)。(b) `harness/` vs `skills/` 分離を採用、`.claude/skills/build-model/harness/AGENTS.md` を別ファイル化して monolithic SKILL.md を slim down (A12 + B13 と統合)。

**Verdict: NOVEL — adopt the handle-reference pattern + harness split.**

#### 10.1.4 zacharyfmarion/openscad-studio

- URL: https://github.com/zacharyfmarion/openscad-studio (GPL-2.0, v1.2.2 April 2026)
- Web app + native macOS desktop variant
- Desktop variant が **localhost MCP server** を expose、外部 agent (Claude Code) が render / diagnostics / screenshot / edit を call 可能
- Astera Studio (HTTP+SPA) と **同 architecture**: human-facing visual UI + agent-facing tool/RPC surface を同 process で持つ
- `skills-lock.json` で version-pinned skill manifest

**Astera angle**: 現行 Studio は HTTP only。**HTTP verb を MCP tool として wrap** すれば agent からの操作が即対応可能 — 同 backend、2 client (human SPA + agent MCP)。GPL-2.0 license 先例: derived works of OpenSCAD GPL inherit it (Astera が OpenSCAD code を embed しない限り無関係)。→ **Tier C11** (§5.4)

**Verdict: NOVEL — validates "wrap Studio as MCP" Astera decision.**

### 10.2 ADJACENT — interesting but not for us

#### 10.2.1 clawd-maf/cad-agent

- URL: https://github.com/clawd-maf/cad-agent (PolyForm Small Business 1.0.0, 36 commits, 18 stars)
- Docker container で **REST API and MCP の both** を expose、build123d で agent から CAD call
- 視覚 loop を server-side で closed: agent submits build123d code → PNG renders + JSON measurements + printability diagnostics back
- Multiple render modes (3D iso / 2D ortho / technical blueprints / multi-view composites)

**Astera angle**: Dual REST+MCP surface は Astera intent と同型だが Docker-first deployment は Astera の single-process Studio と mismatch。build123d DSL は ASDSL の parts+anchors+meso-primitives 抽象とは別 level。**Multi-render-mode catalog** だけは transferable (B12 6-view と統合)。

**Verdict: ADJACENT — pattern-level inspiration only.**

#### 10.2.2 amenti-labs/vibecraft

- URL: https://github.com/amenti-labs/vibecraft (MIT, ~19 commits)
- Closest sibling on Minecraft axis: Claude → MCP (SSE on :8765) → Fabric **client mod** (WebSocket) → in-world `/fill`, `/setblock`
- No DSL — agent emits raw vanilla commands or WorldEdit commands when detected
- Feedback: WebSocket success/failure + player position

**Astera angle**: MCP-as-bridge-to-Minecraft topology を validate するが、Astera は **server-authoritative Paper plugin** で client mod ではない (security model も異なる)。No DSL は ASDSL meso-primitive 哲学と真逆。"Reference for what NOT to do (no DSL)".

**Verdict: ADJACENT — wrong topology, no DSL.**

#### 10.2.3 yuniko-software/minecraft-mcp-server

- URL: https://github.com/yuniko-software/minecraft-mcp-server (Apache-2.0, v2.0.4 Feb 2026, 578 stars, 220 commits)
- Mineflayer-based MCP server で low-level Minecraft primitives (move/fly/place/dig/scan/inventory/chat) を Claude Desktop に expose
- vibecraft より高い maturity (star, commit count)
- No screenshot, feedback = chat + game-state

**Astera angle**: 同 client-side topology limitation + no visual feedback (vibecraft より弱い)。**Clean primitive-category split** (movement / flight / inventory / block / furnace / entity / chat / gamestate) は IA hygiene の良 reference — ASDSL primitive のグループ化に応用可能。

**Verdict: ADJACENT — category-IA reference only.**

#### 10.2.4 mindcraft-bots/mindcraft

- URL: https://github.com/mindcraft-bots/mindcraft (MIT, 5.2k stars, v0.1.4 March 2026, 2025 research paper)
- Most-starred Minecraft-LLM agent
- Bot-profile JSON files (`andy.json`) configure backend LLMs **per task type** (chat / coding / vision / embedding split) + behavioral prompts + example-based learning
- Multi-agent "action by action" reasoning
- Action space wraps Mineflayer

**Astera angle**: **Per-task-type LLM split** (chat vs coding vs vision vs embedding を profile 単位で別 model 指定) は Astera の Studio multi-agent (C3) を細則化する好 reference: planner=Sonnet / coder=Opus / vision-critic=Haiku → **Tier C12** (§5.4)。他 (Mineflayer client / no server-authoritative ops / no DSL) は misaligned.

**Verdict: ADJACENT — per-task LLM split idea only.**

### 10.3 DUPLICATE

#### 10.3.1 iancanderson/openscad-agent

- URL: https://github.com/iancanderson/openscad-agent (MIT, 2 commits)
- Three-skill Claude Code agent for OpenSCAD: `/openscad`, `/preview-scad`, `/export-stl`
- Versioned `.scad` files (001, 002, ...) + automatic PNG render for self-evaluation

**Verdict: DUPLICATE** — functional subset of cad-khana (adds diagnostics) と earthtojake/text-to-cad (adds format breadth + harness scaffolding)。Skip; the two above strictly dominate it.

### 10.4 Sweep notes

- **Voxel × DSL × LLM intersection は post-2024 で OSS 不在**。最も近かったのは MarkovJunior (pre-cutoff + non-LLM)。Astera ASDSL は fresh territory — novelty に良い、benchmark 不在は悪い (= C9 MC-Bench 自前構築の必要性を裏付け)。
- **License watch**: 2 個の pattern-rich finds (`cad-skill` PolyForm Noncommercial、`cad-agent` PolyForm Small Business) は copy-friendly でない。Copy patterns, not code。
- **除外した候補**: 約 6 件 rejected — pure model-weights repos、unmaintained 2024 wrappers、forks of §3 OSS。
- **Watch list**: arXiv 2512.10501 (Zero-shot 3D Map Generation via dual-agent actor/critic for PCG) — paper のみ、public repo 無、6 ヶ月後 sweep で再確認推奨。

### 10.5 Recommendations 優先度 (§10 全体)

1. **cad-khana の diagnostics-first pattern** が highest-information delta — ASDSL に declarative assertion verbs を追加、`{ok, status, hints, diagnostics}` 形に拡張 (B7)
2. **flowful-ai/cad-skill の 6-view render bundle** を即採用 (B12) — license restrictive、reimplement
3. **earthtojake/text-to-cad の `@handle[name]` syntax** を ASDSL に取り込み、`harness/` vs `skills/` directory split で monolith を整理 (B11 + A12 + B13 統合)
4. **openscad-studio パターン** を Astera Studio MCP wrap 判断に流用 (C11)
5. **Minecraft-MCP cluster (§10.2.2-§10.2.4)** は **skip** — all are client-side Mineflayer/Fabric harnesses with no DSL。Astera は server-authoritative Paper plugin、topology/security model も DSL 哲学も異なる。only **mindcraft の per-task LLM split (C12)** だけは抽象 idea として残す。
