---
user-invocable: true
allowed-tools: Bash, Read, Write, Edit, WebSearch, WebFetch, AskUserQuestion, Glob
description: Build a Minecraft Java 3D model collaboratively via the Astera Shape DSL (ASDSL). The LLM speaks high-level design intent (parts dict + anchors + textures); the compiler emits MC voxel cuboids with rotations. Inspired by the Onshape × Opus 4.7 demo and T2BM. Use when user says "create a model", "make a 3D X", or invokes /build-model.
---

# /build-model

Drive **Astera Studio** to build a Minecraft 3D model. The user describes
intent ("amethyst sword with gold hilt"); you express it as a parts dict
that compiles to MC `model.json` with rotations + multi-layer textures.

## Why this works (Onshape × Opus pattern)

Wave 1/2 of this tool let the LLM emit voxel coordinates directly. Result:
"eggplant towers" with no rotations and uniform texture. Wave 3 follows
**three rules from successful LLM-CAD systems** (Jarvis Onshape MCP, KCL,
T2BM):

1. **LLM never emits raw coordinates.** Place parts by name + anchor:
   `place_part name=guard kind=cuboid size=[6,1.5,2] relative_to=blade align=base_center delta_y=-1.5`
2. **Truth envelope on every call.** Each tool returns
   `{ok, status, hints:[...], changes:{...}}`. If `ok:false`, hints tell
   you the fix (`"angle 30 invalid, try 22.5"`). Self-correct on next turn.
3. **Render-first protocol.** After any shape mutation, call
   `describe_model` (returns text summary + 4-view screenshots). Read the
   PNGs and the bbox text. **Both** are required — text catches counting
   errors, image catches orientation/topology.

## Trigger

```
/build-model <slug> --reference <path|url|search:query> [--template <file>]
```

Example:
```
/build-model items/swords/fireblade --reference search:"fantasy sword flame motif" --template longsword
```

## HARD RULES

These are cache-friendly invariants. Read once, never break:

1. **Units are 1/16 block.** Bare numbers, no suffixes. `length: 12` = 12 voxels.
2. **Coordinates are forbidden in your output.** No `from`, no `to`. Use
   `anchor:"origin"` for the first part, then `relative_to:<name>, align:<slot>`
   for everything else.
3. **Rotation angles ∈ {-45, -22.5, 0, 22.5, 45} only.** The compiler
   enforces this. If you want 30°, the envelope will say "try 22.5".
4. **Texture slots are domain-named, not numeric.** Use `blade_body`,
   `blade_edge`, `metal_warm`, `metal_cool`, `wood`, `gem`, `bone`. The
   compiler maps these to MC `#layer_N` references.
5. **Parts dict is your output, not a JSON file you write.** You call tools
   (`asdsl place`, `asdsl set-tex`, …) and the server accumulates them.
   Use `asdsl describe` to read state.
6. **Reference image required.** Either user-provided path/URL or WebSearch.
   Without it, your spec will hallucinate.
7. **Iterate ≥ 5 times.** Each iteration = (one tool call) + screenshot +
   read PNG + annotate `decisions.md` + decide next.
8. **No render-inspect = not done.** `verify:wave3` の syntax check は不十分
   (ForgeCAD "you are building blind unless you render", `docs/research/model-gen-llm-survey.md`
   §9.1.5)。`<ITER_DIR>/decisions.md` の各 Step block に `iter-screenshot` 後の
   visual finding (Step 5 bullet 6 の `### Evidence` sub-section) 記録が
   無ければ「done」と report してはならない。

## Prerequisites

```bash
curl -s http://localhost:3008/api/health | jq .ok                # → true
curl -s http://localhost:3008/api/textures/scopes | jq .vanilla  # → true
# Open http://localhost:3007/models in a real browser before screenshot calls.
```

All CLI commands run from `/IdeaProjects/Astera/tools/studio/web/`:

```bash
cd /IdeaProjects/Astera/tools/studio/web
bun run studio <verb> <args>
```

## Step 0 — Reference image

Pick a target image. Use WebSearch when no path is given:

```
WebSearch query="<user's subject> game item reference"
```

Pick a promising URL, WebFetch to verify it's an image, then pin it:

```bash
bun run studio reference url 'https://...image.png'
# OR
bun run studio reference path /absolute/path/to/ref.png
```

**Read the reference yourself with the Read tool** before placing parts.
You will visually compare every screenshot to this image.

## Step 1 — Init run dir + reset state

```bash
RUN_ID="$(date +%Y%m%d-%H%M%S)-$$"
ITER_DIR="/IdeaProjects/Astera/tools/studio/web/.iter/$RUN_ID"
mkdir -p "$ITER_DIR"
echo "# build-model $RUN_ID — <slug>" > "$ITER_DIR/decisions.md"
echo "Reference: <url or path>" >> "$ITER_DIR/decisions.md"
echo "" >> "$ITER_DIR/decisions.md"

bun run studio asdsl reset "<slug>"
```

Optionally start from an example template:

```bash
bun run studio asdsl load-file /IdeaProjects/Astera/tools/model-gen/asdsl-examples/longsword.asdsl.json
```

## Step 2 — Phase 0: Write `<run-dir>/spec.md` (semantic design spec)

Before **any** ASDSL tool call (`asdsl place|patch|set-tex|set-face-tex|set-var`),
write `<ITER_DIR>/spec.md` in **plain English** describing the design intent at
a *semantic* level. This file is the bridge between the reference image and
the concrete ASDSL breakdown you will write in Step 3.

**HARD constraint (skill rule, not in HARD RULES cache):** Until
`<ITER_DIR>/spec.md` exists, do not call:

- `bun run studio asdsl place …`
- `bun run studio asdsl patch …`
- `bun run studio asdsl set-tex …`
- `bun run studio asdsl set-face-tex …`
- `bun run studio asdsl set-var …`

Texture-listing reads (`list-textures`, `asdsl describe`) and
`iter-screenshot` are fine — they don't mutate ASDSL state.

### spec.md template

Write the file with the `Write` tool at `<ITER_DIR>/spec.md`:

```markdown
# spec — <slug>

## Silhouette
<1-2 sentence prose describing the overall shape. e.g., "A long straight
two-handed sword with a flame motif running along the blade. The hilt is
wrapped wood with a faceted gem pommel.">

## Parts (role only — no kinds, no exact sizes)
- blade: the main cutting surface, runs vertically
- guard: horizontal cross-piece between blade and grip
- grip: cylindrical handle for both hands
- pommel: rounded counterweight at the base

## Relationships (上下 / 左右 / 接続)
- blade sits on top, vertical axis
- guard sits directly below the blade base, perpendicular
- grip sits directly below the guard, vertical
- pommel sits at the bottom of the grip
- (left/right symmetry: blade and guard mirror across the vertical axis)

## Relative sizes (no voxel counts)
- blade ≈ 4× the grip length (dominant element)
- guard ≈ 2× the grip width, thin in depth
- grip length ≈ 3× pommel diameter
- pommel = smallest, roughly cubic envelope

## Texture semantics (material intent, not MC block IDs)
- blade: glossy hardened metal with a flame motif on the flat faces
- guard: warm metal (gold/brass), polished
- grip: dark wrapped wood
- pommel: faceted purple gem

## Uncertainties to resolve via screenshots
- whether the blade needs 22.5° curvature
- whether the pommel should be sphere or chamfered_cuboid
```

Keep the spec strictly **semantic**. Forbidden inside `spec.md`:

- compiler `kind` names (`tapered_prism`, `chamfered_cuboid`, …)
- exact voxel numbers (`length: 12`, `size: [6, 1.5, 2]`)
- MC block IDs (`minecraft:block/amethyst_block`)
- anchor slot names (`base_center`, `align`, `relative_to`)
- rotation angles

Those concrete choices belong to Step 3.

**Why this exists**: `docs/research/model-gen-llm-survey.md` §3.4 — the
Onshape MCP × Claude Opus 4.7 study showed plain-English-spec-first beats
autonomous 1-shot decomposition (1.00 vs 0.53). The semantic layer keeps the
concrete translation in Step 3 grounded in the reference image.

## Step 3 — Phase 1: VISION-DECOMPOSE (translate `spec.md` to ASDSL-concrete breakdown)

First read `<ITER_DIR>/spec.md` back. Then translate each section to the
concrete ASDSL terms below — picking `kind`, exact sizes (in 1/16 block
units), and candidate MC block IDs — and write a structured spec to
`decisions.md`:

```markdown
## Vision decompose

OVERVIEW: One sentence describing the silhouette.
ENVELOPE: Total height/width/depth in voxel units (1/16 block each).
PARTS:
  - blade: tapered_prism, length ~12, octagonal cross-section, pointed tip
  - guard: chamfered_cuboid, wide (6x1.5x2), gold metal
  - grip: cylinder, length ~3, wood-wrapped
  - pommel: sphere, diameter ~2, amethyst gem
RELATIONSHIPS:
  - blade anchor=origin (base sits at MC [8,0,8])
  - guard relative_to=blade align=base_center delta_y=-1.5
  - grip relative_to=guard align=base_center delta_y=-3
  - pommel relative_to=grip align=base_center delta_y=-2
TEXTURES:
  - blade_body: amethyst_block (purple gem flat)
  - blade_edge: diamond_block (light edge highlight)
  - metal_warm: gold_block
  - wood: dark_oak_planks
  - gem: amethyst_cluster
UNCERTAINTIES:
  - blade may need 22.5° curve for a sabre look
  - pommel might be too small at d=2
```

This is YOUR spec, not the user's. Write it before any tool call.

## Step 4 — Place textures first (so face_overrides have slots)

```bash
bun run studio asdsl set-tex blade_body  minecraft:block/amethyst_block
bun run studio asdsl set-tex blade_edge  minecraft:block/diamond_block
bun run studio asdsl set-tex metal_warm  minecraft:block/gold_block
bun run studio asdsl set-tex wood        minecraft:block/dark_oak_planks
bun run studio asdsl set-tex gem         minecraft:block/amethyst_cluster
```

Browse what's available with:

```bash
bun run studio list-textures vanilla 'block/amethyst*'
```

## Step 5 — Place parts (iteration loop, 5+ times)

### Iteration phases — global-first, then detail (A7)

Split the iteration loop into two phases (PartCrafter §3.9, hierarchical attention applied to iteration order):

**Phase 1a — Envelope first** (~iterations 1-3):
- Place 3-7 main parts with round-number sizes
- Rough placement via anchors (`base_center`, `top_center`, etc.)
- Get the silhouette right before any detail
- Done when: `bun run studio asdsl describe` shows all main parts AND iter-screenshot silhouette matches `spec.md`'s "Silhouette" section

**Phase 1b — Part internals** (~iterations 4-N):
- Sub-parts, exact size tuning, face-specific textures
- Only enter after Phase 1a's done criterion is met

Tag the current phase in `decisions.md` (`Phase 1a` / `Phase 1b` label on each Step block).

### Repair order — react to critic findings (A6)

When `critic-report.md` (Step 5 bullet 5 below) surfaces a red-flag, fix in this order (T2BM repair module, `docs/research/model-gen-llm-survey.md` §3.2):

1. **Geometry** first: position, size, collisions, missing parts
2. **Semantic** next: part role / alignment / orientation that violates `spec.md`
3. **Aesthetic** last: textures, color slots, face overrides

Do not jump to aesthetic before geometry — visual issues often stem from geometry, and fixing texture first wastes iterations.

### Iteration steps

Each iteration (7 steps; A2 + A10 + A13 integrated):
0. **Re-read** `<ITER_DIR>/spec.md` if uncertain about design intent before deciding the next move.
1. **Think out loud** (A13, Onshape MCP §9.2.5): emit 1-3 sentences saying WHY this call + WHAT you expect to see after compile + iter-screenshot. Plain assistant output, not a tool call.
2. **ONE tool call** (`place` / `move` / `remove` / `set-var` / `set-tex` / `set-face-tex`).
3. **Screenshot**: `bun run studio iter-screenshot "$RUN_ID" step-NN-<label>`.
4. **Read** the 3 PNGs at `.iter/$RUN_ID/step-NN-<label>-{iso,item_gui,front}.png`.
5. **Self-critic** (A2, CADCodeVerify §3.6): append a `## Step NN` block to `<ITER_DIR>/critic-report.md` answering 5 validation questions:
   - Symmetry OK (where the reference is symmetric)?
   - Anchors all connected (no floating parts)?
   - All main parts from `spec.md` present?
   - Proportions plausible vs `spec.md`'s "Relative sizes" section?
   - Reference image silhouette match (compare iso PNG to the pinned reference)?
   - If any red-flag, the next iteration's bullet 1 (think-out-loud) MUST address it — log as `Repair: <flag>` per the §"Repair order" above.
6. **Annotate** `decisions.md` with a `## Step NN — <label>` block including a mandatory `### Evidence` sub-section (A10, ForgeCAD §9.1.6):
   - **Command**: the exact `bun run studio …` invocation
   - **Observed defects**: cite the specific iter-screenshot file paths
   - **Intent decision**: intentional / unintentional
   - **Expected next action**: 1 sentence

Example first iteration:

```bash
bun run studio asdsl place blade '{
  "kind": "tapered_prism",
  "axis": "y",
  "base_size": [2, 1],
  "tip_size": [0.5, 0.3],
  "length": 12,
  "segments": 8,
  "tip_style": "pointed_rot",
  "cross_section": "octagon"
}'
bun run studio iter-screenshot "$RUN_ID" step-01-blade
# Read the 3 PNGs, then append to decisions.md
```

Available `kind` values (compiler primitives, no need to think in cuboids):

| kind | use for | key params |
|---|---|---|
| `cuboid` | flat blocks, simple parts | `size:[w,h,d]` |
| `tapered_prism` | sword blade, axe head | `axis, base_size, tip_size, length, segments, tip_style, cross_section` |
| `chamfered_cuboid` | guard with rounded edges | `size, chamfer, axis` |
| `cylinder` | grip, shaft, hilt | `axis, length, diameter, roundness` |
| `sphere` | pommel, orb, eye | `diameter, layers` |
| `ring` | hilt wrap, belt | `axis, outer, inner, height, resolution` |

Anchor slots for `align`:
- `origin` / `bottom_center` / `base_center` — point at the bottom-center of target
- `center` — geometric center
- `top_center` / `tip_center` — top
- `front_center` / `back_center` / `left_center` / `right_center` — sides

Use `delta_x/y/z` for fine adjustment after the anchor snap.

### Cross-iteration handles (`@cad[name]`)

`relative_to` accepts either a bare part name (`"blade"`) or the explicit
handle form (`"@cad[blade]"`). Both resolve identically; the handle form is
self-documenting and signals **"I'm referencing an existing part, not
creating a new one"** (ADR-0062 B11). Use the handle form when the LLM emit
context is ambiguous — e.g., when you're placing a new part that anchors
onto something placed many iterations earlier.

```bash
bun run studio asdsl place guard '{
  "kind": "cuboid",
  "size": [6, 1, 3],
  "relative_to": "@cad[blade]",
  "align": "base_center"
}'
```

### Limb-level inspection (`crop` / `crop-model-view`, ADR-0062 B9)

`iter-screenshot` returns full-frame PNGs of the whole model. When you suspect
a defect in a single part (the guard doesn't seat against the blade, the
pommel is misaligned), zoom in **without re-rendering**:

```bash
# 1. Take a screenshot (also written to disk; the response contains image_id)
bun run studio screenshot iso
# → prints { "rendered_view": { "view_id": "iso", "image_id": "img_<sha256>", ... } }

# 2a. Crop to a part's projected bbox (auto-resolves part bbox + camera).
bun run studio crop-model-view iso guard
# → smaller PNG cropped tightly around the guard, with 4-pixel padding

# 2b. Or crop to an explicit pixel bbox (when you can read pixel coords).
bun run studio crop "img_<sha256>" 120,80,260,200
```

`iter-screenshot` also surfaces each frame's `image_id` in its JSON output, so
you can crop into the per-step cached PNG without re-screenshotting:

```bash
bun run studio iter-screenshot "$RUN_ID" step-04-guard iso,front
# JSON output now includes results[].image_id alongside the .png path
bun run studio crop-model-view iso guard
```

Cache lifecycle: the server caches up to 100 PNGs (LRU, in-process). The
cache is flushed automatically on `asdsl reset`, so don't `crop` across a
reset boundary — re-screenshot first.

### Declarative assertions (optional)

Any part may carry `assertions: [...]` (object form, one entry per
constraint). They are evaluated **after compile** and surface as
`diagnostics` in the envelope. A failed assertion degrades status to
`WARNING` (ok=true; the model still compiles), so the agent can decide
whether to repair or proceed.

Four kinds (ADR-0062 B7):

```jsonc
{
  "assertions": [
    // surface-to-surface alignment within 0.5 voxel
    { "kind": "aligned_with", "a": "blade.base_center", "b": "guard.top_center" },
    // no two cuboids of this part overlap by volume
    { "kind": "no_self_intersection" },
    // world bbox fits within an axis-aligned envelope (voxel units)
    { "kind": "fits_within_voxel_envelope", "width": 16, "height": 32, "depth": 16 },
    // minimum separation (Chebyshev) between two parts
    { "kind": "clearance_min", "from": "blade", "to": "hilt", "voxels": 1 }
  ]
}
```

Read `envelope.diagnostics[]` after each mutation. If any entry has
`passed: false`, decide whether the detail (e.g., `"clearance 0.50 <
required 1"`) reflects intent or needs a `patch`.

### Recovering from envelope errors

Every tool returns `{ok, status, hints:[...]}`. On `ok:false`:

```json
{
  "ok": false,
  "error_code": "PLACE_PART_INVALID",
  "hints": [{
    "code": "UNKNOWN_PART",
    "text": "Part 'blader' was referenced but does not exist. Known parts: blade, guard",
    "suggestion": {"available": ["blade", "guard"], "did_you_mean": "blade"}
  }]
}
```

Read `hints[].suggestion.did_you_mean` or `hints[].suggestion.angle` (for
rotation enum violations) and re-call with the corrected value. **Do not
proceed past an `ERROR` envelope** — fix it first.

## Step 6 — Texture per face if needed

By default, the compiler routes faces based on the part's `kind`:
- `tapered_prism`: flat faces (north/south) → `blade_body`, edges → `blade_edge`
- `chamfered_cuboid`: all faces → `metal_warm`
- `cylinder`: all faces → `wood`
- `sphere`: all faces → `gem`

Override per-face when you want variety:

```bash
bun run studio asdsl set-face-tex pommel up gem
bun run studio asdsl set-face-tex pommel down metal_warm
```

## Step 7 — Save + export + verify

```bash
bun run studio iter-screenshot "$RUN_ID" step-FINAL iso,item_gui,front,top,side
bun run studio save items/swords/<name>
bun run studio export-bb items/swords/<name>
bun run verify:wave3 items/swords/<name>
```

`verify:wave3` checks 12 items including rotation usage, texture diversity,
and iteration discipline. Don't ship without 12/12.

## Step 8 — Final report

Build a ForgeCAD-style timestamped GIF of the iteration run (Tier C10,
manual-inspection archive — `docs/research/model-gen-llm-survey.md` §9.1.7):

```bash
bun run studio benchmark:gif "$RUN_ID" --slug items/swords/<name> --model claude-opus-4-7
```

```
✅ /build-model <slug> complete
   - parts: blade (tapered_prism, 15 cuboids), guard (chamfered_cuboid, 2),
     grip (cylinder, 2), pommel (sphere, 33) — 52 MC cuboids
   - textures: 5 slots (blade_body=amethyst, blade_edge=diamond, …)
   - rotation usage: 9/52 = 17%
   - iterations: 7 (.iter/<run-id>/decisions.md)
   - spec: .iter/<run-id>/spec.md (semantic design spec, Phase 0)
   - DoD: 12/12 pass
   - content/models/items/swords/<name>.{model.json,bbmodel}
   - benchmark gif: <path to the GIF you just generated>
```

## Failure mode reference

| Error code | Likely cause | Fix |
|---|---|---|
| `UNKNOWN_PART` | Typo in `relative_to` | Use `suggestion.did_you_mean` |
| `ROTATION_OUT_OF_ENUM` | Asked for non-MC angle | Use `suggestion.angle` |
| `DEPENDENCY_CYCLE` | Two parts reference each other | One must anchor=origin |
| `UNKNOWN_VARIABLE` | Used `@foo` without `set-var foo` | Set the variable first |
| `screenshot 503` | Browser not at /models | Tell user to open it. STOP. |

## Boundaries (ADR-0018)

- ✅ Commit `*.model.json`, `*.bbmodel`, `*.asdsl.json`, `manifest.json` under `content/models/`
- ❌ NEVER commit `.iter/`, `.vanilla-cache/`
- ❌ NEVER add `tools/studio/` to `:plugin:**` build deps
