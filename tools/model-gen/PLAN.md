# Astera 3D BlockBench Studio — Implementation Plan (handoff)

> Self-contained brief for the agent / engineer implementing this tool.
> Read top-to-bottom. ADR-0018 in `docs/adr/` is the policy this lives
> under; nothing in this directory may become a runtime or build
> dependency of Astera proper.

## What we're building

A browser-based MC-compatible 3D model editor for Astera with **Claude
as the agentic CAD co-pilot**, in the spirit of adamdotnew's
"Opus 4.7 SOTA at agentic CAD" Onshape demo — but specialized for
Minecraft's cuboid-only convention so the surface area is dramatically
smaller and the LLM-native fit is far better.

Sister tool to `tools/texture-gen/web/` (already built); the user
should be able to run both side by side, generate textures in one,
build models that consume those textures in the other.

## Why this is tractable (the key insight)

- **BlockBench has no CLI** (issue #1764 open since Feb 2023; Electron
  GUI only). We do **NOT** integrate the BB binary.
- **`.bbmodel` and Minecraft `model.json` are pure JSON**, and both
  use **axis-aligned cuboids exclusively** (no triangles, curves, or
  meshes). This is a tiny, well-bounded format — perfect for LLM
  direct generation.
- **Three.js `BoxGeometry` is exactly the right renderer** — one cube
  per `elements[i]` in the JSON. ~600 KB minified, dev-tool acceptable.
- ⇒ Opus 4.7 can read/write the JSON directly; the user gets WYSIWYG
  in-browser via Three.js; no heavyweight 3D toolchain needed.

Compare to alternatives surveyed:
- TripoAI / Meshy / Genie: produce triangle meshes incompatible with
  MC's cuboid convention; conversion lossy.
- Onshape + LLM (the adam demo): great for mechanical CAD but
  parametric features wasted on axis-aligned cubes.
- BB GUI by hand: works, but slow; AI co-pilot 10× the throughput.
- **LLM-direct-JSON + Three.js preview**: cheapest path to MC-native
  models with AI assistance.

## Architecture

```
┌─────────────┬──────────────────────────┬────────────────┐
│ Chat panel  │ Three.js viewport         │ JSON editor    │
│ (Claude)    │ (BoxGeometry per cuboid)  │ (CodeMirror)   │
│             │                           │                │
│ user: ...   │   [3D model spinning]     │ {              │
│ claude: ... │  [orbit camera, axes]     │   "elements":  │
│   [diff]    │   [grid floor]            │   [...]        │
│ [Apply][↻]  │                           │ }              │
│ [input]     │                           │                │
└─────────────┴──────────────────────────┴────────────────┘
        ↓                                          ↓
Hono backend (:3010) — Claude API proxy + file I/O
        ↓
Output: content/models/<category>/<name>.bbmodel + .model.json
```

All three panels (chat, viewport, JSON) stay synchronized:
- Edit JSON → viewport re-renders within 200ms
- Drag a cube in viewport → JSON updates
- Click "Apply" on Claude's proposal → JSON + viewport both update

## Stack

- **Frontend**: Vite + Vue 3 + Three.js + CodeMirror 6
  (`@codemirror/lang-json`) + Bun. Matches texture-studio.
- **Backend**: Hono on Bun, ports 3009 (frontend) / 3010 (backend).
  Distinct from texture-studio (3007/3008) so both run side by side.
- **Claude API**: `@anthropic-ai/sdk` with prompt caching, model
  `claude-opus-4-7` (1M context for large models). Server-side; key
  from `ANTHROPIC_API_KEY` env.

## **Token economics** (the user's primary concern)

LLM cost is the only nontrivial recurring cost of this tool. Design
choices are made to minimize tokens while keeping the editing loop
responsive:

### 1. Cached system prompt (4-hour TTL)

A single `bbmodel-schema.ts` constant becomes the cached system prompt.
Approximately 5K tokens, containing:
- bbmodel JSON schema (annotated)
- 3 canonical example models (small sword, simple block, mob head)
- MC `model.json` schema (annotated)
- Hard constraints: axis-aligned cuboids, 22.5° rotations, ≤1.5625
  scale, etc.
- Response-format contract (see #4 below)

Sent ONCE per session (Anthropic API auto-caches on cache_control
breakpoint). Cache cost is ~10% of input cost on cache write, then 10%
of normal input cost on cache hits. Across a 30-message session, this
saves ~70% of input tokens vs sending the schema every turn.

### 2. JSON Patch responses, not full rewrites

Output format is **RFC 6902 JSON Patch** (or a structured "diff"
object). A typical edit ("add a 2×2 cube at y=4") is ~30 output tokens
instead of the 2-5K of rewriting the whole bbmodel.

```json
[
  {"op": "add", "path": "/elements/-",
   "value": {"name":"new_cube","from":[0,4,0],"to":[2,6,2]}}
]
```

Server applies the patch in JS; if it fails (invalid path), surfaces
error to chat for Claude to self-correct in next turn.

### 3. Differential context — don't resend the model every turn

Strategy: keep the bbmodel state on the server. Each chat turn sends:
- The user's new message
- A **diff-since-last** of the bbmodel (usually empty — user edited the
  JSON via Claude only)
- The current full bbmodel **as a cached** ephemeral cache entry that
  expires when the model state changes

For a 30-turn session on a 500-line bbmodel, this is ~95% saving vs
naïvely including the full model in every turn's input.

### 4. Two-tier model selection

- **Default: Claude Sonnet 4.6** for routine edits ("move this 1 unit",
  "duplicate that cube"). Cheaper, faster, plenty for mechanical edits.
- **Opus 4.7** opt-in for design decisions ("redesign this sword to
  look more elven", structural critiques). UI exposes a small toggle
  in the chat panel.

User has explicit control of model via the toggle, defaults to Sonnet.

### 5. Compact JSON in API payloads (not pretty-printed)

`JSON.stringify(model)` with no second/third args. Pretty-printing the
JSON in the editor is fine (user-facing), but in API calls strip all
whitespace. Saves 20-30% of input tokens on large models.

### 6. Limit thinking budget

Disable extended thinking for routine edits. Enable only when the user
flips an "深く考える" toggle (or model is Opus 4.7).

### 7. Cost budget per session

UI shows a running session cost counter ($0.00 → cumulative). Hard cap
at $1.00/session unless user explicitly raises. Cheap brake against
runaway loops.

### 8. Streaming responses

Stream the patch JSON token-by-token so user can cancel partway if the
direction is wrong. Saves the wasted output tokens on bad runs.

### Token budget per typical session (estimated)

| Phase | Input tokens | Output tokens | Cost (Sonnet 4.6) |
|---|---|---|---|
| Session bootstrap (cache write) | 5,000 | — | $0.0075 (cached) |
| Each user message (cache hit + diff) | ~300 | ~50 | $0.0009 |
| Heavy redesign request (opt Opus 4.7) | ~500 | ~300 | $0.012 |
| 30-turn session, mostly Sonnet | ~14,000 | ~1,500 | **~$0.04** |

For typical use, **under $0.05 per model designed**. Hard cap at $1
covers extreme outlier sessions. Far cheaper than Codex image gen calls
on the texture side.

## Repository layout (all NEW under `tools/model-gen/`)

```
tools/model-gen/
├── PLAN.md                          # this file
├── samples/                         # example .bbmodel for dev
│   ├── sword.bbmodel
│   ├── pickaxe.bbmodel
│   └── helmet.bbmodel
└── web/
    ├── package.json                 # vue + three + @types/three +
    │                                # @codemirror/state/view/language +
    │                                # @codemirror/lang-json + hono +
    │                                # @anthropic-ai/sdk + bun
    ├── tsconfig.json
    ├── vite.config.ts               # proxy /api to :3010
    ├── index.html
    ├── README.md
    ├── public/favicon.svg
    ├── src/
    │   ├── main.ts
    │   ├── App.vue                  # 3-pane layout
    │   ├── api.ts                   # typed fetch wrappers
    │   ├── types.ts                 # BBModel, Cuboid, Group, Outliner, ChatMessage
    │   ├── three/
    │   │   ├── ThreeViewport.vue    # mount Three.js, orbit, picking
    │   │   ├── bbmodel-to-three.ts  # JSON → Three.Group tree
    │   │   └── three-to-bbmodel.ts  # serialize back (drag-edit)
    │   └── components/
    │       ├── ChatPanel.vue        # message list + input + diff approval
    │       ├── JsonEditor.vue       # CodeMirror w/ JSON validation
    │       ├── ModelList.vue        # sidebar: samples/ + content/models/
    │       └── DiffPreview.vue      # render JSON Patch before apply
    └── server/
        ├── index.ts                 # Hono on :3010
        ├── paths.ts                 # mirror tools/texture-gen/web/server/paths.ts
        ├── bbmodel-schema.ts        # canonical schema doc + cached examples
        ├── manifest.ts              # parallels texture manifest
        └── routes/
            ├── models.ts            # GET/PUT/DELETE bbmodel files
            ├── chat.ts              # POST → Claude API with prompt cache
            └── export.ts            # bbmodel → MC model.json conversion
```

Output sink (NEW):
- `content/models/<category>/<name>.bbmodel` (committed model files)
- `content/models/<category>/<name>.model.json` (MC Java export)
- `content/models/manifest.json` (slug → model record)

## bbmodel format reference

Since BB has no formal schema, read these primary sources:

- `https://www.blockbench.net/wiki/docs/bbmodel` — overview, key fields
- `https://github.com/JannisX11/blockbench/blob/master/js/formats/bbmodel.js`
  — the canonical parser/serializer (ground truth)
- `https://minecraft.wiki/w/Tutorial:Models` — MC Java model.json target
  format

Core minimal `.bbmodel`:

```json
{
  "format_version": "4.10",
  "name": "longsword",
  "resolution": {"width": 16, "height": 16},
  "elements": [
    {
      "name": "blade",
      "from": [7, 0, 7],
      "to": [9, 12, 9],
      "rotation": {"angle": 22.5, "axis": "y", "origin": [8, 0, 8]},
      "uv": { "north": [0, 0, 2, 12] }
    }
  ],
  "outliner": [
    { "name": "blade_group", "children": ["uuid-of-blade"] }
  ],
  "textures": [
    { "name": "metal", "source": "data:image/png;base64,..." }
  ]
}
```

MC Java `model.json` target (for export):

```json
{
  "parent": "item/handheld",
  "textures": { "0": "items/longsword" },
  "elements": [
    { "from": [7,0,7], "to": [9,12,9],
      "rotation": {"origin":[8,0,8], "axis":"y", "angle":22.5},
      "faces": { "north": {"texture":"#0", "uv":[0,0,2,12]} }
    }
  ]
}
```

Conversion is straightforward: rename fields, drop bbmodel-only metadata
(uuid, outliner grouping), strip base64 textures to references.

## Claude as agentic CAD partner — system prompt

Cached system prompt template (paste into `bbmodel-schema.ts`):

```
You are a CAD designer for Minecraft 3D models. The user gives you a
description of a desired change. You read the current bbmodel JSON
state (provided in user message) and respond with:

1. A 1-2 sentence explanation of the change.
2. A JSON Patch (RFC 6902) array that, when applied, produces the
   change.

Constraints (Minecraft hardcoded):
- All elements are axis-aligned cuboids (from/to coordinates).
- Coordinates are in 1/16-block units, range 0-16 typically.
- Rotations only in 22.5° increments around a single axis.
- UV mapping in texture-coordinate space (0-16 for default 16x16).
- Stay within the per-element limit of 0..16 unless user explicitly
  requests a larger model.

If the user's request is ambiguous, ask ONE clarifying question.
Don't guess and then ask for confirmation; ask first.

Always output VALID JSON. Wrap your patch in a fenced ```json block.
```

Plus the cached canonical examples and schema doc (~5K tokens cached).

## API surface (Hono, port 3010)

| Method | Path | Action |
|---|---|---|
| GET | `/api/models` | list `content/models/**.bbmodel` + `samples/*.bbmodel` |
| GET | `/api/models/:path` | return bbmodel JSON |
| PUT | `/api/models/:path` | save bbmodel JSON to disk |
| DELETE | `/api/models/:path` | delete from disk |
| POST | `/api/chat` | `{model_state, message_history, user_input, model: "sonnet"|"opus"}` → Claude API with cache, returns `{explanation, patch, cost_usd}` |
| POST | `/api/export/:path` | bbmodel → MC model.json, write to disk, return result path |
| GET | `/api/manifest` | read `content/models/manifest.json` |
| GET | `/files/models/*` | static-serve bbmodel/model.json for the viewport |

## File workflow

- **Open**: load .bbmodel from samples/ or content/models/
- **Edit**: any of 3 panes (chat, viewport drag, JSON editor) updates
  the in-memory model; debounced auto-save to a draft file
- **Save**: write to content/models/<category>/<name>.bbmodel,
  manifest entry created/updated
- **Export**: bbmodel → MC model.json conversion, written next to .bbmodel
- **Slug**: `astera:models.<dotted-category>.<name>.<variant>` (parallels
  texture-studio's pattern)

## Three.js viewport — implementation notes

```ts
// bbmodel-to-three.ts
function bbmodelToThree(model: BBModel): Three.Group {
  const root = new Three.Group()
  for (const el of model.elements) {
    const w = el.to[0] - el.from[0]
    const h = el.to[1] - el.from[1]
    const d = el.to[2] - el.from[2]
    const geom = new Three.BoxGeometry(w, h, d)
    const mat = new Three.MeshStandardMaterial({ /* texture from el.uv + model.textures */ })
    const mesh = new Three.Mesh(geom, mat)
    mesh.position.set(
      el.from[0] + w/2,
      el.from[1] + h/2,
      el.from[2] + d/2,
    )
    if (el.rotation) {
      const axis = new Three.Vector3(
        el.rotation.axis === 'x' ? 1 : 0,
        el.rotation.axis === 'y' ? 1 : 0,
        el.rotation.axis === 'z' ? 1 : 0,
      )
      mesh.rotateOnAxis(axis, el.rotation.angle * Math.PI / 180)
      // also recenter to rotation.origin if not at mesh center
    }
    mesh.userData.elementId = el.uuid
    root.add(mesh)
  }
  return root
}
```

Selection: raycast on click, highlight selected mesh's outline, surface
the element's index/UUID in the JSON editor (jump to that block).

## UI layout (Japanese, mirrors texture-studio)

- All UI labels in 日本語 from day 1 (the user prefers this).
- Three-pane: チャット (~280px) | ビューポート (flex) | JSON エディタ (~360px)
- Chat panel shows past turns, cost meter ($0.00 of $1.00), model toggle
- Viewport: dark bg + grid floor + axes helpers + orbit controls
- JSON editor: CodeMirror w/ JSON folding, validate against bbmodel
  shape on edit, red squiggle on error

## Phase breakdown (estimated 3-5 days)

**Day 1 — Scaffolding + Three.js viewport**
- Bootstrap Vite + Vue + Three.js per the layout
- ThreeViewport.vue: scene, camera, OrbitControls, axes, grid floor
- bbmodel-to-three.ts (read-only first)
- Hardcoded sample bbmodel renders correctly

**Day 2 — Bidirectional sync + JSON editor**
- CodeMirror integration
- bbmodel → JSON editor + bbmodel ↔ Three.js
- Three.js → bbmodel (drag-edit) optional, can defer

**Day 3 — File management + Claude API**
- Backend CRUD on .bbmodel files
- Chat panel UI
- `/api/chat` with prompt caching, returns explanation + JSON Patch
- DiffPreview component, Apply button

**Day 4 — Export + polish + verification**
- bbmodel → MC model.json conversion (read MC wiki, compare to vanilla)
- Snapshot to PNG (`renderer.domElement.toDataURL`) for asset commit
- Playwright verification (mirrors texture-studio's approach)
- README + ADR-0018 boundary doc

**Day 5 (optional) — Three.js → bbmodel drag-edit, undo stack**

## Reused from texture-studio

- `tools/texture-gen/web/server/paths.ts` — template for `model-gen/web/server/paths.ts`
- Manifest pattern (`content/textures/manifest.json` → `content/models/manifest.json`)
- `.gitignore` patterns for `web/node_modules/` etc.
- Vite + Vue + Hono + Bun setup conventions
- Japanese localization style (no i18n framework, hardcoded strings)
- ADR-0018 boundary statements in README

## Verification checklist (definition of done for v1)

After `cd tools/model-gen/web && bun install && bun run dev`:

1. **Load sample**: ModelList sidebar shows `samples/sword.bbmodel`;
   click → viewport renders the cuboid sword, JSON editor shows raw
   JSON.
2. **Orbit camera**: drag in viewport → camera rotates around model.
3. **Edit JSON directly**: change a cuboid's `to` value → viewport
   updates within 200 ms.
4. **Chat with Claude**: "make the blade twice as wide" → Claude
   responds with explanation + JSON Patch. Click Apply → editor &
   viewport update atomically.
5. **Save**: click 保存 → `content/models/items/swords/sword_v01.bbmodel`
   written, manifest entry created.
6. **Export to MC**: click MC エクスポート → `content/models/items/swords/sword_v01.model.json`
   in MC Java format. Validate vs vanilla MC sample.
7. **Cost meter**: chat panel shows running session cost; under $0.10
   for the above test scenario with Sonnet 4.6.
8. **Boundary check**: `./gradlew check` does not enter
   `tools/model-gen/web/`, no JVM dep on Three.js or Claude SDK.

## Out of scope (v2+)

- Animations / keyframes (bbmodel supports them, but MVP is static)
- Multi-model editing (one model at a time in viewport)
- Texture editor (use texture-studio for that, link via UV mapping)
- Server-side puppeteer render (toDataURL is enough for v1)
- Mobile touch controls
- Undo/redo stack (rely on git for now)
- Real-time multi-user collaboration
- Auto-rigging for entity models
- Bedrock geometry format (`.geo.json`) — Java first
- Vision-based MC reverse-engineering ("here's a screenshot, build the
  model") — defer to vision-slice Phase 3 task

## Boundary reminders (ADR-0018)

- ❌ Do NOT add any script here to `:plugin:**` build dependencies.
- ❌ Do NOT make CI invoke anything here. CI runs `./gradlew check` only.
- ❌ Do NOT commit `ANTHROPIC_API_KEY` to git. `.env` is gitignored.
- ❌ Do NOT bundle Three.js or the Claude SDK into Astera's runtime build.
- ✅ DO commit the resulting `*.bbmodel` and `*.model.json` under
  `content/models/`.
- ✅ DO commit `samples/*.bbmodel` (small, helps dev onboarding).
- ✅ DO commit `manifest.json` (slug → model record).

## Coordination with user's parallel refactor

All work confined to `tools/model-gen/` + `content/models/`. Zero touch
on `plugin/**`, `docs/**`, existing `tools/texture-gen/`. Commit when
user signals their concurrent auto-stage is paused.

## How to start

```bash
cd /IdeaProjects/Astera/tools/model-gen
mkdir -p web/{src/{three,components},server/routes,public,samples} samples
cd web
bun init -y    # bootstrap, then edit package.json per the dep list above
bun add vue three @anthropic-ai/sdk hono
bun add -D @vitejs/plugin-vue vite typescript vue-tsc @types/three \
  @codemirror/state @codemirror/view @codemirror/language @codemirror/lang-json
# follow Day 1 plan; iterate to Day 4
```

When stuck on bbmodel format details, the ground truth is
`js/formats/bbmodel.js` in the BlockBench repo. The format has evolved
across versions; target `format_version: "4.10"` (current stable as of
2026-05).
