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
2. Build prompt template:
   ```
   Generate a single PNG image: a {ROWS}×{COLS} grid of {COUNT} distinct
   32×32 pixel-art {SUBJECT} on a transparent background. Each cell is
   exactly 32×32 pixels with no padding, so the full sheet is {COLS*32}×{ROWS*32}.
   Style: consistent palette across all cells, ≤32 colors total, hard
   1-pixel dark outline, no anti-aliasing, no semi-transparent edges.
   Save as {WORK}/grid.png. Confirm by listing the file size when done.
   ```
3. Invoke:
   ```bash
   codex exec --sandbox workspace-write --cd "$WORK" --skip-git-repo-check \
       --output-last-message "$WORK/codex.log" "$PROMPT"
   ```
   If exit non-zero, surface stderr and fall back to `handoff` mode with the
   user being asked to run the same prompt themselves.
4. Verify `$WORK/grid.png` exists and dimensions are `(COLS*32)×(ROWS*32)`
   using Python one-liner. If not, ask Codex to regenerate (max 2 retries).

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
