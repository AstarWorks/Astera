# Astera rarity tooltip art

`PaperItemMaterializer` sets `minecraft:tooltip_style=astera:<rarity>` on every
Astera item, including GUI icons and refreshed stacks. Minecraft adds `tooltip/`
when resolving the two sprites. `astera:tooltip/<rarity>` would incorrectly look
under `tooltip/tooltip/`.

The 10 PNGs (`<rarity>_{frame,background}.png`, 64px, nine-sliced with an 8px
border) are drawn pixel by pixel by `tools/item-art/scripts/tooltip-frames.py`,
the single source. The Kotlin item-art pipeline does not touch them.

```
python3 tools/item-art/scripts/tooltip-frames.py [--preview docs/assets/tooltip-frames-preview.png]
./gradlew :tools:resource-pack:buildResourcePack
bash tools/item-art/scripts/tooltip-shot.sh orrery-staff
```

Each frame is one 8x8 corner pattern mirrored to the four corners; ornament
richness escalates with rarity. The pack builder already copies PNGs and
converts `*.mcmeta.json` to `*.png.mcmeta`.

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
