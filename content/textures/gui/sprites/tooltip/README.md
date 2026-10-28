# Astera rarity tooltip art

`PaperItemMaterializer` sets `minecraft:tooltip_style=astera:<rarity>` on every
Astera item, including GUI icons and refreshed stacks. Minecraft adds `tooltip/`
when resolving the two sprites. `astera:tooltip/<rarity>` would incorrectly look
under `tooltip/tooltip/`.

The 10 PNGs (`<rarity>_{frame,background}.png`, 36px, nine-sliced with a 12px
border) are generated from geometry formulas by `tools/item-art/scripts/tooltip-frames.py`,
the single source. The Kotlin item-art pipeline does not touch them.

```
python3 tools/item-art/scripts/tooltip-frames.py [--preview docs/assets/tooltip-frames-preview.png]
./gradlew :tools:resource-pack:buildResourcePack
bash tools/item-art/scripts/tooltip-shot.sh orrery-staff
```

Nine-slice behaviour (checked in the 26.3 client, `GuiGraphicsExtractor.blitNineSliceInnerSegment`):
with `stretch_inner` absent/false the 12px edge and centre segments are TILED (truncated at the far end),
not stretched. So each edge is one 12px-period strip that repeats along the tooltip; `stretch_inner: true`
(the old setting) would stretch it and smear any pattern. The sprite starts 12px outside the text
(PADDING 3 + MARGIN 9), so ornaments use only the outer 10px and keep a 2px gap to the text.

Each rarity uses a structurally different motif from `tools/item-art/scripts/geometry/`
(see `docs/art/geometric-patterns.md`): common line + chamfer, uncommon double line + stair + ticks,
rare diamond chain + checker block, epic Greek key (meander) + octagram {8/3}, legendary interlaced braid +
medallion with octagram and glow. Preview: `docs/assets/tooltip-frames-preview.png`.
The pack builder already copies PNGs and converts `*.mcmeta.json` to `*.png.mcmeta`.

Colour comes from the shipped `astera.item.tooltip.rarity` MiniMessage strings
in `content/languages/en.yaml`, shared with lore. Current accents:
common #FFFFFF, uncommon #55FF55, rare #55FFFF, epic #FF55FF, legendary #FFAA00.
Update the script's palette after changing that; keep ja/en rarity tags consistent.

Readability: the background's lightest possible composite over white is
#181A1F. Contrast is >= 17:1 for white, >= 7.4:1 for gray #AAAAAA, and >= 5.4:1
for red #FF5555; the rarity colours are brighter. Existing dark_gray #555555
cannot reach 4.5:1 even on pure black; use gray for text the player must read.
The shot helper preserves the normal font (the VFX overlay normally hides ASCII),
uses one client, and tears down its own stack on exit.
