# Astera Studio — Effects pane

Browser-side authoring UI for **animated 2D pixel-art effects** intended to
appear in-game as Minecraft holograms (Bukkit `ItemDisplay` /
`BillboardMode.CENTER`).

Pixel Composer / Blender-shader-node vocabulary, AI-edited, browser-only.
**Dev-only.** Per [[adr/0018-ai-asset-creation-policy]], nothing in this
directory may become a runtime or build dependency of Astera proper.

Open <http://localhost:3007/effects> after `bun run dev` (the route lives
inside the same studio process as `/textures` and `/models`).

## What it is

A node-DAG editor whose recipe is a JSON document (`*.fxgraph.json`). Each
node is a pure function `(inputs, params, frame) → canvas`. The renderer
walks the DAG once per frame and produces a vanilla MC-flipbook-compatible
sprite sheet at export time.

```
┌─────────────────────┬──────────────────────┬───────────────────┐
│ effect list + node  │ ノードグラフ (Vue Flow) │ Inspect / Chat     │
│ palette (drag-add)  │                       │  (schema-driven)   │
│                     │                       │                   │
│                     ├──────────────────────┤                   │
│                     │ プレビュー (2D / 3D)    │                   │
│                     │ + タイムライン          │                   │
└─────────────────────┴──────────────────────┴───────────────────┘
```

## Two preview modes

- **2D** — exact pixel-art canvas at ×8 zoom (`image-rendering: pixelated`).
  Shows what the actual texture looks like.
- **3D ホログラム** — Three.js mini scene with a face-camera quad, blue
  grid floor, and a wireframe player silhouette for scale. Mocks what an
  in-game `ItemDisplay` with `BillboardMode.CENTER` would look like.

Both modes share the same `OffscreenCanvas` source; one renderFrame() call
feeds both views.

## Node taxonomy (PXC-aligned)

Schema lives in `src/effects/schema.ts` and is served as JSON at
`GET /api/effects/schema` (Claude consumes this directly).

| Category | Nodes | Count |
|---|---|---|
| Generator | `shape.{ring,circle,polygon,line}`, `noise.{perlin,value}`, `image.import`, `ai.image`, `generate.scatter`, `generate.gradient_radial`, `particle.emitter`, **`particle.effector`** | 12 |
| Transform | `xform.{translate,rotate,scale,mirror,polar}` | 5 |
| Filter | `filter.{blur,bloom,dilate,palette}`, **`filter.outline`**, **`filter.trail`**, **`filter.dither`** | 7 |
| Composite | `composite.{over,add,screen,multiply}` | 4 |
| Animation | `anim.{time,rotate,scale_pulse,scale_ramp}`, **`anim.curve`**, **`anim.wiggler`**, **`anim.stagger`** | 7 |
| Values | **`values.number`**, **`values.vec2`** | 2 |
| Sink | `output` | 1 |
| **合計** | | **38** |

`filter.bloom`, `noise.perlin`, and `noise.value` are WebGL2 ports of Pixel
Composer's MIT-licensed `sh_bloom_pass` + `sh_blur_gaussian` + `sh_bloom_blend`
chain and `sh_perlin` shader. See
`src/effects/webgl/shaders/LICENSE-PIXEL-COMPOSER.txt` for attribution.

`particle.emitter` mirrors PXC's canonical Particle node — deterministic
seeded random per-particle lifecycle, **color-over-lifetime gradient built
into the node itself** (no separate gradient_map filter needed). All other
new nodes are pure Canvas2D / ImageData (no shader).

### スカラー / Vec2 / Effector エッジ

Edges are typed and color-coded:

| Kind | Color | Use |
|---|---|---|
| `canvas` | gray | image data (the default) |
| `number` | yellow | scalars from `values.number` / `anim.curve` / `anim.wiggler` |
| `vec2` | orange | 2D coords from `values.vec2` |
| `effector` | violet | particle force config from `particle.effector` |

**Scalar auto-merge**: when a `number` or `vec2` edge targets a port whose
name matches a downstream node's param key, the edge value overrides the
static slider value. e.g. `anim.curve → filter.bloom.intensity` makes the
bloom intensity follow the curve over the loop without any wrapper logic.

### State-bearing ノード

`filter.trail` is the first node that keeps state across frames. The
executor exposes a `ctx.state: Map` that nodes can key by `ctx.nodeId`.
Caller (PreviewPanel / CLI harness) owns the Map and reuses it across
consecutive frames; non-consecutive frame numbers (scrub, loop wrap) cause
the buffer to reset.

### Harness 機能

- **ノード検索** (NodePalette 上部): label / type id / description で
  filtering、Esc または × でクリア
- **💾 保存 (Save as preset)**: ツールバーから現在の fxgraph を
  `content/effects/<slug>.fxgraph.json` に保存。サイドバーに
  `[CONTENT]` として再表示。
- **Drag-stops エディタ**: gradient (色) と curve (数値) の両方が、
  ハンドルを横バー / SVG 上で直接ドラッグして編集可能。JSON テキストは
  `<details>` 折り畳みで保持 (Claude チャットからのアクセス用)
- **Undo / Redo**: ⌘Z / ⌘⇧Z で過去 30 編集まで遡れる
- **3D ホログラム** + 📐 ref helpers toggle
- **再生速度** ×0.25 / 0.5 / 1 / 2 / 4
- **編集オーバーレイ** (🔧 編集) — フルノードグラフ DnD エディタ
- **Claude チャット** (✨ Claude) — RFC 6902 JSON Patch over fxgraph
- **エクスポート** (⬇ エクスポート…) — 縦並び/横並び/フレーム別/モデル
  の組み合わせ + summon コマンド help

### Easing curves

Both `anim.scale_pulse` and `anim.scale_ramp` accept an `easing` param:

| Kind | Use case |
|---|---|
| `linear` / `ease_in` / `ease_out` / `ease_in_out` | Standard Penner easings |
| `back_out` | Overshoot then settle (UI pop) |
| `bounce_out` | Bounce back (impact aftermath) |
| `elastic_out` | Elastic spring (cartoon spawn) |
| `sin` (pulse only) | Smooth periodic back-and-forth |
| `square` (pulse only) | Step / strobe |

`anim.scale_ramp` is the one-shot form: scale from `from`→`to` over
`duration_frames` then hold / fade / loop per the `after` param.
`anim.scale_pulse` is the periodic form: round-trip between `min` and `max`
every `period_seconds`.

### 出力ファイル一覧 (エクスポート後)

| ファイル | 場所 | 用途 |
|---|---|---|
| `<slug>.fxgraph.json` | `content/effects/` | レシピ (再編集 / git diff 用) |
| `<slug>.png` | `content/textures/animations/` | 縦並びスプライトシート |
| `<slug>.mcmeta.json` | `content/textures/animations/` | Mojang flipbook 設定 |
| `<slug>_horiz.png` | `content/textures/animations/` | (任意) 横並びシート |
| `<slug>_NN.png` | `content/textures/animations/` | (任意) フレーム別 PNG |
| `<slug>.model.json` | `content/models/effects/` | item_display モデル |
| `<slug>.summon.md` | `content/effects/` | リソースパック配置 + 召喚コマンド help |
| `manifest.json` | `content/effects/` | 全エフェクトの索引 |

サイドバー下部の「保存先」ブロックから絶対パスを直接コピー可能。

### サンプルショーケース (12 個)

**Wave 1 — 基本 (Day 2):**
- `sparkle_ring`, `casting_glyph`, `hit_flash`, `energy_wave`, `noise_aura`
  — Vue Flow + executor + bloom の概念実証

**Wave 2 — PXC 品質 (Day 3):**
- `magic_circle.fxgraph.json` — 64×64, 48f。多重リング + polar スポーク +
  scatter dots + bloom (`xform.polar`, `generate.scatter`)
- `fireball_impact.fxgraph.json` — 32×32, 16f。`particle.emitter` 主役、
  色補間 (白→黄→橙→赤) + shockwave + bloom
- `swirl_aura.fxgraph.json` — 32×32, 24f。`particle.effector` (vortex) +
  `filter.trail` で渦巻く cyan オーラ

**Wave 3 — 新 4 ショーケース:**
- `lightning_arc.fxgraph.json` — 32×32, 12f。`anim.wiggler` がジグザグ
  `shape.line` を揺らす雷、`generate.scatter` (star) sparks + outline +
  dither で 16-bit RPG ライトニング
- `poison_aura.fxgraph.json` — 32×32, 24f。`noise.perlin` × ring mask +
  上昇 `particle.emitter` バブル + `filter.trail`、緑グロー
- `heal_pillar.fxgraph.json` — 32×32, 16f。連続発生する金粒 +
  `particle.effector` (wind) + bloom + dither、上昇するヒーリングの柱
- `ice_shatter.fxgraph.json` — 32×32, 16f。3 重 `shape.polygon` +
  `anim.scale_ramp` (ease_out, fade) + `filter.trail` で氷の破片爆裂

## Authoring flow

1. Open <http://localhost:3007/effects>.
2. Pick a sample from the left sidebar (or drag nodes from the palette to
   start fresh).
3. Edit params in the right Inspect pane (schema-driven sliders / color
   pickers / selects).
4. Watch the 2D + 3D preview update live.
5. Optional: switch to the **✨ Chat (Claude)** tab and describe the edit
   in Japanese ("もっと回転を速く", "外側に金色のグロー追加"). Claude
   returns a 1–2 sentence explanation + an RFC 6902 JSON Patch. Click
   ✓ 適用 to apply.
6. Click **⬇ エクスポート…** in the toolbar, pick formats, 保存.

## Export pipeline

The export dialog accepts any combination of:

| Format | Output path |
|---|---|
| 縦並びシート + .mcmeta | `content/textures/animations/<slug>.png` + `<slug>.mcmeta.json` |
| 横並びシート | `content/textures/animations/<slug>_horiz.png` |
| フレーム別 PNG | `content/textures/animations/<slug>_NN.png` |
| item_display モデル | `content/models/effects/<slug>.model.json` |

The recipe `*.fxgraph.json` is **always** committed to
`content/effects/<slug>.fxgraph.json`, and an entry is appended to
`content/effects/manifest.json`.

In-game consumption: drop the texture + mcmeta into a resource pack at
`assets/astera/textures/effects/<slug>.png` and reference the model via
`/summon item_display ~ ~ ~ {item:{id:"...",components:{"minecraft:item_model":"astera:effects/<slug>"}}}`.

## Claude chat (Day 4)

Server proxy at `POST /api/effects/chat` forwards to Anthropic API with
prompt caching:

- **System prompt** = full schema dump + canonical example + response
  contract. Sent with `cache_control: ephemeral` → cache-hit for ~5 minutes
  of a session.
- **User turn** = (graph state, cached) + (new instruction, fresh).
- **Output contract** = 1–2 sentence explanation + fenced ```json``` RFC
  6902 patch.
- Two tiers: Sonnet 4.6 default, Opus 4.7 toggle.
- Per-session $1 hard cap shown in the chat header.

Set `ANTHROPIC_API_KEY` in your shell before `bun run dev:api`:

```bash
export ANTHROPIC_API_KEY=sk-ant-…
```

## Architecture

```
Browser (Vite, :3007)
  ├─ EffectsView.vue          3-column layout + toolbar
  ├─ NodeGraph.vue            Vue Flow wrapper (drag-add, connect, delete)
  ├─ NodePalette.vue          schema-driven drag-source list
  ├─ FxNodeBox.vue            custom Vue Flow node renderer
  ├─ PreviewPanel.vue         2D canvas + 3D billboard + timeline
  ├─ NodeInspector.vue        schema-driven param editor
  ├─ ChatPanel.vue            Claude chat w/ patch apply/reject
  └─ ExportDialog.vue         format-checkbox modal

  src/effects/
    schema.ts                 SINGLE SOURCE OF TRUTH for nodes + params
    types.ts                  FxGraph / FxNode / FxEdge
    render.ts                 topological DAG executor
    nodes/*.ts                per-type pure compute functions
    webgl/runner.ts           WebGL2 FBO ping-pong (for bloom, future filters)
    webgl/shaders/bloom.ts    PXC-derived bloom chain (MIT attribution)
    billboard.ts              Three.js hologram preview helper
    export.ts                 client-side packer (sheet / frames / model)
    patch.ts                  RFC 6902 apply via fast-json-patch
        │
        │ /api/effects/*  (proxied to :3008)
        ▼
Hono (Bun, :3008)
  ├─ /api/effects                    list samples + content
  ├─ /api/effects/load/:scope/:slug  read fxgraph JSON
  ├─ /api/effects/save/:slug         PUT recipe to content/effects/
  ├─ /api/effects/:slug              DELETE recipe
  ├─ /api/effects/export             POST packed artifacts → disk + manifest
  ├─ /api/effects/schema             schema JSON for AI consumption
  └─ /api/effects/chat               Claude proxy w/ prompt cache
        │
        │ fs + Anthropic SDK
        ▼
content/effects/<slug>.fxgraph.json   (the recipe)
content/effects/manifest.json         (slug → record)
content/textures/animations/*.png     (rendered sprite sheets)
content/textures/animations/*.mcmeta.json
content/models/effects/*.model.json   (item_display billboard models)
```

## PXC vs Astera Studio 比較 (final, ノード数除く)

| 領域 | PXC | Astera | 状態 |
|---|---|---|---|
| ノード数 | ~300 | 38 | 我々は MC pixel-art FX に絞った subset |
| **スカラー / Vector エッジ** | あり | **あり** ✓ | Day 1 で実装 |
| **`anim.curve`** | あり | **あり** ✓ | Day 2 で multi-keyframe Bezier |
| **Wiggler / Stagger** | あり | **あり** ✓ | Day 2 |
| **State-bearing ノード** (trail) | あり | **あり** ✓ | Day 3 で executor 拡張 |
| **Particle.effector** (Attract/Vortex/Wind/Repel) | あり | **あり** ✓ | Day 3 |
| **filter.outline / dither / trail** | あり | **あり** ✓ | Day 3 / Wave 4 |
| **Drag-stops gradient + curve editor** | あり | **あり** ✓ | Wave 4 (subagent) |
| **ノード検索** | あり | **あり** ✓ | Wave 4 |
| **Preset 保存 UI** | あり | **あり** ✓ | Wave 4 |
| **Undo / Redo** | あり | **あり** ✓ | Day 1 |
| **CLI ヘッドレスレンダー** | (PXC 自体は GUI) | **あり** ✓ | `bun test/render-snapshot.ts`, @napi-rs/canvas |
| **Claude AI 編集** (RFC 6902 patch) | なし | **あり** ✓ | Astera 独自、Day 4 |
| **3D ホログラムプレビュー** (MC item_display sim) | なし | **あり** ✓ | Astera 独自 |
| **MC エクスポート** (sprite sheet + mcmeta + model.json + summon) | なし | **あり** ✓ | Astera 独自 |
| Group / Sub-graph (Ctrl+G) | あり | **なし** | 将来 wave |
| 3D / 音 / vector path | あり | なし | 範囲外 |

**結論**: 「PXC ハーネスの主要 axis」での gap はほぼ埋まった。MC エフェクト
オーサリング特化機能 (Claude / 3D ホログラム / MC エクスポート) で逆に
Astera が上回る部分も。

## 既知制限 (将来 wave)

- Chat は非ストリーミング (cancel ボタンなし、v2 polish)
- Group / Sub-graph (Ctrl+G) アーキテクチャ — preset 共有の moat
- 3D / vector path / オーディオ反応 — MC 用途範囲外、Phase 2 候補

## See also

- [[adr/0018-ai-asset-creation-policy]] — the policy this dir is built under
- [[adr/0017-adopt-datadriven-contents-pattern]] — slug + manifest convention
- `src/effects/webgl/shaders/LICENSE-PIXEL-COMPOSER.txt` — MIT attribution
  for the shader ports
- `../README.md` — Studio root README
- `../../texture-gen/README.md` — sibling static-texture pipeline
- `content/textures/README.md` — output-side conventions for animation sheets
