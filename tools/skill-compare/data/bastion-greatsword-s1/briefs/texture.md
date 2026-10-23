# Brief: limbus-vfx, part 1 — 2D effect textures (sprite strips)

Read `common.md` first, then this, then `particles.md` (same role, done after the textures). Role: draw the high-quality 2D art the effects are made of. You do the textures first and hand the sprite ids to yourself for `particles.md`.

## Goal
Replace the low-res, soft, blotchy sprites of the previous attempt (`bastion-arc`, `bastion-ink`, `bastion-smoke` in `content/textures/generated/sprites/`, 256 px procedural) with new **`bastion-s1-*` sprites** that look like the reference: a crisp, tapered **needle-tipped gold crescent** with a **white-hot inner edge and a dark outer rim**, torn **yellow ink-brush flash**, thin **white-hot streak**, grey **smoke swirl**, **orange spark glints** and **slivers**. Look at the real thing first.

## Look at
`/tmp/claude-1000/-IdeaProjects-Astera/de20caa0-f14b-4c18-b441-07a23b79f448/scratchpad/pilot/frames/`:
- ring crescent: `b01_t07_hit1-contact_f359.jpg`, `b02_t07_hit1-closeup_f360.jpg`, `b03_t09_hit1-streak-ring_f362.jpg` (needle tips on both ends, thick middle, bright inner edge, dark outline, thin extra slivers at the left tip), `e05_t34_hit2-ring-full_f400.jpg` and `e06_t37_hit2-ring-peak_f404.jpg` (hit-2 ring, thickest at the bottom arc).
- ink: `e00_t30_hit2-yellow-ink_f394.jpg`, `e01_t31_hit2-ink-face_f395.jpg`, `e02_t31_hit2-contact_f396.jpg` (flat saturated yellow, torn/ragged edges, brush streaks along the horizontal).
- streak and sparks: `b03_...f362.jpg`, `e03_t32_hit2-orange-streak_f397.jpg` (white-hot core, yellow glow, orange spark streaks).
- smoke: `b03_...f362.jpg` (lower right, grey floor swirl) and `b05_t14_...f370.jpg`.
- beats rows 3-8 and 12-19 in `case.json` for sizes (in H) and hex colours.

## Sprite list (ids are the interface; do not rename)
| id | frames | content | colours | on-screen size where it is used |
|---|---|---|---|---|
| `bastion-s1-crescent` | 6 | needle-tipped crescent arc (about 130 degrees of a circle that fills the frame), frames = growth (f0 short, f2 full) then thinning (f3-f5), tilted-ellipse foreshortening NOT baked in (the director rotates it by `roll`) | core `#FFD23A` -> body `#E8A010`, inner edge white-hot `#FFFBE0` (1-2 px), outer dark rim `#3A2A06` (2-3 px) | 3-5 blocks wide (51-85 px per block) |
| `bastion-s1-ink` | 6 | torn yellow brush flash: wide horizontal ragged slab, 3 stacked variants with different ragged ends, dry-brush streak texture inside (lighter and darker bands along the horizontal), frames = spreading from left to full width | flat `#FFE45A`, shadow band `#E8B810`, hot band `#FFF6B0`, ragged edge alpha-cut (not blurry) | 6-10 blocks wide (25-43 px per block) |
| `bastion-s1-streak` | 1 | white-hot horizontal line, full 256 px wide, band 24-40 px tall in the frame centre, tapered to a needle at the left end and a soft needle at the right end, glow halo | core `#FFFFF0`, glow `#FFE070`, outer `#FFB020` at alpha 0.35 | 4-6 blocks long |
| `bastion-s1-smoke` | 4 | floor swirl: a lazy curved band of grey smoke with dark pockets and light pockets (not uniform), frames = swirl turns and dissolves | light `#B8B8C0`, dark `#4A4A52`, alpha 0.2-0.7 | 3-4 blocks wide |
| `bastion-s1-glint` | 2 | 4-point star ✦ with two long thin spikes (one horizontal, one vertical, 3:1), white centre dot, orange fringe; f1 = bigger/softer | `#FFFFFF` centre, `#FFC040`, `#FF7A12` | 0.3-1.2 blocks |
| `bastion-s1-sliver` | 3 | thin slanted yellow shards (3 variants): 1:8 aspect, tapered, one bright edge | `#FFD23A`, `#FFFBE0` edge, `#3A2A06` rim hairline | 0.1-0.3 blocks long |

## Measurable quality bar
- **Format**: PNG strip of square frames, `size` <= **256 px** (512 fails in the client), RGBA, frames in one row, <= 6 frames per strip (width <= 1536). Source art drawn at 4x then downsampled (LANCZOS) so edges are anti-aliased; no 1-bit alpha steps on curved edges: alpha ramps over 2-3 px.
- **Needle tips**: the crescent and streak taper to <= 2 px thick over the last 12% of their length; no blunt ends.
- **Edge structure**: white-hot band (luma >= 245) 1-2 px along the inner edge, body gradient, dark rim (luma <= 60) 2-3 px along the outer edge. The sprite must still read on a bright sky background (dark rim) and on a dark floor (white-hot edge).
- **No flat fills** except the ink slab, and even the ink has >= 3 tone bands and a ragged, non-circular silhouette (no more than 20% of the outline is smooth).
- **Colour discipline**: <= 4 hues per sprite, palette from the table; dominant colour gold/yellow.
- Verify numerically with a script in the scratchpad (`tools/item-art/scripts/bastion_s1_check.py` is allowed too): size, frame count, alpha edge-ramp width (histogram of alpha in 1..254 along edges), tip thickness, luma of the edge/rim bands. Put the numbers in the handback table.
- Look at each strip magnified (nearest-neighbour 3x) AND composited on 3 backgrounds: `#101010`, `#707070`, `#E8E8E8`. Open the images.

## How sprites are wired (read before writing)
A sprite = `content/sprites/<id>.yaml` (`id`, `frames`, `size`), `content/fonts/vfx/<id>.json` (bitmap font provider; copy the shape of `content/fonts/vfx/bastion-arc.json`, glyphs U+E000+i, ascent `size/2 - 2`, height = size) and the strip `content/textures/generated/sprites/<id>.png`. The existing ones are produced by `:tools:item-art` from `content/textures/sources/sprites.yaml` (`draw: arc|ink|crest|rim|slash`; header "generated by :tools:item-art (ADR-0239)").
**Before drawing: read `tools/item-art/src` and `docs`/ADR-0239 to see which route survives a later `./gradlew :tools:item-art:run`.** Preferred: a Python generator `tools/item-art/scripts/bastion_s1_sprites.py` that writes the strips + the `sprites/*.yaml` + `fonts/vfx/*.json` itself and a `bastion-s1-*` entry in `sprites.yaml` only if the generator supports a hand-made source key (if the item-art run would delete files it does not know, list that as a request instead of working around it; do NOT run item-art over the whole content tree). Never modify or regenerate the existing `bastion-*` sprites (S2/S3/EGO use them).

## File territory
Only: `content/textures/sources/sprites.yaml` (add `bastion-s1-*` lines only), `content/sprites/bastion-s1-*.yaml`, `content/fonts/vfx/bastion-s1-*.json`, `content/textures/generated/sprites/bastion-s1-*.png`, `tools/item-art/scripts/bastion_s1_*.py`, `tools/skill-compare/data/bastion-greatsword-s1/evidence/vfx/textures/**`. The effect files are covered in `particles.md`.

## Self-check
1. Magnified contact sheet of every strip on the 3 backgrounds (opened).
2. Numeric checks above.
3. In-preview: `vfx-preview` a throw-away effect in the scratchpad that places each sprite at its intended size (scale from the table) on a dark floor; open it. Real-client check of at least `crescent`, `ink`, `streak` at their real scale (`make vfx-shot ...`, one at a time; needs the sprites wired into a fx file from particles.md, so do it after step 2 of that brief).
4. Compare side by side with the reference frames (stack your sprite on the video frame in a labelled PNG) and write what is still different.

## Done means
- All 6 strips exist, load in vfx-preview, meet the numeric bar (or misses are listed), and each reads at its on-screen scale in the preview and in at least one real-client frame.
- Magnified and 3-background sheets opened and described. No self-grading; handback lists what is still wrong.
