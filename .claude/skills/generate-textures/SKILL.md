---
user-invocable: true
allowed-tools: Read, Write, Bash, Edit, Glob, AskUserQuestion
description: Generate Minecraft 32×32 textures via AI image generation (Codex CLI) or PXC procedural, slice grid sheets, palette-quantize, vision-review, and place under content/textures/. For weapons, blocks, items, mob sprites. Use when user says "generate texture", "make icons", "make sprites", or invokes /generate-textures.
---

# /generate-textures

Driver for the texture pipeline defined in [[adr/0018-ai-asset-creation-policy]].
Produces 32×32 PNG textures committed under `content/textures/<category>/`.

Quality QA is done by Claude (vision) by default; ask the user only when Claude is uncertain.

## Trigger

```
/generate-textures <category> <prompt> [--count N] [--grid RxC] [--mode auto|handoff]
```

Examples:

```
/generate-textures items swords           # default --count 16, --grid 4x4
/generate-textures items potions --count 8 --grid 2x4
/generate-textures blocks "mossy cobblestone" --mode pxc
```

Categories: `items`, `blocks`, `entities`, `animations` (must match
`content/textures/` subdirs).

## Workflow

### Step 0: Validate inputs and detect mode

1. Parse `category` against `ls content/textures/`. Fail with helpful error if invalid.
2. Default `--count` to 16 if absent, `--grid` to a near-square (4x4, 2x8, etc.).
3. Default `--mode` resolution order:
   - If user passed `--mode pxc` → procedural path (Step P).
   - Else if `codex` is on PATH and `OPENAI_API_KEY` is set → `auto` (Step A).
   - Else → `handoff` (Step H), user runs Codex/ChatGPT externally and drops the file.

### Step A: Automated (Codex CLI) path

1. Create working dir: `WORK=$(mktemp -d -t texgen.XXXX)`. Note path.
2. Build prompt and save to `$WORK/prompt.txt`. Template (adapt subject):
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

   Layout:
   - One square output image divided into {ROWS×COLS} equal cells
   - No gridlines, no text labels, no padding text
   - Background is pure black (we will key it out to transparency in post)
   - Each subject fits centered within its cell

   After generation, save the file to: $WORK/grid.png
   ```
3. Invoke the wrapper (it handles the bwrap-in-bwrap workaround
   automatically — see below):
   ```bash
   bash tools/texture-gen/scripts/codex_generate.sh "$WORK/grid.png" "$WORK/prompt.txt"
   ```
   On non-zero exit, surface `$WORK/codex.stderr.log` and fall back to
   `handoff` mode by asking the user to run the same prompt themselves.
4. Verify `$WORK/grid.png` exists (`file $WORK/grid.png` should report PNG)
   and inspect dimensions with a Python one-liner before slicing. Codex
   often returns square images larger than the literal target
   (e.g., 1024×1024 or 1254×1254); the downstream `palette_quantize.py`
   handles arbitrary input sizes via LANCZOS downscale.

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

1. Print the prompt template (same as Step A.2) to the user.
2. Ask via AskUserQuestion: "Saved the sheet to which path?" (single-line
   text). Resolve `~` and validate file exists.
3. Continue with Step 1's `$WORK/grid.png` symlinked to user-provided path.

### Step P: Procedural (PXC) path

1. Check `bash tools/texture-gen/scripts/render.sh --check`. If PXC_BIN
   missing, instruct user to set it (link to `tools/texture-gen/README.md`).
2. Either:
   - **Existing recipe**: locate `tools/texture-gen/recipes/{category}/{name}.pxc`
   - **New recipe**: ask user to author one in PXC GUI and save under the
     above path. Skill does not currently auto-author `.pxc` graphs (Phase 2).
3. Render: `bash tools/texture-gen/scripts/render.sh <recipe>.pxc --out "$WORK"`.
4. Locate produced PNG(s) in `$WORK`. Skip slicing (PXC exports per-asset).

### Step 1: Slice (auto/handoff paths only)

```bash
python3 tools/texture-gen/scripts/slice_grid.py "$WORK/grid.png" \
    --rows {ROWS} --cols {COLS} --out "$WORK/sliced" --prefix "{NAME}" --trim
```

### Step 2: Quantize + normalize

```bash
python3 tools/texture-gen/scripts/palette_quantize.py "$WORK/sliced/"*.png \
    --colors 32 --size 32,32 --background remove --out "$WORK/final"
```

### Step 3: Vision QA loop (Claude)

For each PNG in `$WORK/final/`:

1. Read it with the Read tool (multimodal).
2. Evaluate against:
   - **Subject match**: does it look like the requested {SUBJECT}?
   - **Pixel-perfectness**: hard edges, no AA, no semi-alpha bleed?
   - **Style consistency**: palette / outline / mass center matches siblings?
   - **Recognizability at 32×32**: silhouette readable at thumbnail scale?
3. Bucket into:
   - **accept** — commit candidate
   - **regenerate** — same prompt, single tile retry (max 1 retry per tile)
   - **reject** — drop, do not commit
4. Only escalate to user via AskUserQuestion if **borderline cases** (e.g.,
   3+ tiles on the fence). Default is to trust Claude's judgment per the
   project policy.

### Step 4: Place + manifest

1. For accepted tiles, copy to `content/textures/{category}/{name}_NN.png`
   where `NN` is a 2-digit index. Skip filenames that already exist (ask
   user to confirm overwrite).
2. Print summary: accepted N, regenerated M, rejected K, working dir
   preserved at `$WORK` for inspection.
3. Suggest a commit message: `feat(textures): add {COUNT} {category}/{name} (AI-generated, reviewed)`.
4. Do NOT auto-commit; user reviews and commits.

## Failure modes & fallbacks

- **Codex unavailable / auth failed**: fall back to `handoff` mode with
  clear instructions, do not abort.
- **All tiles rejected**: surface samples to user with `AskUserQuestion`,
  ask if prompt should be refined.
- **`content/textures/{category}/` does not exist**: create it + log a
  warning (it should have been bootstrapped in the repo).
- **PXC headless segfault / no output**: log PXC stderr, suggest user
  re-render in PXC GUI to verify project file works.

## Constraints (from ADR-0018)

- **Never** modify anything under `:plugin:**` from this skill.
- **Never** add the OpenAI/Anthropic API as a Gradle dependency.
- **Always** treat the working dir as transient; the only persistent
  artifacts are PNGs under `content/textures/` and `.pxc` files under
  `tools/texture-gen/recipes/`.

## See also

- `tools/texture-gen/README.md` — script-level docs
- `content/textures/README.md` — output conventions
- [[adr/0018-ai-asset-creation-policy]] — policy
- [[adr/0010-postpone-generative-ai-to-phase-6]] — original deferral
