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

Don't author here directly. Use the **AsteraStudio** repo (sibling clone):

- `AsteraStudio/tools/texture-gen/scripts/` — slice / quantize / PXC headless pipeline
- `AsteraStudio/.claude/skills/generate-textures/` — Claude-driven end-to-end flow
- Or open AsteraStudio's `/textures` UI (`bun run dev` → http://localhost:3007/textures)

All three write PNGs into this directory (via `STUDIO_TEXTURES_DIR=../Astera/content/textures`
or the sibling-clone default). The deliverable is a committed `*.png` here in the
appropriate subdirectory; PR diff is the visual review.

See [[adr/0079-extract-studio-to-own-repo]] for the extraction rationale.

## Style anchor (`.style.json`)

Each subdirectory may contain a `.style.json` file alongside the PNGs.
It records the dominant palette and outline color extracted from
already-accepted textures in that subdir, and is read by the
`/generate-textures` skill to keep future batches visually consistent
with prior ones.

Shape (written by `AsteraStudio/tools/texture-gen/scripts/extract_style.py`):

```json
{
  "palette":   [[r, g, b], ...],   // top N opaque RGB triples by frequency
  "outline":   [r, g, b],          // mode color along opaque/transparent boundary
  "n_colors":  16,
  "n_sources": 16,
  "generated_from": ["content/textures/items/swords/broadsword_v01.png", "..."]
}
```

- **Producer**: `extract_style.py FILES --out content/textures/<cat>/.style.json`
  is called by the skill at Step 4 after at least 4 tiles are accepted.
- **Consumer**: the skill at Step 0 / Step A inlines the hex-encoded
  palette into the Codex prompt to constrain the next batch.
- **Committed**: yes. Tiny file, anchors future contributors' batches.
- **Removable**: yes, with no breakage. The next batch is simply
  unanchored and may drift in style; the file regenerates on next accept.

## Future (Phase 2): weapon YAML texture references

Forward declaration so the next contributor isn't surprised:

The `plugin:weapons` content schema is expected to extend its YAML form
with a `texture: items/swords/broadsword_v01` field that resolves to a
PNG path under this directory. The variant suffix (`_v01`) lets a single
weapon definition pin a specific take. The resolver and the resource-pack
assembly will land in a dedicated Wave (tentatively `Wave _infra-3`);
until then, textures here are visual prototypes that ship via PR diff
review only, not via game-side loading. Do not block on this — write the
texture, commit the PNG, and let the linker wave wire it.

## See also

- [[adr/0016-content-schema-ssot]] — content schema SSoT pattern
- [[adr/0079-extract-studio-to-own-repo]] — Studio + texture-gen extracted to AsteraStudio
- `AsteraStudio/tools/texture-gen/README.md` — author-side scaffolding (in the sibling repo)
