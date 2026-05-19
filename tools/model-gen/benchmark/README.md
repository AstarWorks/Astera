# `tools/model-gen/benchmark/` — build-model GIF archive (Tier C10)

ForgeCAD-style **timestamped per-model GIF archive** of past build-model
iteration runs. Lightweight, manual-inspection only. See
`docs/research/model-gen-llm-survey.md` §9.1.7 (deep-dive) and §5.4 entry
**C10** (roadmap row).

## Purpose

Each GIF is a chronological flipbook of one `bun run /build-model <slug>`
session: every iteration step PNG that the LLM produced via `iter-screenshot`,
stitched into a single animated GIF at 1 fps. It exists so a human can:

- compare how the same `<slug>` looked across different models (e.g.
  `claude-opus-4-7` vs `claude-sonnet-4-7`) at different points in time, and
- watch the iteration progression of a single run as one artifact, without
  scrubbing through `step-NN-*.png` files.

That's it. There is no scoring, no rubric, no pass/fail.

## Disclaimer (ForgeCAD-quoted, Astera context)

> These GIFs are a chronological archive of past build-model runs. They are
> not a regression rig, are not approved-model recommendations, and are not
> graded on any automated metric. Use them as visual reference points for
> human judgment, not as scores.

## Naming convention

```
<slug-sanitized>-<model>-<YYYY-MM-DD-HH-MM-SS>-v<N>.gif
```

- `<slug-sanitized>` — lowercased, non-alphanumeric runs collapsed to `-`.
  Example: `items/swords/iron-sword` → `items-swords-iron-sword`.
- `<model>` — runtime / model id the LLM was on (e.g. `claude-opus-4-7`,
  `claude-sonnet-4-7`, `gpt-5-codex`). Same sanitization rules.
- `<YYYY-MM-DD-HH-MM-SS>` — local time at GIF-build time.
- `v<N>` — run-version. Auto-increments per `<slug-sanitized>-<model>-<ts>`
  prefix if `--run-version` is omitted.

Concrete example:

```
items-swords-iron-sword-claude-opus-4-7-2026-05-19-14-30-00-v1.gif
```

## Usage

The verb lives on the studio CLI dispatcher (`bun run studio …`). From
`tools/studio/web/`:

```bash
# After a build-model run finishes, with $RUN_ID still set:
bun run studio benchmark:gif "$RUN_ID" \
  --slug items/swords/iron-sword \
  --model claude-opus-4-7

# Use a different camera angle:
bun run studio benchmark:gif "$RUN_ID" \
  --slug items/swords/iron-sword \
  --model claude-opus-4-7 \
  --view item_gui

# Pin a specific run-version (skip auto-increment):
bun run studio benchmark:gif "$RUN_ID" \
  --slug items/swords/iron-sword \
  --model claude-opus-4-7 \
  --run-version 2
```

Output goes to `tools/model-gen/benchmark/`. The script prints a
`{ok, status, changes:{output, frames, …}}` envelope on stdout, matching the
rest of the studio CLI's truth-envelope style.

## Defaults

| Option | Default | Note |
|---|---|---|
| `--view` | `iso` | `iter-screenshot` writes iso/item_gui/front by default — we pick iso for the archive |
| frame delay | 1000 ms | 1 fps, gives the eye time to compare frames |
| loop | infinite | GIF `repeat=0` |
| `--run-version` | auto | scans existing files with the same `<slug>-<model>-<timestamp>` prefix and picks `v(max+1)`, falling back to `v1` |

## Relationship to Tier C1 (ModelBench)

C10 is intentionally a **PoC-closing-friendly first step**. The heavy
ModelBench rig (Tier C1, BlenderLLM §9.3.3 4-axis rubric:
`Attr` / `Spat` / `Inst` / `E_syntax`, plus VLM-judge — see
`docs/research/model-gen-llm-survey.md` §5.4 entry **C9**) stacks **on top**
later: same archive directory + same naming, with an automated grader added.
Until then, this directory is a human flipbook, not a scoreboard.

## Phase 1 PoC closing posture

This archive is **not load-bearing infrastructure**. Per
`.claude/CLAUDE.md` §"Pre-release posture (Phase 1 PoC closing)", break-it-fast
is allowed: file naming, frame rate, default view, and the script's flag
surface can be rewritten without compatibility shims if a better design
emerges. The GIFs themselves are committed snapshots — re-running on a fresh
run produces a new file, never overwrites an old one. If the naming
convention changes, old GIFs stay as-is under the old name (chronological
archive, not regenerated).
