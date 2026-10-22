---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0236: Item art from any raster source — sheet slicing and a pixel-art converter

## Status

Accepted. Extends [[adr/0193-procedural-item-icons]] and [[adr/0223-procedural-gui-icons-and-pack-validation]];
the AI boundary is [[adr/0018-ai-asset-creation-policy]] (tools free, build never calls a generator).

## Context

Founder: "どちらの用途でも使う。色々な手段でテクスチャを作れるべき。" Item icons were only drawn by
code (`:tools:item-art`): consistent, but flat. AI image models now draw a whole batch of icons in one
consistent style in a single generation (one 10x10 sheet → 100 icons), and a staged pixel-art converter
(normalize → smooth → palette → pixelize → cleanup → outline) turns such art into clean low-res sprites
where a plain LANCZOS downscale + quantize (AsteraStudio `palette_quantize.py`) blurs details away.

## Decision

1. **A raster source per item, chosen in `content/textures/sources/items.yaml`.** A source is a committed
   PNG: a single image or a sheet with a `grid` and the item ids of its `cells`. Items not listed stay
   code-drawn. The tool that made the PNG (Codex, ChatGPT, Aseprite, a hand drawing) does not matter.
2. **Sheets are sliced by blob, not by grid line.** The sheet background is keyed once; a cell is every
   blob whose centroid lies in it, so art crossing the grid stays whole and a neighbour's tip stays out.
3. **`PixelConverter` (Kotlin, deterministic)**: background key (border colour, enclosed holes, tinted
   fringe), speck removal, 3x3 median, Lab k-means palette (farthest-point seeds, default 16), per-pixel
   coverage + contrast-weighted vote, orphan / pinhole / low-contrast-noise cleanup, and the shared outline
   colour of the code-drawn icons so both kinds sit together. `convert <image> <outDir>` dumps every stage.
4. **Same output and drift check.** Converted icons go to `textures/generated/items/<id>.png` like
   code-drawn ones; `checkItemArt` re-converts the committed sources, so the build stays AI-free and
   reproducible. `docs/assets/item-art-sources-preview.png` shows source | code | converted per item.
5. **AI sheets are a designer-side step**: `tools/item-art/sheets/<name>.yaml` (grid, key colour, style,
   one line per cell) → `tools/item-art/scripts/gen_sheet.py` (Codex image tool, or `--prompt-only` for any
   model) → `content/textures/sources/sheets/<name>.png`. Not in the Gradle graph.
6. **First batch**: `weapons-v1` (21 weapons, one generation). 20 weapons switch to it; `rtm-blaster`
   stays code-drawn because its magenta-purple glow is keyed out with the magenta background.

## Consequences

- The key colour must be far from the batch's palette; magenta bleeds into purple / pink items.
- Converter changes re-render every sourced icon (the drift check flags them); regenerate and commit.
- Thin details (a rapier blade, small guns drawn wide) lose definition at 32 px; prompt for chunky shapes.
- AsteraStudio's texture-gen pipeline is untouched; its output can be committed here as an image source.
