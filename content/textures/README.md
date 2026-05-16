# content/textures/

32×32 PNG texture assets for items, blocks, entities, and animations.
Committed as static files; not generated at runtime or build time
(see [[adr/0018-ai-asset-creation-policy]]).

## Layout

```
textures/
├── items/         ツール / 装備 / 消費品 / 装飾品。AI 画像生成由来が中心
├── blocks/        ブロック表面。procedural (PXC) 由来が中心、seamless 必須
├── entities/      Mob / NPC スプライト。AI + 手修正の混合
└── animations/    スプライトシート (torch, water, lava, redstone …)
```

## File conventions

- **Resolution**: 32×32 (items, blocks, entities single frames). Animation
  sprite sheets are `32 × (32 × frame_count)` vertically stacked.
- **Format**: PNG, RGBA, no embedded metadata other than basic IHDR.
  Run `palette_quantize.py` to normalize.
- **Palette**: per-asset palette of ≤32 colors. Astera does not enforce a
  global palette yet (Phase 2 candidate).
- **Naming**: `lowercase_snake_case.png`. No spaces, no dashes.
  Variant suffix: `_v01`, `_v02` for multiple takes of the same concept
  before one is selected.
- **Animation sheets**: `name.png` (frames), `name.mcmeta.json` (frame
  timing). Mojang conventions apply.

## How to add

Don't author here directly. Use one of:

- `tools/texture-gen/scripts/` — slice / quantize / PXC headless pipeline
- `.claude/skills/generate-textures/` — Claude-driven end-to-end flow

The deliverable is a committed `*.png` in the appropriate subdirectory.
PR diff is the visual review.

## See also

- [[adr/0018-ai-asset-creation-policy]] — policy authorizing this directory
- [[adr/0016-content-schema-ssot]] — content schema SSoT pattern
- `tools/texture-gen/README.md` — author-side scaffolding
