---
status: Accepted
date: 2026-10-23
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0237: Custom particle sprites — authored like item art, shipped as font glyphs on TextDisplay

## Status

Accepted. Builds on [[adr/0236-item-art-raster-sources-and-pixel-converter]] (sources + converter),
[[adr/0220-display-choreography-vfx-and-combo-mechanics]] (content/vfx planner) and
[[adr/0182-resource-pack-pipeline]] (pack).

## Context

Founder: "パーティクルとかもちゃんと自作できるようにしてね。実際にマイクラにTextDisplayとして提供できるレベルまで".
Vanilla particles cannot be retextured per effect without replacing vanilla ones, and content/vfx text
elements could only show existing characters (✦, «). A TextDisplay renders any font glyph, a bitmap
font provider maps any PNG to a glyph, and a TextDisplay's text can be swapped every tick: that is a
custom, animated, emissive, billboarded particle with no client mod.

## Decision

1. **Authoring** — `content/textures/sources/sprites.yaml` names each sprite's frames: a code-drawn
   painter (`draw: spark|ring|burst|puff|flame|glint|slash`, base `color`), a row of an AI / hand-made
   sheet (`from: <sheet>, row: N, frames: F`) or a strip image split in F. Sheet sheets come from
   `tools/item-art/scripts/gen_sheet.py` with an `animations:` spec (one row = one flipbook).
2. **Conversion** — `PixelConverter.convertFrames`: one background key, one union crop, one palette and
   one scale for all frames of a sprite (frames stay aligned), no outline, small blobs kept (embers,
   shards), green-key despill.
3. **Generated, committed, drift-checked** (`checkItemArt`): `textures/generated/sprites/<id>.png`
   (frame strip), `fonts/vfx/<id>.json` (bitmap provider, frame i = U+E000+i, height = size,
   ascent = size/2 - 2 so the glyph centres on the display origin), `sprites/<id>.yaml` (id, frames, size).
   The pack builder ships `content/fonts/**` as `assets/astera/font/**` and no longer ships
   `content/textures/sources/**`; pack validation checks every font bitmap exists.
4. **Runtime** — `type: sprite` VFX element (`sprite`, `frame_ticks`, `loop`, `color`, `see_through`).
   `SpriteContents` (`content/sprites`, `ISpriteRegistry`) gives frame counts; `VfxTimeline.plan` takes
   them, plans the element as a text display of `SpriteGlyphs.text(id, frame)` and adds the flipbook
   swaps. Swaps inside an interpolation step re-target the step end with the remaining ticks, so content
   swaps never stall motion (also fixes block / text keyframe swaps). content-lint rejects unknown ids.
5. **First set** — 7 code-drawn + 6 AI-sheet sprites (`vfx-v1`); `demo-sprites` VFX + `vfx-demo-sprites`
   skill show all; `couture-rapier-burst` (glint) and `crimson-chain-axe-finish` (blood-splash) use them.

## Consequences

- One display entity per sprite instance: the ADR-0220 display caps apply; use vanilla particles for
  dense clouds and sprites for hero moments.
- Binary alpha (pixel-art look); text displays have no per-glyph additive blending.
- Glyph centring relies on the vanilla text-display layout (1 font px = 1/40 block); verify in client
  when changing `size`.
