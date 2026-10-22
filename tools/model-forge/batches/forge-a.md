# Forge A — seven fantasy weapon props

Branch: `wp/forge-a`. Initial `git merge --no-edit feat/model-forge` reported already up to date.
All seven items are authored and committed individually. Each has a same-named production model,
128×128 atlas referenced as `astera:items/<id>`, seven requested display slots, an image-generated
reference, reproducible recipe, five visually inspected rounds and a final side-by-side sheet.

| Item | Rounds | Elements | Item commit | Local verdict | Remaining weaknesses |
|---|---:|---:|---|---|---|
| couture-rapier (redo) | 5 | 133 | `7c367922` | Pass authoring review | Fine swept scrolls merge at 32px; needle intermittent at 16px; curves remain stepped. |
| bastion-greatsword (redo) | 5 | 105 | `07d47f5e` | Pass authoring review | Shield engraving disappears at 32px; cap facets more angular than concept. |
| crimson-chain-axe | 5 | 163 | `b6d012d7` | Pass authoring review | Crescent steps visible close up; chain less tightly wound than concept; tiny star lost at 32px. |
| dagger | 5 | 100 | `8d6b5ea2` | Pass authoring review | Blade-base notch simplified; fine stitches disappear; taper visibly stepped close up. |
| edict-blade | 5 | 86 | `b0ff252e` | Pass authoring review | Ricasso engraving and jewel claws disappear at inventory size. |
| gossamer-katana | 5 | 148 | `4842500d` | Pass authoring review | Stepped curve visible close up; guard web disappears at inventory size. |
| greatsword | 5 | 96 | `afbbc91d` | Pass authoring review | Tiny wear/rivets disappear at 32px; stepped spear tip visible close up. |

## Review evidence

For each item, `tools/model-forge/work/<id>/` contains:

- `ai-target.png`: built-in image generation using the original weapons-v1 sheet as identity reference,
  the named cell, item YAML and sheet description. Prompts are preserved in `ai-prompt.txt`.
- `redo-1` through `redo-5`: actual model/texture snapshots, comparison PNGs and validation/view records.
  These preserve what was inspected; later recipe refinements do not retroactively alter earlier rounds.
- `review.md`: concrete observed differences and next edits, recorded before advancing each round.
- `final-sheet.png`: generated target alongside front, side, top, 3/4, GUI, both first-person hands,
  both third-person hands, ground and fixed previews, plus 16/32/64px GUI samples.
- `final-review.json`: all five rounds, final production validation, target/artifact hashes and clipping data.
- `recipe.py`: item-local authoring; run `python3 tools/model-forge/work/<id>/recipe.py 5 --publish`
  to reproduce final content and sheet. Other recipes reuse the small helpers in the rapier recipe.

Image generation produced useful identity/shape targets but did **not** consistently obey strict
orthographic top views or identical scale. Most generated tops remain oblique; the greatsword sheet
also includes extra views. Bastion was regenerated once to reduce duplicates. These are known reference
limitations, not silently claimed exact turnarounds. True top views are present in every model render.
No code-drawn construction target or candidate-derived silhouette was used as a quality score.

## Validation and scope

All seven production models passed the existing `tools/model-forge/validate.py` validator against their
real texture references, with explicit `budget=250, density=3`. Each has 80–200 elements, conservative
one-axis rotations, bounded UVs, positive cuboids, 3 texels/unit and all requested display slots.
Every final render's eleven views is unclipped. The user-specified higher detail budget supersedes the
older pipeline's 64-element default; finer 3-texel density is consistent across each atlas.

Rendering/comparison uses existing `model-forge.raster.render`, `forge.tile`, `forge.texture_map` and
`validate.validate` from item-local recipes. The old CLI finalizer requires a code-drawn concept and
64-element defaults, so the recipes write the reviewed assets directly after validation. No shared
`tools/model-forge/*.py` was edited. No Gradle, Docker, tests, push, CI, paid API or remote modeling
service was used. Image generation used the explicitly requested built-in tool. Greatsword's first
image request received a moderation rejection; a clarified static game-asset request succeeded.

These are **local authoring passes**, not client-certified release passes. Preview hands are isolated
orthographic display transforms, not real Minecraft hands/FOV. Client grip placement, animation,
glint, camera occlusion, pack loading and motion z-fighting remain unverified under this task's scope.

Two pre-existing dirty files were preserved and excluded from all commits:
`tools/model-forge/work/couture-rapier/concept.json` and `concept-sheet.png`.
No harness files or unrelated content were changed.

## Tool ideas for the manager

1. Expose the existing validator's budget and density parameters on the CLI.
2. Accept image-generated targets directly, with separately marked/registered view crops and an explicit
   “top not orthographic” warning; do not force an unrelated polygon-target IoU for finalization.
3. Render both hands by default and add an optional real-client capture workflow for grip/FOV review.
4. Add per-material UV-origin offsets or per-face trim choices: always sampling the same bright atlas
   corner made small metal segments too speckled until the ramps were adjusted.
