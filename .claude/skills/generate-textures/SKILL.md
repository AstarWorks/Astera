---
user-invocable: true
allowed-tools: Read, Write, Bash, Edit, Glob, AskUserQuestion
description: Generate Minecraft 32×32 textures via AI image generation (Codex CLI), slice via subject-first detection, palette-quantize, lint, vision-review, and place under content/textures/. For weapons, blocks, items, mob sprites. Use when user says "generate texture", "make icons", "make sprites", or invokes /generate-textures.
---

# /generate-textures

Driver for the texture pipeline defined in [[adr/0018-ai-asset-creation-policy]].
Produces 32×32 PNG textures committed under `content/textures/<category>/`.

Quality QA is done by Claude (vision) by default; ask the user only when
Claude is uncertain.

## Trigger

```
/generate-textures <category> <prompt> [--count N] [--grid RxC]
                   [--bg-threshold N] [--margin N] [--keep-blobs N]
                   [--resume <job-id>]
```

Categories: `items`, `blocks`, `entities`, `animations` (must match
`content/textures/` subdirs).

Examples:

```
/generate-textures items swords                       # default --count 16, --grid 4x4
/generate-textures items potions --count 8 --grid 2x4
/generate-textures blocks "mossy cobblestone" --keep-blobs 0   # no bg removal
/generate-textures items swords --resume swords_1715900000_a3f # restart prior job
```

## Per-category recipe (parameter cheat sheet)

| category    | --bg-threshold | --keep-blobs | --margin | notes |
|-------------|----------------|--------------|----------|-------|
| `items` (swords/tools) | 30 | 1 | 2 | single subject per cell, hard black bg |
| `items` (potions, decorated) | 30 | 1 + `--keep-ratio 0.1` (manual) | 2 | rescues flame/glow fragments |
| `blocks`    | (skip bg remove) | 0 | 0 | seamless surface, no transparency, no centering |
| `entities`  | 30 | 4 | 1 | mob sprites often have multi-part bodies |
| `animations`| 30 | 1 | 2 | per-frame; produce sheet separately |

Where the SKILL is silent the script defaults (Step 2) take over.

## Workflow

### Step 0: Validate inputs, resume detection, Web UI probe

1. Parse `category` against `ls content/textures/`. Fail with a helpful
   error if invalid.
2. **Resume path**: if `--resume <job-id>` given, set
   `WORK=tools/texture-gen/.cache/work/<job-id>` and skip ahead per
   "Resume table" below (Step 1 if `grid.png` exists, Step 2 if
   `sliced/` exists, Step 3 if `final/` exists, Step 4 if `accepted/`
   exists). Bail loudly if the job dir is missing.
3. **Fresh path**: derive `JOB_ID=<category>_<NAME>_$(date +%s)` (NAME =
   sanitized prompt slug, max 24 chars). `WORK=tools/texture-gen/.cache/work/$JOB_ID`.
   `mkdir -p "$WORK"`. Print the job id so the user can resume if Claude
   crashes.
4. Default `--count` to 16, `--grid` to a near-square (4x4, 2x8, etc.).
5. Default `--mode` resolution order:
   - If `codex` is on PATH and `OPENAI_API_KEY` is set → automated (Step A).
   - Else → handoff (Step H): print prompt, user runs Codex/ChatGPT
     externally, supplies the file path.
6. **Web UI probe**: `lsof -i:3007 -sTCP:LISTEN >/dev/null 2>&1 && echo
   ui-up`. If ui-up, plan to surface the Step 2.5 review URL with
   emphasis. If down, plan to either propose `cd tools/studio/web && bun
   run dev` or fall through to Claude-only vision QA (default).
7. **Style anchor probe**: if `content/textures/<category>/.style.json`
   exists, load it. We'll inline its palette into Step A's prompt to
   keep batches visually consistent.
8. **Carry-forward decisions**: if any prior `tools/texture-gen/.cache/rejected/`
   directory exists for this category, read the most recent
   `decisions.md` and lift up to 3 recurring reject reasons into the
   prompt (so Step A actively avoids them).

### Step A: Automated (Codex CLI) path

1. Build prompt and save to `$WORK/prompt.txt`. Template (adapt subject):
   ```
   Use your image-generation tool to create a single PNG at $WORK/grid.png.

   Subject: A {ROWS}×{COLS} grid of {COUNT} distinct {SUBJECT} in pixel-art style.
   - List each cell with its variant name (top-left, top-right, ...) so the
     model knows what goes where.

   Style requirements:
   - Classic 32×32 pixel-art aesthetic look (we will downscale later)
   - Hard 1-pixel dark outlines around every shape
   - Limited palette: ≤16 distinct colors total across all cells
   - Consistent palette and outline width across all variants
   - No anti-aliasing on edges, no semi-transparent pixels
   - Bold silhouettes readable at thumbnail size

   {{IF .style.json present:}}
   - Restrict the palette to these RGB colors (from prior accepted batch):
     {LIST_OF_PALETTE_HEX}
   - Use outline color: {OUTLINE_HEX}

   {{IF prior reject reasons available:}}
   - Avoid these recurring failure modes from prior batches:
     {LIST_OF_REJECT_REASONS}

   Layout:
   - One square output image divided into {ROWS×COLS} equal cells
   - No gridlines, no text labels, no padding text
   - Background is pure black (we will key it out to transparency in post)
   - Each subject fits centered within its cell

   After generation, save the file to: $WORK/grid.png
   ```
2. Invoke the wrapper (it handles the bwrap-in-bwrap workaround
   automatically — see "Why the wrapper is needed" below):
   ```bash
   bash tools/texture-gen/scripts/codex_generate.sh "$WORK/grid.png" "$WORK/prompt.txt"
   ```
   On non-zero exit, surface `$WORK/codex.stderr.log` and fall back to
   `handoff` mode by asking the user to run the same prompt themselves.
3. Verify `$WORK/grid.png` exists (`file $WORK/grid.png` should report PNG)
   and inspect dimensions with a Python one-liner before slicing. Codex
   often returns square images larger than the literal target
   (e.g., 1024×1024 or 1254×1254); the downstream slicer handles
   arbitrary input sizes.

#### Why the wrapper is needed

Codex's image-generation tool writes the produced PNG to
`~/.codex/generated_images/<session_id>/ig_<hash>.png`, then runs a shell
command to copy it to the user-requested path. When Codex Claude runs
*inside* an outer bwrap sandbox (as in Claude Code's environment), the
inner bwrap call fails with `bwrap: Failed to make / slave: Permission
denied`, so the copy step never runs and the final file appears to be
missing. `codex_generate.sh` scrapes the session ID from stderr, locates
the orphaned `ig_*.png` in the session dir, and copies it to the
requested path. If running outside a nested sandbox, the first attempt
succeeds and the fallback path is never taken.

### Step H: Handoff path

1. Print the prompt template (same as Step A.1) to the user.
2. Ask via AskUserQuestion: "Saved the sheet to which path?" (single-line
   text). Resolve `~` and validate file exists.
3. Continue with Step 1's `$WORK/grid.png` symlinked to user-provided path.

### Step P: Procedural (PXC) path — **Phase 2, deferred**

The procedural recipe path (Pixel Composer .pxc files) is scaffolded in
`tools/texture-gen/scripts/render.sh` and `tools/texture-gen/recipes/`
but **not active in Phase 1**. The recipes directory is empty; opening
this path requires authoring per-category `.pxc` graphs in the PXC GUI
first. Treat any `--mode pxc` request as out-of-scope and tell the user
the path will be revisited in a later wave. The skill defaults to Step A
/ Step H only.

### Step 1: Slice via subject-first detection (default)

```bash
python3 tools/texture-gen/scripts/extract_subjects.py "$WORK/grid.png" \
    --out "$WORK/sliced" --prefix "{NAME}" \
    --rows {ROWS} --cols {COLS} \
    --margin {MARGIN} --dark-max {BG_THRESHOLD}
```

`extract_subjects.py` finds each subject via connected-component
detection rather than trusting math-grid alignment. It handles arbitrary
subject counts and off-grid positions (AI image gen rarely respects
exact pixel boundaries).

**Legacy fallback**: if extract_subjects.py returns 0 (no subjects
detected, e.g., a totally non-standard layout), retry with the simpler
math-grid slicer:

```bash
python3 tools/texture-gen/scripts/slice_grid.py "$WORK/grid.png" \
    --rows {ROWS} --cols {COLS} --out "$WORK/sliced" --prefix "{NAME}" --trim
```

Or `smart_slice.py` which uses frame-detect-with-math-snap (try when AI
drew visible gray cell borders).

### Step 2: Quantize + normalize

```bash
python3 tools/texture-gen/scripts/palette_quantize.py "$WORK/sliced/"*.png \
    --colors 32 --size 32,32 \
    --background remove \
    --dark-max {BG_THRESHOLD} \
    --margin {MARGIN} \
    --keep-blobs {KEEP_BLOBS} \
    --out "$WORK/final"
```

`extract_subjects.py` already does most of this work; running
`palette_quantize.py` on its output mostly enforces the final palette
size. Skip this step when `--mode pxc` (PXC exports are already final).

### Step 2.5: Post-quantize lint

```bash
python3 tools/texture-gen/scripts/lint_textures.py "$WORK/final/"*.png --quiet
```

Failed PNGs are auto-rejected (move to `$WORK/lint_failed/`) and never
reach the vision-QA step — saves vision tokens on obvious garbage.
Default checks: mode=RGBA, size=(32,32), ≤32 distinct opaque RGB,
not entirely transparent.

If you suspect LANCZOS bleed (semi-alpha pixels at silhouette edges)
add `--strict-alpha`. Note: many existing committed PNGs fail strict-alpha,
so apply selectively to new batches only.

### Step 2.6: Hand off to Web UI (optional)

If the Step 0 probe found the Web UI running on port 3007:

```
Open http://localhost:3007/textures?job=<JOB_ID>
for human review.
```

The UI reads `$WORK/final/` directly via the Astera Studio backend.
User can accept / reject / regenerate (via `regen_single.sh`) from the
browser. Skill can stop here and let the user drive, or proceed to
Step 3 for an automated first pass.

If the UI is down: print
`cd tools/studio/web && bun install && bun run dev` once, then fall
through to Step 3.

### Step 3: Vision QA loop (Claude) + decisions.md log

Create `$WORK/decisions.md` with a header:

```markdown
# Vision-QA decisions for {JOB_ID}

| tile | verdict | reason |
|------|---------|--------|
```

For each PNG in `$WORK/final/`:

1. Read it with the Read tool (multimodal).
2. Evaluate against:
   - **Subject match**: does it look like the requested {SUBJECT}?
   - **Pixel-perfectness**: hard edges, no AA, no semi-alpha bleed?
   - **Style consistency**: palette / outline / mass center matches siblings?
     (If `.style.json` was loaded, weight palette-match heavily.)
   - **Recognizability at 32×32**: silhouette readable at thumbnail scale?
3. Bucket into:
   - **accept** — move to `$WORK/accepted/`
   - **regenerate** — call `regen_single.sh` once with a refined prompt;
     re-evaluate; if still bad, demote to **reject**.
   - **reject** — move to `$WORK/rejected/`, do not commit.
4. Append one row to `$WORK/decisions.md`:
   ```
   | tile_03.png | REJECT | grey background residue, subject silhouette unreadable |
   | tile_07.png | ACCEPT |  |
   | tile_11.png | REGEN→ACCEPT | hilt was missing; regen added it |
   ```
   Reason text helps the next batch's Step 0 carry-forward avoid the
   same failure modes.
5. Only escalate to user via AskUserQuestion if **borderline cases**
   (e.g., 3+ tiles on the fence). Default is to trust Claude's judgment
   per the project policy.

### Step 4: Place + style anchor + cache archive

1. **Variant resolution** for each accepted PNG:
   - Target filename: `content/textures/{category}/{name}_NN.png`
     where NN is a 2-digit index. NAME normalization is the prompt slug
     used as JOB_ID's NAME component (so re-running the same prompt is
     idempotent across NN).
   - If the target path already exists, ask via AskUserQuestion:
     "{path} exists. Overwrite (v01 in place) / save as next variant
     (_v02, _v03…) / skip this tile?" Variants follow the
     `_v{VV}` convention documented in `content/textures/README.md`.
   - First-batch convention: omit `_vNN` suffix entirely
     (`broadsword.png`), matching how existing committed swords are named.
     Only add `_v02` onward when a deliberate variant lands.
2. **Style anchor refresh** (if at least 4 tiles accepted):
   ```bash
   python3 tools/texture-gen/scripts/extract_style.py \
       content/textures/{category}/{name}_*.png \
       --out content/textures/{category}/.style.json
   ```
   `.style.json` IS committed (gitignore does not cover it). Next batch
   of the same category reads it in Step 0.
3. **Cache archive**: copy `$WORK/rejected/*.png` and `$WORK/decisions.md`
   to `tools/texture-gen/.cache/rejected/{YYYY-MM-DD}/{JOB_ID}/` so the
   "why was that rejected" trail survives session end. The full $WORK
   dir is also persistent (under `.cache/work/`); user can `--resume
   <JOB_ID>` later if needed.
4. Print summary: accepted N, regenerated M, rejected K, JOB_ID, paths
   to .style.json + cache dir.
5. Suggest a commit message:
   `feat(textures): add {COUNT} {category}/{name} (AI-generated, reviewed)`.
6. Do NOT auto-commit; user reviews and commits.

### Resume table

| `--resume <JOB_ID>` finds | Skip to |
|---|---|
| no `$WORK` dir | bail with clear error |
| `grid.png` only | Step 1 (slice) |
| `grid.png` + `sliced/` | Step 2 (quantize) |
| `grid.png` + `sliced/` + `final/` | Step 2.5 (lint) |
| `grid.png` + `sliced/` + `final/` + partial `decisions.md` | Step 3 (resume from last logged tile) |
| `accepted/` already populated | Step 4 (placement) |

## Failure modes & fallbacks

- **Codex unavailable / auth failed**: fall back to `handoff` mode with
  clear instructions, do not abort.
- **All tiles rejected**: surface samples to user with `AskUserQuestion`,
  ask if prompt should be refined. Save the abandoned $WORK in
  `.cache/work/` so user can iterate via `--resume`.
- **`content/textures/{category}/` does not exist**: create it + log a
  warning (it should have been bootstrapped in the repo).
- **Lint fails ≥50% of tiles**: surface to user before vision QA — the
  pipeline parameters are likely wrong for this category.
- **Web UI port 3007 in use by something else**: skip Step 2.6 silently.

## Constraints (from ADR-0018)

- **Never** modify anything under `:plugin:**` from this skill.
- **Never** add the OpenAI/Anthropic API as a Gradle dependency.
- **Always** treat `.cache/` as transient (gitignored); the only
  persistent artifacts are PNGs + `.style.json` under
  `content/textures/`.

## See also

- `tools/texture-gen/README.md` — script-level docs (each script's flags
  and the bwrap workaround)
- `content/textures/README.md` — output conventions (`_vNN` variants,
  resolution, naming)
- [[adr/0018-ai-asset-creation-policy]] — policy that authorizes this
  pipeline
- [[adr/0010-postpone-generative-ai-to-phase-6]] — original deferral
  context
